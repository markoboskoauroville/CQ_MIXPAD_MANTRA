package com.mantra.cqmixpad

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Voice commands: Android's own recogniser, one phrase per press of SPEAK. */
class Voice(private val ctx: Context, private val onPartial: (String) -> Unit, private val onFinal: (String?) -> Unit) {
    private var rec: SpeechRecognizer? = null
    /** Phrases to bias the recogniser toward (Android 13+): the band's names and the mixing words. */
    var hints: List<String> = emptyList()

    fun listen(language: String = "en-US") {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { onPartial("listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { onFinal(null) }
            override fun onResults(b: Bundle?) {
                onFinal(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (hints.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        })
    }

    fun stop() { rec?.destroy(); rec = null }
}

/**
 * The real-time analyser (RTA): the room through the phone's microphone (unprocessed source, no AGC), ten octave bands
 * 31 Hz .. 16 kHz, each band's share of the whole energy in dB: the same measure as SANTOSHI_CONCERT_MIX compare_ref.py,
 * so it compares straight with the Krishna Das reference profile [KD] (mean of the two references in
 * ~/Music/SantoshiKoncert/reference, measured 7.11.2026).
 */
class Rta {
    companion object {
        val BANDS = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val NAMES = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        /** Krishna Das references, band share of the whole (dB). */
        val KD = doubleArrayOf(-43.4, -11.7, -6.5, -5.4, -7.6, -8.7, -14.0, -18.7, -25.3, -37.6)
    }

    /** Smoothed live profile (about 1 s), and the long average since the last reset (for MATCH). */
    val live = MutableStateFlow(DoubleArray(10) { -60.0 })
    val level = MutableStateFlow(-90.0)
    private val sum = DoubleArray(10); private var frames = 0
    @Volatile private var running = false

    fun average(): DoubleArray = if (frames == 0) live.value.copyOf() else share(sum.map { it / frames }.toDoubleArray())
    fun reset() { sum.fill(0.0); frames = 0 }
    fun seconds(): Int = frames * 4096 / 48000

    private fun share(power: DoubleArray): DoubleArray {
        val tot = power.sum().coerceAtLeast(1e-30)
        return DoubleArray(power.size) { 10 * log10(power[it] / tot + 1e-12) }
    }

    fun text(): String {
        val a = average()
        return buildString {
            append("RTA through the phone microphone, ${seconds()} s averaged, room level ${level.value.toInt()} dBFS. ")
            append("Band share of the whole (dB) 31..16k: ").append(a.joinToString(", ") { String.format(java.util.Locale.US, "%.1f", it) })
            append(". Krishna Das reference: ").append(KD.joinToString(", ") { String.format(java.util.Locale.US, "%.1f", it) })
            append(". Mix minus reference: ").append(a.indices.joinToString(", ") { NAMES[it] + " " + String.format(java.util.Locale.US, "%+.1f", a[it] - KD[it]) })
            append(". (Phone mic: below 63 Hz it reads low; trust trends from 63 Hz up.)")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun run() = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        val rate = 48000; val n = 8192
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var rec = AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, n * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, n * 4))
        }
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val bin = IntArray(n / 2) { k -> val f = k.toDouble() * rate / n; BANDS.indexOfFirst { f >= it / sqrt(2.0) && f < it * sqrt(2.0) } }
        val ring = ShortArray(n); val hop = ShortArray(n / 2)
        val smooth = DoubleArray(10)
        try {
            rec.startRecording()
            while (running && isActive) {
                var got = 0
                while (got < hop.size) { val r = rec.read(hop, got, hop.size - got); if (r <= 0) break; got += r }
                System.arraycopy(ring, n / 2, ring, 0, n / 2); System.arraycopy(hop, 0, ring, n / 2, n / 2)
                val re = DoubleArray(n) { ring[it] / 32768.0 * window[it] }; val im = DoubleArray(n)
                var ms = 0.0; for (s in hop) { val x = s / 32768.0; ms += x * x }
                level.value = 10 * log10(ms / hop.size + 1e-12)
                fft(re, im)
                val p = DoubleArray(10)
                for (k in 1 until n / 2) { val b = bin[k]; if (b >= 0) p[b] += re[k] * re[k] + im[k] * im[k] }
                for (i in 0 until 10) { smooth[i] = 0.8 * smooth[i] + 0.2 * p[i]; sum[i] += p[i] }
                frames++
                live.value = share(smooth)
            }
        } finally { rec.stop(); rec.release(); running = false }
    }

    fun stop() { running = false }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val c = cos(ang * k); val s = sin(ang * k)
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * c - im[i + k + len / 2] * s
                    val vi = re[i + k + len / 2] * s + im[i + k + len / 2] * c
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                }
            }
            len = len shl 1
        }
    }
}
