package com.mantra.cqmixpad

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The CQ's live meters over its private protocol (Marko, 7.11.2026: "mixer itself has rta, use that"). Reverse-engineered
 * by Jon Skeet's DigiMixer (github.com/jskeet/DemoCode, DigiMixer.CqSeries): frames are `7F type len32LE data`;
 * the client binds a UDP socket, tells the desk its port on TCP 51326 (type 0, port u16 LE), the desk answers with
 * its own UDP port (type 0), the client sends ClientInit (type 12, 02 00), then a keep-alive (type 5) every 3 s over
 * UDP to the desk's port and repeats the TCP handshake (the forum's heartbeat); the desk streams meters over UDP:
 * type 8 inputs (20 bytes per channel, the level at +14), type 9 outputs (16 bytes per channel, the level at +14;
 * 0..5 = Out1..6, 6/7 = Main L/R), types 10, 23, 24 not decoded yet (23 is the RTA candidate, 760 bytes).
 * Level: dB = (raw - 0x8000) / 256 - 18 (DigiMixer's offset, unverified on a CQ).
 */
class Meters(private val host: () -> String, private val scope: CoroutineScope, private val log: (String) -> Unit) {
    /** Input meters by input index (0 = Ip1), dBFS. */
    val inputs = MutableStateFlow(FloatArray(32) { -90f })
    /** Output meters: 0..5 Out1..6, 6 Main L, 7 Main R. */
    val outputs = MutableStateFlow(FloatArray(16) { -90f })
    val alive = MutableStateFlow(false)
    /** The raw RTA-candidate frame (type 23), kept for decoding. */
    @Volatile var raw23: ByteArray? = null
    private var job: Job? = null
    private var frames = 0; private var moving = false
    private var last8: ByteArray? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val ip = host()
                if (ip.isBlank()) { delay(2000); continue }
                try { session(ip) } catch (e: Exception) { alive.value = false; log("  meters: ${e.message}") }
                delay(3000)
            }
        }
    }

    private fun frame(type: Int, data: ByteArray): ByteArray {
        val b = ByteArray(6 + data.size); b[0] = 0x7F; b[1] = type.toByte()
        val n = data.size; b[2] = (n and 0xFF).toByte(); b[3] = (n shr 8 and 0xFF).toByte(); b[4] = (n shr 16 and 0xFF).toByte(); b[5] = (n shr 24 and 0xFF).toByte()
        System.arraycopy(data, 0, b, 6, n); return b
    }

    private suspend fun session(ip: String) {
        val udp = DatagramSocket(0); udp.soTimeout = 1000
        val myPort = udp.localPort
        val tcp = Socket(); tcp.connect(InetSocketAddress(ip, 51326), 3000); tcp.soTimeout = 3000
        val out = tcp.getOutputStream(); val inp = tcp.getInputStream()
        val handshake = frame(0, byteArrayOf((myPort and 0xFF).toByte(), (myPort shr 8).toByte()))
        out.write(handshake); out.flush()
        // the desk's answer: its own UDP port
        var deskPort = -1
        val t0 = System.currentTimeMillis()
        while (deskPort < 0 && System.currentTimeMillis() - t0 < 4000) {
            val (type, data) = readFrame(inp) ?: break
            if (type == 0 && data.size >= 2) deskPort = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        }
        if (deskPort < 0) { tcp.close(); udp.close(); throw java.io.IOException("no UDP port from the desk") }
        out.write(frame(1, ByteArray(0))); out.write(frame(12, byteArrayOf(2, 0))); out.flush()
        val desk = InetSocketAddress(InetAddress.getByName(ip), deskPort)
        log("  meters: desk UDP $deskPort, ours $myPort")
        // keep-alives: UDP every 3 s and the TCP handshake again (the forum's heartbeat); drain TCP meanwhile
        val keep = scope.launch(Dispatchers.IO) {
            val ka = frame(5, ByteArray(0))
            while (isActive) {
                try { udp.send(DatagramPacket(ka, ka.size, desk)); out.write(handshake); out.flush() } catch (_: Exception) { break }
                delay(2500)
            }
        }
        val drain = scope.launch(Dispatchers.IO) { val b = ByteArray(8192); try { while (isActive && inp.read(b) >= 0) {} } catch (_: Exception) {} }
        try {
            val buf = ByteArray(4096); var quiet = 0
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try { udp.receive(p) } catch (_: java.net.SocketTimeoutException) { if (++quiet > 5) throw java.io.IOException("meter stream stopped"); continue }
                quiet = 0
                parse(buf, p.length)
                alive.value = true
            }
        } finally { keep.cancel(); drain.cancel(); tcp.close(); udp.close(); alive.value = false }
    }

    private fun readFrame(inp: InputStream): Pair<Int, ByteArray>? {
        val h = ByteArray(6); var r = 0
        while (r < 6) { val n = inp.read(h, r, 6 - r); if (n < 0) return null; r += n }
        if (h[0] != 0x7F.toByte()) {
            // a fixed 8-byte F7 message: skip its remaining two bytes and try again
            inp.read(ByteArray(2)); return Pair(-1, ByteArray(0))
        }
        val len = (h[2].toInt() and 0xFF) or ((h[3].toInt() and 0xFF) shl 8) or ((h[4].toInt() and 0xFF) shl 16)
        val d = ByteArray(len); r = 0
        while (r < len) { val n = inp.read(d, r, len - r); if (n < 0) return null; r += n }
        return Pair(h[1].toInt() and 0xFF, d)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun db(raw: Int): Float = if (raw == 0) -90f else ((raw - 0x8000) / 256f - 18f).coerceIn(-90f, 18f)

    private fun parse(b: ByteArray, n: Int) {
        var i = 0
        while (i + 6 <= n && b[i] == 0x7F.toByte()) {
            val type = b[i + 1].toInt() and 0xFF
            val len = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
            val o = i + 6
            if (o + len > n) break
            when (type) {
                8 -> {
                    val a = FloatArray(len / 20) { db(u16(b, o + it * 20 + 14)) }
                    inputs.value = a
                    val snap = b.copyOfRange(o, o + len)
                    if (last8 != null && !snap.contentEquals(last8)) moving = true
                    last8 = snap
                    if (++frames == 200) log(if (moving) "  meters: live (input meters move)" else "  meters: frames arrive but do not move")
                }
                9 -> outputs.value = FloatArray(len / 16) { db(u16(b, o + it * 16 + 14)) }
                23 -> raw23 = b.copyOfRange(o, o + len)
            }
            i = o + len
        }
    }
}
