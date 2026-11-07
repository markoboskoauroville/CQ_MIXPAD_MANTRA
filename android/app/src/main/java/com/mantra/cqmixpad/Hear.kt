package com.mantra.cqmixpad

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Voice commands through AssemblyAI (Marko, 7.11.2026: "for voice recognition use AssemblyAI ... look at TTT my
 * keyboard app"). The Sync path, as TTT_MINI's OpenAiCompatibleClient.transcribeAssemblyAiSync does it:
 * POST https://sync.assemblyai.com/transcribe, multipart `audio` (WAV) + optional `config`, the raw key in
 * Authorization (no Bearer), the model in the X-AAI-Model header (universal-3-5-pro); the text comes back in the
 * same response. The config's `prompt` describes the audio (the band's names, the mixing words), which is what
 * makes "djembe" come back as djembe. His keys are in files/assemblyai.keys (32 hex each); a refused one passes
 * to the next.
 */
class Hear(private val ctx: Context) {
    private val keysFile = File(ctx.filesDir, "assemblyai.keys")
    private val prefs = ctx.getSharedPreferences("hear", Context.MODE_PRIVATE)
    @Volatile var stopNow = false

    fun hasKeys() = keys().isNotEmpty()
    private fun keys(): List<String> = try { keysFile.readLines().map { it.trim() }.filter { it.matches(Regex("[0-9a-f]{32}")) } } catch (_: Exception) { emptyList() }

    /** Records until 1.2 s of quiet after speech, 12 s, or [stopNow]. Null when nothing was said. */
    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Double) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        stopNow = false
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(1600)   // 100 ms
        var spoke = false; var quiet = 0; var chunks = 0
        var floor = -60.0
        try {
            rec.startRecording()
            while (isActive && !stopNow && chunks < 120) {
                val n = rec.read(buf, 0, buf.size); if (n <= 0) break
                chunks++
                var ms = 0.0; for (i in 0 until n) { val x = buf[i] / 32768.0; ms += x * x }
                val db = 10 * log10(ms / n + 1e-12)
                onLevel(db)
                if (chunks <= 3) floor = maxOf(floor, db)            // the room in the first 300 ms
                val loud = db > maxOf(floor + 10, -45.0)
                if (loud) { spoke = true; quiet = 0 } else if (spoke) quiet++
                for (i in 0 until n) { pcm.write(buf[i].toInt() and 0xFF); pcm.write((buf[i].toInt() shr 8) and 0xFF) }
                if (spoke && quiet >= 12) break
            }
        } finally { rec.stop(); rec.release() }
        if (!spoke) null else wav(pcm.toByteArray(), rate)
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val o = ByteArrayOutputStream()
        fun i32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
        o.write("RIFF".toByteArray()); i32(36 + pcm.size); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(pcm.size); o.write(pcm)
        return o.toByteArray()
    }

    /** The text, or null when every key failed (the caller falls back to Android's recogniser). */
    suspend fun transcribe(wav: ByteArray, vocabulary: List<String>): String? = withContext(Dispatchers.IO) {
        val ks = keys(); if (ks.isEmpty()) return@withContext null
        val prompt = "A sound engineer gives short spoken commands to a mixing desk app during a concert of the " +
            "Santoshi kirtan band. Words that occur: " + vocabulary.joinToString(", ") + ", decibels, dB."
        val config = JSONObject().put("prompt", prompt).toString()
        val start = prefs.getInt("key", 0).coerceIn(0, ks.size - 1)
        for (i in ks.indices) {
            val idx = (start + i) % ks.size
            try {
                val boundary = "----cqmixpad" + System.nanoTime()
                val c = URL("https://sync.assemblyai.com/transcribe").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 30000
                c.setRequestProperty("Authorization", ks[idx])                 // raw key, no Bearer
                c.setRequestProperty("X-AAI-Model", "universal-3-5-pro")
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.outputStream.use { out ->
                    fun part(head: String, body: ByteArray) {
                        out.write("--$boundary\r\n$head\r\n\r\n".toByteArray()); out.write(body); out.write("\r\n".toByteArray())
                    }
                    part("Content-Disposition: form-data; name=\"audio\"; filename=\"command.wav\"\r\nContent-Type: audio/wav", wav)
                    part("Content-Disposition: form-data; name=\"config\"\r\nContent-Type: application/json", config.toByteArray())
                    out.write("--$boundary--\r\n".toByteArray())
                }
                val code = c.responseCode
                if (code in listOf(401, 402, 403, 429)) { c.disconnect(); continue }
                if (code != 200) { c.disconnect(); return@withContext null }
                val text = JSONObject(c.inputStream.bufferedReader().readText()).optString("text").trim()
                c.disconnect()
                prefs.edit().putInt("key", idx).apply()
                return@withContext text
            } catch (_: Exception) { continue }
        }
        null
    }
}
