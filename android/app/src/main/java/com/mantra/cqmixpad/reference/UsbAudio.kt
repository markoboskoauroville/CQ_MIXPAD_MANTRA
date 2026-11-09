package com.mantra.cqmixpad.reference

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

/**
 * The CQ-12T's USB-B audio on the Pixel (Marko, 9.11.2026: "on the phone you will have an audio device from the
 * Allen & Heath itself ... our mixer app needs a RECORDER"). Android's USB audio class driver shows the desk as an input
 * device; the desk's CONFIG > USB/SD > Stream Mode decides what comes: Stereo = 2 channels (the Main), Multitrack = 16
 * (Main L/R on 15/16 by the desk's Channel Assignment). This class reads it without touching the desk:
 *  - LISTEN: the stream feeds the live analysis (the profile vs the reference, MATCH), nothing written;
 *  - REC: also written to Music/CQ MixPad/<place date HHmm>.wav (48 kHz, 24-bit; Main L/R, or all 16) or .m4a (AAC 256k).
 * The take's whole-file profile is kept as [lastTake] when it stops.
 */
class UsbAudio(private val ctx: Context, private val log: (String) -> Unit) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    val device = MutableStateFlow<String?>(null)        // "CQ12T  16 ch" or null
    val maxChannels = MutableStateFlow(0)
    val running = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val elapsedMs = MutableStateFlow(0L)
    val levels = MutableStateFlow(floatArrayOf(-120f, -120f))
    val clip = MutableStateFlow(false)
    val lastFile = MutableStateFlow("")
    val lastTake = MutableStateFlow<Profile?>(null)
    val status = MutableStateFlow("")
    @Volatile var live: Analyzer? = null; private set
    /** Pauses the app's own microphone capture (the phone RTA / feedback guard): with the CQ plugged in, Android routes
     *  it to the same USB input, and the older capture keeps the samples: ours read about 1 s of audio, then zeros
     *  (measured 9.11.2026, RecordActivityMonitor: an UNPROCESSED 1-ch session of ours on the USB device). */
    @Volatile var micHold: (Boolean) -> Unit = {}
    @Volatile private var stopFlag = false
    private var thread: Thread? = null

    init {
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>) = scan()
            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>) = scan()
        }, null)
        scan()
    }

    fun find(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }

    private fun scan() {
        val d = find()
        // 9.11.2026: in Multitrack the Pixel lists NO channel counts for the CQ (its HAL finds no profile for 16) yet opens
        // 16 by index mask; in Stereo it lists 2. Empty lists = the 16-channel stream.
        val mc = d?.channelCounts?.maxOrNull() ?: d?.channelIndexMasks?.maxOfOrNull { Integer.bitCount(it) } ?: (if (d != null) 16 else 0)
        maxChannels.value = if (d == null) 0 else maxOf(mc, 2)
        val was = device.value
        device.value = d?.let { "${it.productName}  ${maxOf(mc, 2)} ch" }
        if (was != device.value) log("  USB audio: " + (device.value ?: "none") + (d?.let { " rates " + it.sampleRates.joinToString("/") + " ch " + it.channelCounts.joinToString("/") } ?: ""))
        if (d == null && running.value) stop()
    }

    /** [record]: write a file; [all]: every channel the stream has (multitrack) instead of Main L/R; [aac]: m4a instead of WAV. */
    @SuppressLint("MissingPermission")
    fun start(record: Boolean, baseName: String, all: Boolean, aac: Boolean) {
        if (running.value) { if (record && !recording.value) { stop(); } else return }
        val dev = find() ?: run { status.value = "no USB audio device: the CQ's USB-B to the Pixel"; return }
        stopFlag = false
        thread = Thread({ loop(dev, record, baseName, all, aac) }, "cq-usb-audio").apply { start() }
    }

    fun stop() { stopFlag = true; thread?.join(4000); thread = null }

    /** Stereo, or every channel the stream has, through the first capture source that carries samples ([openUsb]). */
    private fun open(dev: AudioDeviceInfo): Pair<AudioRecord, Int>? {
        val mc = maxChannels.value
        return openUsb(dev, if (mc > 2) listOf(mc, 2) else listOf(2), log)
    }

    companion object {
        /** Measured on the Pixel 7 with the CQ-12T, 9.11.2026: UNPROCESSED, MIC, VOICE_RECOGNITION and DEFAULT open the
         *  USB input and read exact zeros; VOICE_PERFORMANCE carries the desk's audio (16-bit and float), CAMCORDER only
         *  in 16-bit. So: those first, each checked for non-zero samples in its first 0.8 s; the first one is kept when
         *  the desk is silent (nothing to check with). Returns a STARTED record. */
        @SuppressLint("MissingPermission")
        /** Did the last [openUsb] find real samples (false = it kept a silent stream). */
        @Volatile var lastCarries = false
        fun openUsb(dev: AudioDeviceInfo, channelCounts: List<Int>, log: (String) -> Unit): Pair<AudioRecord, Int>? {
            lastCarries = false
            val tries = ArrayList<Triple<Int, Int, Int>>()     // source, channels, encoding
            for (ch in channelCounts) {
                tries += Triple(MediaRecorder.AudioSource.VOICE_PERFORMANCE, ch, AudioFormat.ENCODING_PCM_FLOAT)
                tries += Triple(MediaRecorder.AudioSource.VOICE_PERFORMANCE, ch, AudioFormat.ENCODING_PCM_16BIT)
                tries += Triple(MediaRecorder.AudioSource.CAMCORDER, ch, AudioFormat.ENCODING_PCM_16BIT)
            }
            var first: Pair<AudioRecord, Int>? = null
            for ((src, ch, enc) in tries) {
                val r = try {
                    val f = AudioFormat.Builder().setSampleRate(48000).setEncoding(enc)
                        .apply { if (ch > 2) setChannelIndexMask((1 shl ch) - 1) else setChannelMask(AudioFormat.CHANNEL_IN_STEREO) }.build()
                    val bytes = if (enc == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    AudioRecord.Builder().setAudioSource(src).setAudioFormat(f).setBufferSizeInBytes(48000 / 2 * ch * bytes).build()
                } catch (e: Exception) { null } ?: continue
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                r.setPreferredDevice(dev)
                try { r.startRecording() } catch (e: Exception) { r.release(); continue }
                if (r.routedDevice?.id != dev.id || r.channelCount != ch) { r.stop(); r.release(); continue }
                // the check: any non-zero sample in 0.8 s
                val n = r.channelCount; var nonzero = false
                val fb = FloatArray(4800 * n); val sb = ShortArray(4800 * n)
                repeat(8) {
                    val got = if (enc == AudioFormat.ENCODING_PCM_FLOAT) r.read(fb, 0, fb.size, AudioRecord.READ_BLOCKING) else r.read(sb, 0, sb.size)
                    if (!nonzero) for (i in 0 until maxOf(got, 0)) if ((if (enc == AudioFormat.ENCODING_PCM_FLOAT) fb[i] != 0f else sb[i].toInt() != 0)) { nonzero = true; break }
                }
                val sn = if (src == MediaRecorder.AudioSource.VOICE_PERFORMANCE) "VOICE_PERFORMANCE" else "CAMCORDER"
                if (nonzero) { first?.first?.let { it.stop(); it.release() }; lastCarries = true; log("  USB audio: $sn $ch ch ${if (enc == AudioFormat.ENCODING_PCM_FLOAT) "float" else "16-bit"} carries samples"); return r to enc }
                if (first == null) first = r to enc else { r.stop(); r.release() }
            }
            first?.let { log("  USB audio: no source carried samples (silent desk?): keeping the first") }
            return first
        }
    }

    private fun loop(dev: AudioDeviceInfo, record: Boolean, baseName: String, all: Boolean, aac: Boolean) {
        micHold(true); Thread.sleep(400)
        val (rec, enc) = open(dev) ?: run { status.value = "Android refused the USB input"; micHold(false); return }
        val ch = rec.channelCount; val rate = rec.sampleRate
        val main = if (ch >= 16) intArrayOf(14, 15) else intArrayOf(0, minOf(1, ch - 1))
        val an = Analyzer(rate, ch, main, rollSec = 20.0); live = an
        val take = if (record) Analyzer(rate, ch, main) else null
        val outCh = if (all) ch else 2
        var sink: Sink? = null
        try {
            val routed = rec.routedDevice
            running.value = true
            if (record) {
                sink = if (aac && outCh <= 2) AacSink(ctx, baseName, rate) else WavSink(ctx, baseName, rate, outCh)
                lastFile.value = sink.name; recording.value = true
            }
            status.value = "USB in: $ch ch at $rate Hz (${if (enc == AudioFormat.ENCODING_PCM_FLOAT) "float" else "16-bit"})" +
                (if (routed?.id != dev.id) " · ROUTED TO ${routed?.productName}" else "") + (if (record) " · REC ${sink!!.name}" else "")
            log("  " + status.value)
            val frames = rate / 10
            val fbuf = FloatArray(frames * ch); val sbuf = ShortArray(frames * ch)
            val out = FloatArray(frames * outCh)
            val t0 = System.currentTimeMillis()
            var peakHold = 0
            while (!stopFlag) {
                val got = if (enc == AudioFormat.ENCODING_PCM_FLOAT) rec.read(fbuf, 0, fbuf.size, AudioRecord.READ_BLOCKING)
                          else rec.read(sbuf, 0, sbuf.size).also { n -> for (i in 0 until maxOf(n, 0)) fbuf[i] = sbuf[i] / 32768f }
                if (got <= 0) { status.value = "USB read error $got"; break }
                val nf = got / ch
                an.feed(fbuf, nf); take?.feed(fbuf, nf)
                var pl = 0f; var pr = 0f
                for (f in 0 until nf) { val l = abs(fbuf[f * ch + main[0]]); val r = abs(fbuf[f * ch + main[1]]); if (l > pl) pl = l; if (r > pr) pr = r }
                levels.value = floatArrayOf(if (pl > 0) 20 * log10(pl) else -120f, if (pr > 0) 20 * log10(pr) else -120f)
                if (pl >= 0.999f || pr >= 0.999f) { clip.value = true; peakHold = 20 } else if (peakHold > 0 && --peakHold == 0) clip.value = false
                sink?.let { s ->
                    if (all) s.write(fbuf, nf) else { for (f in 0 until nf) { out[2 * f] = fbuf[f * ch + main[0]]; out[2 * f + 1] = fbuf[f * ch + main[1]] }; s.write(out, nf) }
                }
                elapsedMs.value = System.currentTimeMillis() - t0
            }
        } catch (e: Exception) { status.value = "USB audio stopped: ${e.javaClass.simpleName} ${e.message}"; log("  " + status.value) }
        finally {
            runCatching { rec.stop() }; rec.release()
            sink?.let { s -> runCatching { s.close() }; log("  USB REC saved: Music/CQ MixPad/${s.name} (${elapsedMs.value / 1000} s)") }
            take?.let { lastTake.value = it.result("take" + System.currentTimeMillis().toString(36), sink?.name?.substringBeforeLast('.') ?: "take", "CQ USB take ${sink?.name}") }
            running.value = false; recording.value = false; live = null; micHold(false)
            levels.value = floatArrayOf(-120f, -120f)
        }
    }

    interface Sink { val name: String; fun write(x: FloatArray, frames: Int); fun close() }

    private fun mediaFile(ctx: Context, name: String, mime: String): Pair<android.net.Uri, ParcelFileDescriptor> {
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/CQ MixPad"); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
            ?: throw IllegalStateException("MediaStore refused $name")
        return uri to ctx.contentResolver.openFileDescriptor(uri, "rw")!!
    }
    private fun publish(ctx: Context, uri: android.net.Uri) {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }

    /** 24-bit PCM WAV, the header's sizes written at close. */
    inner class WavSink(private val ctx: Context, base: String, private val rate: Int, private val ch: Int) : Sink {
        override val name = "$base.wav"
        private val uri: android.net.Uri; private val pfd: ParcelFileDescriptor; private val os: FileOutputStream
        private var data = 0L
        private var bb = ByteBuffer.allocate(0)
        init { val (u, p) = mediaFile(ctx, name, "audio/wav"); uri = u; pfd = p; os = FileOutputStream(p.fileDescriptor); os.write(header(0)) }
        private fun header(dataBytes: Long): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt()); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(ch.toShort()); h.putInt(rate); h.putInt(rate * ch * 3)
            h.putShort((ch * 3).toShort()); h.putShort(24); h.put("data".toByteArray()); h.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
            return h.array()
        }
        override fun write(x: FloatArray, frames: Int) {
            val n = frames * ch
            if (bb.capacity() < n * 3) bb = ByteBuffer.allocate(n * 3)
            bb.clear()
            for (i in 0 until n) { val v = (x[i].coerceIn(-1f, 1f) * 8388607f).toInt(); bb.put(v.toByte()); bb.put((v shr 8).toByte()); bb.put((v shr 16).toByte()) }
            os.write(bb.array(), 0, n * 3); data += n * 3
        }
        override fun close() {
            os.flush(); os.channel.position(0); os.write(header(data)); os.flush(); os.fd.sync(); os.close(); pfd.close(); publish(ctx, uri)
        }
    }

    /** AAC-LC 256 kbit/s stereo in .m4a: about 115 MB an hour instead of 1 GB. */
    inner class AacSink(private val ctx: Context, base: String, private val rate: Int) : Sink {
        override val name = "$base.m4a"
        private val uri: android.net.Uri; private val pfd: ParcelFileDescriptor
        private val mux: MediaMuxer; private val enc: MediaCodec
        private var track = -1; private var started = false; private var pts = 0L
        private val info = MediaCodec.BufferInfo()
        init {
            val (u, p) = mediaFile(ctx, name, "audio/mp4"); uri = u; pfd = p
            mux = MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        }
        override fun write(x: FloatArray, frames: Int) {
            var f0 = 0
            while (f0 < frames) {
                val ib = enc.dequeueInputBuffer(10_000)
                if (ib >= 0) {
                    val b = enc.getInputBuffer(ib)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val k = minOf(frames - f0, b.capacity() / 4)
                    for (i in 0 until k * 2) b.putShort((x[f0 * 2 + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    enc.queueInputBuffer(ib, 0, k * 4, pts * 1_000_000 / rate, 0); pts += k; f0 += k
                }
                drain(false)
            }
        }
        private fun drain(end: Boolean) {
            var waits = 0
            while (true) {
                val ob = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.outputFormat); mux.start(); started = true }
                else if (ob >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && started) mux.writeSampleData(track, enc.getOutputBuffer(ob)!!, info)
                    enc.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                } else if (!end || ++waits > 100) return
            }
        }
        override fun close() {
            val ib = enc.dequeueInputBuffer(100_000); if (ib >= 0) enc.queueInputBuffer(ib, 0, 0, pts * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true); enc.stop(); enc.release(); if (started) mux.stop(); mux.release(); pfd.close(); publish(ctx, uri)
        }
    }
}
