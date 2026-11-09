package com.mantra.cqmixpad

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

private val Bg = Color(0xFF0A0F1C)
private val Panel = Color(0xFF141C2E)
private val Rim = Color(0xFF263149)
private val Text1 = Color(0xFFF2F2F7)
private val Grey = Color(0xFF8E8E93)
private val Red = Color(0xFFFF453A)
private val Green = Color(0xFF33D17A)
private val Amber = Color(0xFFFF9F0A)
private val Blue = Color(0xFF0A84FF)
private val Cyan = Color(0xFF64D2FF)
private val Sand = Color(0xFFF2DDB4)      // TTT's ink

/** The CQ-12T's meter frames arrive but their layout is not decoded (7.11.2026): hidden until it is. */
private const val METERS_DECODED = false

/** The mixer's own RTA is not decoded (7.11.2026). Until it is, nothing is mixed automatically from the Krishna Das reference. */
private const val RTA_DECODED = true   // the desk's RTA, decoded 7.11.2026 (Recorder.rta)
private val RecRed = Color(0xFFEF4444)    // TTT's recording red
/** Strip width: wide enough for a thumb (Marko, 8.11.2026: "adapted to the fat finger"). */
private val STRIP_W = 80.dp
/** The picture's channel colour buttons (9.11.2026): pink, orange, yellow, green, cyan, light blue, violet, mint. The desk's own
 *  channel colours are not read (PROTOCOL.md: colour is a write-only 7F 07 message so far), so a fixed palette by channel. */
private val ChPalette = listOf(Color(0xFFF472B6), Color(0xFFFB923C), Color(0xFFFACC15), Color(0xFF4ADE80),
    Color(0xFF22D3EE), Color(0xFF7DD3FC), Color(0xFFA78BFA), Color(0xFF5EEAD4))
private fun chColor(code: Int) = ChPalette[Math.floorMod(code, ChPalette.size)]
private val Cap = Color(0xFFE6EAF2)
private val Dark = Color(0xFF0F1626)

class MainActivity : ComponentActivity() {
    private lateinit var link: Link
    private lateinit var store: Store
    private lateinit var brain: Brain
    private val rta = Rta()
    private var rtaJob: Job? = null
    private lateinit var speech: Speech
    private lateinit var voice: Voice
    private lateinit var hear: Hear
    private lateinit var meters: Meters
    private lateinit var recorder: Recorder
    private val feedback = Feedback()
    private var fbGuard by mutableStateOf(true)
    /** The desk's meters about 1.5 s ago, to see which microphone rose with a ringing tone. */
    private val meterHistory = ArrayDeque<Pair<FloatArray, FloatArray>>()
    private val fbCut = HashMap<Int, Double>()
    private var fbLast = 0L
    private var fbLastHz = 0.0

    private val log = mutableStateListOf<String>()
    private val pending = mutableStateListOf<Brain.Action>()
    private var busy by mutableStateOf(false)
    private var heard by mutableStateOf("")
    private var speakAloud by mutableStateOf(true)
    private var micOk by mutableStateOf(false)
    private var rtaOn by mutableStateOf(false)
    private var rtaWasOn = false
    private var listening by mutableStateOf(false)
    private var recPaused by mutableStateOf(false)
    private var recStart by mutableStateOf(0L)
    /** The mic level while recording a command (dBFS), for the VU meter. */
    private val micDb = kotlinx.coroutines.flow.MutableStateFlow(-90f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        link = Link(this, lifecycleScope)
        brain = Brain(store)
        speech = Speech(this, lifecycleScope, { log.add(0, it) }, { on -> rta.deaf = on })
        voice = Voice(this, onPartial = { heard = it }, onFinal = { t ->
            heard = ""; listening = false
            if (rtaWasOn) { rtaWasOn = false; toggleRta() }
            if (!t.isNullOrBlank()) command(t, fromVoice = true) else say("I did not catch that.")
        })
        hear = Hear(this)
        link.start()
        meters = Meters({ link.host.value }, lifecycleScope, { log.add(0, it) }, filesDir)
        // the meter session stays off until the CQ-12T's meter layout is decoded (it competed for the desk's port)
        if (METERS_DECODED) meters.start()
        recorder = Recorder({ link.host.value }, lifecycleScope) { log.add(0, it) }
        recorder.startsStore = getSharedPreferences("takes", MODE_PRIVATE)
        // the desk's RTA feeds the RTA strip (the phone microphone only when the desk does not stream)
        lifecycleScope.launch { recorder.rta.collect { b -> if (b != null) { rta.feedDesk(b); if (!rtaOn) rtaOn = true } } }
        recorder.start()
        lifecycleScope.launch {     // a new take while a concert is on: written into that concert
            var was = Recorder.State.UNKNOWN
            recorder.state.collect { s ->
                if (s == Recorder.State.RECORDING && was != Recorder.State.RECORDING) {
                    kotlinx.coroutines.delay(1500); store.addTake(recorder.take.value.ifBlank { "take " + java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date()) })
                }
                was = s
            }
        }
        lifecycleScope.launch {          // meter history, 4 per second, 2 s long
            while (true) {
                kotlinx.coroutines.delay(250)
                meterHistory.addLast(meters.inputs.value.copyOf() to meters.outputs.value.copyOf())
                while (meterHistory.size > 8) meterHistory.removeFirst()
            }
        }
        lifecycleScope.launch {          // the desk's meters for Sonnet: 4 a second, 10 s
            while (true) {
                kotlinx.coroutines.delay(250)
                if (recorder.metersAlive()) {
                    val i = recorder.inMeters.value; val o = recorder.outMeters.value
                    levelHist.addLast(i.copyOf(16) + floatArrayOf(o[6], o[7])); while (levelHist.size > 40) levelHist.removeFirst()
                }
            }
        }
        lifecycleScope.launch { recordingWatch() }
        // learning MixPad's registration: files/proxy.flag starts the proxy (the meter session pauses meanwhile)
        val proxy = Proxy({ link.host.value }, filesDir, lifecycleScope) { log.add(0, it) }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val flag = java.io.File(filesDir, "proxy.flag")
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (flag.exists()) { val s = flag.readText().trim().toIntOrNull() ?: 180; flag.delete(); meters.pause(s + 5); proxy.start(s) }
            }
        }
        // a text channel for tests and helpers (8.11.2026): files/command.txt (adb run-as) is carried out as a typed
        // command (a first word MATCH: asks with the RTA, suggestions wait for APPLY); every answer goes to files/answer.txt
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val f = java.io.File(filesDir, "command.txt")
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (f.exists()) { val t = f.readText().trim(); f.delete(); if (t.isNotBlank()) runOnUiThread {
                    if (t.startsWith("MATCH:")) command(t.removePrefix("MATCH:").trim(), fromVoice = false, withRta = true) else command(t, fromVoice = false) } }
            }
        }
        // the feedback guard listens to the phone in the room; it stays quiet while the desk plays from the SD card
        // (8.11.2026: a sustained note of the playback read as feedback at 199 Hz)
        rta.onSpectrum = { spec -> if (fbGuard && !sdPlaying()) feedback.onSpectrum(spec)?.let { e -> runOnUiThread { onFeedback(e) } } }
        // the RTA candidate (type 23) saved every 2 s for decoding: files/rta23.bin, 760-byte records
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val f = java.io.File(filesDir, "rta23.bin"); val f8 = java.io.File(filesDir, "m8.bin")
            var tick = 0
            while (true) {
                kotlinx.coroutines.delay(250); tick++
                if (tick % 8 == 0) meters.raw23?.let { if (f.length() < 3_000_000) f.appendBytes(it) }
                // raw input meters, 4 per second, a rolling 60 s (decoding the CQ-12T's meter order)
                meters.raw8?.let { if (f8.length() > 640L * 240) f8.writeBytes(ByteArray(0)); f8.appendBytes(it) }
            }
        }
        setContent { ZoomPan { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micOk) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        else if (fbGuard && !rtaOn && !listening) toggleRta()      // the feedback guard listens from the start
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk && fbGuard && !rtaOn) toggleRta()
    }

    override fun onDestroy() { speech.shutdown(); voice.stop(); rta.stop(); super.onDestroy() }

    private fun say(t: String) {
        log.add(0, t); while (log.size > 30) log.removeAt(log.lastIndex)
        if (speakAloud) speech.say(t)
    }

    // ---------- the desk as text, for Sonnet ----------

    /** The last 10 s of the desk's meters, 4 a second: inputs Ip1..Ip16 and Main L/R. */
    private val levelHist = ArrayDeque<FloatArray>()
    private fun levelsText(): String? {
        if (!recorder.metersAlive() || levelHist.size < 8) return null
        val labels = store.labels.value
        val n = levelHist.first().size
        fun avg(k: Int) = 10 * kotlin.math.log10(levelHist.map { Math.pow(10.0, it[k] / 10.0) }.average() + 1e-12)
        fun peak(k: Int) = levelHist.maxOf { it[k] }.toDouble()
        return buildString {
            append("MEASURED LEVELS, last ${levelHist.size / 4} s (desk meters, dB, 0 = nominal about -18 dBFS; post-preamp, before the fader; -110 = silent):\n")
            for (k in 0 until minOf(16, n - 2)) if (peak(k) > -100) {
                val id = "Ip${k + 1}"; val l = labels[id]
                append(String.format(Locale.US, "%s%s: average %.1f, peak %.1f\n", id, l?.let { " [${it.name}]" } ?: "", avg(k), peak(k)))
            }
            append(String.format(Locale.US, "MAIN L/R (after faders): average %.1f / %.1f, peak %.1f / %.1f\n", avg(n - 2), avg(n - 1), peak(n - 2), peak(n - 1)))
            append("A channel's level in the mix = its measured average + its MAIN fader dB. Silent channels are not listed.\n")
        }
    }

    private fun deskText(): String = buildString {
        val labels = store.labels.value
        for (s in link.present.value) {
            val l = labels[s.id]
            append(s.id)
            if (l != null) append(" [${l.name}, ${l.instrument}]")
            append(": ").append(if (link.muted(Cq.muteParam(s))) "MUTED" else "on")
            append(", pan ").append(link.values[Cq.panMainParam(s)]?.let { Cq.valueToPan(it).toInt() } ?: 0)
            for (m in Cq.MIXES.take(9)) Cq.sendParam(s, m)?.let { p -> link.values[p]?.let { append(", $m ${Cq.dbText(Cq.valueToDb(it))}") } }
            append('\n')
        }
        append("MASTERS: ").append(Cq.MIXES.take(9).mapNotNull { m -> Cq.masterParam(m)?.let { p -> link.values[p]?.let { "$m ${Cq.dbText(Cq.valueToDb(it))}" } } }.joinToString(", ")).append('\n')
        Cq.masterMuteParam("MAIN")?.let { append("MAIN master ").append(if (link.muted(it)) "MUTED" else "on").append('\n') }
        for (d in 1..4) link.values[Cq.dcaLevelParam(d)]?.let { append("DCA$d ${Cq.dbText(Cq.valueToDb(it))}${if (link.muted(Cq.dcaMuteParam(d))) " MUTED" else ""}\n") }
        // the desk's measured levels (8.11.2026): what each player really delivers, so the balance can be judged
        levelsText()?.let { append(it) }
        // each input's processing as the desk holds it now (read from its state; set_eq / set_hpf set ABSOLUTE values)
        if (recorder.state0.value != null) {
            append("INPUT SETTINGS NOW (from the desk): ")
            append((0 until 10).joinToString("; ") { k ->
                fun f(d: Double?) = d?.let { String.format(Locale.US, "%+.1f", it) } ?: "?"
                "Ip${k + 1} trim ${f(recorder.trimNow(k))}, HPF ${if (recorder.hpfOnNow(k) == true) String.format(Locale.US, "%.0f Hz", recorder.hpfHzNow(k) ?: 0.0) else "off"}, EQ " +
                    listOf("LF", "LM", "HM", "HF").mapIndexed { b, n -> "$n ${f(recorder.peqNow(k, b))}@${recorder.peqHzNow(k, b)?.let { String.format(Locale.US, "%.0f", it) } ?: "?"}" }.joinToString(" ") +
                    (recorder.compNow(k)?.let { ", $it" } ?: "")
            }).append('\n')
        }
        if (METERS_DECODED && meters.alive.value) {
            val inp = meters.inputs.value; val o = meters.outputs.value
            append("LIVE METERS (dBFS, the desk's own): ")
            append(link.present.value.filter { it.code < 16 && it.code < inp.size }.joinToString(", ") { "${it.id} ${inp[it.code].toInt()}" })
            if (o.size > 7) append(", Main L ${o[6].toInt()} R ${o[7].toInt()}")
            append('\n')
        }
    }

    // ---------- Sonnet ----------

    private fun command(text: String, fromVoice: Boolean, withRta: Boolean = false) {
        if (busy) return
        if (store.apiKey() == null) { say("There is no Anthropic key on the phone yet. Tap KEY."); return }
        busy = true
        log.add(0, "YOU: $text")
        lifecycleScope.launch {
            try {
                val rtaText = if (withRta || rtaJob?.isActive == true) rta.text() else null
                held.clear()
                val t0 = System.currentTimeMillis()
                val a = brain.ask(text, deskText(), rtaText, link.present.value.map { it.id }, effortHigh = withRta)
                log.add(0, String.format(Locale.US, "  (Sonnet %.1f s)", (System.currentTimeMillis() - t0) / 1000.0))
                try { java.io.File(filesDir, "answer.txt").appendText("YOU: $text\nSONNET: ${a.say}\nACTIONS: ${a.actions.joinToString(" | ") { it.text() }}\n\n") } catch (_: Exception) {}
                if (withRta) { pending.clear(); pending.addAll(a.actions.filter { it.name !in setOf("remember", "note_for_marko", "set_label") }) }
                for (act in a.actions) {
                    if (!withRta || act.name in setOf("remember", "note_for_marko", "set_label")) apply(act)
                }
                // the concert keeps what was done tonight
                if (!withRta && a.actions.any { it.name in setOf("set_level", "change_level", "set_mute", "set_pan", "set_eq", "set_hpf", "set_trim", "set_comp") }) {
                    kotlinx.coroutines.delay(400); store.saveMix(HashMap(link.values), deskText())
                }
                // always spoken: Sonnet's sentence, or what was done when it gave none
                val done = a.actions.filter { it.name != "note_for_marko" && it.name != "remember" }.map { it.text() }
                val notes = a.actions.count { it.name == "note_for_marko" }
                when {
                    a.say.isNotBlank() -> say((a.say + " " + held.joinToString(" ")).trim())
                    withRta && pending.isNotEmpty() -> say("I suggest ${pending.size} moves. Tap apply to make them.")
                    done.isNotEmpty() -> say(a.actions.filter { it.name != "note_for_marko" && it.name != "remember" }.map { it.spoken(store.labels.value) }.filter { it.isNotBlank() }
                        .joinToString(", ").replaceFirstChar { it.uppercase() } + (if (notes > 0) ". And $notes step${if (notes > 1) "s" else ""} for you in MixPad." else ".") + (if (held.isNotEmpty()) " " + held.joinToString(" ") else ""))
                    notes > 0 -> say("$notes step${if (notes > 1) "s" else ""} for you in MixPad, in orange.")
                    else -> say("Nothing to change.")
                }
            } catch (e: Exception) {
                val chain = generateSequence(e as Throwable?) { it.cause }.take(6).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
                log.add(0, "  ✗ $chain")
                try { java.io.File(filesDir, "answer.txt").appendText("YOU: $text\nERROR: $chain\n\n") } catch (_: Exception) {}
                say("Sonnet did not answer: ${e.message?.take(140)}")
            } finally { busy = false }
        }
    }

    /** Carries out one action; the safety limits are here, not only in the prompt. */
    /** Moves the safety limit changed, said after the answer so the voice never claims what the desk did not do. */
    private val held = ArrayList<String>()

    private fun apply(a: Brain.Action) {
        val i = a.input
        val ch = i.optString("channel"); val mix = i.optString("mix", "MAIN").ifBlank { "MAIN" }
        fun levelParam(): Int? = when {
            ch == "MASTER" -> Cq.masterParam(mix)
            ch.startsWith("DCA") -> ch.removePrefix("DCA").toIntOrNull()?.let { Cq.dcaLevelParam(it) }
            else -> Cq.src(ch)?.let { Cq.sendParam(it, mix) }
        }
        when (a.name) {
            "set_level", "change_level" -> {
                val p = levelParam() ?: return log.add(0, "  ✗ ${a.text()} (no such control)")
                val now = link.db(p)
                var target = if (a.name == "set_level") i.optDouble("db") else (if (now <= Cq.MIN_DB) -40.0 else now) + i.optDouble("delta_db")
                if (target.isNaN()) return
                target = target.coerceIn(Cq.MIN_DB, Cq.MAX_DB)
                if (now > Cq.MIN_DB && target - now > 6.0) {
                    target = now + 6.0; log.add(0, "  ! limited to +6 dB at once")
                    val who = store.labels.value[ch]?.let { it.name.ifBlank { it.instrument.substringBefore(",") } } ?: ch
                    held += "I held $who to plus 6 dB at once, for safety, now ${Cq.dbText(target).replace("+", "plus ").replace("-", "minus ")} dB."
                }
                link.setDb(p, target)
                log.add(0, "  ✓ ${a.text()}")
            }
            "set_mute" -> {
                val muted = i.optBoolean("muted", true)
                val p = when {
                    ch == "MASTER" -> Cq.masterMuteParam(mix)
                    ch.startsWith("DCA") -> ch.removePrefix("DCA").toIntOrNull()?.let { Cq.dcaMuteParam(it) }
                    else -> Cq.src(ch)?.let { Cq.muteParam(it) }
                } ?: return
                link.setMute(p, muted); log.add(0, "  ✓ ${a.text()}")
            }
            "set_pan" -> { Cq.src(ch)?.let { link.setPan(Cq.panMainParam(it), i.optDouble("percent", 0.0).coerceIn(-100.0, 100.0)) }; log.add(0, "  ✓ ${a.text()}") }
            "set_label" -> { store.setLabel(ch, i.optString("name"), i.optString("role")); log.add(0, "  ✓ ${a.text()}") }
            "recall_scene" -> { link.scene(i.optInt("number", 1)); log.add(0, "  ✓ ${a.text()}") }
            "soft_key" -> { link.softKey(i.optInt("number", 1)); log.add(0, "  ✓ ${a.text()}") }
            "remember" -> { store.remember(i.optString("note")); log.add(0, "  ✓ ${a.text()}") }
            "note_for_marko" -> log.add(0, "🟠 ${i.optString("text")}")
            "set_trim" -> {
                val c = Cq.src(ch)?.code?.takeIf { ch.startsWith("Ip") && it < 10 } ?: return log.add(0, "  ✗ ${a.text()} (trim only on Ip1..Ip10)")
                val ok = recorder.trim(c, i.optDouble("db", 0.0))
                log.add(0, if (ok) "  ✓ ${a.text()}" else "  ✗ ${a.text()} (no private link to the desk)")
            }
            "set_comp" -> {
                val c = Cq.src(ch)?.code?.takeIf { ch.startsWith("Ip") && it < 10 } ?: return log.add(0, "  ✗ ${a.text()} (compressor only on Ip1..Ip10)")
                var ok = true
                if (i.has("threshold_db")) ok = recorder.compThreshold(c, i.optDouble("threshold_db")) && ok
                if (i.has("ratio")) ok = recorder.compRatio(c, i.optDouble("ratio")) && ok
                if (i.has("gain_db")) ok = recorder.compGain(c, i.optDouble("gain_db")) && ok
                if (i.has("attack_ms")) ok = recorder.compAttack(c, i.optDouble("attack_ms")) && ok
                if (i.has("release_ms")) ok = recorder.compRelease(c, i.optDouble("release_ms")) && ok
                if (i.has("on")) ok = recorder.compIn(c, i.optBoolean("on")) && ok
                log.add(0, if (ok) "  ✓ ${a.text()}" else "  ✗ ${a.text()} (no private link to the desk)")
            }
            "set_eq", "set_hpf" -> {
                val c = Cq.src(ch)?.code?.takeIf { ch.startsWith("Ip") && it < 10 } ?: return log.add(0, "  ✗ ${a.text()} (EQ only on Ip1..Ip10)")
                val ok = if (a.name == "set_eq") {
                    val band = listOf("LF", "LM", "HM", "HF").indexOf(i.optString("band")).coerceAtLeast(0)
                    (!i.has("freq_hz") || recorder.peqHz(c, band, i.optDouble("freq_hz"))) &&
                    (!i.has("width_oct") || band == 0 || band == 3 || recorder.peqWidth(c, band, i.optDouble("width_oct"))) &&
                    recorder.peqGain(c, band, i.optDouble("gain_db", 0.0)) && recorder.peqIn(c, true)
                } else {
                    val on = i.optBoolean("on", true)
                    (!on || i.optDouble("hz", 0.0) <= 0 || recorder.hpfHz(c, i.optDouble("hz"))) && recorder.hpf(c, on)
                }
                log.add(0, if (ok) "  ✓ ${a.text()}" else "  ✗ ${a.text()} (no private link to the desk)")
            }
        }
    }

    /** Words the recogniser should expect: tonight's names and roles and the mixing words. */
    private fun vocabulary(): List<String> = (store.labels.value.values.flatMap { listOf(it.name, it.instrument.substringBefore(",")) } +
        listOf("djembe", "mridangam", "Svemir", "Tajana", "Igor", "Snoopy", "Santoshi", "Krishna Das", "monitor", "reverb",
            "decibels", "mute", "unmute", "pan", "backing vocal", "lead vocal", "bass", "guitar", "channel", "scene")).filter { it.isNotBlank() }.distinct()

    /** SPEAK: AssemblyAI (Sync, the band's words in its prompt); Android's recogniser only when AssemblyAI fails. */
    private fun startListening() {
        speech.stop()
        if (rtaOn) { rta.stop(); rtaJob = null; rtaOn = false; rtaWasOn = true }
        listening = true
        if (!hear.hasKeys()) { voice.hints = vocabulary(); voice.listen(); return }
        lifecycleScope.launch {
            heard = ""
            recPaused = false; recStart = System.currentTimeMillis()
            val wav = hear.record { db -> micDb.value = db.toFloat() }
            micDb.value = -90f
            if (wav == null) { val cancelled = hear.cancel; heard = ""; listening = false; resumeRta(); if (!cancelled) say("I did not hear anything."); return@launch }
            heard = "understanding…"
            val t0 = System.currentTimeMillis()
            val text = hear.transcribe(wav, vocabulary())
            heard = ""; listening = false; resumeRta()
            if (text == null) { log.add(0, "  ! AssemblyAI did not answer: Android's recogniser instead"); listening = true; voice.hints = vocabulary(); voice.listen(); return@launch }
            log.add(0, String.format(Locale.US, "  (AssemblyAI %.1f s)", (System.currentTimeMillis() - t0) / 1000.0))
            if (text.isBlank()) say("I did not catch that.") else command(text, fromVoice = true)
        }
    }

    /** A ringing tone: find the microphone on the desk's meters, pull it down 3 dB in the mix that rings, tell Marko. */
    private fun onFeedback(e: Feedback.Event) {
        val now = System.currentTimeMillis()
        // one alert per tone: the same frequency (within 3 %) is not announced again for 10 s, any tone not within 4 s
        if (now - fbLast < 4000) return
        if (now - fbLast < 10000 && fbLastHz > 0 && kotlin.math.abs(e.hz - fbLastHz) < fbLastHz * 0.03) return
        fbLast = now; fbLastHz = e.hz
        val hz = if (e.hz >= 1000) String.format(Locale.US, "%.1f kilohertz", e.hz / 1000) else "${e.hz.toInt()} hertz"
        val before = meterHistory.firstOrNull()
        val mix = if (before != null) Feedback.ringingMix(before.second, meters.outputs.value) else "MAIN"
        val mixWord = if (mix == "MAIN") "the main" else "monitor ${mix.removePrefix("OUT")}"
        val cands = link.present.value.filter { it.code < 16 }.map { it.code }
        // the desk's meter layout on the CQ-12T is not decoded yet (7.11.2026): until it is, no channel is blamed or cut
        val ch = if (METERS_DECODED && before != null && meters.alive.value) Feedback.culprit(before.first, meters.inputs.value, cands) else null
        if (ch == null) {
            log.add(0, String.format(Locale.US, "  ⚠ FEEDBACK %.0f Hz", e.hz))
            log.add(0, String.format(Locale.US, "🟠 MixPad: Feedback Assistant on Main LR (or the monitor that rings); or a PEQ notch at %.0f Hz on the open vocal mics", e.hz))
            say("Feedback at $hz. Pull down the microphone that rings, or turn on the Feedback Assistant in MixPad.")
            store.remember(String.format(Locale.US, "feedback at %.0f Hz", e.hz))
            return
        }
        val src = Cq.SOURCES.first { it.code == ch }
        val p = Cq.sendParam(src, mix) ?: return
        val cut = fbCut[p] ?: 0.0
        val who = store.labels.value[src.id]?.let { it.name.ifBlank { it.instrument.substringBefore(",") } } ?: src.id
        if (cut >= 9.0) { say("Feedback at $hz on $who again. I already took $who down 9 dB. Please fix it in MixPad: Feedback Assistant on $mixWord."); return }
        link.setDb(p, link.db(p) - 3.0); fbCut[p] = cut + 3.0
        log.add(0, String.format(Locale.US, "  ⚠ feedback %.0f Hz on %s (%s): %s -3 dB in %s", e.hz, who, src.id, src.id, mix))
        log.add(0, "🟠 MixPad: Feedback Assistant on ${if (mix == "MAIN") "Main LR" else mix}, or a PEQ notch on ${src.id} at ${e.hz.toInt()} Hz")
        say("Feedback at $hz on $who in $mixWord. I pulled $who down 3 dB.")
        store.remember(String.format(Locale.US, "feedback at %.0f Hz on %s (%s) in %s", e.hz, who, src.id, mix))
    }

    // ---------- the concert recording (Marko, 7.11.2026: "it is very important to not miss recording of concert") ----------

    private val said = HashSet<String>()
    private var lastRecTry = 0L
    private var lastRecAlert = 0L
    private var confirmStop by mutableStateOf(false)

    /** Every 15 s: reminders before the concert, REC by itself 10 min before it, and loud alerts until the desk says recording. */
    private suspend fun recordingWatch() {
        while (true) {
            kotlinx.coroutines.delay(15000)
            val start = concertStart() ?: continue
            val now = System.currentTimeMillis()
            val min = 60_000L
            val auto = start - 10 * min
            val recording = recorder.state.value == Recorder.State.RECORDING
            fun once(key: String, text: String) { if (said.add(key)) say(text) }
            if (recorder.refused.value >= 3 && !recording && System.currentTimeMillis() > (concertStart() ?: Long.MAX_VALUE) - 40 * 60_000)
                once("mixpad" + (System.currentTimeMillis() / 300_000), "Close CQ MixPad. It is holding the desk's recorder, so I cannot press record.")
            val hhmm = java.text.SimpleDateFormat("HH:mm", Locale.US)
            if (now in (start - 30 * min) until (start - 15 * min)) once("r30", "The concert starts at ${hhmm.format(start)}. I start the multitrack recording by myself at ${hhmm.format(auto)}.")
            if (now in (start - 15 * min) until auto) once("r15", "Fifteen minutes to the concert. Recording starts at ${hhmm.format(auto)}.")
            if (now in auto until (start + 60 * min) && !recording) {
                if (now - lastRecTry > 60_000) {
                    lastRecTry = now
                    val sent = recorder.rec()
                    log.add(0, if (sent) "  ● REC sent to the desk" else "  ! REC could not be sent (no desk connection)")
                    if (!sent || recorder.state.value != Recorder.State.RECORDING) once("auto" + (now / 600_000), "Starting the concert recording on the SD card.")
                } else if (now - lastRecTry > 10_000 && now - lastRecAlert > 120_000) {
                    lastRecAlert = now
                    say("Attention. The concert is NOT recording. Press record in MixPad, multitrack, or on the desk.")
                }
            }
            if (recording && now in (start + 30 * min) until (start + 40 * min)) once("end", "The Santoshi set should be over. Stop the recording when you are done.")
        }
    }

    /** The current concert's start, today only. */
    private fun concertStart(): Long? {
        val d = store.dateIso() ?: return null; val s = store.startTime() ?: return null
        return try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$d $s")!!.time } catch (_: Exception) { null }
    }

    @Composable
    private fun RecChip() {
        val st by recorder.state.collectAsState()
        val since by recorder.since.collectAsState()
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
        val el = if (since > 0) (now - since) / 1000 else 0L
        val conn by recorder.connected.collectAsState()
        when {
            st == Recorder.State.RECORDING -> Chip(String.format(Locale.US, "● REC %d:%02d:%02d", el / 3600, el / 60 % 60, el % 60), RecRed, filled = true) { confirmStop = true }
            st == Recorder.State.STOPPED -> Chip("● START REC", Grey) { startRec() }
            else -> Chip(if (conn) "REC …" else "REC: NO LINK", Amber) { startRec() }
        }
        val start = concertStart()
        if (start != null && st != Recorder.State.RECORDING && System.currentTimeMillis() < start - 600_000)
            Text("  auto REC " + java.text.SimpleDateFormat("HH:mm", Locale.US).format(start - 600_000), color = Grey, fontSize = 11.sp)
    }

    private fun startRec() {
        if (recorder.rec()) { log.add(0, "  ● REC sent to the desk"); say("Recording.") } else say("No link to the desk's recorder. It reconnects by itself in a moment.")
    }

    /** The RECORDING tab: everything about the SD multitrack, straight from the desk. */
    @Composable
    private fun RecordingPage(modifier: Modifier) {
        val st by recorder.state.collectAsState()
        val since by recorder.since.collectAsState()
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
        val el = if (since > 0) (now - since) / 1000 else 0L
        val refused by recorder.refused.collectAsState()
        val left by recorder.remaining.collectAsState()
        val take by recorder.take.collectAsState()
        val conn by recorder.connected.collectAsState()
        val concert by store.current.collectAsState()
        fun hms(s: Long) = String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (st == Recorder.State.RECORDING) Color(0xFF3A1010) else Panel).padding(16.dp)) {
                Column {
                    Text(when { st == Recorder.State.RECORDING -> "● RECORDING"; st == Recorder.State.STOPPED -> "STOPPED"; conn -> "WAITING FOR THE DESK"; else -> "NO LINK TO THE RECORDER" },
                        color = when (st) { Recorder.State.RECORDING -> RecRed; Recorder.State.STOPPED -> Text1; else -> Amber }, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (st == Recorder.State.RECORDING) Text(hms(el), color = Text1, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text("SD multitrack (all armed tracks)" + (if (take.isNotBlank()) " · take $take" else ""), color = Grey, fontSize = 14.sp)
                    if (left > 0) Text("card left: about " + hms(left), color = Grey, fontSize = 14.sp)
                    if (refused >= 2) Text("The desk refuses the recorder link: CQ MixPad is holding it. Close CQ MixPad (swipe it away).", color = Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            // the armed tracks, a badge each (Marko, 7.11.2026: "which channels are armed for recording")
            val armed by recorder.armed.collectAsState()
            val lab by store.labels.collectAsState()
            Text("ARMED TRACKS" + (armed?.let { " · " + Integer.bitCount(it) + " of 16" } ?: " · waiting for the desk"), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                for (n in 1..16) {
                    val on = armed?.let { (it shr (n - 1)) and 1 == 1 }
                    val who = lab["Ip$n"]?.let { it.name.ifBlank { it.instrument.substringBefore(",").take(8) } } ?: ""
                    Column(Modifier.padding(2.dp).width(58.dp).clip(RoundedCornerShape(6.dp))
                        .background(when (on) { true -> RecRed.copy(alpha = 0.85f); false -> Color(0xFF2A2A2E); null -> Panel })
                        .padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$n", color = if (on == true) Color.White else Grey, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(if (on == true) "● REC" else "off", color = if (on == true) Color.White else Grey, fontSize = 10.sp)
                        Text(who, color = if (on == true) Color.White else Grey, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
            Row(Modifier.padding(vertical = 8.dp)) {
                if (st != Recorder.State.RECORDING) Chip("● START RECORDING", RecRed, filled = true) { startRec() }
                if (st != Recorder.State.STOPPED) Chip("■ STOP", Grey) { confirmStop = true }
            }
            // the SD player (a virtual soundcheck from the card) and the USB stick's stereo recorder (Main LR), 8.11.2026
            Text("SD PLAYER (the selected take)", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Chip("⏮ PREV", Grey) { recorder.sdPrev() }; Chip("▶ ❚❚", Green, filled = true) { recorder.sdPlayPause(); sdPlayingFlag = !sdPlayingFlag; sdPlayingAt = System.currentTimeMillis() }
                Chip("■ STOP", Grey) { recorder.sdStop(); sdPlayingFlag = false }; Chip("NEXT ⏭", Grey) { recorder.sdNext() }
            }
            var goTo by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("go to minute ", color = Grey, fontSize = 13.sp)
                Box(Modifier.width(70.dp).clip(RoundedCornerShape(6.dp)).background(Panel).padding(8.dp)) {
                    BasicTextField(goTo, { goTo = it.filter { c -> c.isDigit() || c == '.' || c == ':' } }, textStyle = TextStyle(color = Text1, fontSize = 14.sp),
                        cursorBrush = SolidColor(Cyan), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
                Chip("GO", Cyan) {
                    val sec = if (goTo.contains(':')) goTo.split(':').let { (it[0].toDoubleOrNull() ?: 0.0) * 60 + (it.getOrNull(1)?.toDoubleOrNull() ?: 0.0) } else (goTo.toDoubleOrNull() ?: 0.0) * 60
                    recorder.sdSeek((sec * 1000).toLong()); log.add(0, "  SD player: to ${goTo} min")
                }
            }
            Text("USB STICK (stereo, the Main LR mix)", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            val usbSince by recorder.usbSince.collectAsState()
            val usbTake by recorder.usbTake.collectAsState()
            if (usbSince > 0) Text("● RECORDING TO USB  " + hms((now - usbSince) / 1000), color = RecRed, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            else if (usbTake.isNotBlank()) Text("last stick take: $usbTake", color = Grey, fontSize = 13.sp)
            Row {
                Chip("● REC TO USB", RecRed, filled = true) {
                    if (sdPlayingFlag) { say("The USB stick cannot record while the SD card plays: the desk ignores it. Stop the player first, or print through the computer's USB audio."); log.add(0, "  ✗ USB stick: not while the SD plays") }
                    else { recorder.usbRec(); log.add(0, "  USB stick: recording") }
                }
                Chip("■ STOP USB", Grey) { recorder.usbStop(); log.add(0, "  USB stick: stopped") }
            }
            val start = concertStart()
            Text("CONCERT", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(concert?.let { "${it.venue} · ${it.date}" } ?: "no concert chosen", color = Text1, fontSize = 15.sp)
            if (start != null) {
                val f = java.text.SimpleDateFormat("HH:mm", Locale.US)
                Text("Santoshi at ${f.format(start)} · auto REC at ${f.format(start - 600_000)} (if the desk is not recording yet)", color = Text1, fontSize = 14.sp)
                Text("reminders 30 and 15 min before; an alert every 2 min while it does not record; recording never stops by itself", color = Grey, fontSize = 12.sp)
            }
            store.programme()?.let { pr ->
                Text("PROGRAMME", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                pr.split(";").forEach { Text(it.trim(), color = if (it.contains("Santoshi")) Cyan else Text1, fontSize = 13.sp) }
            }
            val takes = store.takes()
            if (takes.isNotEmpty()) {
                Text("TAKES OF THIS CONCERT", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                takes.forEach { Text(it, color = Text1, fontSize = 13.sp) }
            }
            Text("The card is never formatted; every concert is added (named here by place and date).", color = Grey, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }

    /** The VIEW tab: which strips show, in which order (the screen only; the desk is not touched). */
    @Composable
    private fun ViewPage(modifier: Modifier, present: List<Cq.Src>) {
        var order by remember { mutableStateOf(viewOrder(present)) }
        var hidden by remember { mutableStateOf(viewHidden()) }
        val labels by store.labels.collectAsState()
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Text("Show, hide and order the strips (only on this screen)", color = Grey, fontSize = 12.sp)
            order.forEachIndexed { i, id ->
                val l = labels[id]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(Panel).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (id in hidden) "HIDDEN" else "SHOWN", if (id in hidden) Grey else Green, filled = id !in hidden) {
                        hidden = if (id in hidden) hidden - id else hidden + id; saveView(order, hidden)
                    }
                    Text("$id  " + (l?.let { it.name.ifBlank { it.instrument } } ?: ""), color = Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Chip("▲", Grey) { if (i > 0) { order = order.toMutableList().also { val x = it[i]; it[i] = it[i - 1]; it[i - 1] = x }; saveView(order, hidden) } }
                    Chip("▼", Grey) { if (i < order.size - 1) { order = order.toMutableList().also { val x = it[i]; it[i] = it[i + 1]; it[i + 1] = x }; saveView(order, hidden) } }
                }
            }
        }
    }

    private val viewPrefs by lazy { getSharedPreferences("view", MODE_PRIVATE) }
    private fun viewOrder(present: List<Cq.Src>): List<String> {
        val saved = viewPrefs.getString("order", "")!!.split(",").filter { it.isNotBlank() }
        val ids = present.map { it.id }
        return saved.filter { it in ids } + ids.filter { it !in saved }
    }
    private fun viewHidden(): Set<String> = viewPrefs.getString("hidden", "")!!.split(",").filter { it.isNotBlank() }.toSet()
    private fun saveView(order: List<String>, hidden: Set<String>) {
        viewPrefs.edit().putString("order", order.joinToString(",")).putString("hidden", hidden.joinToString(",")).apply(); viewTick++
    }
    private var viewTick by mutableStateOf(0)

    // ---- moving a strip by a long press (Marko, 8.11.2026): it lands where it is dropped; a moved strip keeps an
    // orange underline. The order is the same list as the VIEW tab's.
    private var dragId by mutableStateOf<String?>(null)
    private var dragDx by mutableStateOf(0f)
    private fun movedSet(): Set<String> = viewPrefs.getString("moved", "")!!.split(",").filter { it.isNotBlank() }.toSet()
    private fun dropStrip(id: String, dx: Float, stripPx: Float, present: List<Cq.Src>) {
        val hidden = viewHidden()
        val shown = viewOrder(present).filter { it !in hidden }.toMutableList()
        val from = shown.indexOf(id); if (from < 0) return
        val to = (from + Math.round(dx / stripPx)).coerceIn(0, shown.size - 1)
        if (to == from) return
        shown.removeAt(from); shown.add(to, id)
        viewPrefs.edit().putString("moved", (movedSet() + id).joinToString(",")).apply()
        saveView(shown + viewOrder(present).filter { it in hidden }, hidden)
        log.add(0, "  moved $id to place ${to + 1}")
    }

    // ---- CUSTOM tab: which strips, in which order (its own list; EDIT or a second tap on CUSTOM opens the editor)
    private var customEdit by mutableStateOf(false)
    private fun customList(): List<String> = viewPrefs.getString("custom", "")!!.split(",").filter { it.isNotBlank() }
    private fun saveCustom(l: List<String>) { viewPrefs.edit().putString("custom", l.joinToString(",")).apply(); viewTick++ }

    @Composable
    private fun CustomEditPage(modifier: Modifier, present: List<Cq.Src>) {
        var chosen by remember { mutableStateOf(customList()) }
        val labels by store.labels.collectAsState()
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CUSTOM: choose the strips and their order", color = Grey, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Chip("DONE", Green, filled = true) { customEdit = false }
            }
            val ordered = chosen + present.map { it.id }.filter { it !in chosen }
            ordered.forEach { id ->
                val l = labels[id]; val i = chosen.indexOf(id)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(Panel).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (i >= 0) "ON" else "OFF", if (i >= 0) Green else Grey, filled = i >= 0) {
                        chosen = if (i >= 0) chosen - id else chosen + id; saveCustom(chosen)
                    }
                    Text("$id  " + (l?.let { it.name.ifBlank { it.instrument } } ?: ""), color = Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (i >= 0) {
                        Chip("▲", Grey) { if (i > 0) { chosen = chosen.toMutableList().also { val x = it[i]; it[i] = it[i - 1]; it[i - 1] = x }; saveCustom(chosen) } }
                        Chip("▼", Grey) { if (i < chosen.size - 1) { chosen = chosen.toMutableList().also { val x = it[i]; it[i] = it[i + 1]; it[i + 1] = x }; saveCustom(chosen) } }
                    }
                }
            }
        }
    }

    /** The desk plays from the SD card (pressed in this app, or seen by its state code). */
    @Volatile private var sdPlayingAt = 0L
    private fun sdPlaying() = System.currentTimeMillis() - sdPlayingAt < 6 * 3600_000L && sdPlayingFlag
    @Volatile private var sdPlayingFlag = false

    // ---- the input panel: gain, trim, HPF, EQ of one input, big buttons, values read back from the desk's state
    private var inputCh by mutableStateOf<Int?>(null)
    private fun openInput(ch: Int) { inputCh = ch; recorder.askState(); showOnRta(ch) }
    private fun closeInput() { inputCh = null; showOnRta(0x38) }

    @Composable
    private fun StepRow(title: String, value: String, steps: List<Pair<String, () -> Unit>>, extra: (@Composable () -> Unit)? = null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(value, color = Text1, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                extra?.invoke()
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                steps.forEach { (l, f) ->
                    Box(Modifier.weight(1f).padding(3.dp).height(52.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2E))
                        .clickable { f() }, contentAlignment = Alignment.Center) { Text(l, color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    @Composable
    private fun InputPanel(modifier: Modifier, ch: Int) {
        val st by recorder.state0.collectAsState()
        val labels by store.labels.collectAsState()
        val id = "Ip${ch + 1}"; val l = labels[id]
        fun f1(d: Double?) = d?.let { String.format(Locale.US, "%+.1f dB", it) } ?: "…"
        st.hashCode()
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$id  " + (l?.let { "${it.name} · ${it.instrument}" } ?: ""), color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Chip("CLOSE", Green, filled = true) { closeInput() }
            }
            if (st == null) Text("Reading the desk's settings…", color = Amber, fontSize = 13.sp)
            Text("The RTA above shows this input now. Values come from the desk (read every 30 s, and after each change here).", color = Grey, fontSize = 11.sp)
            val g = recorder.gainNow(ch); val t = recorder.trimNow(ch)
            StepRow("GAIN (analogue input)", g?.let { String.format(Locale.US, "%.1f dB", it) } ?: "…",
                listOf(-3.0, -1.0, 1.0, 3.0).map { d -> (if (d > 0) "+${d.toInt()}" else "${d.toInt()}") to { recorder.gain(ch, (recorder.gainNow(ch) ?: 0.0) + d); log.add(0, "  $id gain ${d}"); Unit } })
            StepRow("TRIM (SD / USB / digital)", f1(t),
                listOf(-3.0, -1.0, 1.0, 3.0).map { d -> (if (d > 0) "+${d.toInt()}" else "${d.toInt()}") to { recorder.trim(ch, (recorder.trimNow(ch) ?: 0.0) + d); log.add(0, "  $id trim ${d}"); Unit } })
            val on = recorder.hpfOnNow(ch); val hz = recorder.hpfHzNow(ch)
            StepRow("HIGH-PASS (low cut)" + (if (on == true) "" else " · off"), hz?.let { String.format(Locale.US, "%.0f Hz", it) } ?: "…",
                listOf("−" to { recorder.hpfHz(ch, (recorder.hpfHzNow(ch) ?: 100.0) / 1.12); Unit }, "+" to { recorder.hpfHz(ch, (recorder.hpfHzNow(ch) ?: 100.0) * 1.12); Unit },
                    (if (on == true) "OFF" else "ON") to { recorder.hpf(ch, on != true); Unit }))
            listOf("LF (low)", "LM (low mid)", "HM (high mid)", "HF (high)").forEachIndexed { b, n ->
                StepRow("EQ $n", f1(recorder.peqNow(ch, b)),
                    listOf(-2.0, -1.0, 1.0, 2.0).map { d -> (if (d > 0) "+${d.toInt()}" else "${d.toInt()}") to { recorder.peqGain(ch, b, (recorder.peqNow(ch, b) ?: 0.0) + d); recorder.peqIn(ch, true); Unit } })
            }
        }
    }

    /** Show one channel (0..9) or Main LR (0x38) on the RTA at the top: the desk's own analyser of that source. */
    private fun showOnRta(ch: Int) { recorder.setRtaSource(ch); rta.restart() }

    private fun resumeRta() { if (rtaWasOn) { rtaWasOn = false; toggleRta() } }

    private fun toggleRta() {
        if (System.currentTimeMillis() - recorder.rtaAt < 3000) { rtaOn = true; return }   // the desk's RTA is the source
        if (rtaJob?.isActive == true) { rta.stop(); rtaJob = null; rtaOn = false }
        else if (micOk) { rta.reset(); rtaJob = lifecycleScope.launch { rta.run() }; rtaOn = true }
        else openAppSettings()
    }

    private fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun saveConcert() {
        store.saveMix(HashMap(link.values), deskText())
        say("Saved the mix of ${store.current.value?.venue}, ${store.current.value?.date}.")
    }

    /** The concert's stored mix sent to the desk (faders, sends, pans, mutes). */
    /** The concert onto the desk: its base scene first (processing, presets, names, FX), then its own mix on top,
     *  so a scene recall never undoes tonight's changes. */
    private fun sendMixToDesk() {
        val m = store.mix(); val scene = store.baseScene()
        lifecycleScope.launch {
            if (scene > 0) { link.scene(scene); log.add(0, "  ✓ scene $scene recalled (processing, presets, FX)"); kotlinx.coroutines.delay(2500) }
            if (m.isEmpty()) { say("Scene $scene is on the desk. This concert has no mix of its own yet."); return@launch }
            m.forEach { (p, v) -> link.setRaw(p, v) }
            log.add(0, "  ✓ ${m.size} values of ${store.current.value?.venue} on top")
            say("${store.current.value?.venue} is on the desk: scene $scene and its mix.")
        }
    }

    // ---------- the screen ----------

    @Composable
    private fun ZoomPan(content: @Composable () -> Unit) {
        // Marko, 8.11.2026: "three fingers to scroll the interface, pinch to resize the interface, one finger to operate".
        // Two fingers pinch the scale (0.6x .. 2.5x, remembered); three fingers move the view in every direction; one finger
        // goes on to the controls untouched. Zoomed out, the screen lays out more room; zoomed in, the same layout grows.
        val prefs = remember { getSharedPreferences("view", MODE_PRIVATE) }
        var scale by remember { mutableStateOf(prefs.getFloat("uiScale", 1f)) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        fun clampPan(p: Offset, sc: Float): Offset {
            val ls = minOf(sc, 1f); val vw = box.width / ls * sc; val vh = box.height / ls * sc
            return Offset(p.x.coerceIn(minOf(0f, box.width - vw), 0f), p.y.coerceIn(minOf(0f, box.height - vh), 0f))
        }
        Box(Modifier.fillMaxSize().background(Bg).clipToBounds().onSizeChanged { box = it }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                do {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val n = ev.changes.count { it.pressed }
                    if (n >= 2) {
                        if (n == 2) scale = (scale * ev.calculateZoom()).coerceIn(0.6f, 2.5f)
                        else pan = clampPan(pan + ev.calculatePan(), scale)
                        pan = clampPan(pan, scale)
                        ev.changes.forEach { it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
                prefs.edit().putFloat("uiScale", scale).apply()
            }
        }) {
            val sc = scale; val pn = pan
            Box(Modifier.layout { m, c ->
                val ls = minOf(sc, 1f)
                val w = (c.maxWidth / ls).toInt(); val h = (c.maxHeight / ls).toInt()
                val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) {
                    pl.placeWithLayer(0, 0) {
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        scaleX = sc; scaleY = sc; translationX = pn.x; translationY = pn.y
                    }
                }
            }) { content() }
            if (sc != 1f) Text(String.format(Locale.US, "%.0f%%  (pinch · 3 fingers move)", sc * 100), color = Grey, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
    }

    @Composable
    private fun Screen() {
        val tick by link.tick.collectAsState()
        val status by link.status.collectAsState()
        val connected by link.connected.collectAsState()
        val present by link.present.collectAsState()
        val labels by store.labels.collectAsState()
        var mix by remember { mutableStateOf("MAIN") }
        var keyDialog by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        var concertDialog by remember { mutableStateOf(false) }
        val concert by store.current.collectAsState()
        tick.hashCode()

        Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp).imePadding()) {
            // top bar
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (connected) Green else Red))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("CQ MixPad (Mantra)", color = Text1, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(status, color = Grey, fontSize = 11.sp, maxLines = 2)
                    val alive by meters.alive.collectAsState()
                    if (alive && METERS_DECODED) { val o by meters.outputs.collectAsState(); if (o.size > 7) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("MAIN ", color = Grey, fontSize = 10.sp)
                        LevelBar(o[6], Modifier.width(90.dp).height(5.dp)); Spacer(Modifier.width(3.dp)); LevelBar(o[7], Modifier.width(90.dp).height(5.dp))
                    } }
                }
                if (!micOk) Chip("PERMISSIONS", Amber) { openAppSettings() }
                if (store.apiKey() == null) Chip("KEY", Amber) { keyDialog = true }
                Chip(if (speakAloud) "VOICE ON" else "VOICE OFF", if (speakAloud) Green else Grey) { speakAloud = !speakAloud; speech.stop() }
                Chip(if (showLog) "LOG ▲" else "LOG", if (showLog) Cyan else Grey, filled = showLog) { showLog = !showLog }
                Chip("SAVE", Blue) { saveConcert() }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Chip(concert?.let { "${it.venue} · ${it.date}" } ?: "CONCERT", Amber, filled = true) { concertDialog = true }
                RecChip()
            }
            // mixes
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Chip("REC", if (mix == "REC") RecRed else RecRed.copy(alpha = 0.7f), filled = mix == "REC") { mix = "REC" }
                for (m in listOf("MAIN", "CUSTOM", "OUT1", "OUT2", "OUT3", "OUT4", "OUT5", "OUT6", "FX1", "FX2")) Chip(m, if (m == mix) Cyan else Grey, filled = m == mix) { if (m == "CUSTOM" && mix == "CUSTOM") customEdit = !customEdit; mix = m }
                Chip("VIEW", if (mix == "VIEW") Cyan else Grey, filled = mix == "VIEW") { mix = "VIEW" }
            }
            if (mix == "REC") RecordingPage(Modifier.weight(1f))
            else if (mix == "VIEW") ViewPage(Modifier.weight(1f), present)
            else if (mix == "CUSTOM" && customEdit) CustomEditPage(Modifier.weight(1f), present)
            else if (inputCh != null) { RtaStrip(); InputPanel(Modifier.weight(1f), inputCh!!) }
            else {
            RtaStrip()
            // the faders. CUSTOM (Marko, 8.11.2026: "same as in Mixer"): the strips he chose, in his order, on the MAIN mix.
            viewTick.hashCode()
            val eff = if (mix == "CUSTOM") "MAIN" else mix
            val shown = (if (mix == "CUSTOM") customList() else viewOrder(present).filter { it !in viewHidden() })
                .mapNotNull { id -> present.firstOrNull { it.id == id } }
            val inM by recorder.inMeters.collectAsState()
            val outM by recorder.outMeters.collectAsState()
            val metersOn = recorder.metersAlive()
            val rtaSrc by recorder.rtaSource.collectAsState()
            val stripPx = with(androidx.compose.ui.platform.LocalDensity.current) { STRIP_W.toPx() }
            Row(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                if (mix == "CUSTOM") Column(Modifier.padding(4.dp)) { Chip("EDIT", Amber) { customEdit = true } }
                for (s in shown) {
                    val p = Cq.sendParam(s, eff) ?: continue
                    val l = labels[s.id]
                    val isIp = s.id.startsWith("Ip") && s.code < 10
                    val id = s.id
                    Strip(top = l?.name?.ifBlank { null } ?: s.id, sub = if (l != null) s.id + " · " + l.instrument else "",
                        db = link.db(p), muted = link.muted(Cq.muteParam(s)),
                        meter = if (metersOn && s.id.startsWith("Ip") && s.code < 16) inM[s.code] else null,
                        pan = if (eff == "MAIN") link.values[Cq.panMainParam(s)]?.let { Cq.valueToPan(it) } else null,
                        onDb = { link.setDb(p, it) }, onMute = { link.setMute(Cq.muteParam(s), !link.muted(Cq.muteParam(s))) },
                        onPan = { link.setPan(Cq.panMainParam(s), it) },
                        moved = mix != "CUSTOM" && id in movedSet(),
                        selected = isIp && rtaSrc == s.code,
                        onTitle = if (isIp) ({ showOnRta(if (rtaSrc == s.code) 0x38 else s.code) }) else null,
                        titleMod = if (mix == "CUSTOM") Modifier else Modifier.pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragId = id; dragDx = 0f },
                                onDragEnd = { dropStrip(id, dragDx, stripPx, present); dragId = null; dragDx = 0f },
                                onDragCancel = { dragId = null; dragDx = 0f }) { ch, amount -> ch.consume(); dragDx += amount.x }
                        },
                        modifier = if (dragId == id) Modifier.graphicsLayer { translationX = dragDx; alpha = 0.85f } else Modifier,
                        onInput = if (isIp) ({ openInput(s.code) }) else null, tint = chColor(s.code))
                }
                Cq.masterParam(eff)?.let { p ->
                    val mp = Cq.masterMuteParam(eff)
                    val n = eff.removePrefix("OUT").toIntOrNull()
                    Strip(top = if (eff == "MAIN") "MAIN LR" else eff, sub = "master", db = link.db(p),
                        muted = mp?.let { link.muted(it) } ?: false, pan = null, master = true,
                        meter = if (!metersOn) null else if (eff == "MAIN") outM[6] else if (n != null) outM[n - 1] else null,
                        meter2 = if (metersOn && eff == "MAIN") outM[7] else null,
                        selected = eff == "MAIN" && rtaSrc == 0x38,
                        onTitle = if (eff == "MAIN") ({ showOnRta(0x38) }) else null,
                        onDb = { link.setDb(p, it) }, onMute = { mp?.let { link.setMute(it, !link.muted(it)) } }, onPan = {}, tint = Color(0xFFFFD27A))
                }
                if (eff == "MAIN") for (d in 1..4) if (link.values.containsKey(Cq.dcaLevelParam(d))) {
                    Strip(top = "DCA$d", sub = "", db = link.db(Cq.dcaLevelParam(d)), muted = link.muted(Cq.dcaMuteParam(d)), pan = null,
                        onDb = { link.setDb(Cq.dcaLevelParam(d), it) }, onMute = { link.setMute(Cq.dcaMuteParam(d), !link.muted(Cq.dcaMuteParam(d))) }, onPan = {}, tint = Color(0xFFA78BFA))
                }
            }
            }
            // suggestions waiting for APPLY
            if (pending.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(8.dp)) {
                    Text("SONNET SUGGESTS", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    pending.forEach { Text(it.text(), color = Text1, fontSize = 13.sp) }
                    Row {
                        Chip("APPLY", Green, filled = true) { pending.toList().forEach { apply(it) }; pending.clear(); store.saveMix(HashMap(link.values), deskText()) }
                        Chip("DISMISS", Grey) { pending.clear() }
                    }
                }
            }
            // what was said and done: one line, the whole list behind the LOG chip (the faders get the room)
            if (!showLog) { val last = if (heard.isNotBlank()) "… $heard" else log.firstOrNull()
                if (last != null) Text(last, color = Grey, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { showLog = true }.padding(vertical = 2.dp)) }
            else Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                if (heard.isNotBlank()) Text("… $heard", color = Cyan, fontSize = 13.sp)
                log.take(12).forEach {
                    Text(it, color = when { it.startsWith("YOU") -> Grey; it.startsWith("🟠") -> Amber; it.startsWith("  ") -> Green; else -> Text1 }, fontSize = 13.sp)
                }
            }
            // the command line to Sonnet
            CommandBar()
        }
        if (keyDialog) KeyDialog { keyDialog = false }
        if (concertDialog) ConcertDialog { concertDialog = false }
        if (confirmStop) Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(10.dp)).background(Panel).padding(16.dp)) {
                Text("Stop the concert recording?", color = Text1, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("The SD card keeps everything recorded so far.", color = Grey, fontSize = 13.sp)
                Row {
                    Chip("KEEP RECORDING", Green, filled = true) { confirmStop = false }
                    Chip("STOP", RecRed) { confirmStop = false; recorder.stop(); log.add(0, "  ■ STOP sent to the desk") }
                }
            }
        }
    }

    @Composable
    private fun RtaPill(label: String, color: Color, filled: Boolean, onClick: () -> Unit) {
        Box(Modifier.padding(end = 6.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(if (filled) color.copy(alpha = 0.28f) else Color(0x990A0F1C))
            .border(1.dp, color, RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }

    @Composable
    private fun RtaStrip() {
        val live by rta.live.collectAsState()
        val on = rtaOn
        val src by recorder.rtaSource.collectAsState()
        val onMain = src == 0x38
        val labelsNow by store.labels.collectAsState()
        val sigLv by rta.level.collectAsState()
        var kdShow by remember { mutableStateOf(false) }
        val sig = sigLv > -80   // no signal: the bars rest at the floor, not at the interpolated share of silence
        // the spectrum as in the lifestyle picture (9.11.2026): rainbow LED bars, colour by frequency (red low, violet high),
        // over a fine grid; the ten octave bands of the desk's RTA drawn as 48 bars, interpolated; the buttons sit in its corners
        Box(Modifier.fillMaxWidth().height(270.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF0C1424), Color(0xFF101A2E))))
            .border(1.dp, Rim, RoundedCornerShape(12.dp))) {
            val deskBands by recorder.rta.collectAsState()
            val peaks = remember { FloatArray(31) }
            Canvas(Modifier.fillMaxSize()) {
                val nb = 31; val w = size.width / nb
                val top0 = 34f; val h = size.height - top0 - 4f   // room for the buttons above the bars
                for (i in 0..9) drawRect(Color(0x1AFFFFFF), Offset(0f, top0 + h * i / 9f), Size(size.width, 1f))
                for (i in 0..nb step 3) drawRect(Color(0x14FFFFFF), Offset(i * w, top0), Size(1f, h))
                // the 31 bands as share of the whole (dB, the scale of the Krishna Das line); the phone mic's ten octaves
                // are interpolated when the desk is not the source
                val fresh = System.currentTimeMillis() - recorder.rtaAt < 3000 && deskBands != null
                val share = FloatArray(nb) { j ->
                    if (fresh) {
                        val b = deskBands!!; var tot = 0.0
                        for (k in 0 until 31) if (b[k] > -105) tot += Math.pow(10.0, b[k] / 10.0)
                        (b[j] - 10 * Math.log10(tot + 1e-12)).toFloat()
                    } else {
                        val x = j / (nb - 1f) * 9f; val i0 = x.toInt().coerceAtMost(8); val t = x - i0
                        (live[i0] * (1 - t) + live[i0 + 1] * t).toFloat()
                    }
                }
                val seg = 8f
                for (j in 0 until nb) {
                    val col = Color.hsv(j / (nb - 1f) * 285f, 0.85f, 1f)
                    val live1 = on && sig
                    val frac = if (live1) (1.0 - (-share[j] / 45.0)).coerceIn(0.0, 1.0).toFloat()
                               else 0.05f + 0.025f * (1 + Math.sin(j * 0.7).toFloat())     // idle: a low floor, still a rainbow
                    val n = maxOf(2, (frac * h / seg).toInt())
                    peaks[j] = if (live1) maxOf(frac, peaks[j] - 0.004f) else 0f
                    val c = if (live1) col else col.copy(alpha = 0.45f)
                    for (k in 0 until n) drawRoundRect(c, Offset(j * w + w * 0.12f, top0 + h - (k + 1) * seg + 1.5f), Size(w * 0.76f, seg - 3f),
                        androidx.compose.ui.geometry.CornerRadius(2.5f))
                    if (live1 && peaks[j] > frac + 0.02f)
                        drawRect(Color.White.copy(alpha = 0.7f), Offset(j * w + w * 0.12f, top0 + h - peaks[j] * h), Size(w * 0.76f, 2.5f))
                }
                if (on && kdShow) {
                    var px = 0f; var py = 0f
                    for (i in 0 until 10) {
                        val cx = (3 * i + 2) * w + w / 2
                        val cy = top0 + h * (-Rta.KD[i] / 45.0).toFloat().coerceIn(0f, 1f)
                        if (i > 0) drawLine(Amber, Offset(px, py), Offset(cx, cy), 2.5f)
                        px = cx; py = cy
                    }
                }
            }
            if (!on) Text("RTA off: tap RTA", color = Grey, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                RtaPill(if (on) "RTA ON" else "RTA", if (on) Cyan else Grey, on) { toggleRta() }
                RtaPill(if (fbGuard) "FB ON" else "FB OFF", if (fbGuard) Red else Grey, fbGuard) {
                    fbGuard = !fbGuard; feedback.reset()
                    if (fbGuard && !rtaOn) toggleRta()
                    say(if (fbGuard) "Feedback guard on." else "Feedback guard off.")
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                RtaPill("MATCH KD", if (RTA_DECODED) Amber else Grey, kdShow) {
                    kdShow = !kdShow; if (!kdShow) return@RtaPill
                    if (!RTA_DECODED) { say("Matching to the reference is off. I cannot read the mixer's real-time analyser yet, so I will not mix from a reference."); return@RtaPill }
                    if (System.currentTimeMillis() - recorder.rtaAt > 3000) { say("The mixer's analyser is not streaming. I will not match without it."); return@RtaPill }
                    if (rta.meanLevel() < -60) { say("The main mix is silent, nothing to match."); return@RtaPill }
                    command("Compare the RTA with the Krishna Das profile and suggest how to fit the mix, vocals in the first plan.", fromVoice = false, withRta = true)
                }
            }
        }
        if (on) {
            val lv by rta.level.collectAsState()
            val who = if (onMain) "MAIN LR" else "Ip${src + 1}" + (labelsNow["Ip${src + 1}"]?.name?.ifBlank { null }?.let { " $it" } ?: "")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(Locale.US, "RTA %s · %.0f dB%s%s", who, lv, if (lv < -80) " · no signal" else "", if (onMain) " · orange = Krishna Das" else " · tap its name again for MAIN"),
                    color = if (lv < -80) Amber else Grey, fontSize = 10.sp, modifier = Modifier.weight(1f))
                if (!onMain) Text("MAIN", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { showOnRta(0x38) }.padding(horizontal = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Rta.NAMES.forEachIndexed { i, n ->
                Text(if (on && onMain) String.format(Locale.US, "%s %+.0f", n, live[i] - Rta.KD[i]) else n, color = Grey, fontSize = 9.sp,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }

    @Composable
    private fun CommandBar() {
        var text by remember { mutableStateOf("") }
        if (listening && hear.hasKeys()) { RecordBar(); return }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, Rim, RoundedCornerShape(8.dp)).background(Panel).padding(10.dp)) {
                if (text.isEmpty()) Text(if (busy) "Sonnet is working…" else "Tell Sonnet: backing vocal Tajana on channel 4…", color = Grey, fontSize = 14.sp)
                BasicTextField(text, { text = it }, textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { command(text.trim(), false); text = "" } }),
                    modifier = Modifier.fillMaxWidth())
            }
            // the voice command button, beside the box (Marko, 7.11.2026)
            MicButton(enabled = micOk && !busy) { if (!micOk) openAppSettings() else if (!busy) startListening() }
            Chip("SEND", if (busy) Grey else Cyan, filled = !busy) { if (text.isNotBlank()) { command(text.trim(), false); text = "" } }
        }
    }

    /** While a voice command records: the time, TTT's VU meter, PAUSE / RESUME, ■ send now, ✕ cancel. */
    @Composable
    private fun RecordBar() {
        val db by micDb.collectAsState()
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(200) } }
        val secs = ((now - recStart) / 1000).coerceAtLeast(0)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (recPaused) Grey else RecRed))
            Text(String.format(Locale.US, " %d:%02d ", secs / 60, secs % 60), color = Sand, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            VuMeter(db, dim = recPaused, modifier = Modifier.weight(1f).height(14.dp))
            Chip(if (recPaused) "RESUME" else "PAUSE", Amber, filled = recPaused) { recPaused = !recPaused; hear.paused = recPaused }
            Chip("■", Green, filled = true) { recPaused = false; hear.paused = false; hear.stopNow = true }
            Chip("✕", Grey) { hear.cancel = true }
        }
    }

    @Composable
    private fun ConcertDialog(close: () -> Unit) {
        val list by store.concerts.collectAsState()
        val cur by store.current.collectAsState()
        var venue by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(java.text.SimpleDateFormat("d.M.yyyy", Locale.US).format(java.util.Date())) }
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { close() }, contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(10.dp)).background(Panel).padding(14.dp).clickable(enabled = false) {}) {
                Text("CONCERTS", color = Amber, fontWeight = FontWeight.Bold)
                Text("Each concert keeps its own names, roles and mix.", color = Grey, fontSize = 12.sp)
                list.sortedByDescending { it.id }.forEach { c ->
                    Text("${if (c.id == cur?.id) "▶ " else "   "}${c.venue} · ${c.date}", color = if (c.id == cur?.id) Cyan else Text1, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable { store.open(c.id) }.padding(vertical = 7.dp))
                }
                Row { Chip("PUT IT ON THE DESK (scene ${store.baseScene()} + its mix)", Green) { sendMixToDesk(); close() } }
                Text("NEW CONCERT (a copy of the one marked ▶)", color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, Rim, RoundedCornerShape(6.dp)).padding(8.dp)) {
                    if (venue.isEmpty()) Text("place", color = Grey, fontSize = 14.sp)
                    BasicTextField(venue, { venue = it }, textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Cyan), modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, Rim, RoundedCornerShape(6.dp)).padding(8.dp)) {
                    BasicTextField(date, { date = it }, textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Cyan), modifier = Modifier.fillMaxWidth())
                }
                Row {
                    Chip("CREATE", Green, filled = true) { if (venue.isNotBlank()) { store.newConcert(venue, date, cur?.id); close() } }
                    Chip("CLOSE", Grey) { close() }
                }
            }
        }
    }

    @Composable
    private fun KeyDialog(close: () -> Unit) {
        var k by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { close() }, contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.9f).clip(RoundedCornerShape(10.dp)).background(Panel).padding(16.dp).clickable(enabled = false) {}) {
                Text("Anthropic key", color = Text1, fontWeight = FontWeight.Bold)
                Text("Paste it once; it stays only in this app.", color = Grey, fontSize = 12.sp)
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, Rim, RoundedCornerShape(6.dp)).padding(8.dp)) {
                    BasicTextField(k, { k = it }, textStyle = TextStyle(color = Text1, fontSize = 13.sp), cursorBrush = SolidColor(Cyan), modifier = Modifier.fillMaxWidth())
                }
                Chip("SAVE", Green, filled = true) { if (k.trim().startsWith("sk-ant-")) { store.setApiKey(k); close() } }
            }
        }
    }
}

@Composable
private fun Chip(label: String, color: Color, filled: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.padding(3.dp).clip(RoundedCornerShape(6.dp))
        .background(if (filled) color.copy(alpha = 0.25f) else Color.Transparent)
        .border(1.dp, color, RoundedCornerShape(6.dp)).clickable { onClick() }.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 14.dp)) {
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** One channel strip in the look of the lifestyle picture (9.11.2026): colour button on top, two small dark buttons (mute, IN),
 *  a slim groove with the desk's meter inside and a light cap, a name tab at the bottom (lit in the channel colour). */
@Composable
private fun Strip(top: String, sub: String, db: Double, muted: Boolean, pan: Double?, master: Boolean = false, meter: Float? = null,
                  onDb: (Double) -> Unit, onMute: () -> Unit, onPan: (Double) -> Unit,
                  meter2: Float? = null, moved: Boolean = false, selected: Boolean = false, onTitle: (() -> Unit)? = null,
                  titleMod: Modifier = Modifier, modifier: Modifier = Modifier, onInput: (() -> Unit)? = null,
                  tint: Color = Cyan) {
    val maxV = Cq.dbToValue(Cq.MAX_DB).toFloat()
    val setDb by androidx.compose.runtime.rememberUpdatedState(onDb)
    val curDb by androidx.compose.runtime.rememberUpdatedState(db)
    val curPan by androidx.compose.runtime.rememberUpdatedState(pan ?: 0.0)
    val setPan by androidx.compose.runtime.rememberUpdatedState(onPan)
    val tapMod = if (onTitle != null) Modifier.clickable { onTitle() } else Modifier
    Column(modifier.width(STRIP_W).fillMaxHeight().padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1C2740), Color(0xFF131B2D))))
        .border(1.dp, if (selected) tint else Rim, RoundedCornerShape(10.dp)).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // the colour button: a tap shows this channel on the RTA at the top, a long press picks the strip up to move it
        Box(titleMod.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.72f)))).then(tapMod),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.85f)))
        }
        Spacer(Modifier.height(5.dp))
        // two small dark rounded squares, as in the picture: M = mute, IN = the input's settings (8.11.2026: "quickly enter its input settings")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(if (muted) Red else Dark)
                .border(1.dp, if (muted) Red else Rim, RoundedCornerShape(10.dp)).clickable { onMute() }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(20.dp)) {   // a speaker, crossed out when muted
                    val c = if (muted) Color.White else Grey
                    val pth = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height * 0.36f); lineTo(size.width * 0.3f, size.height * 0.36f); lineTo(size.width * 0.62f, size.height * 0.1f)
                        lineTo(size.width * 0.62f, size.height * 0.9f); lineTo(size.width * 0.3f, size.height * 0.64f); lineTo(0f, size.height * 0.64f); close() }
                    drawPath(pth, c)
                    if (muted) drawLine(c, Offset(size.width * 0.7f, size.height * 0.2f), Offset(size.width, size.height * 0.8f), 3f)
                    else { drawArc(c, -40f, 80f, false, Offset(size.width * 0.55f, size.height * 0.25f), Size(size.width * 0.4f, size.height * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f)) }
                }
            }
            if (onInput != null) Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Dark)
                .border(1.dp, Rim, RoundedCornerShape(10.dp)).clickable { onInput() }, contentAlignment = Alignment.Center) {
                Text("IN", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            } else Spacer(Modifier.weight(1f))
        }
        if (pan != null) {
            Canvas(Modifier.fillMaxWidth().height(30.dp).pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { setPan(0.0) })
            }.pointerInput(Unit) {
                // relative: the knob moves only by the finger's travel, a touch never jumps it
                var start = 0.0; var acc = 0f
                detectHorizontalDragGestures(onDragStart = { start = curPan; acc = 0f }) { _, dx ->
                    acc += dx
                    setPan((start + acc / size.width * 200).coerceIn(-100.0, 100.0))
                }
            }) {
                drawRoundRect(Dark, Offset(0f, size.height / 2 - 3), Size(size.width, 6f), androidx.compose.ui.geometry.CornerRadius(3f))
                val x = ((pan + 100) / 200 * size.width).toFloat()
                drawRoundRect(tint, Offset(x - 5, 3f), Size(10f, size.height - 6), androidx.compose.ui.geometry.CornerRadius(4f))
            }
            Text(if (kotlin.math.abs(pan) < 1) "C" else if (pan < 0) "L${(-pan).toInt()}" else "R${pan.toInt()}", color = Grey, fontSize = 9.sp)
        }
        Text(Cq.dbText(db), color = if (muted) Red else Cap, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        val pos = (Cq.dbToValue(db) / maxV).coerceIn(0f, 1f)
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).pointerInput(Unit) {
            // relative: the fader moves only by the finger's travel; a touch anywhere never jumps it (live safety)
            var start = 0f; var acc = 0f
            detectVerticalDragGestures(onDragStart = { start = Cq.dbToValue(curDb) / maxV; acc = 0f }) { _, dy ->
                acc += dy
                val f = (start - acc / size.height).coerceIn(0f, 1f)
                setDb(Cq.valueToDb((f * maxV).toInt()))
            }
        }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { setDb(0.0) }) }) {
            val cx = size.width / 2
            val cr = androidx.compose.ui.geometry.CornerRadius(7f)
            // the scale ticks either side of the groove, 0 mark brighter
            val zero = size.height * (1f - Cq.dbToValue(0.0) / maxV)
            for (i in 0..20) { val ty = size.height * i / 20f; drawRect(Rim, Offset(cx - 28, ty), Size(5f, 2f)); drawRect(Rim, Offset(cx + 23, ty), Size(5f, 2f)) }
            drawRect(Grey, Offset(cx - 32, zero - 1), Size(10f, 2f)); drawRect(Grey, Offset(cx + 22, zero - 1), Size(10f, 2f))
            drawRoundRect(Dark, Offset(cx - 14, 0f), Size(28f, size.height), cr)
            // the desk's meter inside the groove, on the fader's own scale; cyan, green, yellow over +6, red over +15
            fun frac(d: Double) = 1f - Cq.dbToValue(d.coerceIn(-85.0, Cq.MAX_DB)) / maxV
            val grad = androidx.compose.ui.graphics.Brush.verticalGradient(
                *listOf(frac(Cq.MAX_DB) to Color(0xFFEF4444), frac(15.0) to Color(0xFFEF4444), frac(8.0) to Color(0xFFFACC15),
                    frac(0.0) to Color(0xFF4ADE80), frac(-30.0) to Color(0xFF22D3EE), 1f to Color(0xFF22D3EE)).toTypedArray(),
                startY = 0f, endY = size.height)
            fun bar(d: Float?, x: Float, w: Float) {
                if (d == null || d < -85f) return
                val t = size.height * frac(d.toDouble())
                drawRoundRect(grad, Offset(x, t), Size(w, size.height - t), androidx.compose.ui.geometry.CornerRadius(w / 2))
            }
            if (meter2 == null) bar(meter, cx - 11f, 22f) else { bar(meter, cx - 12f, 11f); bar(meter2, cx + 1f, 11f) }
            // the light cap
            val y = size.height * (1f - pos)
            val cw = size.width * 0.8f; val ch = 34f
            drawRoundRect(Color(0x66000000), Offset(cx - cw / 2 + 1, y - ch / 2 + 3), Size(cw, ch), androidx.compose.ui.geometry.CornerRadius(6f))
            drawRoundRect(if (muted) Color(0xFFFCA5A5) else Cap, Offset(cx - cw / 2, y - ch / 2), Size(cw, ch), androidx.compose.ui.geometry.CornerRadius(6f))
            drawRect(Color(0xFF6B7488), Offset(cx - cw / 2 + 6, y - 1f), Size(cw - 12, 2f))
        }
        // the name tab, lit in the channel colour while the channel is open; dark with white letters when muted
        val lit = !muted
        Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(if (lit) tint else Dark)
            .border(1.dp, if (lit) tint else Rim, RoundedCornerShape(8.dp)).then(tapMod), contentAlignment = Alignment.Center) {
            Text(top, color = if (lit) Color(0xFF0A0F1C) else Text1, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 3.dp))
        }
        Text(sub.substringBefore(" · "), color = Grey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        // Marko, 8.11.2026: a fader moved from its own place gets one orange underline
        if (moved) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Amber))
    }
}


/** A microphone drawn, so no icon library is needed: green ring, mic capsule and stand. */
@Composable
private fun MicButton(enabled: Boolean, onClick: () -> Unit) {
    val c = if (enabled) Green else Grey
    Box(Modifier.padding(3.dp).width(44.dp).height(40.dp).clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.18f))
        .border(1.dp, c, RoundedCornerShape(8.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(22.dp).height(26.dp)) {
            val w = size.width; val h = size.height
            drawRoundRect(c, Offset(w * 0.32f, 0f), Size(w * 0.36f, h * 0.6f), androidx.compose.ui.geometry.CornerRadius(w * 0.18f))
            drawArc(c, 0f, 180f, false, Offset(w * 0.15f, h * 0.25f), Size(w * 0.7f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            drawRect(c, Offset(w * 0.47f, h * 0.75f), Size(w * 0.06f, h * 0.17f))
            drawRect(c, Offset(w * 0.3f, h * 0.9f), Size(w * 0.4f, h * 0.06f))
        }
    }
}

/**
 * TTT's VU meter (TTT_MINI overlay/MaRecordingLine.kt MaVuView), the same numbers: floor -54 dB, fast to rise and
 * slow to fall (0.3), a peak mark falling 0.6 dB per 40 ms, green under -12, amber to -3, red above; dimmed when paused.
 */
@Composable
private fun VuMeter(db: Float, dim: Boolean, modifier: Modifier) {
    var smoothed by remember { mutableStateOf(-54f) }
    var peak by remember { mutableStateOf(-54f) }
    val level by androidx.compose.runtime.rememberUpdatedState(db.coerceIn(-54f, 0f))
    LaunchedEffect(Unit) {
        while (true) {
            val d = level
            smoothed = if (d > smoothed) d else smoothed + (d - smoothed) * 0.3f
            peak = if (d > peak) d else (peak - 0.6f).coerceAtLeast(-54f)
            kotlinx.coroutines.delay(40)
        }
    }
    fun norm(x: Float) = ((x + 54f) / 54f).coerceIn(0f, 1f)
    Canvas(modifier.padding(horizontal = 6.dp)) {
        val w = size.width; val h = size.height; val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
        drawRoundRect(Color(0xFF2A2A2E), cornerRadius = r)
        val n = norm(smoothed)
        val col = when { smoothed > -3f -> Color(0xFF9B3B33); smoothed > -12f -> Color(0xFFF0883E); else -> Color(0xFF56D364) }
        if (n > 0f) drawRoundRect(if (dim) col.copy(alpha = 0.35f) else col, size = Size(w * n, h), cornerRadius = r)
        val p = norm(peak)
        if (p > 0f) drawRect(Sand, Offset((w * p).coerceIn(2f, w - 2f) - 1.5f, 0f), Size(3f, h))
    }
}


/** A desk meter, horizontal: -60..0 dBFS, green / amber over -12 / red over -3 (TTT's colours). */
@Composable
private fun LevelBar(db: Float, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color(0xFF2A2A2E))
        val n = ((db + 60f) / 60f).coerceIn(0f, 1f)
        val c = when { db > -3f -> Color(0xFF9B3B33); db > -12f -> Color(0xFFF0883E); else -> Color(0xFF56D364) }
        if (n > 0f) drawRect(c, size = Size(size.width * n, size.height))
    }
}
