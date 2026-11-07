package com.mantra.cqmixpad

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Sonnet 5.5 (Marko, 7.11.2026: "link this with API from cloud, using Sonnet, to intelligently mix and control").
 * One request per command: the mixer's state, what the app knows about Santoshi, the latest room analysis and Marko's
 * words go in; tool calls (the actions) and one short sentence come out. Voice commands are carried out at once;
 * ANALYZE suggestions wait for Marko's APPLY.
 */
class Brain(private val store: Store) {
    data class Action(val name: String, val input: JSONObject) {
        fun text(): String = when (name) {
            "set_level" -> "${input.optString("channel")} → ${input.optString("mix")} at ${input.optDouble("db")} dB"
            "change_level" -> "${input.optString("channel")} → ${input.optString("mix")} ${String.format(java.util.Locale.US, "%+.1f", input.optDouble("delta_db"))} dB"
            "set_mute" -> "${input.optString("channel")} ${if (input.optBoolean("muted")) "MUTE" else "unmute"}"
            "set_pan" -> "${input.optString("channel")} pan ${input.optDouble("percent").toInt()}%"
            "set_label" -> "${input.optString("channel")} = ${input.optString("name")} (${input.optString("role")})"
            "recall_scene" -> "recall scene ${input.optInt("number")}"
            "soft_key" -> "press soft key ${input.optInt("number")}"
            "remember" -> "remember: ${input.optString("note")}"
            "note_for_marko" -> "YOU: ${input.optString("text")}"
            else -> "$name $input"
        }

        /** For the ear: the person or role instead of the channel code, words instead of symbols. */
        fun spoken(labels: Map<String, Store.Label>): String {
            val ch = input.optString("channel")
            val l = labels[ch]
            val who = when {
                ch == "MASTER" -> "the ${input.optString("mix", "main").lowercase()} master"
                l != null && l.name.isNotBlank() -> l.name
                l != null && l.instrument.isNotBlank() -> "the " + l.instrument.substringBefore(",")
                else -> "channel " + ch.removePrefix("Ip")
            }
            val mix = input.optString("mix", "MAIN").let { if (it == "MAIN" || it.isBlank()) "" else " in " + it.lowercase().replace("out", "monitor ").replace("fx", "effect ") }
            fun db(d: Double) = (if (d < 0) "minus " else "") + String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(d)).removeSuffix(".0") + " dB"
            return when (name) {
                "set_level" -> "$who$mix at ${db(input.optDouble("db"))}"
                "change_level" -> input.optDouble("delta_db").let { "$who$mix ${if (it < 0) "down" else "up"} ${db(kotlin.math.abs(it))}" }
                "set_mute" -> "$who ${if (input.optBoolean("muted")) "muted" else "unmuted"}"
                "set_pan" -> input.optDouble("percent").toInt().let { "$who panned " + if (it == 0) "to the centre" else "${kotlin.math.abs(it)} percent ${if (it < 0) "left" else "right"}" }
                "set_label" -> "channel ${ch.removePrefix("Ip")} is ${input.optString("name").ifBlank { input.optString("role") }}"
                "recall_scene" -> "scene ${input.optInt("number")} recalled"
                "soft_key" -> "soft key ${input.optInt("number")} pressed"
                else -> ""
            }
        }
    }
    data class Answer(val say: String, val actions: List<Action>)

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private fun client(): AnthropicClient {
        val key = store.apiKey() ?: throw IllegalStateException("no Anthropic key on the phone yet")
        if (client == null || clientKey != key) {
            client = AnthropicOkHttpClient.builder().apiKey(key).build(); clientKey = key
        }
        return client!!
    }

    private fun tool(name: String, description: String, required: List<String>, vararg props: Pair<String, Any>): Tool {
        val p = Tool.InputSchema.Properties.builder()
        props.forEach { (k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)) }
        return Tool.builder().name(name).description(description)
            .inputSchema(Tool.InputSchema.builder().properties(p.build()).required(required).build()).build()
    }

    private fun tools(channels: List<String>): List<Tool> {
        val ch = mapOf("type" to "string", "enum" to channels + listOf("MASTER", "DCA1", "DCA2", "DCA3", "DCA4"),
            "description" to "a channel; MASTER = the master of the mix")
        val mix = mapOf("type" to "string", "enum" to Cq.MIXES, "description" to "MAIN = front of house; OUT1..OUT6 = monitor/aux outputs; FX1/FX2 = effect sends")
        return listOf(
            tool("set_level", "Set a channel's level into a mix (its fader on MAIN, its send on an OUT or FX), or a mix MASTER, in dB (-90 = off, max +10).",
                listOf("channel", "mix", "db"), "channel" to ch, "mix" to mix, "db" to mapOf("type" to "number")),
            tool("change_level", "Raise or lower a level by some dB.", listOf("channel", "mix", "delta_db"),
                "channel" to ch, "mix" to mix, "delta_db" to mapOf("type" to "number")),
            tool("set_mute", "Mute or unmute a channel, a mix MASTER (with mix), or a DCA.", listOf("channel", "muted"),
                "channel" to ch, "mix" to mix, "muted" to mapOf("type" to "boolean")),
            tool("set_pan", "Pan a channel in the MAIN mix, -100 (left) .. 0 (centre) .. 100 (right).", listOf("channel", "percent"),
                "channel" to ch, "percent" to mapOf("type" to "number")),
            tool("set_label", "Label a channel. name = only the person's name, as written on the channel (\"Diana\"); role = the channel's function, used for mixing (\"backing vocal\", \"lead vocal\", \"acoustic guitar DI\", \"mridangam high head\"). Remembered for every concert.",
                listOf("channel", "name", "role"), "channel" to ch, "name" to mapOf("type" to "string"), "role" to mapOf("type" to "string")),
            tool("recall_scene", "Recall a scene stored on the CQ (1..100). Scene 1 is 'Santoshi Mix'.", listOf("number"),
                "number" to mapOf("type" to "integer")),
            tool("soft_key", "Press one of the CQ's soft keys (1..3).", listOf("number"), "number" to mapOf("type" to "integer")),
            tool("remember", "Keep a lesson about mixing Santoshi for the next concerts (one short sentence).", listOf("note"),
                "note" to mapOf("type" to "string")),
            tool("note_for_marko", "Something only Marko can do in CQ MixPad (the MIDI link cannot load Library presets, change EQ/compressor/gain or write names on the desk): one short instruction, e.g. 'Ip1: Processing → Library → Acoustic Guitar'.",
                listOf("text"), "text" to mapOf("type" to "string")),
        )
    }

    private val system = """
You are the mixing engineer's assistant inside "CQ MixPad (Mantra)", an app on Marko's phone wired to his Allen & Heath CQ-12T.
Marko mixes the Santoshi band (kirtan, gentle music, about two hours, eight songs) and is learning to mix as he goes.
What you know about Santoshi (from his tutorial, 2026):
- Three lead voices: Tajana (high, already bright, Quick preset vocal high pitch, centre), Svemir and Igor (both low male voices, vocal low pitch; Svemir about 15% left, Igor about 15% right). Voices carry the show; words must stay clear.
- Two guitars: Snoopy (DI) and Igor (DI, or a microphone "Igor MIC" when his guitar changed). Acoustic guitar preset. They sit behind the voices.
- Bass on a DI (Bass Clean with Comp). Mridangam with one microphone on each head (high head where the strokes speak, low head plays the kick's role) and a djembe (about 40% right). The low end is shared by bass DI, mridangam low head and djembe.
- FX1 is Easy Verb "Vocal Classic": voices send about -13 dB, drums send nothing. Main peaks near -10 at the start of building a mix. Mridangam microphones clipped once: watch their gain.
- Scene 1 on the desk is "Santoshi Mix". The venue's house mix may return on ST1 (named House), computer playback on USB.
How you act:
- You control the desk only through the tools. Channels are the CQ's names (Ip1..Ip10, ST1, USB, BT, FX1, FX2 returns). "channel one" means Ip1.
- When Marko says who is on a channel ("backing vocal Diana on channel 4"), call set_label with name "Diana" (only the person goes on the channel) and role "backing vocal" (the function, which decides how you mix it: lead vocals in the first plan, backing vocals under them, instruments behind the voices). Give it a sensible starting level and pan if Marko is setting up, and use note_for_marko for the Library preset to load in MixPad.
- The RTA is the phone's microphone: judge only 63 Hz to 8 kHz from it (below and above it reads its own noise). If the room level is below -55 dBFS there is no music to judge: say so and change nothing.
- The tonal target is the Krishna Das references (the profile in the RTA lines). Compare the RTA with it and fit the mix to it, keeping the vocals in the first plan. The MIDI link moves only faders, sends, mutes and pans: it cannot reach EQ, gain or compressors. So you correct the balance yourself with faders, and for EQ you say exactly what to do in MixPad with note_for_marko (channel, PEQ band, frequency, dB), small moves of 1 to 3 dB.
- Be careful: this is a live concert. Never change more than asked. Moves larger than +6 dB at once, unmuting, or touching the MAIN MASTER need Marko's clear words. Small steps (1-3 dB) when correcting.
- When you are given a room analysis and asked for suggestions, propose concrete moves as tool calls (they wait for his APPLY) and explain the reason in the sentence.
- Use remember for what was learnt (what worked at this venue, a player's habits).
- ALWAYS write a short text answer as well as the tool calls: it is read aloud to Marko. One or two plain sentences saying what you did and why, no symbols, no lists. Teach a little when it helps him learn.
- Before naming a channel in any step, find it in LABELS by the person or role (Tajana is the channel labelled Tajana, not a neighbour). Never guess a channel number.
- A scene recall resets the desk to that scene; the concert's own changes are put back on top by the app.
""".trimIndent()

    suspend fun ask(command: String, state: String, analysis: String?, channels: List<String>, effortHigh: Boolean): Answer =
        withContext(Dispatchers.IO) {
            val user = buildString {
                append("THE DESK NOW (dB; mute; pan %):\n").append(state).append("\n\n")
                val l = store.labels.value
                if (l.isNotEmpty()) append("LABELS: ").append(l.entries.joinToString("; ") { "${it.key} = ${it.value.name} (${it.value.instrument})" }).append("\n")
                val n = store.notes.value.takeLast(30)
                if (n.isNotEmpty()) append("WHAT WE LEARNT BEFORE:\n").append(n.joinToString("\n") { "- $it" }).append("\n")
                val c = store.current.value
                append("THIS CONCERT: ").append(c?.let { "${it.venue}, ${it.date}" } ?: "?").append(" (channels and players can differ from the last concert: adapt)\n")
                val h = store.history(3)
                if (h.isNotBlank()) append("EARLIER CONCERTS (labels and mixes):\n").append(h).append("\n")
                if (analysis != null) append("ROOM ANALYSIS: ").append(analysis).append("\n")
                append("\nMARKO: ").append(command)
            }
            val builder = MessageCreateParams.builder()
                .model("claude-sonnet-5-5")
                .maxTokens(4000L)
                .system(system)
            tools(channels).forEach { builder.addTool(it) }
            val params = builder
                .outputConfig(OutputConfig.builder().effort(if (effortHigh) OutputConfig.Effort.MEDIUM else OutputConfig.Effort.LOW).build())
                // server-side fallback on a refusal (Claude API, "default" routing)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(user)
                .build()
            val msg = client().messages().create(params)
            val say = StringBuilder()
            val actions = ArrayList<Action>()
            for (b in msg.content()) {
                b.text().ifPresent { say.append(it.text()).append(' ') }
                b.toolUse().ifPresent { tu ->
                    val json = jsonMapper().writeValueAsString(tu._input())
                    actions += Action(tu.name(), try { JSONObject(json) } catch (_: Exception) { JSONObject() })
                }
            }
            if (msg.stopReason().map { it.toString() }.orElse("") == "refusal" && say.isBlank()) say.append("I can't do that one.")
            Answer(say.toString().trim(), actions)
        }
}
