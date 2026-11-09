package com.mantra.cqmixpad.reference

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

private val PBg = Color(0xF20A0F1C)
private val PRim = Color(0xFF263149)
private val PText = Color(0xFFF2F2F7)
private val PGrey = Color(0xFF8E8E93)
private val PAmber = Color(0xFFFF9F0A)
private val PCyan = Color(0xFF64D2FF)
private val PRed = Color(0xFFEF4444)
private val PGreen = Color(0xFF33D17A)

/** The REF button: under the UI button, upper right, the same size and place in CQ, MA and S1 (task 54). Red while the USB recorder records. */
@Composable
fun RefButton(desk: ReferenceDesk, modifier: Modifier, onClick: () -> Unit) {
    val rec by desk.usb.recording.collectAsState()
    val c = if (rec) PRed else PAmber
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 66.dp, end = 16.dp).size(56.dp).clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF2A1E08)).border(2.dp, c, RoundedCornerShape(12.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(if (rec) "● REC" else "REF", color = c, fontSize = if (rec) 13.sp else 18.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Big(label: String, color: Color, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (enabled) color else PGrey
    Box(Modifier.padding(end = 8.dp, bottom = 8.dp).height(56.dp).clip(RoundedCornerShape(12.dp))
        .background(if (filled) c.copy(alpha = 0.3f) else Color(0x990A0F1C)).border(2.dp, c, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled) { onClick() }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(label, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Small(label: String, color: Color, filled: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(end = 6.dp, bottom = 6.dp).height(44.dp).clip(RoundedCornerShape(10.dp))
        .background(if (filled) color.copy(alpha = 0.3f) else Color.Transparent).border(1.dp, color, RoundedCornerShape(10.dp))
        .clickable { onClick() }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun f1(d: Double?) = d?.let { String.format(Locale.US, "%+.1f", it) } ?: "–"

/** LOAD REFERENCE, the reference vs the live Main, MATCH / UNDO, and the CQ USB recorder (task 54). */
@Composable
fun ReferencePanel(desk: ReferenceDesk, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val refs by desk.store.all.collectAsState()
    val sel by desk.store.selected.collectAsState()
    val live by desk.live.collectAsState()
    val busy by desk.busy.collectAsState()
    val orange by desk.orange.collectAsState()
    val lines by desk.lines.collectAsState()
    val matching by desk.matching.collectAsState()
    val dev by desk.usb.device.collectAsState()
    val maxCh by desk.usb.maxChannels.collectAsState()
    val running by desk.usb.running.collectAsState()
    val rec by desk.usb.recording.collectAsState()
    val ms by desk.usb.elapsedMs.collectAsState()
    val lv by desk.usb.levels.collectAsState()
    val clip by desk.usb.clip.collectAsState()
    val ust by desk.usb.status.collectAsState()
    val take by desk.usb.lastTake.collectAsState()
    val file by desk.usb.lastFile.collectAsState()
    var all by remember { mutableStateOf(false) }
    var aac by remember { mutableStateOf(false) }
    var showTake by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ref = refs.firstOrNull { it.id == sel }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var name = "reference"
            runCatching { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.') } }
            desk.load(uri, name)
        }
    }

    Column(Modifier.fillMaxSize().background(PBg).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp)
        .verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 80.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("REFERENCE", color = PAmber, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(12.dp))
            Text("live: " + (live?.let { String.format(Locale.US, "%s · %.0f s · %.0f dB", it.source, it.seconds, it.levelDb) } ?: "none"),
                color = if (live == null) PAmber else PCyan, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Big("CLOSE", PGrey) { onClose() }
        }
        // the references
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
            refs.forEach { p -> Small(p.name.take(28), PAmber, p.id == sel) { desk.select(p.id); confirmDelete = false } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Big("LOAD REFERENCE", PAmber, filled = true, enabled = busy.isBlank()) { pick.launch(arrayOf("audio/*")) }
            Big("MATCH", PCyan, filled = true, enabled = ref != null) { scope.launch { desk.match() } }
            Big("UNDO", PRed, filled = matching, enabled = matching) { scope.launch { desk.undo() } }
            Big("ASK SONNET", PGreen) { desk.ask() }
            if (ref != null && !ref.builtin) Big(if (confirmDelete) "DELETE ${ref.name.take(12)}?" else "DELETE", PRed, filled = confirmDelete) {
                if (confirmDelete) { desk.store.delete(ref.id); desk.select(desk.store.selected.value); confirmDelete = false } else confirmDelete = true
            }
        }
        if (busy.isNotBlank()) Text(busy, color = PAmber, fontSize = 13.sp)
        ref?.let { Text(String.format(Locale.US, "%s · %s LUFS · crest %s dB · LRA %s LU · width %s dB%s", it.name, f1(it.lufs), f1(it.crestDb), f1(it.lra), f1(it.widthDb),
            if (it.seconds > 0) String.format(Locale.US, " · %.0f s", it.seconds) else ""), color = PGrey, fontSize = 12.sp) }

        // the curves: reference amber, live cyan (or the last take, green)
        val cmp = if (showTake && take != null) take!!.thirds else live?.thirds
        val cmpColor = if (showTake && take != null) PGreen else PCyan
        Box(Modifier.fillMaxWidth().height(220.dp).padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0C1424)).border(1.dp, PRim, RoundedCornerShape(12.dp))) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val nb = 31; val w = size.width / nb; val h = size.height
                fun y(db: Double) = (h * (-db / 50.0)).toFloat().coerceIn(0f, h)
                for (i in 0..5) drawRect(Color(0x1AFFFFFF), Offset(0f, h * i / 5f), Size(size.width, 1f))
                cmp?.let { c -> for (j in 0 until nb) { val col = Color.hsv(j / (nb - 1f) * 285f, 0.8f, 1f).copy(alpha = 0.55f)
                    drawRect(col, Offset(j * w + w * 0.15f, y(c[j])), Size(w * 0.7f, h - y(c[j]))) } }
                cmp?.let { c -> for (j in 1 until nb) drawLine(cmpColor, Offset((j - 0.5f) * w, y(c[j - 1])), Offset((j + 0.5f) * w, y(c[j])), 3f) }
                ref?.let { r -> for (j in 1 until nb) drawLine(PAmber, Offset((j - 0.5f) * w, y(r.thirds[j - 1])), Offset((j + 0.5f) * w, y(r.thirds[j])), 4f) }
            }
            Text("amber = ${ref?.name ?: "-"} · ${if (showTake && take != null) "green = the last take" else "cyan = live Main"} · share of the whole, 0 to -50 dB",
                color = PGrey, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        // the difference per octave
        if (ref != null && cmp != null) {
            val o = Bands.octShare(cmp); val r = ref.octaves
            Row(Modifier.fillMaxWidth()) {
                Bands.OCT_NAMES.forEachIndexed { i, n ->
                    val d = o[i] - r[i]
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(n, color = PGrey, fontSize = 11.sp)
                        Text(String.format(Locale.US, "%+.1f", d), color = if (Math.abs(d) < 2) PGreen else if (Math.abs(d) < 5) PAmber else PRed,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
            Text("numbers = ${if (showTake) "take" else "live"} minus reference, dB per octave (+ = more than the reference)", color = PGrey, fontSize = 10.sp)
        }
        orange.forEach { Text("▶ $it", color = PAmber, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }

        // the CQ USB recorder
        Text("CQ USB AUDIO", color = PText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(dev?.let { "$it · " + (if (maxCh >= 16) "Multitrack (Main = 15/16)" else "Stereo (the Main)") } ?: "no USB audio: CQ-12T USB-B to the Pixel's USB-C (desk on Wi-Fi then)",
            color = if (dev == null) PAmber else PCyan, fontSize = 12.sp)
        if (ust.isNotBlank()) Text(ust, color = PGrey, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Big(if (rec) "■ STOP" else "● REC", PRed, filled = rec, enabled = dev != null || rec) {
                if (rec) desk.usb.stop() else desk.usb.start(true, desk.recName(), all, aac)
            }
            Big(if (running && !rec) "LISTENING" else "LISTEN", PCyan, filled = running && !rec, enabled = dev != null && !rec) {
                if (running) desk.usb.stop() else desk.usb.start(false, desk.recName(), false, false)
            }
            if (maxCh >= 16) Small(if (all) "ALL ${maxCh}" else "MAIN L/R", PCyan, all) { if (!rec) all = !all }
            Small(if (aac) "AAC 256k" else "WAV 24-bit", PCyan, aac) { if (!rec) aac = !aac }
            val s = ms / 1000
            Text(String.format(Locale.US, "  %d:%02d:%02d", s / 3600, s / 60 % 60, s % 60), color = if (rec) PRed else PGrey, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (running) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("L" to lv[0], "R" to lv[1]).forEach { (n, d) ->
                Text("$n ", color = PGrey, fontSize = 12.sp)
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF1C2333))) {
                    val fr = ((d + 60f) / 60f).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(fr).height(10.dp).background(if (d > -1f) PRed else if (d > -9f) PAmber else PGreen))
                }
                Text(String.format(Locale.US, " %.0f  ", d), color = PGrey, fontSize = 12.sp)
            }
            if (clip) Text("CLIP", color = PRed, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        if (file.isNotBlank()) Text("Music/CQ MixPad/$file", color = PGrey, fontSize = 11.sp)
        if (take != null) Row(Modifier.horizontalScroll(rememberScrollState())) {
            Small(if (showTake) "SHOWING THE TAKE" else "COMPARE THE TAKE", PGreen, showTake) { showTake = !showTake }
            Small("TAKE AS REFERENCE", PAmber, false) { desk.addTake() }
            Text(String.format(Locale.US, "  %s: %.0f s · %s LUFS · crest %s · width %s", take!!.name, take!!.seconds, f1(take!!.lufs), f1(take!!.crestDb), f1(take!!.widthDb)),
                color = PGrey, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
        MultiSection(desk)
        // what was done
        lines.take(12).forEach { Text(it, color = PGrey, fontSize = 11.sp) }
        Spacer(Modifier.height(40.dp))
    }
}

/** The multitrack mirror of the desk's SD recorder (Marko, 9.11.2026), and the desk's USB Stream Mode. */
@Composable
private fun MultiSection(desk: ReferenceDesk) {
    val m = desk.multi
    val scope = rememberCoroutineScope()
    val mirror by m.mirror.collectAsState()
    val running by m.running.collectAsState()
    val st by m.status.collectAsState()
    val tr by m.tracks.collectAsState()
    val pk by m.peaks.collectAsState()
    val ms by m.elapsedMs.collectAsState()
    val probe by m.probeText.collectAsState()
    val takes by m.takes.collectAsState()
    val dump by desk.recorderState()
    dump.hashCode()
    val mode = m.streamMode()
    val armed = desk.armedTracks()
    Text("MULTITRACK (the SD recorder's armed tracks, one WAV each)", color = PText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
    Text("Stream Mode on the desk: ${mode ?: "…"} · armed: ${if (armed.isEmpty()) "…" else armed.joinToString(",")}", color = PGrey, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        Big(if (mode == "Multitrack") "STREAM: MULTITRACK" else "STREAM: STEREO", PCyan, filled = mode == "Multitrack", enabled = !running) {
            m.setStream(mode != "Multitrack")
        }
        Big(if (running) "■ STOP MULTI" else "● REC MULTI", PRed, filled = running) {
            if (running) { desk.deskStop() } else { if (mode != "Multitrack") m.setStream(true); desk.deskRec() }
        }
        Small(if (mirror) "MIRROR SD: ON" else "MIRROR SD: OFF", PAmber, mirror) { m.mirror.value = !mirror }
        Small("PROBE CHANNELS", PCyan, false) { scope.launch(kotlinx.coroutines.Dispatchers.IO) { m.probe() } }
        val s = ms / 1000
        if (running) Text(String.format(Locale.US, "  %d:%02d:%02d", s / 3600, s / 60 % 60, s % 60), color = PRed, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    val n = if (running) tr.size else armed.size
    val free = m.freeBytes()
    val left = m.minutesLeft(maxOf(n, 1))
    Text(String.format(Locale.US, "phone storage: %.1f GB free · %d min left at %d track%s (8.6 MB a minute a track)", free / 1e9, left, maxOf(n, 1), if (n == 1) "" else "s"),
        color = if (left < 15) PRed else PGrey, fontSize = 12.sp)
    if (left < 15) Text("STORAGE LOW: fewer tracks, or move takes off the phone (nothing is deleted by the app)", color = PRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    if (st.isNotBlank()) Text(st, color = if (running) PRed else PGrey, fontSize = 11.sp)
    if (running && pk.isNotEmpty()) Row(Modifier.fillMaxWidth().height(60.dp).padding(vertical = 4.dp)) {
        pk.forEachIndexed { i, d ->
            Column(Modifier.weight(1f).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).width(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1C2333)), contentAlignment = Alignment.BottomCenter) {
                    val fr = ((d + 60f) / 60f).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().fillMaxHeight(fr).background(if (d > -1f) PRed else if (d > -9f) PAmber else PGreen))
                }
                Text(tr.getOrNull(i)?.toString() ?: "", color = PGrey, fontSize = 10.sp)
            }
        }
    }
    if (probe.isNotBlank()) Text(probe, color = PCyan, fontSize = 11.sp)
    takes.take(6).forEach { (folder, uris) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Small("SHARE", PGreen, false) { m.share(folder) }
            Text("${folder.removePrefix("Documents/CQ MixPad/")} · ${uris.size} files", color = PGrey, fontSize = 12.sp)
        }
    }
}
