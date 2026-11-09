package com.mantra.cqmixpad.reference

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

/** The references: Krishna Das built in, Marko's loaded ones in files/refs/<id>.json; the selected one in prefs "refs". */
class RefStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "refs").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("refs", Context.MODE_PRIVATE)
    val all = MutableStateFlow<List<Profile>>(emptyList())
    val selected = MutableStateFlow(RefStoreIds.KD)

    val kd = Profile(RefStoreIds.KD, "Krishna Das", Bands.KD_FILE31, -14.0, null, null, null, null, 0.0, builtin = true,
        source = "two references, ~/Music/SantoshiKoncert/reference (desk-measured 8.11.2026)")

    init { reload(); selected.value = prefs.getString("selected", RefStoreIds.KD) ?: RefStoreIds.KD; if (current() == null) select(RefStoreIds.KD) }

    fun reload() {
        val l = dir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        all.value = listOf(kd) + l
    }
    fun current(): Profile? = all.value.firstOrNull { it.id == selected.value }
    fun select(id: String) { selected.value = id; prefs.edit().putString("selected", id).apply() }
    fun save(p: Profile) { File(dir, p.id + ".json").writeText(toJson(p).toString(1)); reload() }
    fun delete(id: String) { if (id == RefStoreIds.KD) return; File(dir, "$id.json").delete(); if (selected.value == id) select(RefStoreIds.KD); reload() }

    companion object {
        fun toJson(p: Profile) = JSONObject().apply {
            put("id", p.id); put("name", p.name); put("thirds", JSONArray(p.thirds.map { Math.round(it * 100) / 100.0 }))
            put("octaves", JSONArray(p.octaves.map { Math.round(it * 100) / 100.0 }))
            p.lufs?.let { put("lufs", it) }; p.peakDb?.let { put("peak", it) }; p.crestDb?.let { put("crest", it) }
            p.lra?.let { put("lra", it) }; p.widthDb?.let { put("width", it) }; put("seconds", p.seconds); put("source", p.source)
            put("measured", java.text.SimpleDateFormat("d.M.yyyy HH:mm", java.util.Locale.US).format(java.util.Date()))
        }
        fun fromJson(o: JSONObject): Profile {
            val t = o.getJSONArray("thirds"); fun opt(k: String) = if (o.has(k)) o.getDouble(k) else null
            return Profile(o.getString("id"), o.getString("name"), DoubleArray(31) { t.getDouble(it) }, opt("lufs"), opt("peak"), opt("crest"),
                opt("lra"), opt("width"), o.optDouble("seconds", 0.0), false, o.optString("source", ""))
        }
    }
}

/** Decodes any audio file Android can (MP3, WAV, M4A/AAC, FLAC, OGG) with MediaExtractor + MediaCodec into an [Analyzer]. */
object FileDecoder {
    const val MAX_SECONDS = 30 * 60.0

    fun analyse(ctx: Context, uri: Uri, name: String, progress: (Double) -> Unit): Profile {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        try {
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalArgumentException("no audio in this file")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0); codec.start()
            var an: Analyzer? = null
            var pcmFloat = false
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            var samples = 0L
            try {
                while (!outDone) {
                    if (!inDone) {
                        val ib = codec.dequeueInputBuffer(10_000)
                        if (ib >= 0) {
                            val buf = codec.getInputBuffer(ib)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || (an != null && samples / rate.toDouble() > MAX_SECONDS)) {
                                codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                            } else { codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val ob = codec.dequeueOutputBuffer(info, 10_000)
                    if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        an = null
                    } else if (ob >= 0) {
                        if (info.size > 0) {
                            val a = an ?: Analyzer(rate, ch).also { an = it }
                            val bb = codec.getOutputBuffer(ob)!!.order(ByteOrder.LITTLE_ENDIAN)
                            bb.position(info.offset); bb.limit(info.offset + info.size)
                            val x: FloatArray
                            if (pcmFloat) { val fb = bb.asFloatBuffer(); x = FloatArray(fb.remaining()); fb.get(x) }
                            else { val sb = bb.asShortBuffer(); x = FloatArray(sb.remaining()) { sb.get() / 32768f } }
                            val frames = x.size / ch
                            a.feed(x, frames); samples += frames
                            if (dur > 0) progress((samples / rate.toDouble() / minOf(dur, MAX_SECONDS)).coerceIn(0.0, 1.0))
                        }
                        codec.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            } finally { runCatching { codec.stop() }; codec.release() }
            val a = an ?: throw IllegalArgumentException("the file decoded to nothing")
            return a.result("r" + System.currentTimeMillis().toString(36), name, "file " + name + " (" + mime + ", " + rate + " Hz, " + ch + " ch)")
        } finally { ex.release() }
    }
}
