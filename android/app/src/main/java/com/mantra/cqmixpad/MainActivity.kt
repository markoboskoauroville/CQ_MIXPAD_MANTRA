package com.mantra.cqmixpad

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import org.json.JSONObject
import java.util.Locale

private val Bg = Color(0xFF0B0B0C)
private val Panel = Color(0xFF17171A)
private val Rim = Color(0xFF2C2C2E)
private val Text1 = Color(0xFFF2F2F7)
private val Grey = Color(0xFF8E8E93)
private val Red = Color(0xFFFF453A)
private val Green = Color(0xFF33D17A)
private val Amber = Color(0xFFFF9F0A)
private val Blue = Color(0xFF0A84FF)
private val Cyan = Color(0xFF64D2FF)

class MainActivity : ComponentActivity() {
    private lateinit var link: Link
    private lateinit var store: Store
    private lateinit var brain: Brain
    private val rta = Rta()
    private var rtaJob: Job? = null
    private var tts: TextToSpeech? = null
    private lateinit var voice: Voice

    private val log = mutableStateListOf<String>()
    private val pending = mutableStateListOf<Brain.Action>()
    private var busy by mutableStateOf(false)
    private var heard by mutableStateOf("")
    private var speakAloud by mutableStateOf(true)
    private var micOk by mutableStateOf(false)
    private var rtaOn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        link = Link(this, lifecycleScope)
        brain = Brain(store)
        tts = TextToSpeech(this) { tts?.language = Locale.US }
        voice = Voice(this, onPartial = { heard = it }, onFinal = { t ->
            heard = ""
            if (!t.isNullOrBlank()) command(t, fromVoice = true) else say("I did not catch that.")
        })
        link.start()
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micOk) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() { tts?.shutdown(); voice.stop(); rta.stop(); super.onDestroy() }

    private fun say(t: String) {
        log.add(0, t); while (log.size > 30) log.removeAt(log.lastIndex)
        if (speakAloud) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "say")
    }

    // ---------- the desk as text, for Sonnet ----------

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
                val a = brain.ask(text, deskText(), rtaText, link.present.value.map { it.id }, effortHigh = withRta)
                if (withRta) { pending.clear(); pending.addAll(a.actions.filter { it.name !in setOf("remember", "note_for_marko", "set_label") }) }
                for (act in a.actions) {
                    if (!withRta || act.name in setOf("remember", "note_for_marko", "set_label")) apply(act)
                }
                if (a.say.isNotBlank()) say(a.say)
            } catch (e: Exception) {
                say("Sonnet did not answer: ${e.message?.take(140)}")
            } finally { busy = false }
        }
    }

    /** Carries out one action; the safety limits are here, not only in the prompt. */
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
                if (now > Cq.MIN_DB && target - now > 6.0) { target = now + 6.0; log.add(0, "  ! limited to +6 dB at once") }
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
        }
    }

    private fun toggleRta() {
        if (rtaJob?.isActive == true) { rta.stop(); rtaJob = null; rtaOn = false }
        else if (micOk) { rta.reset(); rtaJob = lifecycleScope.launch { rta.run() }; rtaOn = true }
        else openAppSettings()
    }

    private fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun saveConcert() {
        val mix = JSONObject()
        link.values.forEach { (k, v) -> mix.put(k.toString(), v) }
        mix.put("desk", deskText())
        val name = store.saveConcert(mix)
        say("Concert saved: $name.")
    }

    // ---------- the screen ----------

    @Composable
    private fun Screen() {
        val tick by link.tick.collectAsState()
        val status by link.status.collectAsState()
        val connected by link.connected.collectAsState()
        val present by link.present.collectAsState()
        val labels by store.labels.collectAsState()
        var mix by remember { mutableStateOf("MAIN") }
        var keyDialog by remember { mutableStateOf(false) }
        tick.hashCode()

        Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp).imePadding()) {
            // top bar
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (connected) Green else Red))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("CQ MixPad (Mantra)", color = Text1, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(status, color = Grey, fontSize = 11.sp, maxLines = 2)
                }
                if (!micOk) Chip("PERMISSIONS", Amber) { openAppSettings() }
                if (store.apiKey() == null) Chip("KEY", Amber) { keyDialog = true }
                Chip(if (speakAloud) "VOICE ON" else "VOICE OFF", if (speakAloud) Green else Grey) { speakAloud = !speakAloud; tts?.stop() }
                Chip("SAVE", Blue) { saveConcert() }
            }
            // mixes
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (m in listOf("MAIN", "OUT1", "OUT2", "OUT3", "OUT4", "OUT5", "OUT6", "FX1", "FX2")) Chip(m, if (m == mix) Cyan else Grey, filled = m == mix) { mix = m }
            }
            RtaStrip()
            // the faders
            Row(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                for (s in present) {
                    val p = Cq.sendParam(s, mix) ?: continue
                    val l = labels[s.id]
                    Strip(top = l?.name?.ifBlank { null } ?: s.id, sub = if (l != null) s.id + " · " + l.instrument else "",
                        db = link.db(p), muted = link.muted(Cq.muteParam(s)),
                        pan = if (mix == "MAIN") link.values[Cq.panMainParam(s)]?.let { Cq.valueToPan(it) } else null,
                        onDb = { link.setDb(p, it) }, onMute = { link.setMute(Cq.muteParam(s), !link.muted(Cq.muteParam(s))) },
                        onPan = { link.setPan(Cq.panMainParam(s), it) })
                }
                Cq.masterParam(mix)?.let { p ->
                    val mp = Cq.masterMuteParam(mix)
                    Strip(top = if (mix == "MAIN") "MAIN LR" else mix, sub = "master", db = link.db(p),
                        muted = mp?.let { link.muted(it) } ?: false, pan = null, master = true,
                        onDb = { link.setDb(p, it) }, onMute = { mp?.let { link.setMute(it, !link.muted(it)) } }, onPan = {})
                }
                if (mix == "MAIN") for (d in 1..4) if (link.values.containsKey(Cq.dcaLevelParam(d))) {
                    Strip(top = "DCA$d", sub = "", db = link.db(Cq.dcaLevelParam(d)), muted = link.muted(Cq.dcaMuteParam(d)), pan = null,
                        onDb = { link.setDb(Cq.dcaLevelParam(d), it) }, onMute = { link.setMute(Cq.dcaMuteParam(d), !link.muted(Cq.dcaMuteParam(d))) }, onPan = {})
                }
            }
            // suggestions waiting for APPLY
            if (pending.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(8.dp)) {
                    Text("SONNET SUGGESTS", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    pending.forEach { Text(it.text(), color = Text1, fontSize = 13.sp) }
                    Row {
                        Chip("APPLY", Green, filled = true) { pending.toList().forEach { apply(it) }; pending.clear() }
                        Chip("DISMISS", Grey) { pending.clear() }
                    }
                }
            }
            // what was said and done
            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                if (heard.isNotBlank()) Text("… $heard", color = Cyan, fontSize = 13.sp)
                log.take(12).forEach {
                    Text(it, color = when { it.startsWith("YOU") -> Grey; it.startsWith("🟠") -> Amber; it.startsWith("  ") -> Green; else -> Text1 }, fontSize = 13.sp)
                }
            }
            // the command line to Sonnet
            CommandBar()
        }
        if (keyDialog) KeyDialog { keyDialog = false }
    }

    @Composable
    private fun RtaStrip() {
        val live by rta.live.collectAsState()
        val on = rtaOn
        Row(Modifier.fillMaxWidth().height(96.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Panel)) {
                val n = 10; val w = size.width / n
                fun y(db: Double) = (size.height * (-db / 50.0)).toFloat().coerceIn(0f, size.height)
                for (i in 0 until n) {
                    if (on) {
                        val top = y(live[i])
                        drawRect(Cyan.copy(alpha = 0.75f), Offset(i * w + w * 0.15f, top), Size(w * 0.7f, size.height - top))
                    }
                    val k = y(Rta.KD[i])
                    drawRect(Amber, Offset(i * w + 2f, k - 2f), Size(w - 4f, 4f))
                }
            }
            Column(Modifier.padding(start = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Chip(if (on) "RTA ON" else "RTA", if (on) Cyan else Grey, filled = on) { toggleRta() }
                Chip("MATCH KD", Amber) {
                    if (rtaJob?.isActive != true) toggleRta()
                    command("Compare the RTA with the Krishna Das profile and suggest how to fit the mix, vocals in the first plan.", fromVoice = false, withRta = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Rta.NAMES.forEachIndexed { i, n ->
                Text(if (on) String.format(Locale.US, "%s %+.0f", n, live[i] - Rta.KD[i]) else n, color = Grey, fontSize = 9.sp,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1)
            }
            Spacer(Modifier.width(78.dp))
        }
    }

    @Composable
    private fun CommandBar() {
        var text by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, Rim, RoundedCornerShape(8.dp)).background(Panel).padding(10.dp)) {
                if (text.isEmpty()) Text(if (busy) "Sonnet is working…" else "Tell Sonnet: backing vocal Diana on channel 4…", color = Grey, fontSize = 14.sp)
                BasicTextField(text, { text = it }, textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { command(text.trim(), false); text = "" } }),
                    modifier = Modifier.fillMaxWidth())
            }
            Chip("SEND", if (busy) Grey else Cyan, filled = !busy) { if (text.isNotBlank()) { command(text.trim(), false); text = "" } }
            Chip("SPEAK", if (micOk) Green else Amber, filled = micOk) { if (micOk) { tts?.stop(); voice.listen() } else openAppSettings() }
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
        .border(1.dp, color, RoundedCornerShape(6.dp)).clickable { onClick() }.padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** One channel strip: name, mute, fader (the CQ's own taper: travel = the 14-bit value), dB, pan on MAIN. */
@Composable
private fun Strip(top: String, sub: String, db: Double, muted: Boolean, pan: Double?, master: Boolean = false,
                  onDb: (Double) -> Unit, onMute: () -> Unit, onPan: (Double) -> Unit) {
    val maxV = Cq.dbToValue(Cq.MAX_DB).toFloat()
    val setDb by androidx.compose.runtime.rememberUpdatedState(onDb)
    val setPan by androidx.compose.runtime.rememberUpdatedState(onPan)
    Column(Modifier.width(64.dp).fillMaxHeight().padding(horizontal = 2.dp).clip(RoundedCornerShape(6.dp))
        .background(if (master) Color(0xFF1E1A12) else Panel).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(top, color = Text1, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Grey, fontSize = 9.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(5.dp))
            .background(if (muted) Red else Color(0xFF2A2A2E)).clickable { onMute() }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text("MUTE", color = if (muted) Color.White else Grey, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (pan != null) {
            Canvas(Modifier.fillMaxWidth().height(22.dp).pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { setPan(0.0) })
            }.pointerInput(Unit) {
                detectHorizontalDragGestures { ch, _ ->
                    setPan(((ch.position.x / size.width) * 200 - 100).toDouble().coerceIn(-100.0, 100.0))
                }
            }) {
                drawRect(Rim, Offset(0f, size.height / 2 - 2), Size(size.width, 4f))
                val x = ((pan + 100) / 200 * size.width).toFloat()
                drawRect(Cyan, Offset(x - 4, 2f), Size(8f, size.height - 4))
            }
            Text(if (kotlin.math.abs(pan) < 1) "C" else if (pan < 0) "L${(-pan).toInt()}" else "R${pan.toInt()}", color = Grey, fontSize = 9.sp)
        }
        Text(Cq.dbText(db), color = if (muted) Red else Cyan, fontSize = 11.sp)
        val pos = (Cq.dbToValue(db) / maxV).coerceIn(0f, 1f)
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).pointerInput(Unit) {
            detectVerticalDragGestures { ch, _ ->
                val f = (1f - ch.position.y / size.height).coerceIn(0f, 1f)
                setDb(Cq.valueToDb((f * maxV).toInt()))
            }
        }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { setDb(0.0) }) }) {
            val cx = size.width / 2
            drawRect(Rim, Offset(cx - 3, 0f), Size(6f, size.height))
            val zero = size.height * (1f - Cq.dbToValue(0.0) / maxV)
            drawRect(Grey, Offset(cx - 14, zero - 1), Size(28f, 2f))
            val y = size.height * (1f - pos)
            drawRect(if (muted) Red else Text1, Offset(cx - size.width * 0.38f, y - 10), Size(size.width * 0.76f, 20f))
        }
    }
}
