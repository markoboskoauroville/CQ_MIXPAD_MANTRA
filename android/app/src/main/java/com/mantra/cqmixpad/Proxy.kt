package com.mantra.cqmixpad

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Learning CQ MixPad's registration (7.11.2026). The desk answers our private-protocol commands (REC, STOP, live
 * meters) only after the 8 KB registration CQ MixPad sends when it connects (forum thread 32441: "if an ESP32 connects
 * without having gone through this registration process, nothing works"); its bytes were never published.
 * So: CQ MixPad is pointed at this phone ("Other…" → 127.0.0.1), this proxy listens on TCP 51326, connects to the
 * desk and passes every byte both ways, writing them down: files/mp_c2d.bin (MixPad → desk) and files/mp_d2c.bin
 * (desk → MixPad), each chunk as [8-byte ms time][4-byte length][bytes]. Started by files/proxy.flag.
 */
class Proxy(private val desk: () -> String, private val dir: File, private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private var job: Job? = null
    @Volatile var active = false; private set

    fun start(seconds: Int = 180) {
        if (job?.isActive == true) return
        active = true
        job = scope.launch(Dispatchers.IO) {
            val until = System.currentTimeMillis() + seconds * 1000L
            // MixPad's first contact is UDP 51320 "CQ Find": pass it to the desk and the desk's answer back
            val finder = scope.launch(Dispatchers.IO) {
                try {
                    java.net.DatagramSocket(null).use { s ->
                        s.reuseAddress = true; s.bind(InetSocketAddress(51320)); s.soTimeout = 2000
                        val up = java.net.DatagramSocket(); up.soTimeout = 1500
                        val buf = ByteArray(2048)
                        while (isActive && System.currentTimeMillis() < until) {
                            val p = java.net.DatagramPacket(buf, buf.size)
                            try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                            val q = p.data.copyOf(p.length)
                            File(dir, "mp_find.bin").appendBytes(q)
                            up.send(java.net.DatagramPacket(q, q.size, InetSocketAddress(desk(), 51320)))
                            val r = java.net.DatagramPacket(ByteArray(2048), 2048)
                            try { up.receive(r) } catch (_: java.net.SocketTimeoutException) { log("  proxy: the desk did not answer CQ Find"); continue }
                            val ans = r.data.copyOf(r.length)
                            File(dir, "mp_found.bin").appendBytes(ans)
                            s.send(java.net.DatagramPacket(ans, ans.size, p.socketAddress))
                            log("  proxy: CQ Find answered (${ans.size} bytes)")
                        }
                        up.close()
                    }
                } catch (e: Exception) { log("  proxy finder: ${e.message}") }
            }
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(51326)); server.soTimeout = 2000
                    log("  proxy: listening on :51326 (all interfaces) for CQ MixPad ($seconds s)")
                    while (isActive && System.currentTimeMillis() < until) {
                        val client = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                        log("  proxy: CQ MixPad connected")
                        val d = Socket(); d.connect(InetSocketAddress(desk(), 51326), 3000); d.tcpNoDelay = true; client.tcpNoDelay = true
                        val c2d = FileOutputStream(File(dir, "mp_c2d.bin"), true); val d2c = FileOutputStream(File(dir, "mp_d2c.bin"), true)
                        val a = scope.launch(Dispatchers.IO) { pipe(client.getInputStream(), d.getOutputStream(), c2d) }
                        val b = scope.launch(Dispatchers.IO) { pipe(d.getInputStream(), client.getOutputStream(), d2c) }
                        a.join(); b.cancel(); client.close(); d.close(); c2d.close(); d2c.close()
                        log("  proxy: session ended")
                    }
                }
            } catch (e: Exception) { log("  proxy: ${e.message}") }
            finder.cancel()
            active = false
            log("  proxy: stopped")
        }
    }

    private fun pipe(i: InputStream, o: OutputStream, rec: FileOutputStream) {
        val b = ByteArray(16384)
        try {
            while (true) {
                val n = i.read(b); if (n < 0) break
                o.write(b, 0, n); o.flush()
                synchronized(rec) {
                    rec.write(java.nio.ByteBuffer.allocate(12).putLong(System.currentTimeMillis()).putInt(n).array()); rec.write(b, 0, n); rec.flush()
                }
            }
        } catch (_: Exception) {}
    }
}
