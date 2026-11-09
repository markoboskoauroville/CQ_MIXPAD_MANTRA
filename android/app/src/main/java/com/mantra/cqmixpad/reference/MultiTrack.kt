package com.mantra.cqmixpad.reference

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import com.mantra.cqmixpad.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/**
 * The multitrack mirror (Marko, 9.11.2026): "It simply sees on the mixer which tracks are armed and what is recording to
 * the SD card at the moment. Then, with the same settings, in real time, it streams the multichannel audio to the phone
 * and records each track as a WAV file of its own. Faster transfer of multitracks."
 *
 * The desk's SD state and armed mask come from [Recorder] (decoded 7.11.2026); the audio is the CQ's USB stream in
 * Stream Mode Multitrack (F7 11 11 13 00; Stereo = 13 01; the dump byte 38870 = 1 Multitrack, 3 Stereo, measured
 * 9.11.2026). Capture thread → Oboe's FifoBuffer (ported, OboePort.kt) → writer thread → one 24-bit WAV per armed track
 * (Oboe's WaveFileWriter, ported), sample-aligned because every file takes the same frames. Folder:
 * Documents/CQ MixPad/<place date>/<take>/<take>_<NN>_<label>.wav + manifest.json. Nothing is ever deleted.
 *
 * MEASURED on the Pixel 7 (9.11.2026): its USB audio HAL opens the CQ at 16 ch / 32-bit / 48 kHz but logs
 * "can't find valid ch for 16" and offers apps no profile with more than 2 channels; [probe] says what each channel
 * request gives. The mirror records what Android delivers and says so in the manifest.
 */
class MultiTrack(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val recorder: Recorder,
    private val usb: UsbAudio,
    private val log: (String) -> Unit,
    private val labelOf: (Int) -> String,
    private val concertName: () -> String,
) {
    val mirror = MutableStateFlow(true)                 // follow the desk's SD REC/STOP
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val tracks = MutableStateFlow<List<Int>>(emptyList())     // 1-based track numbers being written
    val peaks = MutableStateFlow(FloatArray(0))
    val elapsedMs = MutableStateFlow(0L)
    val probeText = MutableStateFlow("")
    val takes = MutableStateFlow<List<Pair<String, List<Uri>>>>(emptyList())
    @Volatile private var stopFlag = false
    private var capture: Thread? = null

    private val am = ctx.getSystemService(AudioManager::class.java)
    private fun device(): AudioDeviceInfo? = usb.find()

    // ---------- Stream Mode ----------
    /** "Multitrack" / "Stereo" / null (no dump yet), from the dump byte measured 9.11.2026. */
    fun streamMode(): String? = recorder.state0.value?.let { d -> if (d.size > 38870) when (d[38870].toInt()) { 1 -> "Multitrack"; 3 -> "Stereo"; else -> "? (${d[38870]})" } else null }
    fun setStream(multi: Boolean): Boolean {
        val was = streamMode()
        val ok = recorder.sendRaw(byteArrayOf(0xF7.toByte(), 0x11, 0x11, 0x13, if (multi) 0 else 1, 0, 0, 0))
        recorder.state0.value?.let { d -> if (d.size > 38870) { val c = d.copyOf(); c[38870] = if (multi) 1 else 3; recorder.state0.value = c } }
        log("  STREAM MODE: $was -> ${if (multi) "Multitrack" else "Stereo"} (F7 11 11 13 0${if (multi) 0 else 1})")
        scope.launch { delay(1500); recorder.askState() }
        return ok
    }

    // ---------- storage ----------
    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)
    /** Minutes left at [n] tracks of 48 kHz 24-bit (8.64 MB a minute a track). */
    fun minutesLeft(n: Int): Long = if (n <= 0) 0 else freeBytes() / (n * 48000L * 3 * 60)

    init {
        scope.launch {
            var was = Recorder.State.UNKNOWN
            recorder.state.collect { s ->
                if (mirror.value && s == Recorder.State.RECORDING && was == Recorder.State.STOPPED && !running.value) {
                    delay(1500)       // the take's name arrives with the next status
                    start(recorder.take.value.ifBlank { "take" })
                }
                if (s == Recorder.State.STOPPED && running.value) stop()
                was = s
            }
        }
        scope.launch(Dispatchers.IO) { refreshTakes() }
    }

    // ---------- the probe ----------
    @SuppressLint("MissingPermission")
    fun probe(): String {
        val dev = device() ?: return "no USB audio device".also { probeText.value = it }
        val sb = StringBuilder("USB ${dev.productName}: channelCounts ${dev.channelCounts.joinToString("/")}, index masks ${dev.channelIndexMasks.joinToString("/") { Integer.toHexString(it) }}, encodings ${dev.encodings.joinToString("/")}, stream ${streamMode()}\n")
        if (usb.running.value) usb.stop()
        for (ch in listOf(16, 12, 10, 8, 6, 4, 2)) for (enc in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            val r = try {
                val f = AudioFormat.Builder().setSampleRate(48000).setEncoding(enc).setChannelIndexMask((1 shl ch) - 1).build()
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED).setAudioFormat(f).setBufferSizeInBytes(48000 * ch * 4 / 5).build()
            } catch (e: Exception) { sb.append("  $ch ch ${encName(enc)}: refused (${e.javaClass.simpleName})\n"); null } ?: continue
            if (r.state != AudioRecord.STATE_INITIALIZED) { sb.append("  $ch ch ${encName(enc)}: not initialised\n"); r.release(); continue }
            r.setPreferredDevice(dev)
            try {
                r.startRecording()
                val n = r.channelCount; val buf = FloatArray(4800 * n); val sbuf = ShortArray(4800 * n)
                val sq = DoubleArray(n); var frames = 0
                repeat(5) {
                    val got = if (enc == AudioFormat.ENCODING_PCM_FLOAT) r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                              else r.read(sbuf, 0, sbuf.size).also { g -> for (i in 0 until maxOf(g, 0)) buf[i] = sbuf[i] / 32768f }
                    for (i in 0 until maxOf(got, 0)) { val v = buf[i].toDouble(); sq[i % n] += v * v }
                    frames += maxOf(got, 0) / n
                }
                sb.append("  $ch ch ${encName(enc)}: OPEN, delivers ${n} ch at ${r.sampleRate} Hz on ${r.routedDevice?.productName}; RMS dB per channel ")
                sb.append(sq.joinToString(" ") { String.format(Locale.US, "%.0f", 10 * log10(it / maxOf(frames, 1) + 1e-12)) }).append('\n')
            } catch (e: Exception) { sb.append("  $ch ch ${encName(enc)}: ${e.javaClass.simpleName} ${e.message}\n") }
            finally { runCatching { r.stop() }; r.release() }
            break
        }
        // 9.11.2026: every request above opened but read exact zeros from the CQ: try the other capture sources (the
        // Pixel routes USB capture through its AoC DSP; the source picks the DSP path), stereo mask, both encodings
        val sources = listOf(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED", MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION", MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT", MediaRecorder.AudioSource.VOICE_PERFORMANCE to "VOICE_PERFORMANCE")
        for ((src, sn) in sources) for (enc in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) {
            val r = try {
                val f = AudioFormat.Builder().setSampleRate(48000).setEncoding(enc).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
                AudioRecord.Builder().setAudioSource(src).setAudioFormat(f).setBufferSizeInBytes(48000 * 2 * 4 / 5).build()
            } catch (e: Exception) { sb.append("  $sn ${encName(enc)}: refused\n"); null } ?: continue
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); sb.append("  $sn ${encName(enc)}: not initialised\n"); continue }
            r.setPreferredDevice(dev)
            try {
                r.startRecording(); Thread.sleep(300)
                val n = r.channelCount; val buf = FloatArray(4800 * n); val sbuf = ShortArray(4800 * n)
                val sq = DoubleArray(n); var frames = 0; var nonzero = 0
                repeat(5) {
                    val got = if (enc == AudioFormat.ENCODING_PCM_FLOAT) r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                              else r.read(sbuf, 0, sbuf.size).also { g -> for (i in 0 until maxOf(g, 0)) buf[i] = sbuf[i] / 32768f }
                    for (i in 0 until maxOf(got, 0)) { val v = buf[i].toDouble(); if (v != 0.0) nonzero++; sq[i % n] += v * v }
                    frames += maxOf(got, 0) / n
                }
                sb.append("  $sn stereo ${encName(enc)}: on ${r.routedDevice?.productName}, non-zero samples $nonzero, RMS dB ")
                sb.append(sq.joinToString(" ") { String.format(Locale.US, "%.0f", 10 * log10(it / maxOf(frames, 1) + 1e-12)) }).append('\n')
            } catch (e: Exception) { sb.append("  $sn ${encName(enc)}: ${e.javaClass.simpleName}\n") }
            finally { runCatching { r.stop() }; r.release() }
        }
        probeText.value = sb.toString(); log("  PROBE: " + sb.toString().replace('\n', ' '))
        return sb.toString()
    }
    private fun encName(e: Int) = if (e == AudioFormat.ENCODING_PCM_FLOAT) "float" else "16-bit"

    // ---------- record ----------
    @SuppressLint("MissingPermission")
    private fun openBest(dev: AudioDeviceInfo): Pair<AudioRecord, Int>? {
        for (ch in listOf(16, 12, 8, 4, 2)) for (enc in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            try {
                val f = AudioFormat.Builder().setSampleRate(48000).setEncoding(enc).setChannelIndexMask((1 shl ch) - 1).build()
                val r = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED).setAudioFormat(f)
                    .setBufferSizeInBytes(48000 * ch * 4 / 2).build()
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                r.setPreferredDevice(dev); return r to enc
            } catch (_: Exception) {}
        }
        return null
    }

    /** Starts the phone's copy of the take [take] (the desk's name, "CQ-MT008"). */
    fun start(take: String) {
        if (running.value) return
        val dev = device() ?: run { status.value = "no USB audio: the CQ's USB-B to the Pixel"; log("  MULTITRACK: " + status.value); return }
        if (usb.running.value) usb.stop()
        stopFlag = false
        capture = Thread({ record(dev, take) }, "cq-multitrack").apply { start() }
    }

    fun stop() { stopFlag = true; capture?.join(6000); capture = null }

    private class Track(val n: Int, val name: String, val uri: Uri, val pfd: ParcelFileDescriptor, val w: WaveFileWriter)

    private fun record(dev: AudioDeviceInfo, take: String) {
        val (rec, enc) = openBest(dev) ?: run { status.value = "Android refused the USB input"; log("  MULTITRACK: " + status.value); return }
        val ch = rec.channelCount; val rate = rec.sampleRate
        val armedMask = recorder.armed.value ?: 0xFFFF
        val want = (1..16).filter { armedMask and (1 shl (it - 1)) != 0 }
        val got = want.filter { it <= ch }
        val missing = want.filter { it > ch }
        val folder = "Documents/CQ MixPad/" + clean(concertName().ifBlank { "CQ" }) + "/" + clean(take)
        val started = System.currentTimeMillis()
        val list = ArrayList<Track>()
        try {
            for (n in got) {
                val name = String.format(Locale.US, "%s_%02d_%s.wav", clean(take), n, clean(labelOf(n)))
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: throw IllegalStateException("MediaStore refused $name")
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")!!
                list += Track(n, name, uri, pfd, WaveFileWriter(FileOutputStream(pfd.fileDescriptor), rate, 1, 24))
            }
        } catch (e: Exception) { status.value = "could not create the files: ${e.message}"; log("  MULTITRACK: " + status.value); rec.release(); return }
        tracks.value = got
        val fifo = FifoBuffer(ch, rate * 4)                     // 4 s of every channel
        val peak = FloatArray(list.size)
        var framesWritten = 0L
        val writer = Thread({
            val blk = FloatArray(ch * 4800); val mono = FloatArray(4800)
            while (true) {
                val n = fifo.read(blk, 4800)
                if (n == 0) { if (stopFlag) break; Thread.sleep(10); continue }
                for ((ti, t) in list.withIndex()) {
                    val c = t.n - 1; var p = peak[ti]
                    for (f in 0 until n) { val v = blk[f * ch + c]; mono[f] = v; val a = abs(v); if (a > p) p = a }
                    peak[ti] = p
                    t.w.write(mono, 0, n)
                }
                framesWritten += n
            }
        }, "cq-multitrack-writer")
        try {
            rec.startRecording()
            running.value = true
            status.value = String.format(Locale.US, "%s: %d of %d armed tracks (Android gives %d ch, %s)%s · %s", take, got.size, want.size, ch,
                if (enc == AudioFormat.ENCODING_PCM_FLOAT) "float" else "16-bit", if (missing.isNotEmpty()) " · NOT on the phone: " + missing.joinToString(",") else "", folder)
            log("  MULTITRACK: " + status.value)
            writer.start()
            val buf = FloatArray(ch * 960); val sbuf = ShortArray(ch * 960)
            var tick = 0
            while (!stopFlag) {
                val n = if (enc == AudioFormat.ENCODING_PCM_FLOAT) rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                        else rec.read(sbuf, 0, sbuf.size).also { g -> for (i in 0 until maxOf(g, 0)) buf[i] = sbuf[i] / 32768f }
                if (n <= 0) { status.value = "USB read error $n"; break }
                fifo.write(buf, n / ch)
                elapsedMs.value = System.currentTimeMillis() - started
                if (++tick % 10 == 0) { peaks.value = FloatArray(peak.size) { if (peak[it] > 0) 20 * log10(peak[it]) else -120f }; for (i in peak.indices) peak[i] = 0f }
            }
        } catch (e: Exception) { status.value = "stopped: ${e.javaClass.simpleName} ${e.message}"; log("  MULTITRACK: " + status.value) }
        finally {
            stopFlag = true
            runCatching { rec.stop() }; rec.release()
            writer.join(10_000)
            for (t in list) { runCatching { t.w.close() }; runCatching { t.pfd.close() }
                ctx.contentResolver.update(t.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
            // manifest.json beside the tracks
            runCatching {
                val m = JSONObject().apply {
                    put("take", take); put("concert", concertName()); put("start", java.text.SimpleDateFormat("d.M.yyyy HH:mm:ss", Locale.US).format(java.util.Date(started)))
                    put("seconds", framesWritten.toDouble() / rate); put("frames", framesWritten); put("rate", rate); put("bits", 24)
                    put("usbChannels", ch); put("streamMode", streamMode() ?: "?"); put("armed", JSONArray(want)); put("notOnPhone", JSONArray(missing))
                    put("overrunFrames", fifo.overruns.get())
                    put("tracks", JSONArray(list.map { t -> JSONObject().put("track", t.n).put("label", labelOf(t.n)).put("file", t.name)
                        .put("gainDb", recorder.gainNow(t.n - 1) ?: JSONObject.NULL).put("trimDb", recorder.trimNow(t.n - 1) ?: JSONObject.NULL) }))
                }
                val v = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "manifest.json"); put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder) }
                ctx.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)?.let { u ->
                    ctx.contentResolver.openOutputStream(u)?.use { it.write(m.toString(1).toByteArray()) } }
            }
            val r = String.format(Locale.US, "%s saved: %d tracks x %.1f s in %s%s", take, list.size, framesWritten.toDouble() / rate, folder,
                if (fifo.overruns.get() > 0) " · DROPOUTS ${fifo.overruns.get()} frames" else " · no dropouts")
            status.value = r; log("  MULTITRACK: $r")
            running.value = false; peaks.value = FloatArray(0)
            refreshTakes()
        }
    }

    private fun clean(s: String) = s.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()

    // ---------- the takes on the phone, and sharing them ----------
    fun refreshTakes() {
        val res = LinkedHashMap<String, MutableList<Uri>>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Documents/CQ MixPad/%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), c.getLong(0))
                    res.getOrPut(c.getString(1).trimEnd('/')) { ArrayList() } += uri
                }
            }
        }
        takes.value = res.entries.map { it.key to it.value.toList() }
    }

    /** Android's share sheet (Drive, Nearby Share, Gmail…) with every file of the take; the Mac pulls it with adb. */
    fun share(folder: String) {
        val uris = takes.value.firstOrNull { it.first == folder }?.second ?: return
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/wav"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share ${folder.substringAfterLast('/')}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
