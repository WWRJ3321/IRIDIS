package com.iris.localai.character

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 2.3.2 角色卡: a SillyTavern character card kept as its full JSON ([raw], always normalised to
 * `chara_card_v3`), so fields IRIDIS does not know (extensions, assets, lorebook decorators …) are
 * written back unchanged on export. Only the fields the editor shows are read / replaced.
 *
 * IRIDIS's own per-card bindings live in `data.extensions.iridis` (AIRI keeps its in
 * `extensions.airi`): model file name, read-aloud voice, image-style prompt prefix.
 * Pure Kotlin (unit-tested in v232/CharacterV232Test).
 */
data class CharacterCard(val raw: JsonObject) {
    val data: JsonObject get() = raw["data"] as? JsonObject ?: JsonObject(emptyMap())

    private fun str(key: String): String = (data[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun strs(key: String): List<String> = (data[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    val name get() = str("name")
    /** V3: `{{char}}` uses the nickname when there is one. */
    val nickname get() = str("nickname")
    val description get() = str("description")
    val personality get() = str("personality")
    val scenario get() = str("scenario")
    val firstMes get() = str("first_mes")
    val mesExample get() = str("mes_example")
    val creatorNotes get() = str("creator_notes")
    val systemPrompt get() = str("system_prompt")
    val postHistory get() = str("post_history_instructions")
    val alternateGreetings get() = strs("alternate_greetings")
    val tags get() = strs("tags")
    val creator get() = str("creator")
    val characterBook: JsonObject? get() = data["character_book"] as? JsonObject

    val charName: String get() = nickname.ifBlank { name }

    private val iridis: JsonObject get() = ((data["extensions"] as? JsonObject)?.get("iridis") as? JsonObject) ?: JsonObject(emptyMap())
    private fun bind(key: String) = (iridis[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    /** Bound model file name ("" = keep the current model). */
    val bindModel get() = bind("model")
    /** Bound voice pack id / speaker / clone voice ("" / -1 = keep the current voice). */
    val bindTtsPack get() = bind("tts_pack")
    val bindTtsSpeaker get() = (iridis["tts_speaker"] as? JsonPrimitive)?.intOrNull ?: -1
    val bindCloneVoice get() = bind("clone_voice")
    /** Prefix added to generate_image prompts in this card's chats ("" = none). */
    val bindImageStyle get() = bind("image_style")

    /** Short line for the list: creator notes' first line, else the description. */
    fun blurb(max: Int = 60): String {
        val src = creatorNotes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (src.length <= max) src else src.take(max) + "…"
    }

    /** Copy with data fields replaced (null value = remove); every other field stays. */
    fun withData(vararg pairs: Pair<String, JsonElement?>): CharacterCard {
        val m = LinkedHashMap(data)
        for ((k, v) in pairs) if (v == null) m.remove(k) else m[k] = v
        val r = LinkedHashMap(raw); r["data"] = JsonObject(m)
        return CharacterCard(JsonObject(r))
    }

    /** Copy with `extensions.iridis` keys replaced ("" / null = remove the binding). */
    fun withBindings(model: String? = bindModel, ttsPack: String? = bindTtsPack, ttsSpeaker: Int = bindTtsSpeaker, cloneVoice: String? = bindCloneVoice, imageStyle: String? = bindImageStyle): CharacterCard {
        val ext = LinkedHashMap((data["extensions"] as? JsonObject) ?: JsonObject(emptyMap()))
        val b = LinkedHashMap(iridis)
        fun put(k: String, v: String?) { if (v.isNullOrBlank()) b.remove(k) else b[k] = JsonPrimitive(v) }
        put("model", model); put("tts_pack", ttsPack); put("clone_voice", cloneVoice); put("image_style", imageStyle)
        if (ttsSpeaker >= 0 && !ttsPack.isNullOrBlank()) b["tts_speaker"] = JsonPrimitive(ttsSpeaker) else b.remove("tts_speaker")
        if (b.isEmpty()) ext.remove("iridis") else ext["iridis"] = JsonObject(b)
        return withData("extensions" to JsonObject(ext))
    }

    /** The V2 view for the `chara` PNG chunk / V2 readers: same data, V2 spec header. */
    fun toV2(): JsonObject {
        val r = LinkedHashMap(raw)
        r["spec"] = JsonPrimitive(CardSpec.V2); r["spec_version"] = JsonPrimitive("2.0")
        return JsonObject(r)
    }

    fun toJson(pretty: Boolean = true): String = (if (pretty) CardSpec.prettyJson else CardSpec.json).encodeToString(JsonObject.serializer(), raw)

    companion object {
        /** A fresh card as the editor creates it. */
        fun blank(name: String = ""): CharacterCard = CardSpec.normalize(JsonObject(mapOf("name" to JsonPrimitive(name))))

        fun text(v: String): JsonElement = JsonPrimitive(v)
        fun list(v: List<String>): JsonElement = JsonArray(v.map { JsonPrimitive(it) })
    }
}

object CardSpec {
    const val V2 = "chara_card_v2"
    const val V3 = "chara_card_v3"
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Data fields every V3 card has (filled with empty values when missing). */
    private val STRING_FIELDS = listOf("name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version")
    private val LIST_FIELDS = listOf("alternate_greetings", "tags", "group_only_greetings")
    /** Top-level keys of a V1 card (no spec, fields at the root). */
    private val V1_KEYS = setOf("name", "description", "personality", "scenario", "first_mes", "mes_example")

    /**
     * Any accepted card JSON (V1 flat, V2, V3) → V3 with all known fields present. Unknown keys at
     * both levels are kept. Throws [IllegalArgumentException] when it is not a character card.
     */
    fun normalize(src: JsonObject): CharacterCard {
        val spec = (src["spec"] as? JsonPrimitive)?.contentOrNull
        val data: JsonObject = when {
            src["data"] is JsonObject && (spec == V2 || spec == V3 || spec == null) -> src["data"] as JsonObject
            spec == null && src.keys.any { it in V1_KEYS } -> JsonObject(src.filterKeys { it != "spec" && it != "spec_version" })
            else -> throw IllegalArgumentException("not a character card")
        }
        val name = (data["name"] as? JsonPrimitive)?.contentOrNull
        if (name == null && src["data"] == null) throw IllegalArgumentException("not a character card")
        val d = LinkedHashMap<String, JsonElement>(data)
        for (k in STRING_FIELDS) if (d[k] !is JsonPrimitive || d[k] is JsonNull) d[k] = JsonPrimitive((d[k] as? JsonPrimitive)?.contentOrNull.orEmpty())
        for (k in LIST_FIELDS) if (d[k] !is JsonArray) d[k] = JsonArray(emptyList())
        if (d["extensions"] !is JsonObject) d["extensions"] = JsonObject(emptyMap())
        val top = LinkedHashMap<String, JsonElement>()
        // V2/V3 cards keep their other top-level keys (some tools add them); a V1 card has none left.
        if (src["data"] is JsonObject) top.putAll(src)
        top["spec"] = JsonPrimitive(V3); top["spec_version"] = JsonPrimitive("3.0"); top["data"] = JsonObject(d)
        return CharacterCard(JsonObject(top))
    }

    fun parse(text: String): CharacterCard {
        val el = json.parseToJsonElement(text.trimStart('\uFEFF'))
        return normalize(el as? JsonObject ?: throw IllegalArgumentException("not a character card"))
    }

    internal fun specOf(o: JsonObject) = o["spec"]?.jsonPrimitive?.contentOrNull
}
