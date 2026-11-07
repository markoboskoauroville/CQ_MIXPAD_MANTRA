package com.mantra.cqmixpad

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app learns about the Santoshi band, concert after concert, kept in the app's own files:
 * santoshi.json  = labels (channel → name, instrument), notes (what worked, what to watch), and
 * concerts/<date>-<venue>.json = every SAVE CONCERT: the whole mix as the CQ reported it.
 * The mixer itself cannot take names over its MIDI protocol, so the names live here and on the screen.
 */
class Store(ctx: Context) {
    private val dir = ctx.filesDir
    private val file = File(dir, "santoshi.json")
    private val concerts = File(dir, "concerts").apply { mkdirs() }
    val keyFile = File(dir, "anthropic.key")

    data class Label(val name: String, val instrument: String)

    val labels = MutableStateFlow<Map<String, Label>>(emptyMap())
    val notes = MutableStateFlow<List<String>>(emptyList())
    val venue = MutableStateFlow("Embassy")

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val j = JSONObject(file.readText())
            val l = j.optJSONObject("labels") ?: JSONObject()
            labels.value = l.keys().asSequence().associateWith { k ->
                val o = l.getJSONObject(k); Label(o.optString("name"), o.optString("instrument"))
            }
            val n = j.optJSONArray("notes") ?: JSONArray()
            notes.value = (0 until n.length()).map { n.getString(it) }
            venue.value = j.optString("venue", venue.value)
        } catch (_: Exception) { }
    }

    private fun save() {
        val l = JSONObject()
        labels.value.forEach { (k, v) -> l.put(k, JSONObject().put("name", v.name).put("instrument", v.instrument)) }
        file.writeText(JSONObject().put("labels", l).put("notes", JSONArray(notes.value)).put("venue", venue.value).toString(2))
    }

    fun setLabel(ch: String, name: String, instrument: String) {
        val old = labels.value[ch]
        labels.value = labels.value + (ch to Label(name.ifBlank { old?.name ?: "" }, instrument.ifBlank { old?.instrument ?: "" }))
        save()
    }

    fun remember(note: String) {
        val stamp = SimpleDateFormat("d.M.yyyy", Locale.US).format(Date())
        notes.value = (notes.value + "$stamp, ${venue.value}: $note").takeLast(200)
        save()
    }

    fun setVenue(v: String) { venue.value = v; save() }

    fun saveConcert(mix: JSONObject): String {
        val day = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val name = "$day-${venue.value.replace(Regex("[^A-Za-z0-9]+"), "_")}.json"
        File(concerts, name).writeText(JSONObject().put("venue", venue.value).put("saved", day).put("mix", mix)
            .put("labels", JSONObject(labels.value.mapValues { "${it.value.name} (${it.value.instrument})" })).toString(2))
        return name
    }

    /** The last few concerts, newest first, for the brain to learn from. */
    fun recentConcerts(n: Int = 3): List<JSONObject> =
        (concerts.listFiles()?.sortedByDescending { it.name } ?: emptyList()).take(n).mapNotNull {
            try { JSONObject(it.readText()) } catch (_: Exception) { null }
        }

    fun apiKey(): String? = keyFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.startsWith("sk-ant-") }
    fun setApiKey(k: String) { keyFile.writeText(k.trim()) }
}
