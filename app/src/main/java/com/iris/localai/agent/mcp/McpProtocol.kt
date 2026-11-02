package com.iris.localai.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 2.3.0 ②: pure parts of the MCP client (JSON-RPC 2.0 framing, SSE parsing, tool-name namespacing,
 * input-schema flattening for the text prompt). No Android, no network — unit-tested (McpV230Test).
 * Remote servers only (Streamable HTTP 2025-03-26+, legacy HTTP+SSE fallback): no stdio / subprocess.
 */
object JsonRpc {
    const val PROTOCOL_VERSION = "2025-06-18"
    /** Versions we accept back from initialize (the server may answer an older one). */
    val SUPPORTED = setOf("2025-06-18", "2025-03-26", "2024-11-05")
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    fun request(id: Long, method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", method)
        if (params != null) put("params", params)
    }.toString()

    fun notification(method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", method)
        if (params != null) put("params", params)
    }.toString()

    fun initializeParams(clientVersion: String): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", JsonObject(emptyMap()))
        put("clientInfo", buildJsonObject { put("name", "IRIDIS"); put("version", clientVersion) })
    }

    data class Message(val id: Long?, val method: String?, val result: JsonElement?, val error: RpcError?) {
        val isResponse get() = id != null && method == null
    }
    data class RpcError(val code: Int, val message: String)

    /** One JSON-RPC message or a batch array → messages; anything unreadable is skipped. */
    fun parse(text: String): List<Message> {
        val el = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull() ?: return emptyList()
        val items = if (el is JsonArray) el.toList() else listOf(el)
        return items.mapNotNull { it as? JsonObject }.map { o ->
            val err = (o["error"] as? JsonObject)?.let { e ->
                RpcError((e["code"] as? JsonPrimitive)?.longOrNull?.toInt() ?: -1, (e["message"] as? JsonPrimitive)?.contentOrNull ?: "error")
            }
            val idEl = o["id"] as? JsonPrimitive
            val id = idEl?.longOrNull ?: idEl?.contentOrNull?.toLongOrNull()
            Message(id, (o["method"] as? JsonPrimitive)?.contentOrNull, o["result"], err)
        }
    }

    /** The response for [id] among [messages], or null. */
    fun responseFor(id: Long, messages: List<Message>): Message? = messages.firstOrNull { it.isResponse && it.id == id }
}

/**
 * Incremental text/event-stream parser (WHATWG rules): `event:`, `data:` (multi-line joined by \n),
 * `id:`, comments `:`, CR/LF/CRLF line ends, blank line dispatches. Feed it chunks; it returns the
 * events completed by each chunk.
 */
class SseParser {
    data class Event(val event: String, val data: String, val id: String?)

    private val line = StringBuilder()
    private val data = StringBuilder()
    private var hasData = false
    private var event = ""
    var lastId: String? = null
        private set
    private var lastWasCr = false

    fun feed(chunk: String): List<Event> {
        val out = ArrayList<Event>()
        for (ch in chunk) {
            if (ch == '\n' && lastWasCr) { lastWasCr = false; continue }
            lastWasCr = ch == '\r'
            if (ch == '\n' || ch == '\r') { processLine(line.toString(), out); line.setLength(0) } else line.append(ch)
        }
        return out
    }

    /** End of stream: a pending event without its blank line is dispatched (lenient). */
    fun finish(): List<Event> {
        val out = ArrayList<Event>()
        if (line.isNotEmpty()) { processLine(line.toString(), out); line.setLength(0) }
        processLine("", out)
        return out
    }

    private fun processLine(l: String, out: MutableList<Event>) {
        if (l.isEmpty()) {
            if (hasData) out += Event(event.ifEmpty { "message" }, data.toString(), lastId)
            data.setLength(0); hasData = false; event = ""
            return
        }
        if (l.startsWith(":")) return
        val colon = l.indexOf(':')
        val field = if (colon < 0) l else l.substring(0, colon)
        var value = if (colon < 0) "" else l.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
            "event" -> event = value
            "id" -> if ('\u0000' !in value) lastId = value
        }
    }
}

/** mcp_<server>_<tool> names: [a-z0-9_], ≤ 64 chars, unique. */
object McpNames {
    const val PREFIX = "mcp_"
    const val MAX = 64

    fun slug(s: String, max: Int = 24): String {
        val t = s.lowercase().map { c -> if (c in 'a'..'z' || c in '0'..'9') c else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_')
        return t.take(max).trim('_').ifEmpty { "x" }
    }

    fun namespaced(server: String, tool: String): String {
        val srv = slug(server, 16)
        val room = MAX - PREFIX.length - srv.length - 1
        return PREFIX + srv + "_" + slug(tool, room)
    }

    /** Namespaces every (server, tool) pair, adding _2, _3 … to collisions (input order kept). */
    fun assign(pairs: List<Pair<String, String>>): List<String> {
        val used = HashSet<String>()
        return pairs.map { (s, t) ->
            val base = namespaced(s, t)
            var n = base; var i = 2
            while (!used.add(n)) { val suf = "_$i"; n = base.take(MAX - suf.length) + suf; i++ }
            n
        }
    }
}

/**
 * Input schema → compact text signature for the system prompt (the same text for local and online
 * models, since every model here calls tools through the prompt). Nested objects are flattened to
 * dotted names (depth ≤ 2) and folded back by [unflatten] before the call; string values are
 * coerced to the declared number / boolean types (small models quote everything).
 */
object McpSchema {
    data class Param(val name: String, val type: String, val required: Boolean, val enum: List<String> = emptyList())

    const val DESC_MAX = 100
    private const val MAX_PARAMS = 12

    fun params(schema: JsonObject?, prefix: String = "", depth: Int = 0, parentRequired: Boolean = true): List<Param> {
        val props = schema?.get("properties") as? JsonObject ?: return emptyList()
        val req = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        val out = ArrayList<Param>()
        for ((k, v) in props) {
            val o = v as? JsonObject ?: continue
            val required = parentRequired && k in req
            val type = typeOf(o)
            if (type == "object" && depth < 1 && o["properties"] is JsonObject) {
                out += params(o, "$prefix$k.", depth + 1, required)
            } else {
                val enum = (o["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                out += Param(prefix + k, type, required, enum)
            }
        }
        return out.sortedBy { if (it.required) 0 else 1 }
    }

    private fun typeOf(o: JsonObject): String {
        val t = o["type"]
        val s = when (t) {
            is JsonPrimitive -> t.contentOrNull
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.firstOrNull { it != "null" }
            else -> null
        }
        return s ?: (o["anyOf"] as? JsonArray ?: o["oneOf"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.let(::typeOf)?.takeIf { x -> x != "any" } } ?: "any"
    }

    /** `a, b?:int, mode?:x|y` — strings are untyped (the common case, saves tokens). */
    fun signature(schema: JsonObject?): String {
        val ps = params(schema)
        val shown = ps.take(MAX_PARAMS).joinToString(", ") { p ->
            val t = when {
                p.enum.isNotEmpty() -> p.enum.take(6).joinToString("|")
                p.type == "string" || p.type == "any" -> ""
                p.type == "integer" -> "int"
                p.type == "number" -> "num"
                p.type == "boolean" -> "bool"
                p.type == "array" -> "list"
                else -> p.type
            }
            p.name + (if (p.required) "" else "?") + (if (t.isEmpty()) "" else ":$t")
        }
        return if (ps.size > MAX_PARAMS) "$shown, …" else shown
    }

    fun trimDescription(d: String?): String {
        val one = d.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (one.length <= DESC_MAX) return one
        val cut = one.take(DESC_MAX)
        val sentence = cut.lastIndexOfAny(charArrayOf('.', '。', ';', '；'))
        return if (sentence >= DESC_MAX / 2) cut.substring(0, sentence + 1) else cut.trimEnd() + "…"
    }

    fun promptLine(name: String, schema: JsonObject?, description: String?): String {
        val d = trimDescription(description)
        return "$name(${signature(schema)})" + if (d.isEmpty()) "" else ": $d"
    }

    /** Dotted keys back into nested objects, and string values coerced to the schema's types. */
    fun unflatten(args: JsonObject, schema: JsonObject?): JsonObject {
        val types = params(schema).associate { it.name to it.type }
        val root = LinkedHashMap<String, Any>()
        for ((k, v) in args) {
            val value = coerce(v, types[k])
            val parts = k.split('.')
            if (parts.size == 1 || types[k] == null) { root[k] = value; continue }
            @Suppress("UNCHECKED_CAST")
            var m = root
            for (p in parts.dropLast(1)) {
                @Suppress("UNCHECKED_CAST")
                m = (m.getOrPut(p) { LinkedHashMap<String, Any>() } as? LinkedHashMap<String, Any>) ?: break
            }
            m[parts.last()] = value
        }
        return toJson(root)
    }

    @Suppress("UNCHECKED_CAST")
    private fun toJson(m: Map<String, Any>): JsonObject = JsonObject(m.mapValues { (_, v) -> if (v is Map<*, *>) toJson(v as Map<String, Any>) else v as JsonElement })

    private fun coerce(v: JsonElement, type: String?): JsonElement {
        val p = v as? JsonPrimitive ?: return v
        if (!p.isString) return v
        val s = p.content.trim()
        return when (type) {
            "integer" -> s.toLongOrNull()?.let { JsonPrimitive(it) } ?: v
            "number" -> s.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: v
            "boolean" -> when (s.lowercase()) { "true", "yes", "1" -> JsonPrimitive(true); "false", "no", "0" -> JsonPrimitive(false); else -> v }
            "array", "object" -> runCatching { JsonRpc.json.parseToJsonElement(s) }.getOrNull()?.takeIf { it !is JsonPrimitive } ?: v
            else -> v
        }
    }
}

/** tools/call result → text for the model and one line for the step card. */
object McpResult {
    data class Text(val ok: Boolean, val text: String)

    fun of(result: JsonElement?): Text {
        val o = result as? JsonObject ?: return Text(true, result?.toString().orEmpty())
        val isError = (o["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val parts = (o["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { c ->
            when ((c["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (c["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                "image" -> "[image ${(c["mimeType"] as? JsonPrimitive)?.contentOrNull ?: ""}]"
                "audio" -> "[audio]"
                "resource_link" -> "[resource ${(c["uri"] as? JsonPrimitive)?.contentOrNull ?: ""}]"
                "resource" -> (c["resource"] as? JsonObject)?.let { r -> (r["text"] as? JsonPrimitive)?.contentOrNull ?: "[resource ${(r["uri"] as? JsonPrimitive)?.contentOrNull ?: ""}]" }.orEmpty()
                else -> c.toString()
            }
        }.orEmpty()
        val text = parts.joinToString("\n").ifBlank { o["structuredContent"]?.takeIf { it !is JsonNull }?.toString().orEmpty() }
        return Text(!isError, text)
    }

    /** One line for the step card. */
    fun summary(t: Text): String = if (t.text.isBlank()) (if (t.ok) "完成" else "失败") else t.text.replace(Regex("\\s+"), " ").trim().take(60)
}

/** Which MCP tools reach the prompt: the first [cap] enabled ones, in server then tool order. */
object McpCap {
    const val DEFAULT = 20
    data class Pick<T>(val injected: List<T>, val dropped: Int)
    fun <T> select(tools: List<T>, cap: Int = DEFAULT): Pick<T> = Pick(tools.take(cap), (tools.size - cap).coerceAtLeast(0))
}
