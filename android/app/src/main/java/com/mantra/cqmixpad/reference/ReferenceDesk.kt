package com.mantra.cqmixpad.reference

import android.content.Context
import android.net.Uri
import com.mantra.cqmixpad.Recorder
import com.mantra.cqmixpad.Rta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Task 54 (Marko, 9.11.2026): "transfer audio from the mixer to the phone, analyze its profile and compare it with
 * Krishna Das or any other reference ... 'load reference': I load any MP3, WAV ... and you mix it in the way of that
 * reference file."
 *
 * The live Main comes from the best source there is: (1) the CQ's USB audio on the Pixel ([UsbAudio], real audio, the
 * file measure), (2) the desk's own RTA (31 bands over the private protocol, brought into the file measure with
 * Bands.CAL31), (3) the phone microphone (ten octaves, trends only). MATCH moves the Main LR PEQ (block 28, CH 38, the
 * four gains only; the desk's frequencies and widths stay) toward the selected reference; UNDO puts the four gains back
 * as they were in the dump taken before the first MATCH, then checks a fresh dump against it byte by byte.
 */
class ReferenceDesk(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val recorder: Recorder,
    private val rta: Rta,
    private val fbGuard: () -> Boolean,
    private val log: (String) -> Unit,
    private val say: (String) -> Unit,
    /** Sonnet, as MATCH KD asks it: the text goes with the RTA (now against the selected reference). */
    private val askSonnet: (String) -> Unit,
    /** "Indian Embassy 7.11.2026": the concert's place and date, for the recordings' names. */
    private val concertName: () -> String,
    /** Track n (1-based) as the band calls it: "Tajana", else "Ip3" / "Tr11". */
    private val labelOf: (Int) -> String = { if (it <= 10) "Ip$it" else "Tr$it" },
) {
    data class Live(val thirds: DoubleArray, val source: String, val seconds: Double, val levelDb: Double)

    val store = RefStore(ctx)
    val usb = UsbAudio(ctx, log)
    val multi = MultiTrack(ctx, scope, recorder, usb, log, labelOf, concertName)
    val live = MutableStateFlow<Live?>(null)
    val busy = MutableStateFlow("")
    val orange = MutableStateFlow<List<String>>(emptyList())
    val lines = MutableStateFlow<List<String>>(emptyList())
    val matching = MutableStateFlow(false)         // a MATCH session is open (UNDO possible)
    /** Seconds of the live average. */
    var windowSec = 20

    private var before: ByteArray? = null
    private var beforeGains: DoubleArray? = null
    private var mark: ByteArray? = null
    private val deskRing = ArrayDeque<DoubleArray>()
    private val matchLog = File(ctx.filesDir, "reference/match_log.txt").apply { parentFile?.mkdirs() }
    private val answer = File(ctx.filesDir, "ref_answer.txt")

    init {
        applyTarget()
        // the desk's RTA, averaged over [windowSec] (about 25 frames a second), only while it shows the Main
        scope.launch {
            recorder.rta.collect { b ->
                if (b == null || recorder.rtaSource.value != 0x38) return@collect
                val p = DoubleArray(31) { val d = b[it].toDouble(); if (d <= -105) 0.0 else Math.pow(10.0, d / 10) }
                if (p.sum() <= 0) return@collect
                synchronized(deskRing) { deskRing.addLast(p); while (deskRing.size > windowSec * 25) deskRing.removeFirst() }
            }
        }
        scope.launch { while (true) { delay(500); live.value = liveNow() } }
        // a text channel for tests (adb run-as): files/ref_command.txt in, files/ref_answer.txt out
        scope.launch(Dispatchers.IO) {
            val f = File(ctx.filesDir, "ref_command.txt")
            while (true) {
                delay(1000)
                if (f.exists()) { val t = f.readText().trim(); f.delete(); for (l in t.lines()) if (l.isNotBlank()) {
                    val r = try { command(l.trim()) } catch (e: Exception) { "ERROR ${e.javaClass.simpleName}: ${e.message}" }
                    answer.appendText("> $l\n$r\n\n")
                } }
            }
        }
    }

    private fun f1(d: Double?) = d?.let { String.format(Locale.US, "%+.1f", it) } ?: "?"
    private fun line(s: String) { log("  REF: $s"); lines.value = (listOf(s) + lines.value).take(40) }

    fun select(id: String) { store.select(id); applyTarget(); line("reference: ${store.current()?.name}") }
    private fun applyTarget() { store.current()?.let { rta.target = it.deskOctaves; rta.targetName = it.name } }

    fun liveNow(): Live? {
        usb.live?.let { a -> val t = a.liveThirds(); if (t != null && a.liveSeconds() >= 2 && a.momentary() > -90) return Live(t, "CQ USB", a.liveSeconds(), a.momentary()) }
        val fresh = System.currentTimeMillis() - recorder.rtaAt < 3000 && recorder.rtaSource.value == 0x38
        if (fresh) synchronized(deskRing) {
            if (deskRing.size >= 10) {
                val avg = DoubleArray(31) { i -> deskRing.sumOf { it[i] } / deskRing.size }
                val db = FloatArray(31) { if (avg[it] > 0) (10 * Math.log10(avg[it])).toFloat() else -110f }
                return Live(Bands.deskToFile31(db), "desk RTA", deskRing.size / 25.0, 10 * Math.log10(avg.sum()))
            }
        }
        if (rta.level.value > -80 && System.currentTimeMillis() - recorder.rtaAt > 3000) return Live(Bands.octToThirds(rta.live.value), "phone mic", rta.seconds().toDouble(), rta.level.value)
        return null
    }

    // ---------- LOAD REFERENCE ----------
    fun load(uri: Uri, name: String) {
        scope.launch(Dispatchers.IO) {
            busy.value = "reading $name …"
            try {
                val p = FileDecoder.analyse(ctx, uri, name) { busy.value = String.format(Locale.US, "reading %s … %.0f %%", name, it * 100) }
                store.save(p); select(p.id)
                line(String.format(Locale.US, "loaded %s: %.0f s, %s LUFS, crest %s, LRA %s, width %s", p.name, p.seconds, f1(p.lufs), f1(p.crestDb), f1(p.lra), f1(p.widthDb)))
                say("Reference ${p.name} loaded.")
            } catch (e: Exception) { line("could not read $name: ${e.message}"); say("I could not read that file.") }
            finally { busy.value = "" }
        }
    }

    fun addTake() { usb.lastTake.value?.let { store.save(it); select(it.id); line("the take ${it.name} is now a reference") } }

    // ---------- MIX TO REFERENCE ----------
    private val G0 = 16200      // Main LR PEQ in the dump: CH 0x38 * 280 + 520; gain +0, freq +2, width +4, band stride 6
    private fun mainBands(): List<Matcher.Band>? = (0 until 4).map { b ->
        val g = recorder.wordAt(G0 + 6 * b) ?: return null
        val hz = recorder.wordAt(G0 + 2 + 6 * b)?.let { Recorder.hzOf(it) } ?: return null
        val w = recorder.wordAt(G0 + 4 + 6 * b)?.let { if (it in 0..25) Recorder.WIDTHS[it] else 1.0 } ?: return null
        Matcher.Band(hz, w, (g - 0x8000) / 256.0)
    }
    private fun setMainGain(band: Int, db: Double) {
        val v = (0x8000 + Math.round(db.coerceIn(-15.0, 15.0) * 256)).toInt()
        recorder.patchAt(G0 + 6 * band, v)
        recorder.sendRaw(byteArrayOf(0xF7.toByte(), 0x28, 0x28, 0x0C, 0x38, band.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }
    /** A fresh 7F 04 dump from the desk (the app's copy is patched by every write; only a fresh one is the truth). */
    private suspend fun freshDump(): ByteArray? {
        val n0 = recorder.dumps.value
        if (!recorder.askState()) return null
        repeat(60) { delay(100); if (recorder.dumps.value > n0) return recorder.state0.value?.copyOf() }
        return null
    }
    private fun diff(a: ByteArray, b: ByteArray): List<Int> = (0 until minOf(a.size, b.size)).filter { a[it] != b[it] } + (if (a.size != b.size) listOf(-1) else emptyList())

    suspend fun match(): String = withContext(Dispatchers.IO) {
        val ref = store.current() ?: return@withContext "no reference selected"
        val lv = liveNow() ?: return@withContext "no live Main (no CQ USB audio, the desk's RTA not on Main, no phone mic)".also { say("I hear no live main mix.") }
        if (lv.levelDb < -70) return@withContext "the Main is silent (${f1(lv.levelDb)} dB): nothing to match".also { say("The main mix is silent, nothing to match.") }
        if (!recorder.connected.value) return@withContext "the desk's private link is not connected (CQ MixPad open?)".also { say("I am not connected to the mixer's private link.") }
        if (before == null) {
            val d = freshDump() ?: return@withContext "the desk sent no state dump: not matching".also { say("The mixer did not send its state, I will not touch it.") }
            before = d; beforeGains = mainBands()?.map { it.gainDb }?.toDoubleArray()
            line("dump before MATCH kept (${d.size} bytes); Main PEQ gains ${beforeGains?.joinToString(" ") { f1(it) }}")
        }
        val bands = mainBands() ?: return@withContext "the Main PEQ is not in the dump"
        val p = Matcher.plan(Bands.octShare(lv.thirds), ref.octaves, bands, beforeGains!!, fbGuard())
        val ts = java.text.SimpleDateFormat("d.M.yyyy HH:mm:ss", Locale.US).format(java.util.Date())
        val changed = ArrayList<String>()
        for (b in 0 until 4) if (Math.abs(p.gains[b] - bands[b].gainDb) >= 0.25) {
            setMainGain(b, p.gains[b])
            changed += String.format(Locale.US, "Main PEQ %s %.0f Hz: %+.1f -> %+.1f dB", Matcher.NAMES[b], bands[b].hz, bands[b].gainDb, p.gains[b])
        }
        val diffs = Bands.octShare(lv.thirds).indices.joinToString(" ") { Bands.OCT_NAMES[it] + " " + f1(Bands.octShare(lv.thirds)[it] - ref.octaves[it]) }
        matchLog.appendText("$ts MATCH to ${ref.name} from ${lv.source} (${lv.seconds.toInt()} s): live-ref $diffs\n" + changed.joinToString("") { "  $it\n" } +
            p.held.joinToString("") { "  held: $it\n" } + p.notes.joinToString("") { "  orange: $it\n" })
        changed.forEach { line(it) }; p.held.forEach { line("held: $it") }
        orange.value = p.notes
        matching.value = true
        val r = "MATCH to ${ref.name} (${lv.source}, ${lv.seconds.toInt()} s): live-ref $diffs\n" +
            (if (changed.isEmpty()) "no PEQ move needed" else changed.joinToString("\n")) +
            (if (p.held.isNotEmpty()) "\n" + p.held.joinToString("\n") else "") + (if (p.notes.isNotEmpty()) "\norange: " + p.notes.joinToString(" | ") else "")
        say(if (changed.isEmpty()) "The main is close to ${ref.name}; nothing to move on the main EQ." else "I moved ${changed.size} main EQ band${if (changed.size > 1) "s" else ""} toward ${ref.name}. Undo puts them back.")
        r
    }

    suspend fun undo(): String = withContext(Dispatchers.IO) {
        val b0 = before ?: return@withContext "nothing to undo"
        val g = beforeGains ?: return@withContext "nothing to undo"
        for (b in 0 until 4) setMainGain(b, g[b])
        delay(600)
        val now = freshDump()
        val d = now?.let { diff(b0, it) }
        val ts = java.text.SimpleDateFormat("d.M.yyyy HH:mm:ss", Locale.US).format(java.util.Date())
        val r = "UNDO: Main PEQ gains back to ${g.joinToString(" ") { f1(it) }}; dump diff against the one before MATCH: " +
            (d?.let { if (it.isEmpty()) "ZERO" else "${it.size} bytes at ${it.take(12).joinToString(",")}" } ?: "no fresh dump")
        matchLog.appendText("$ts $r\n"); line(r)
        if (d?.isEmpty() == true) { before = null; beforeGains = null; matching.value = false; orange.value = emptyList() }
        say(if (d?.isEmpty() == true) "Undone. The mixer is exactly as before." else "Undone, but the mixer differs from before in ${d?.size ?: "unknown"} places. Look at the log.")
        r
    }

    fun ask() {
        val ref = store.current() ?: return
        askSonnet("Compare the RTA with the ${ref.name} reference profile and suggest how to fit the mix, vocals in the first plan. " +
            "The Main LR PEQ is already moved by MATCH; suggest fader balance and, as orange steps, what the desk link cannot set.")
    }

    /** For the panel: recompose when the desk's dump or armed tracks change. */
    @androidx.compose.runtime.Composable
    fun recorderState() = androidx.compose.runtime.produceState(0, recorder) {
        launch { recorder.state0.collect { value = value + 1 } }
        launch { recorder.armed.collect { value = value + 1 } }
    }
    fun armedTracks(): List<Int> = recorder.armed.value?.let { a -> (1..16).filter { a and (1 shl (it - 1)) != 0 } } ?: emptyList()
    fun deskRec() { recorder.rec(); line("desk SD REC sent; the phone follows with the armed tracks") }
    fun deskStop() { recorder.stop(); line("desk SD STOP sent") }

    fun recName(): String = concertName().ifBlank { "CQ" } + " " + java.text.SimpleDateFormat("HHmm", Locale.US).format(java.util.Date())

    /** The test channel's commands. */
    private suspend fun command(c: String): String {
        val w = c.substringBefore(' ').uppercase(); val arg = c.substringAfter(' ', "").trim()
        return when (w) {
            "LOAD" -> { val path = arg.substringBefore('|').trim(); val name = arg.substringAfter('|', File(path).nameWithoutExtension).trim()
                val p = withContext(Dispatchers.IO) { FileDecoder.analyse(ctx, Uri.fromFile(File(path)), name) {} }
                store.save(p); select(p.id); "loaded " + describe(p) }
            "SELECT" -> { val p = store.all.value.firstOrNull { it.name.equals(arg, true) || it.id == arg } ?: return "no reference $arg"; select(p.id); "selected ${p.name}" }
            "DELETE" -> { val p = store.all.value.firstOrNull { it.name.equals(arg, true) || it.id == arg } ?: return "no reference $arg"; store.delete(p.id); "deleted ${p.name}" }
            "MATCH" -> match()
            "UNDO" -> undo()
            "MARK" -> { mark = freshDump(); "mark: ${mark?.size ?: "no dump"} bytes" }
            "DIFF" -> { val a = mark ?: return "no mark"; val b = freshDump() ?: return "no fresh dump"; val d = diff(a, b); "diff vs mark: " + if (d.isEmpty()) "ZERO" else "${d.size} bytes at ${d.take(20)}" }
            "LISTEN" -> { usb.start(false, recName(), false, false); delay(1500); usb.status.value }
            "REC" -> { usb.stop(); usb.start(true, recName(), arg.contains("all", true), arg.contains("aac", true)); delay(1500); usb.status.value }
            "STOP" -> { usb.stop(); "stopped: ${usb.lastFile.value}; take " + (usb.lastTake.value?.let { describe(it) } ?: "none") }
            "ADDTAKE" -> { addTake(); "selected ${store.current()?.name}" }
            "STATUS" -> status()
            "STREAM" -> { multi.setStream(arg.startsWith("m", true)); delay(4000); "stream mode now ${multi.streamMode()}; USB ${usb.device.value}" }
            "PROBE" -> withContext(Dispatchers.IO) { multi.probe() }
            "MSTART" -> { multi.start(arg.ifBlank { "PHONE-" + java.text.SimpleDateFormat("HHmmss", Locale.US).format(java.util.Date()) }); delay(2500); multi.status.value }
            "MSTOP" -> { withContext(Dispatchers.IO) { multi.stop() }; multi.status.value }
            "DESKREC" -> { recorder.rec(); delay(4000); "desk ${recorder.state.value} ${recorder.take.value}; phone: ${multi.status.value}" }
            "DESKSTOP" -> { recorder.stop(); delay(5000); "desk ${recorder.state.value}; phone: ${multi.status.value}" }
            "MSTATUS" -> "desk ${recorder.state.value} take ${recorder.take.value} armed ${recorder.armed.value?.let { Integer.toBinaryString(it) }} stream ${multi.streamMode()}; phone running ${multi.running.value}: ${multi.status.value}; free ${multi.freeBytes() / 1_000_000} MB; takes ${multi.takes.value.map { it.first + " (" + it.second.size + ")" }}"
            else -> "commands: STREAM multi|stereo, PROBE, MSTART [take], MSTOP, DESKREC, DESKSTOP, MSTATUS, LOAD <path>|<name>, SELECT <name>, DELETE <name>, MATCH, UNDO, MARK, DIFF, LISTEN, REC [all] [aac], STOP, ADDTAKE, STATUS"
        }
    }

    fun describe(p: Profile) = String.format(Locale.US, "%s: %.0f s, %s LUFS, peak %s, crest %s, LRA %s, width %s; octaves %s", p.name, p.seconds,
        f1(p.lufs), f1(p.peakDb), f1(p.crestDb), f1(p.lra), f1(p.widthDb), p.octaves.joinToString(" ") { String.format(Locale.US, "%.1f", it) })

    fun status(): String = buildString {
        val ref = store.current()
        append("references: ").append(store.all.value.joinToString(", ") { it.name }).append("\nselected: ").append(ref?.let { describe(it) }).append('\n')
        append("USB: ").append(usb.device.value ?: "none").append(" · ").append(usb.status.value).append('\n')
        val lv = liveNow()
        if (lv == null) append("live: none\n") else {
            val o = Bands.octShare(lv.thirds)
            append(String.format(Locale.US, "live (%s, %.0f s, %.0f dB): ", lv.source, lv.seconds, lv.levelDb)).append(o.joinToString(" ") { String.format(Locale.US, "%.1f", it) }).append('\n')
            if (ref != null) append("live-ref: ").append(o.indices.joinToString(" ") { Bands.OCT_NAMES[it] + " " + f1(o[it] - ref.octaves[it]) }).append('\n')
        }
        usb.lastTake.value?.let { t -> append("last take: ").append(describe(t)).append('\n')
            if (ref != null) append("take-ref: ").append(t.octaves.indices.joinToString(" ") { Bands.OCT_NAMES[it] + " " + f1(t.octaves[it] - ref.octaves[it]) }).append('\n') }
        append("match session open: ").append(matching.value)
    }
}
