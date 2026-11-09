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
    /** The desk's own RTA (decoded 7.11.2026 by the man-in-the-middle on the Mac): 31 one-third-octave bands,
     *  20 Hz .. 20 kHz, dB = (raw - 0x8000) / 256, floor -110; in the UDP meter message type 23 (760 bytes), words
     *  312..342 (u16 LE). Switched on by F7 2F 2F 0F 00 38 01 00 (Main LR PEQ, RTA On). Null until it streams. */
    val rta = MutableStateFlow<FloatArray?>(null)
    @Volatile var rtaAt = 0L

    private suspend fun session(ip: String) {
        // the meter stream comes over UDP to the port we name in the handshake: one session carries recorder AND RTA,
        // because the desk takes only one private-protocol client at a time
        val udp = java.net.DatagramSocket(0); udp.soTimeout = 1000
        val myPort = udp.localPort
        val hello = f(0, myPort and 0xFF, myPort shr 8)
        val s = Socket(); s.connect(InetSocketAddress(ip, 51326), 3000); s.soTimeout = 6000; s.tcpNoDelay = true
        val o = s.getOutputStream(); val i = s.getInputStream()
        o.write(hello); o.flush(); delay(100); o.write(f(1)); o.flush(); delay(100); o.write(f(12, 2, 0)); o.flush()
        delay(100); o.write(byteArrayOf(0xF7.toByte(), 0x2F, 0x2F, 0x0F, 0x00, rtaSource.value.toByte(), 0x01, 0x00)); o.flush()   // RTA on (its source)
        val udpJob = scope.launch(Dispatchers.IO) { meterLoop(udp, ip) }
        out = o; connected.value = true; refused.value = 0
        val dump = scope.launch(Dispatchers.IO) {     // ask for the full data (armed tracks) now and every minute
            while (isActive) { delay(1500); try { synchronized(this@Recorder) { o.write(f(3)); o.flush() } } catch (_: Exception) { break }; delay(28_500) }
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
                    if (type == 4 && len > 200) {
                        armed.value = (d[len - 100].toInt() and 0xFF) or ((d[len - 99].toInt() and 0xFF) shl 8)
                        if (len > 32000) state0.value = d          // the whole desk state: input settings read from here
                    }
                } else if (b0 == 0xF7) {
                    // fixed 8-byte messages (as measured on the CQ-12T, 7.11.2026)
                    val d = ByteArray(7); readFully(r, d)
                    if ((d[0].toInt() and 0xFF) == 0x12 && (d[1].toInt() and 0xFF) == 0x12 && (d[2].toInt() and 0xFF) == 0x0D) {
                        // its codes differ between firmwares and flicker at transitions: logged only, the 55-byte status decides
                        log(String.format("  recorder: desk state code %02x", d[3].toInt() and 0xFF))
                    }
                }
            }
        } finally { beat.cancel(); dump.cancel(); udpJob.cancel(); s.close(); udp.close() }
    }

    /** Keep-alives to the desk's UDP port 51324 every 2.5 s; type 23 frames carry the RTA. */
    private suspend fun meterLoop(udp: java.net.DatagramSocket, ip: String) {
        val desk = InetSocketAddress(ip, 51324)
        val ka = f(5)
        val keep = scope.launch(Dispatchers.IO) {
            while (isActive) { try { udp.send(java.net.DatagramPacket(ka, ka.size, desk)) } catch (_: Exception) {}; delay(2500) }
        }
        try {
            val buf = ByteArray(8192)
            while (scope.isActive) {
                val p = java.net.DatagramPacket(buf, buf.size)
                try { udp.receive(p) } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { break }
                var j = 0; val n = p.length
                while (j + 6 <= n && buf[j] == 0x7F.toByte()) {
                    val type = buf[j + 1].toInt() and 0xFF
                    val len = (buf[j + 2].toInt() and 0xFF) or ((buf[j + 3].toInt() and 0xFF) shl 8)
                    val o = j + 6
                    if (len < 0 || o + len > n) break
                    // input meters (type 8: 20 bytes per input, the level at +14) and output meters (type 9: 16 bytes
                    // per output, the level at +14; 6/7 = Main L/R), dB = (raw - 0x8000)/256 with 0 = the desk's nominal
                    // (DigiMixer's layout; verified on the CQ-12T 8.11.2026 with the SD playback of CQ-MT006)
                    if (type == 8 && len >= 20 * 16) {
                        inMeters.value = FloatArray(16) { k -> lvl(buf, o + 20 * k + 14) }; metersAt = System.currentTimeMillis()
                    }
                    if (type == 9 && len >= 16 * 8) outMeters.value = FloatArray(8) { k -> lvl(buf, o + 16 * k + 14) }
                    if (type == 23 && len >= 343 * 2) {
                        rta.value = FloatArray(31) { k -> val w = o + (312 + k) * 2; val raw = (buf[w].toInt() and 0xFF) or ((buf[w + 1].toInt() and 0xFF) shl 8); (raw - 0x8000) / 256f }
                        rtaAt = System.currentTimeMillis()
                    }
                    j = o + len
                }
            }
        } finally { keep.cancel() }
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
                if (n.startsWith("CQ-M")) take.value = n.replace("CQ-M", "CQ-MT")
                else if (n.startsWith("CQ-S")) usbTake.value = n.replace("CQ-S", "CQ-ST")   // the USB stick's stereo take
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
    private fun lvl(b: ByteArray, w: Int): Float = (((b[w].toInt() and 0xFF) or ((b[w + 1].toInt() and 0xFF) shl 8)) - 0x8000) / 256f

    /** Input meters Ip1..Ip16 and output meters Out1..6, Main L, Main R (dB, 0 = nominal, about -18 dBFS; -110 = silence). */
    val inMeters = MutableStateFlow(FloatArray(16) { -110f })
    val outMeters = MutableStateFlow(FloatArray(8) { -110f })
    @Volatile var metersAt = 0L
    fun metersAlive() = System.currentTimeMillis() - metersAt < 3000

    /** The CQ private protocol's 8-byte message F7 BB BB PP CH SS VL VH (cq_mixpad_protocol, decoded 8.11.2026). */
    private fun m(block: Int, param: Int, ch: Int, sub: Int, v: Int) = byteArrayOf(0xF7.toByte(), block.toByte(), block.toByte(),
        param.toByte(), ch.toByte(), sub.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun dbRaw(db: Double) = (0x8000 + Math.round(db.coerceIn(-100.0, 100.0) * 256)).toInt().coerceIn(0, 0xFFFF)

    /** The desk's full state (the 7F 04 dump, 42 824 bytes on the CQ-12T), asked at connect and every minute; the
     *  desk never echoes a change, so every write here also patches this copy. Offsets measured 8.11.2026 by dump diffs
     *  (cq_mixpad_protocol): per input k (Ip1 = 0), 280 bytes apart: HPF freq 484, HPF in 486, PEQ gain 520 + 6*band,
     *  trim 656; analogue gain at 31248 + 4k. */
    val state0 = MutableStateFlow<ByteArray?>(null)
    private fun word(off: Int): Int? = state0.value?.let { d -> if (off + 1 < d.size) (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8) else null }
    private fun patch(off: Int, v: Int) { val d = state0.value ?: return; if (off + 1 >= d.size) return
        val c = d.copyOf(); c[off] = (v and 0xFF).toByte(); c[off + 1] = ((v shr 8) and 0xFF).toByte(); state0.value = c }
    private fun dbOf(off: Int) = word(off)?.let { (it - 0x8000) / 256.0 }
    /** Calibrated 8.11.2026 (cq_mixpad_protocol "Calibrated scales", 15 points each, read on CQ MixPad's own screen):
     *  frequency Hz = 4 * 2^(raw/4608); times us = 2^(raw/1793.44); comp ratio and PEQ width are index tables. */
    companion object Scales {
        fun hzOf(raw: Int) = 4.0 * Math.pow(2.0, raw / 4608.0)
        fun rawHz(hz: Double) = Math.round(4608 * Math.log(hz.coerceIn(20.0, 20000.0) / 4.0) / Math.log(2.0)).toInt().coerceIn(0, 0xFFFF)
        fun msOf(raw: Int) = Math.pow(2.0, raw / 1793.44) / 1000.0
        fun rawMs(ms: Double) = Math.round(1793.44 * Math.log(ms.coerceIn(0.001, 5000.0) * 1000.0) / Math.log(2.0)).toInt().coerceIn(0, 0xFFFF)
        val RATIOS = doubleArrayOf(1.0, 1.03, 1.05, 1.07, 1.1, 1.15, 1.2, 1.25, 1.3, 1.35, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.0, 2.3, 2.5, 2.7,
            3.0, 3.3, 3.5, 3.7, 4.0, 4.3, 4.5, 4.7, 5.0, 5.3, 5.5, 5.7, 6.0, 7.0, 8.0, 10.0, 12.0, 16.0, 20.0, 40.0, Double.POSITIVE_INFINITY)
        /** PEQ width in octaves by index 1..25 (0 = shelf). */
        val WIDTHS = doubleArrayOf(0.0, 1.5, 1.4, 1.3, 1.2, 1.1, 1.0, 0.95, 0.9, 0.85, 0.8, 0.75, 0.7, 2.0 / 3, 0.6, 0.55, 0.5, 0.45, 0.4,
            1.0 / 3, 0.3, 0.25, 0.2, 1.0 / 6, 0.13, 1.0 / 9)
        fun ratioIndex(r: Double) = RATIOS.indices.minByOrNull { Math.abs(Math.log(RATIOS[it].coerceAtMost(100.0)) - Math.log(r.coerceIn(1.0, 100.0))) } ?: 0
        fun widthIndex(oct: Double) = (1 until WIDTHS.size).minByOrNull { Math.abs(Math.log(WIDTHS[it]) - Math.log(oct.coerceIn(0.1, 1.5))) } ?: 16
    }
    fun hpfHzNow(ch: Int) = word(484 + 280 * ch)?.let { hzOf(it) }
    fun peqHzNow(ch: Int, band: Int) = word(522 + 280 * ch + 6 * band)?.let { hzOf(it) }
    fun peqWidthNow(ch: Int, band: Int) = word(524 + 280 * ch + 6 * band)?.let { if (it in 1..25) WIDTHS[it] else null }
    fun compNow(ch: Int): String? { val b = 280 * ch; val r = state0.value?.getOrNull(580 + b)?.toInt()?.and(0xFF) ?: return null
        val thr = dbOf(576 + b) ?: return null
        return String.format(java.util.Locale.US, "comp %.1f dB %s:1 att %.1f ms rel %.0f ms gain %+.1f", thr,
            RATIOS.getOrNull(r)?.let { if (it.isInfinite()) "inf" else "%.2f".format(java.util.Locale.US, it).trimEnd('0').trimEnd('.') } ?: "?",
            word(572 + b)?.let { msOf(it) } ?: 0.0, word(574 + b)?.let { msOf(it) } ?: 0.0, dbOf(578 + b) ?: 0.0) }
    fun hpfOnNow(ch: Int) = word(486 + 280 * ch)?.let { (it and 0xFF) == 1 }
    fun peqNow(ch: Int, band: Int) = dbOf(520 + 280 * ch + 6 * band)
    fun trimNow(ch: Int) = dbOf(656 + 280 * ch)
    /** The desk's own channel name and colour index (dump slot of CH: name at 384, 13 bytes; colour u32 at 400; read on the CQ-12T 9.11.2026: 0 default, 6 purple). */
    fun nameNow(ch: Int): String? { val b = state0.value ?: return null; val o = 384 + 280 * ch; if (o + 13 > b.size) return null
        return String(b, o, 13, Charsets.ISO_8859_1).takeWhile { it != '\u0000' }.trim().ifBlank { null } }
    fun colourNow(ch: Int): Int? = state0.value?.getOrNull(400 + 280 * ch)?.toInt()?.and(0xFF)
    fun gainNow(ch: Int) = dbOf(31248 + 4 * ch)
    fun askState(): Boolean = send(f(3))

    /** Input gain (analogue preamp, block 0A param 0C sub 01) and trim (block 0A param 0F), dB. */
    fun gain(ch: Int, db: Double): Boolean { val v = dbRaw(db.coerceIn(0.0, 60.0)); patch(31248 + 4 * ch, v); return send(m(0x0A, 0x0C, ch, 1, v)) }
    fun trim(ch: Int, db: Double): Boolean { val v = dbRaw(db.coerceIn(-24.0, 24.0)); patch(656 + 280 * ch, v); return send(m(0x0A, 0x0F, ch, 0, v)) }

    /** The RTA's source: 0x38 = Main LR, 0..9 = Ip1..Ip10 (verified: each channel shows its own spectrum). */
    val rtaSource = MutableStateFlow(0x38)
    fun setRtaSource(ch: Int): Boolean { rtaSource.value = ch; return send(m(0x2F, 0x0F, 0, ch, 1)) }

    /** PEQ band gain (band 0 LF, 1 LM, 2 HM, 3 HF) on Ip channel 0..9, dB. */
    fun peqGain(ch: Int, band: Int, db: Double): Boolean { val v = dbRaw(db.coerceIn(-15.0, 15.0)); patch(520 + 280 * ch + 6 * band, v); return send(m(0x0F, 0x0C, ch, band, v)) }
    fun peqIn(ch: Int, on: Boolean): Boolean = send(m(0x0F, 0x10, ch, 0, if (on) 1 else 0))
    /** PEQ band frequency (Hz) and width (octaves, nearest of the desk's 25 widths). */
    fun peqHz(ch: Int, band: Int, hz: Double): Boolean { val v = rawHz(hz); patch(522 + 280 * ch + 6 * band, v); return send(m(0x0F, 0x0D, ch, band, v)) }
    fun peqWidth(ch: Int, band: Int, oct: Double): Boolean { val v = widthIndex(oct); patch(524 + 280 * ch + 6 * band, v); return send(m(0x0F, 0x0E, ch, band, v)) }
    /** Compressor (block 10): in 0C, threshold 0E (dB), ratio 0F (index), gain 10 (dB), attack 11, release 12 (ms). */
    fun compIn(ch: Int, on: Boolean): Boolean = send(m(0x10, 0x0C, ch, 0, if (on) 1 else 0))
    fun compThreshold(ch: Int, db: Double): Boolean { val v = dbRaw(db.coerceIn(-46.0, 18.0)); patch(576 + 280 * ch, v); return send(m(0x10, 0x0E, ch, 0, v)) }
    fun compRatio(ch: Int, r: Double): Boolean { val v = ratioIndex(r); state0.value?.let { d -> word(580 + 280 * ch)?.let { patch(580 + 280 * ch, (it and 0xFF00) or v) } }; return send(m(0x10, 0x0F, ch, 0, v)) }
    fun compGain(ch: Int, db: Double): Boolean { val v = dbRaw(db.coerceIn(0.0, 18.0)); patch(578 + 280 * ch, v); return send(m(0x10, 0x10, ch, 0, v)) }
    fun compAttack(ch: Int, ms: Double): Boolean { val v = rawMs(ms.coerceIn(0.3, 300.0)); patch(572 + 280 * ch, v); return send(m(0x10, 0x11, ch, 0, v)) }
    fun compRelease(ch: Int, ms: Double): Boolean { val v = rawMs(ms.coerceIn(50.0, 2000.0)); patch(574 + 280 * ch, v); return send(m(0x10, 0x12, ch, 0, v)) }
    /** HPF on/off and frequency (Hz = 4 * 2^(raw/4608), calibrated; kept to 20..400 Hz). */
    fun hpf(ch: Int, on: Boolean): Boolean { word(486 + 280 * ch)?.let { patch(486 + 280 * ch, (it and 0xFF00) or (if (on) 1 else 0)) }; return send(m(0x0C, 0x0C, ch, 0, if (on) 1 else 0)) }
    fun hpfHz(ch: Int, hz: Double): Boolean { val v = rawHz(hz.coerceIn(20.0, 400.0))
        patch(484 + 280 * ch, v); return send(m(0x0C, 0x0D, ch, 0, v)) }

    /** The SD player (block 11 param 10) and the USB stick's stereo recorder (param 0F). */
    private fun t(param: Int, op: Int) = byteArrayOf(0xF7.toByte(), 0x11, 0x11, param.toByte(), op.toByte(), 0xFF.toByte(), 0, 0, 0)
    fun sdPrev() = send(t(0x10, 0)); fun sdStop() = send(t(0x10, 1)); fun sdPlayPause() = send(t(0x10, 2)); fun sdNext() = send(t(0x10, 4))
    fun sdSeek(ms: Long): Boolean = send(byteArrayOf(0xF7.toByte(), 0x11, 0x11, 0x10, 0x06, (ms and 0xFF).toByte(), ((ms shr 8) and 0xFF).toByte(),
        ((ms shr 16) and 0xFF).toByte(), ((ms shr 24) and 0xFF).toByte()))
    /** The USB stick's stereo recorder, as pressed here (the desk's status for it is not decoded yet): since when. */
    val usbSince = MutableStateFlow(0L)
    val usbTake = MutableStateFlow("")
    fun usbRec(): Boolean { usbSince.value = System.currentTimeMillis(); return send(t(0x0F, 3)) }
    fun usbStop(): Boolean { usbSince.value = 0L; return send(t(0x0F, 1)) }

    fun rec(): Boolean = send(byteArrayOf(0xF7.toByte(), 0x11, 0x11, 0x11, 0x03, 0xFF.toByte(), 0, 0, 0))
    fun stop(): Boolean = send(byteArrayOf(0xF7.toByte(), 0x11, 0x11, 0x10, 0x01, 0xFF.toByte(), 0, 0, 0,
        0xF7.toByte(), 0x11, 0x11, 0x11, 0x01, 0xFF.toByte(), 0, 0, 0))
}
