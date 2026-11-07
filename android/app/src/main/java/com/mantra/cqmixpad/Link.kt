package com.mantra.cqmixpad

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The one TCP MIDI connection to the CQ (the CQ allows only one at a time; CQ MixPad uses its own protocol, so both
 * work together). Finds the mixer on the Ethernet cable (the Pixel's Ethernet tethering hands it an address), asks for
 * every value, and keeps [values] in step with whatever moves on the desk or in MixPad.
 */
class Link(private val ctx: Context, private val scope: CoroutineScope) {
    val status = MutableStateFlow("looking for the mixer")
    val connected = MutableStateFlow(false)
    val host = MutableStateFlow("")
    /** parameter → 14-bit value, as the CQ last reported it. */
    val values = ConcurrentHashMap<Int, Int>()
    /** Bumped on every change, so the screen redraws. */
    val tick = MutableStateFlow(0L)
    /** Sources the CQ answered for (its real channels). */
    val present = MutableStateFlow<List<Cq.Src>>(emptyList())

    private var out: OutputStream? = null
    private var socket: Socket? = null
    private var job: Job? = null
    private val prefs = ctx.getSharedPreferences("link", Context.MODE_PRIVATE)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val ip = find()
                if (ip == null) { status.value = "no mixer on the cable: is Ethernet tethering on?"; delay(3000); continue }
                try { run(ip) } catch (e: Exception) { status.value = "lost the mixer (${e.message}), trying again" }
                connected.value = false
                delay(1500)
            }
        }
    }

    /** The last address first, then every address of the Ethernet subnet, port 51325. */
    private suspend fun find(): String? = withContext(Dispatchers.IO) {
        val last = prefs.getString("ip", null)
        if (last != null && probe(last, 600)) return@withContext last
        val bases = ethernetPrefixes()
        if (bases.isEmpty()) return@withContext null
        status.value = "scanning ${bases.joinToString { "$it.x" }}"
        for (base in bases) {
            val hits = (1..254).map { h -> async { if (probe("$base.$h", 400)) "$base.$h" else null } }.awaitAll().filterNotNull()
            if (hits.isNotEmpty()) { prefs.edit().putString("ip", hits.first()).apply(); return@withContext hits.first() }
        }
        null
    }

    private fun probe(ip: String, ms: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, Cq.PORT), ms) }; true
    } catch (_: Exception) { false }

    /** "10.173.135" for every IPv4 /24 on an eth or usb interface. */
    private fun ethernetPrefixes(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && (it.name.startsWith("eth") || it.name.startsWith("usb")) }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress?.substringBeforeLast('.') }
            .distinct()
    } catch (_: Exception) { emptyList() }

    private fun run(ip: String) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(ip, Cq.PORT), 2000)
        socket = s; out = s.getOutputStream(); host.value = ip
        connected.value = true
        status.value = "connected to the CQ at $ip"
        values.clear()
        val parser = Cq.Parser { param, v -> values[param] = v; tick.value = tick.value + 1 }
        scope.launch(Dispatchers.IO) { readAll() }
        val input = s.getInputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) throw java.io.IOException("closed by the mixer")
            parser.feed(buf, n)
        }
    }

    /** Asks the CQ for every value we show, then decides which channels exist. */
    private suspend fun readAll() {
        val asks = ArrayList<Int>()
        for (src in Cq.SOURCES) {
            asks += Cq.muteParam(src); asks += Cq.panMainParam(src)
            for (m in Cq.MIXES) Cq.sendParam(src, m)?.let { asks += it }
        }
        for (m in Cq.MIXES) { Cq.masterParam(m)?.let { asks += it }; Cq.masterMuteParam(m)?.let { asks += it } }
        for (d in 1..4) { asks += Cq.dcaLevelParam(d); asks += Cq.dcaMuteParam(d) }
        for (chunk in asks.chunked(24)) {
            val o = out ?: return
            val bytes = chunk.map { Cq.get(it) }.reduce { a, b -> a + b }
            synchronized(this) { o.write(bytes); o.flush() }
            delay(30)
        }
        delay(800)
        present.value = Cq.SOURCES.filter { values.containsKey(Cq.muteParam(it)) }
        status.value = "connected to the CQ at ${host.value}, ${present.value.size} channels"
    }

    /** One writer, in order: a fader drag reaches the desk in the order it was made. */
    private val queue = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init {
        scope.launch(Dispatchers.IO) {
            for (bytes in queue) {
                val o = out ?: continue
                try { synchronized(this@Link) { o.write(bytes); o.flush() } } catch (_: Exception) { socket?.close() }
            }
        }
    }

    fun send(bytes: ByteArray) { queue.trySend(bytes) }

    // ---- what the screen and the brain call ----

    fun db(param: Int): Double = values[param]?.let { Cq.valueToDb(it) } ?: Cq.MIN_DB
    fun muted(param: Int): Boolean = (values[param] ?: 0) == 1

    fun setDb(param: Int, db: Double) {
        val v = Cq.dbToValue(db); values[param] = v; tick.value = tick.value + 1
        send(Cq.setValue(param, v))
    }
    fun setMute(param: Int, on: Boolean) {
        values[param] = if (on) 1 else 0; tick.value = tick.value + 1
        send(Cq.setMute(param, on))
    }
    fun setPan(param: Int, pct: Double) {
        val v = Cq.panToValue(pct); values[param] = v; tick.value = tick.value + 1
        send(Cq.setValue(param, v))
    }
    fun scene(n: Int) { send(Cq.scene(n)); scope.launch(Dispatchers.IO) { delay(700); readAll() } }
    fun softKey(n: Int) { send(Cq.softKeyPress(n)); scope.launch { delay(120); send(Cq.softKeyRelease(n)) } }
    fun refresh() { scope.launch(Dispatchers.IO) { readAll() } }
}
