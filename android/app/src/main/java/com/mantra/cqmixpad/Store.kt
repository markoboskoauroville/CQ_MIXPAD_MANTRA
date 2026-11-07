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
 * Every concert is its own record (Marko, 7.11.2026: "each concert needs to have its preset in the app ... put the
 * date ... the channels are changing from concert to concert, some things are same, some are changing"):
 * concerts/<yyyy-mm-dd> <venue>.json = venue, date, labels (channel → person's name + the channel's role), the mix
 * (CQ parameter → value, as the desk last held it) and the desk as text. A new concert starts as a copy of the one
 * chosen as its template, then changes. Lessons (notes) are shared by all concerts, each stamped with its concert.
 * The first concert is Šubić, 17.10.2026 (the recorded one, its balance from SANTOSHI_CONCERT_MIX); the second the
 * Indian Embassy, 7.11.2026.
 */
class Store(ctx: Context) {
    private val dir = ctx.filesDir
    private val notesFile = File(dir, "santoshi.json")
    private val concertsDir = File(dir, "concerts").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)
    val keyFile = File(dir, "anthropic.key")

    data class Label(val name: String, val instrument: String)
    data class Concert(val id: String, val venue: String, val date: String)

    val concerts = MutableStateFlow<List<Concert>>(emptyList())
    val current = MutableStateFlow<Concert?>(null)
    val labels = MutableStateFlow<Map<String, Label>>(emptyMap())
    val notes = MutableStateFlow<List<String>>(emptyList())

    init { seed(); loadNotes(); refresh(); open(prefs.getString("current", null) ?: concerts.value.lastOrNull()?.id) }

    // ---------- concerts ----------

    private fun file(id: String) = File(concertsDir, "$id.json")
    private fun read(id: String): JSONObject = try { JSONObject(file(id).readText()) } catch (_: Exception) { JSONObject() }

    private fun refresh() {
        concerts.value = (concertsDir.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()).mapNotNull {
            try { val j = JSONObject(it.readText()); Concert(it.name.removeSuffix(".json"), j.optString("venue"), j.optString("date")) } catch (_: Exception) { null }
        }.sortedBy { it.id }
    }

    fun open(id: String?) {
        val c = concerts.value.firstOrNull { it.id == id } ?: concerts.value.lastOrNull() ?: return
        current.value = c; prefs.edit().putString("current", c.id).apply()
        val l = read(c.id).optJSONObject("labels") ?: JSONObject()
        labels.value = l.keys().asSequence().associateWith { k -> val o = l.getJSONObject(k); Label(o.optString("name"), o.optString("role")) }
    }

    /** dd.mm.yyyy + venue; a copy of [template] (labels and mix), then its own. */
    fun newConcert(venue: String, date: String, template: String?): String {
        val iso = toIso(date)
        val id = "$iso ${venue.trim()}"
        val t = template?.let { read(it) } ?: JSONObject()
        val j = JSONObject().put("venue", venue.trim()).put("date", date.trim()).put("template", template ?: "")
            .put("labels", t.optJSONObject("labels") ?: JSONObject()).put("mix", t.optJSONObject("mix") ?: JSONObject())
            .put("desk", t.optString("desk", ""))
        file(id).writeText(j.toString(2)); refresh(); open(id)
        return id
    }

    private fun toIso(d: String): String = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(SimpleDateFormat("d.M.yyyy", Locale.US).parse(d.trim())!!)
    } catch (_: Exception) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    private fun update(f: (JSONObject) -> Unit) {
        val c = current.value ?: return
        val j = read(c.id); f(j); file(c.id).writeText(j.toString(2))
    }

    fun setLabel(ch: String, name: String, role: String) {
        val old = labels.value[ch]
        val l = Label(name.ifBlank { old?.name ?: "" }, role.ifBlank { old?.instrument ?: "" })
        labels.value = labels.value + (ch to l)
        update { j -> (j.optJSONObject("labels") ?: JSONObject().also { j.put("labels", it) }).put(ch, JSONObject().put("name", l.name).put("role", l.instrument)) }
    }

    /** SAVE: the desk as it is now becomes this concert's mix. */
    fun saveMix(values: Map<Int, Int>, desk: String) {
        val m = JSONObject(); values.forEach { (k, v) -> m.put(k.toString(), v) }
        update { it.put("mix", m).put("desk", desk).put("saved", SimpleDateFormat("d.M.yyyy HH:mm", Locale.US).format(Date())) }
    }

    /** When the concert starts, "HH:mm" on its date (Indian Embassy 7.11.2026: 17:30, Marko). */
    fun startTime(): String? = current.value?.let { read(it.id).optString("start", "").ifBlank { null } }
    fun dateIso(): String? = current.value?.id?.substringBefore(" ")

        /** The CQ scene this concert starts from (Scene 1 "Santoshi Mix" unless the concert says otherwise). */
    fun baseScene(): Int = current.value?.let { read(it.id).optInt("scene", 1) } ?: 1

    /** This concert's stored mix: parameter → value. */
    fun mix(id: String? = current.value?.id): Map<Int, Int> {
        val m = (id?.let { read(it) } ?: JSONObject()).optJSONObject("mix") ?: return emptyMap()
        return m.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to m.getInt(k) } }.toMap()
    }

    /** The other concerts, newest first, as text for the brain to learn from. */
    fun history(n: Int = 3): String = concerts.value.filter { it.id != current.value?.id }.sortedByDescending { it.id }.take(n)
        .joinToString("\n") { c ->
            val j = read(c.id); val l = j.optJSONObject("labels") ?: JSONObject()
            "${c.venue}, ${c.date}: " + l.keys().asSequence().joinToString("; ") { k -> val o = l.getJSONObject(k); "$k ${o.optString("name")} (${o.optString("role")})" } +
                (j.optString("desk").takeIf { it.isNotBlank() }?.let { "\n  mix: " + it.replace("\n", " | ").take(1500) } ?: "")
        }

    // ---------- lessons ----------

    private fun loadNotes() {
        try { val n = JSONObject(notesFile.readText()).optJSONArray("notes") ?: JSONArray(); notes.value = (0 until n.length()).map { n.getString(it) } } catch (_: Exception) { }
    }

    fun remember(note: String) {
        val c = current.value
        notes.value = (notes.value + "${c?.venue ?: "?"} ${c?.date ?: ""}: $note").takeLast(200)
        notesFile.writeText(JSONObject().put("notes", JSONArray(notes.value)).toString(2))
    }

    fun apiKey(): String? = keyFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.startsWith("sk-ant-") }
    fun setApiKey(k: String) { keyFile.writeText(k.trim()) }

    // ---------- the first two concerts ----------

    private fun seed() {
        if (prefs.getBoolean("seeded", false)) {
            // v15: the Embassy's start time, for phones seeded before it existed
            val e = file("2026-11-07 Indian Embassy")
            if (e.exists()) { val j = JSONObject(e.readText()); if (!j.has("start")) { j.put("start", "17:35").put("programme", "16:30 guests arrive; 17:00 welcome; 17:05 dance (Indian diaspora); 17:10 song by Ms Avneet Kaur Royar; 17:15 dance (Ana Jug troupe); 17:35-18:00 Santoshi Kirtan band; 18:00 refreshments; 19:00 close"); e.writeText(j.toString(2)) } }
            return
        }
        val labels = JSONObject()
        fun lab(ch: String, name: String, role: String) { labels.put(ch, JSONObject().put("name", name).put("role", role)) }
        lab("Ip1", "Svemir", "lead vocal, low male"); lab("Ip2", "", "bass DI"); lab("Ip3", "", "mridangam high head")
        lab("Ip4", "", "mridangam low head"); lab("Ip5", "", "djembe"); lab("Ip6", "Igor", "lead vocal, low male")
        lab("Ip7", "Igor", "acoustic guitar DI"); lab("Ip8", "Tajana", "lead vocal, high female")
        lab("Ip9", "Snoopy", "acoustic guitar DI"); lab("Ip10", "Igor", "acoustic guitar mic (second half)")
        // the Šubić balance: the mean of the eight songs' faders of the October virtual soundcheck, the tutorial's pans,
        // the voices into FX1 at -13.3 dB (SANTOSHI_CONCERT_MIX balance_plan.json, README "The CQ-12T")
        val fader = mapOf("Ip1" to 0.4, "Ip2" to -5.7, "Ip3" to 1.6, "Ip4" to -3.0, "Ip5" to 2.0, "Ip6" to -1.1,
            "Ip7" to -1.2, "Ip8" to 3.3, "Ip9" to 0.1, "Ip10" to -1.6)
        val pan = mapOf("Ip1" to -15.0, "Ip6" to 15.0, "Ip5" to 40.0)
        val mix = JSONObject()
        val desk = StringBuilder()
        for ((ch, db) in fader) {
            val s = Cq.src(ch)!!
            mix.put(Cq.sendParam(s, "MAIN").toString(), Cq.dbToValue(db))
            mix.put(Cq.panMainParam(s).toString(), Cq.panToValue(pan[ch] ?: 0.0))
            mix.put(Cq.muteParam(s).toString(), 0)
            desk.append("$ch: MAIN ${Cq.dbText(db)}, pan ${(pan[ch] ?: 0.0).toInt()}")
            if (ch in listOf("Ip1", "Ip6", "Ip8")) { mix.put(Cq.sendParam(s, "FX1").toString(), Cq.dbToValue(-13.3)); desk.append(", FX1 -13.3") }
            desk.append('\n')
        }
        desk.append("Ip10 (Igor's guitar mic) only in the second half, after his guitar broke; muted in the first.")
        val subic = "2026-10-17 Šubić"
        file(subic).writeText(JSONObject().put("venue", "Šubić").put("date", "17.10.2026").put("labels", labels).put("mix", mix)
            .put("desk", desk.toString()).put("template", "").toString(2))
        val embassy = "2026-11-07 Indian Embassy"
        file(embassy).writeText(JSONObject().put("venue", "Indian Embassy").put("date", "7.11.2026").put("start", "17:35").put("programme", "16:30 guests arrive; 17:00 welcome; 17:05 dance (Indian diaspora); 17:10 song by Ms Avneet Kaur Royar; 17:15 dance (Ana Jug troupe); 17:35-18:00 Santoshi Kirtan band; 18:00 refreshments; 19:00 close").put("labels", labels).put("mix", mix)
            .put("desk", desk.toString()).put("template", subic).toString(2))
        prefs.edit().putBoolean("seeded", true).putString("current", embassy).apply()
    }
}
