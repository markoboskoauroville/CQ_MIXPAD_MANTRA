package com.mantra.cqmixpad

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The CQ's SD multitrack recorder over its private protocol (TCP 51326), decoded on Marko's CQ-12T, 7.11.2026:
 * handshake 7F00 (96 CF) / 7F01 / 7F0C 02 00, heartbeat 7F00 every 2 s (no registration was needed);
 * REC = F7 11 11 11 03 FF 00 00 00, STOP = F7 11 11 10 01 FF 00 00 00 + F7 11 11 11 01 FF 00 00 00.
 * The desk reports, about 4 times a second, a 55-byte message (7F 07, data 11 12 1C …): byte 7 = 0x40 while recording
 * (otherwise the take's name sits there), u32 LE at 16 = seconds recorded, u32 at 20 = seconds of card left;
 * F7 12 12 0D <state>: 0x05 recording, 0x00 stopped (this firmware); 283-byte messages (11 12 17/18 …) list the takes,
 * the name at byte 27 ("CQ-MT002").
 */
class Recorder(private val host: () -> String, private val scope: CoroutineScope, private val log: (String) -> Unit) {
    enum class State { UNKNOWN, RECORDING, STOPPED }
    val state = MutableStateFlow(State.UNKNOWN)
    val elapsed = MutableStateFlow(0L)          // seconds, from the desk
    val remaining = MutableStateFlow(0L)        // seconds of SD left, from the desk
    val take = MutableStateFlow("")             // the take being recorded / last one
    val connected = MutableStateFlow(false)
    /** Armed SD tracks, bit 0 = track 1; null until the desk has sent its full data. */
    val armed = MutableStateFlow<Int?>(null)
    @Volatile private var out: OutputStream? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val ip = host()
                if (ip.isBlank()) { delay(2000); continue }
                try { session(ip); refused.value = 0 } catch (e: Exception) {
                    // "unreachable" is a moved or missing desk; a plain timeout with the desk reachable is MixPad holding the link
                    val m = e.message ?: ""
                    val unreachable = m.contains("EHOSTUNREACH") || m.contains("ENETUNREACH") || m.contains("No route")
                    if (!unreachable && (e is java.net.SocketTimeoutException || m.contains("failed to connect"))) refused.value = refused.value + 1
                    if (unreachable) refused.value = 0
                    log("  recorder: ${e.message}")
                }
                connected.value = false; out = null; state.value = State.UNKNOWN
                delay(2000)
            }
        }
    }

    private fun f(type: Int, vararg d: Int): ByteArray {
        val b = ByteArray(6 + d.size); b[0] = 0x7F; b[1] = type.toByte(); b[2] = d.size.toByte()
        d.forEachIndexed { i, v -> b[6 + i] = v.toByte() }; return b
    }
    private val hello = f(0, 0x96, 0xCF)

    private suspend fun session(ip: String) {
        val s = Socket(); s.connect(InetSocketAddress(ip, 51326), 3000); s.soTimeout = 6000; s.tcpNoDelay = true
        val o = s.getOutputStream(); val i = s.getInputStream()
        o.write(hello); o.flush(); delay(100); o.write(f(1)); o.flush(); delay(100); o.write(f(12, 2, 0)); o.flush()
        out = o; connected.value = true; refused.value = 0
        val dump = scope.launch(Dispatchers.IO) {     // ask for the full data (armed tracks) now and every minute
            while (isActive) { delay(1500); try { synchronized(this@Recorder) { o.write(f(3)); o.flush() } } catch (_: Exception) { break }; delay(58_500) }
        }
        log("  recorder: connected")
        val beat = scope.launch(Dispatchers.IO) {
            while (isActive) { delay(2000); try { synchronized(this@Recorder) { o.write(hello); o.flush() } } catch (_: Exception) { break } }
        }
        try {
            val r = java.io.BufferedInputStream(i)
            while (true) {
                val b0 = r.read(); if (b0 < 0) throw java.io.IOException("the desk closed the recorder link")
                if (b0 == 0x7F) {
                    r.mark(8)
                    val h = ByteArray(5); readFully(r, h)
                    val type = h[0].toInt() and 0xFF
                    val len = (h[1].toInt() and 0xFF) or ((h[2].toInt() and 0xFF) shl 8) or ((h[3].toInt() and 0xFF) shl 16) or ((h[4].toInt() and 0xFF) shl 24)
                    // a 0x7F inside a message is not a frame: an impossible type or length means resync from the next byte
                    if (type > 40 || len < 0 || len > (if (type == 4) 200_000 else 4096)) { r.reset(); continue }
                    val d = ByteArray(len); readFully(r, d)
                    if (type == 7) onMessage(d)
                    // the full data dump: the armed tracks are a 16-bit mask 100 bytes from its end (measured 7.11.2026:
                    // ff 03 = tracks 1-10, ff 07 = 1-11)
                    if (type == 4 && len > 200) armed.value = (d[len - 100].toInt() and 0xFF) or ((d[len - 99].toInt() and 0xFF) shl 8)
                } else if (b0 == 0xF7) {
                    // fixed 8-byte messages (as measured on the CQ-12T, 7.11.2026)
                    val d = ByteArray(7); readFully(r, d)
                    if ((d[0].toInt() and 0xFF) == 0x12 && (d[1].toInt() and 0xFF) == 0x12 && (d[2].toInt() and 0xFF) == 0x0D) {
                        // its codes differ between firmwares and flicker at transitions: logged only, the 55-byte status decides
                        log(String.format("  recorder: desk state code %02x", d[3].toInt() and 0xFF))
                    }
                }
            }
        } finally { beat.cancel(); dump.cancel(); s.close() }
    }

    private fun readFully(r: java.io.InputStream, b: ByteArray) {
        var n = 0; while (n < b.size) { val k = r.read(b, n, b.size - n); if (k < 0) throw java.io.IOException("closed"); n += k }
    }
    private fun u32(d: ByteArray, o: Int) = (d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or
        ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24)
    private fun name(d: ByteArray, o: Int, max: Int) = String(d, o, minOf(max, d.size - o), Charsets.US_ASCII).substringBefore('\u0000')

    private var lastRec = false; private var same = 0

    private fun onMessage(d: ByteArray) {
        if (d.size == 55 && (d[0].toInt() and 0xFF) == 0x11 && (d[1].toInt() and 0xFF) == 0x12 && (d[2].toInt() and 0xFF) == 0x1C) {
            val rec = (d[7].toInt() and 0xFF) == 0x40
            // three status messages in a row (about 0.75 s) before the state changes: no flicker
            if (rec == lastRec) same++ else { lastRec = rec; same = 1 }
            if (same >= 3 || state.value == State.UNKNOWN && same >= 2) setState(if (rec) State.RECORDING else State.STOPPED)
            if (!rec) {
                val n = name(d, 7, 4) + name(d, 12, 4)                  // "CQ-M" + "002" (the desk splits it)
                if (n.startsWith("CQ")) take.value = n.replace("CQ-M", "CQ-MT")
            }
            remaining.value = (u32(d, 20) * 1.98).toLong()      // calibrated against MixPad's "Remaining" (7.11.2026)
        } else if (d.size == 283 && (d[0].toInt() and 0xFF) == 0x11 && (d[1].toInt() and 0xFF) == 0x12) {
            val n = name(d, 27, 16); if (n.startsWith("CQ-MT") && state.value == State.RECORDING) take.value = n
        }
    }

    /** When the desk was first seen recording: the take's time is counted here (the desk's own counter is not the take's). */
    val since = MutableStateFlow(0L)
    /** Connect attempts that failed in a row (CQ MixPad holding the desk's one private link). */
    val refused = MutableStateFlow(0)

    /** Take start times kept on the phone, so the time survives an app restart during a take. */
    var startsStore: android.content.SharedPreferences? = null

    private fun setState(s: State) {
        if (s == State.RECORDING && state.value != State.RECORDING) {
            val now = System.currentTimeMillis()
            val p = startsStore
            // a take already running when the app started: its stored start, if this phone saw it begin
            val stored = p?.getLong("start", 0L) ?: 0L
            val storedTake = p?.getString("take", "") ?: ""
            since.value = if (stored > 0 && now - stored < 12 * 3600_000L && (storedTake.isBlank() || storedTake == take.value || state.value == State.UNKNOWN)) stored else now
            if (since.value == now) p?.edit()?.putLong("start", now)?.putString("take", take.value)?.apply()
        }
        if (s == State.STOPPED && state.value == State.RECORDING) startsStore?.edit()?.putLong("start", 0L)?.apply()
        if (s != state.value) log("  recorder: the desk is ${if (s == State.RECORDING) "RECORDING" else "stopped"}")
        state.value = s
    }

    /** Network writes never on the main thread (Android throws NetworkOnMainThreadException there): one writer thread. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun send(b: ByteArray): Boolean {
        val o = out ?: return false
        writer.execute {
            try { synchronized(this) { o.write(b); o.flush() }; log("  recorder: sent to the desk") }
            catch (e: Exception) { log("  recorder: could not send (${e.javaClass.simpleName})") }
        }
        return true
    }
    fun rec(): Boolean = send(byteArrayOf(0xF7.toByte(), 0x11, 0x11, 0x11, 0x03, 0xFF.toByte(), 0, 0, 0))
    fun stop(): Boolean = send(byteArrayOf(0xF7.toByte(), 0x11, 0x11, 0x10, 0x01, 0xFF.toByte(), 0, 0, 0,
        0xF7.toByte(), 0x11, 0x11, 0x11, 0x01, 0xFF.toByte(), 0, 0, 0))
}
