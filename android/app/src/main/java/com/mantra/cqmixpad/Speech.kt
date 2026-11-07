package com.mantra.cqmixpad

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The app speaks through Speechify (Marko, 7.11.2026: "when application speaks to me, it needs to speak through
 * Speechify"): Beatrice (beatrice_32, simba-3.2), the same voice as CQ MixPad Tutorial. The whole sentence is rendered
 * to a file first and then played, never streamed (no sound drops). His keys are in the app's files
 * (files/speechify.keys, one per line, put there from the Mac over adb); a spent or refused key passes to the next.
 * Only when every key fails does Android's own voice speak, and the log says so.
 */
class Speech(private val ctx: Context, private val scope: CoroutineScope, private val log: (String) -> Unit,
             private val speaking: (Boolean) -> Unit = {}) {
    private val keysFile = File(ctx.filesDir, "speechify.keys")
    private val prefs = ctx.getSharedPreferences("speech", Context.MODE_PRIVATE)
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = TextToSpeech(ctx) { tts?.language = Locale.US }
    private var n = 0

    private fun keys(): List<String> = try {
        keysFile.readLines().map { it.trim() }.filter { it.startsWith("sk_") }
    } catch (_: Exception) { emptyList() }

    fun hasKeys() = keys().isNotEmpty()

    fun say(text: String) {
        stop()
        val my = ++n
        scope.launch {
            val f = withContext(Dispatchers.IO) { render(text) }
            if (my != n) return@launch
            if (f != null) {
                speaking(true)
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { it.release(); if (player === it) player = null; scope.launch { kotlinx.coroutines.delay(300); speaking(false) } }
                    prepare(); start()
                }
            } else {
                log("  ! Speechify did not answer: Android's voice instead")
                speaking(true); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say")
                scope.launch { kotlinx.coroutines.delay(400L + text.length * 70L); speaking(false) }
            }
        }
    }

    fun stop() { n++; player?.let { try { it.stop(); it.release() } catch (_: Exception) {} }; player = null; tts?.stop(); speaking(false) }
    fun shutdown() { stop(); tts?.shutdown(); tts = null }

    private fun render(text: String): File? {
        val ks = keys(); if (ks.isEmpty()) return null
        val start = prefs.getInt("key", 0).coerceIn(0, ks.size - 1)
        for (i in ks.indices) {
            val idx = (start + i) % ks.size
            try {
                val c = URL("https://api.sws.speechify.com/v1/audio/speech").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 30000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer " + ks[idx])
                c.outputStream.use {
                    it.write(JSONObject().put("input", text).put("voice_id", "beatrice_32").put("model", "simba-3.2")
                        .put("audio_format", "mp3").toString().toByteArray())
                }
                val code = c.responseCode
                if (code in listOf(401, 402, 403, 429)) { c.disconnect(); continue }   // spent, refused, limited: next key
                if (code != 200) { c.disconnect(); return null }
                val j = JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
                val f = File(ctx.cacheDir, "say.mp3")
                f.writeBytes(Base64.decode(j.getString("audio_data"), Base64.DEFAULT))
                prefs.edit().putInt("key", idx).apply()
                return f
            } catch (_: Exception) { continue }
        }
        return null
    }
}
