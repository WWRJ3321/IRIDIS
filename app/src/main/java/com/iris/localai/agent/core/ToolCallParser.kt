package com.iris.localai.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** How the model wrote its tool call (logged, and used by the tests). */
enum class CallFormat { FENCED, QWEN_TAG, PLAIN_JSON }

data class ToolCall(val name: String, val args: JsonObject, val format: CallFormat) {
    /** The one canonical form the system prompt teaches; history always carries this form. */
    fun canonical(): String = "```tool\n" + buildJsonObject {
        put("tool", name)
        put("args", args)
    }.toString() + "\n```"
}

sealed interface ParsedReply {
    /** [before] = prose the model wrote ahead of the call (shown, never executed). */
    data class Call(val call: ToolCall, val before: String) : ParsedReply
    data class Final(val text: String) : ParsedReply
    /** The model clearly tried to call a tool but the block could not be read. */
    data class Malformed(val error: String, val before: String) : ParsedReply
}

/**
 * 1.9.0: tolerant tool-call reader for small local models. The prompt teaches one format,
 *
 *     ```tool
 *     {"tool": "read_file", "args": {"path": "a.txt"}}
 *     ```
 *
 * but Qwen-family models (Agents-A1, Qwen3) are trained on `<tool_call>{"name":…,"arguments":…}</tool_call>`
 * and some just print a bare JSON object, so all three are accepted. JSON is repaired before it
 * is given up on: raw newlines inside strings (file contents), trailing commas, single quotes,
 * Python True/False/None, and a block cut off at the end of the reply.
 * Pure Kotlin, unit-tested (V190Test).
 */
object ToolCallParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private val THINK = Regex("(?is)<think>.*?</think>")
    private val QWEN_OPEN = Regex("(?i)<tool_call>")
    private val QWEN_CLOSE = Regex("(?i)</tool_call>")
    /** ```tool / ```tool_call / ```json / ``` (language optional), up to the end of that line. */
    private val FENCE_OPEN = Regex("```[ \\t]*([A-Za-z_]*)[ \\t]*\\r?\\n?")
    private val TOOL_FENCE_LANGS = setOf("tool", "tool_call", "toolcall", "tool_code", "function", "function_call")
    private val NAME_KEYS = listOf("tool", "name", "tool_name", "function")
    private val ARG_KEYS = listOf("args", "arguments", "parameters", "params", "input")
    private val TOOL_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]{0,63}$")
    /** 1.10.4: role names the models sometimes emit as a "tool" — plain prose, never a call. */
    val ROLE_NAMES = setOf("system", "user", "assistant", "human", "ai", "tool", "function", "bot", "model")
    /** 1.10.4: LFM2 native form — a line starting `tool_call:<name>{...}`. */
    /**
     * 1.13.3: Gemma 4 native form — `<|tool_call>call:name{key: <|"|>value<|"|>}<tool_call|>`.
     * Keys are bare and strings may be wrapped in the `<|"|>` escape token.
     */
    private val GEMMA_OPEN = Regex("<\\|tool_call>\\s*(?:call\\s*:)?\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*")
    private const val GEMMA_CLOSE = "<tool_call|>"
    /** Gemma's argument body → JSON the lenient parser accepts. */
    internal fun gemmaArgs(body: String): String {
        val t = body.replace("<|\"|>", "\"")
        val out = StringBuilder(t.length + 16)
        var inStr = false; var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (inStr) {
                out.append(ch)
                if (ch == '\\' && i + 1 < t.length) { out.append(t[i + 1]); i += 2; continue }
                if (ch == '"') inStr = false
                i++; continue
            }
            if (ch == '"') { inStr = true; out.append(ch); i++; continue }
            // 2.1.8: bare value right after `:` (`query: 诡秘之主 小说`) → one quoted string
            if (out.trimEnd().lastOrNull() == ':') {
                JsonRepair.bareValue(t, i)?.let { (q, end) -> out.append(q); i = end; continue }
            }
            // bare key right after { or , → quoted
            if ((ch.isLetter() || ch == '_') && out.trimEnd().lastOrNull().let { it == '{' || it == ',' }) {
                var j = i
                while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
                var k = j
                while (k < t.length && t[k].isWhitespace()) k++
                if (k < t.length && t[k] == ':') { out.append('"').append(t, i, j).append('"'); i = j; continue }
            }
            out.append(ch); i++
        }
        return out.toString()
    }
    private val LFM_CALL = Regex("(?m)^\\s*tool_call\\s*:\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*(\\{.*)$")
    /** 1.10.4: common misnames → the real tool. Keys are lowercase/underscores stripped. */
    private val ALIASES = mapOf(
        "ls" to "list_dir", "dir" to "list_dir", "listdir" to "list_dir", "ll" to "list_dir",
        "cat" to "read_file", "read" to "read_file", "open" to "read_file", "readfile" to "read_file", "view" to "read_file", "file" to "read_file",
        "write" to "write_file", "writefile" to "write_file", "save" to "write_file", "createfile" to "write_file", "create" to "write_file",
        "edit" to "edit_file", "editfile" to "edit_file", "replace" to "edit_file", "patch" to "edit_file", "modify" to "edit_file",
        "search" to "search_files", "grep" to "search_files", "find" to "search_files", "glob" to "search_files", "searchfiles" to "search_files",
        "rm" to "delete_file", "del" to "delete_file", "delete" to "delete_file", "remove" to "delete_file", "deletefile" to "delete_file",
        "calc" to "calculator", "math" to "calculator", "calculate" to "calculator",
        "time" to "datetime", "date" to "datetime", "now" to "datetime",
        "fetch" to "web_fetch", "http" to "web_fetch", "web" to "web_fetch", "webfetch" to "web_fetch", "curl" to "web_fetch", "url" to "web_fetch",
        "todo" to "plan", "todos" to "plan", "checklist" to "plan", "load" to "activate", "enable" to "activate",
        "history" to "recall", "remember" to "memory", "note" to "memory",
    )
    /** Map a misnamed call to a real tool name; null when nothing plausibly matches. */
    fun canonicalizeName(raw: String, available: Set<String>): String? {
        val key = raw.trim().lowercase().replace(Regex("[_\\-.\\s]"), "")
        if (key.isEmpty()) return null
        available.firstOrNull { it.lowercase() == raw.trim().lowercase() }?.let { return it }
        available.firstOrNull { it.lowercase().replace(Regex("[_\\-.\\s]"), "") == key }?.let { return it }
        val alias = ALIASES[key]
        if (alias != null && available.any { it == alias }) return alias
        // one edit away (case/typo): "readflle" → "read_file"
        if (raw.length >= 4) for (t in available) if (editDistanceLe2(key, t.lowercase().replace("_", ""))) return t
        return null
    }
    private fun editDistanceLe2(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 2) return false
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (cur.min() > 2) return false
            prev = cur
        }
        return prev[b.length] <= 2
    }

    /**
     * 2.2.3: the main string argument of single-argument tools: a positional `name("v")` maps to it,
     * and when a call's args are missing / only the signature placeholders (`prompt, image?`) it is
     * filled from the user's latest message ([fillMainArg]).
     */
    val MAIN_ARG = mapOf(
        "generate_image" to "prompt", "web_search" to "query", "search_local" to "query", "screen" to "task",
    )
    /** 2.2.3: first positional argument of other tools in Python-style calls. */
    private val POSITIONAL = MAIN_ARG + mapOf(
        "read_file" to "path", "write_file" to "path", "skill" to "name",
        "web_fetch" to "url", "kb_search" to "query", "memory" to "action", "plan" to "title", "activate" to "tool",
    )
    /**
     * 2.2.5: registered tool names, for the tolerant fallback (a known name followed by key/value
     * pairs in any form). The agent loop sets it per run; the default covers the built-in tools.
     */
    @Volatile var knownTools: Set<String> = emptySet()
    private fun known(): Set<String> = knownTools.ifEmpty { POSITIONAL.keys + setOf("edit_file", "list_dir", "search_files", "todo", "datetime", "recall", "phone", "browser", "process_file") }

    /** 2.2.5: GLM-4.5 pairs `<arg_key>k</arg_key><arg_value>v</arg_value>` (value close tag optional). */
    private val ARG_PAIR = Regex("(?is)<arg_key>\\s*(.*?)\\s*</arg_key>\\s*(?:<arg_value>(.*?)(?:</arg_value>|(?=<arg_key>)|(?=</tool_call>)|$))?")
    /** The tool name written right before the first `<arg_key>`: `<name`, `<name>`, `name`, `"name":`. */
    private val GLM_NAME_TAIL = Regex("(?s)<?\\s*[\"']?([A-Za-z_][A-Za-z0-9_.\\-]*)[\"']?\\s*>?\\s*:?\\s*$")
    private val NOT_NAMES = setOf("tool_call", "arg_key", "arg_value", "function", "parameter")
    /** 2.2.5: markup that only ever appears in a tool call; never left as the visible answer. */
    private val CALL_MARKUP = Regex("(?i)</?arg_key>|</?arg_value>|</?tool_call>|<function\\s*=|<parameter\\s*=")

    internal fun glmArgs(s: String): JsonObject = buildJsonObject {
        ARG_PAIR.findAll(s).forEach { m ->
            val k = m.groupValues[1].trim()
            if (k.isNotEmpty() && TOOL_NAME.matches(k)) put(k, JsonPrimitive(m.groupValues[2].trim()))
        }
    }

    /** Start of `<name` + `<arg_key>` in [s] (the call's first character), with the name; null when absent. */
    internal fun glmStart(s: String, from: Int = 0): Pair<Int, String>? {
        val k = s.indexOf("<arg_key>", from, ignoreCase = true)
        if (k < 0) return null
        val head = s.substring(from, k)
        val m = GLM_NAME_TAIL.find(head) ?: return null
        val name = m.groupValues[1]
        if (name.lowercase() in NOT_NAMES || name.lowercase() in ROLE_NAMES) return null
        return (from + m.range.first) to name
    }

    /** 2.2.5: GLM-4.5 body `name<arg_key>k</arg_key><arg_value>v</arg_value>…` (also `<name<arg_key>`, `<name>`). */
    internal fun glmCall(body: String): ToolCall? {
        val (start, name) = glmStart(body) ?: return null
        val args = glmArgs(body.substring(start))
        return if (args.isEmpty()) null else ToolCall(name, args, CallFormat.QWEN_TAG)
    }

    private val KV_PAIR = Regex("^[\\s,;]*([A-Za-z_][A-Za-z0-9_]*)\\s*[=:]\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^\\s,;)}\"']+)")

    /**
     * 2.2.5: tolerant fallback — a registered tool name immediately followed by arguments as
     * arg_key/arg_value pairs, `<parameter=k>v`, a JSON object, or `k="v"` pairs.
     */
    internal fun knownToolCall(text: String): Pair<ToolCall, Int>? {
        for (name in known().sortedByDescending { it.length }) {
            val re = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
            for (m in re.findAll(text)) {
                var i = m.range.last + 1
                while (i < text.length && (text[i].isWhitespace() || text[i] in ">\"'`:(")) i++
                val rest = text.substring(i)
                val start = m.range.first.let { if (it > 0 && text[it - 1] == '<') it - 1 else it }
                val args: JsonObject? = when {
                    rest.startsWith("<arg_key>", ignoreCase = true) -> glmArgs(rest)
                    rest.startsWith("<parameter", ignoreCase = true) -> buildJsonObject {
                        XML_PARAM.findAll(rest).forEach { p -> put(p.groupValues[1], JsonPrimitive(p.groupValues[2].trim())) }
                    }
                    rest.startsWith("{") -> (JsonRepair.parse(JsonRepair.extractObject(rest, 0), json) as? JsonObject)?.let { o ->
                        listOf("arguments", "args", "parameters", "input").firstNotNullOfOrNull { k -> o[k] as? JsonObject } ?: o
                    }
                    else -> {
                        var r = rest
                        val o = buildJsonObject {
                            while (true) {
                                val p = KV_PAIR.find(r) ?: break
                                put(p.groupValues[1], JsonPrimitive(p.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")))
                                r = r.substring(p.range.last + 1)
                            }
                        }
                        o
                    }
                }
                if (args != null && args.isNotEmpty()) return ToolCall(name, args, CallFormat.QWEN_TAG) to start
            }
        }
        return null
    }

    /** 2.2.3: qwen3_coder / LFM XML form `<function=name><parameter=k>v</parameter></function>`. */
    private val XML_FN = Regex("(?is)<function\\s*=\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*>(.*?)(?:</function>|$)")
    private val XML_PARAM = Regex("(?is)<parameter\\s*=\\s*([A-Za-z_][A-Za-z0-9_\\-]*)\\s*>(.*?)(?:</parameter>|(?=<parameter\\s*=)|(?=</function>)|$)")
    /** 2.2.3: Python / function-call form `name(a="v", k=1)` / `name("v")` / `name(prompt, image?)`. */
    private val PY_CALL = Regex("(?s)^\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*\\((.*)\\)\\s*;?\\s*$")

    internal fun xmlCall(body: String): ToolCall? {
        val m = XML_FN.find(body) ?: return null
        val name = m.groupValues[1]
        if (name.lowercase() in ROLE_NAMES) return null
        val args = buildJsonObject {
            XML_PARAM.findAll(m.groupValues[2]).forEach { p ->
                val v = p.groupValues[2].trim('\n', '\r')
                put(p.groupValues[1], JsonPrimitive(v.trim().takeIf { it.isNotEmpty() } ?: v))
            }
        }
        return ToolCall(name, args, CallFormat.QWEN_TAG)
    }

    /** Splits `a="x, y", k=[1,2]` on top-level commas (quotes and brackets respected). */
    private fun splitTop(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        var q: Char? = null; var depth = 0; var i = 0
        while (i < s.length) {
            val c = s[i]
            if (q != null) {
                cur.append(c)
                if (c == '\\' && i + 1 < s.length) { cur.append(s[i + 1]); i += 2; continue }
                if (c == q) q = null
            } else when (c) {
                '"', '\'' -> { q = c; cur.append(c) }
                '(', '[', '{' -> { depth++; cur.append(c) }
                ')', ']', '}' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { out += cur.toString(); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun pyValue(raw: String): JsonElement? {
        val v = raw.trim()
        if (v.length >= 2 && (v[0] == '"' || v[0] == '\'') && v.last() == v[0]) {
            val inner = v.substring(1, v.length - 1)
            val sb = StringBuilder(); var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) {
                    when (val n = inner[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); else -> sb.append(n) }
                    i += 2; continue
                }
                sb.append(c); i++
            }
            return JsonPrimitive(sb.toString())
        }
        when (v) { "True", "true" -> return JsonPrimitive(true); "False", "false" -> return JsonPrimitive(false); "None", "null" -> return JsonNull }
        v.toLongOrNull()?.let { return JsonPrimitive(it) }
        v.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        if (v.startsWith("{") || v.startsWith("[")) JsonRepair.parse(v, json)?.let { return it }
        return null
    }

    /**
     * 2.2.3: reads `name(...)`. Bare identifiers (`prompt, image?`) are the signature copied from the
     * prompt, not values: they are dropped (the args then stay empty and [fillMainArg] may fill them).
     */
    internal fun pyCall(body: String, format: CallFormat): ToolCall? {
        val m = PY_CALL.find(body.trim()) ?: return null
        val name = m.groupValues[1]
        if (name.lowercase() in ROLE_NAMES || !TOOL_NAME.matches(name)) return null
        val args = LinkedHashMap<String, JsonElement>()
        var positional = 0
        for (part in splitTop(m.groupValues[2])) {
            val eq = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*[=:]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(part)
            if (eq != null) {
                val v = pyValue(eq.groupValues[2]) ?: JsonPrimitive(eq.groupValues[2].trim())
                args[eq.groupValues[1]] = v
            } else {
                val v = pyValue(part) ?: continue  // a placeholder like `prompt` / `image?`
                val key = if (positional == 0) POSITIONAL[name] ?: "arg0" else "arg$positional"
                positional++
                args[key] = v
            }
        }
        return ToolCall(name, JsonObject(args), format)
    }

    /**
     * 2.2.3: a call to a single-main-argument tool whose main argument is missing, blank or just the
     * placeholder text gets the user's latest message instead of failing.
     */
    fun fillMainArg(parsed: ParsedReply, userText: String): ParsedReply {
        if (parsed !is ParsedReply.Call || userText.isBlank()) return parsed
        val key = MAIN_ARG[parsed.call.name] ?: return parsed
        val cur = (parsed.call.args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (cur != null && !placeholder(key, cur)) return parsed
        val kept = parsed.call.args.filter { (k, v) -> !(v is JsonPrimitive && v.isString && placeholder(k, v.content.trim())) }
        return parsed.copy(call = parsed.call.copy(args = JsonObject(kept + (key to JsonPrimitive(userText.trim())))))
    }
    /** Blank, the key's own name, or a signature list (`prompt, image?`) — not a real value. */
    private fun placeholder(key: String, v: String): Boolean =
        v.isEmpty() || v.trimEnd('?').equals(key, true) || (SIGNATURE.matches(v) && (',' in v || v.endsWith('?')))
    /** `prompt` / `prompt, image?` — the signature from the prompt line, not a value. */
    private val SIGNATURE = Regex("^[A-Za-z_][A-Za-z0-9_]*\\??(\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*\\??)*$")

    /**
     * 2.2.8: strict reading first; a reply it calls malformed goes through every tolerant reader
     * ([tolerant]) before the agent loop spends a retry on it.
     */
    fun parse(raw: String): ParsedReply {
        val reply = k2Normalize(raw)
        val r = parseStrict(reply)
        if (r !is ParsedReply.Malformed) return r
        return tolerant(reply)?.let { (call, before) -> ParsedReply.Call(call, before) } ?: r
    }

    /**
     * 2.2.8: last-chance readers for a reply the strict parser could not read: fenced / <tool_call>
     * bodies and the whole text, each tried as (repaired) JSON — single quotes, trailing commas,
     * Python True/False/None —, `name(k="v")` function calls, `name{…}` / `name k="v"` after a
     * registered tool name, XML `<function=…>` and GLM `<arg_key>`. Returns the call and the prose before it.
     */
    /** 2.2.13: K2 Horizon call / reasoning markup rewritten into the `<tool_call>` + GLM arg_key form read below. */
    const val K2_CALL_OPEN = "<ifm|tool_call>"
    const val K2_CALL_CLOSE = "</ifm|tool_call>"
    private val K2_ARG_TYPE = Regex("(?s)<ifm\\|arg_type>.*?</ifm\\|arg_type>\\s*")
    private val K2_THINK = Regex("<(/?)ifm\\|think(?:_fast|_faster)?>")

    internal fun k2Normalize(s: String): String {
        if (!s.contains("ifm|")) return s
        var t = K2_ARG_TYPE.replace(s, "").replace("<ifm|tool_calls>", "").replace("</ifm|tool_calls>", "")
        for (x in listOf("tool_call", "arg_key", "arg_value")) t = t.replace("<ifm|$x>", "<$x>").replace("</ifm|$x>", "</$x>")
        return K2_THINK.replace(t) { "<${it.groupValues[1]}think>" }
    }

    /**
     * 2.2.14: the reply with all reasoning removed, so a call only *mentioned* while thinking is
     * never run: closed `<think>…</think>` blocks, everything before a lone `</think>` (the
     * template opened thinking in the prompt) and an unclosed `<think>` to the end. Gemma 4 /
     * K2 reasoning tags are mapped to `<think>` first.
     */
    fun outsideThink(raw: String): String {
        var t = k2Normalize(raw)
        if (t.contains(ChannelThinkTags.OPEN)) t = com.iris.localai.engine.ChannelThink.convert(t)
        t = THINK.replace(t, "")
        val close = t.lastIndexOf("</think>", ignoreCase = true)
        if (close >= 0) t = t.substring(close + "</think>".length)
        val open = t.indexOf("<think>", ignoreCase = true)
        if (open >= 0) t = t.substring(0, open)
        return t
    }

    private object ChannelThinkTags { const val OPEN = "<|channel>" }

    internal fun tolerant(reply: String): Pair<ToolCall, String>? {
        val text = outsideThink(reply).trim()
        if (text.isEmpty()) return null
        val segments = ArrayList<Int>() // start offsets of candidate bodies; the whole text is offset 0
        FENCE_OPEN.findAll(text).forEach { segments += (it.range.last + 1).coerceAtMost(text.length) }
        QWEN_OPEN.findAll(text).forEach { segments += it.range.last + 1 }
        segments += 0
        for (seg in segments.distinct()) {
            val body = text.substring(seg).let { b -> b.indexOf("```").let { if (it >= 0 && seg > 0) b.substring(0, it) else b } }
                .let { b -> QWEN_CLOSE.find(b)?.let { b.substring(0, it.range.first) } ?: b }
            readAny(body)?.let { (call, at) -> return call to text.substring(0, fenceStart(text, seg + at)).trim() }
        }
        return null
    }

    /** Where the prose before a call at [at] ends: before an opening fence / <tool_call> right above it. */
    private fun fenceStart(text: String, at: Int): Int {
        val head = text.substring(0, at)
        val cut = maxOf(head.lastIndexOf("```"), QWEN_OPEN.findAll(head).lastOrNull()?.range?.first ?: -1)
        return if (cut >= 0 && head.substring(cut).replace(Regex("(?i)```[A-Za-z_]*|<tool_call>"), "").isBlank()) cut else at
    }

    private fun readAny(body: String): Pair<ToolCall, Int>? {
        val names = known()
        // JSON-ish objects, first readable one wins (`name{…}` after a tool name is read below).
        var i = body.indexOf('{')
        while (i >= 0) {
            val pre = body.substring(0, i).trimEnd().trimEnd('"', '\'', ':', '`')
            val obj = if (names.any { pre.endsWith(it) && TOOL_NAME.matches(it) }) null else JsonRepair.parse(JsonRepair.extractObject(body, i), json) as? JsonObject
            if (obj != null) {
                val nameRaw = NAME_KEYS.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.trim()?.lowercase()
                if (nameRaw == null || nameRaw !in ROLE_NAMES) (toCall(obj, CallFormat.PLAIN_JSON) as? Result.Ok)?.let { return it.call to i }
            }
            i = body.indexOf('{', i + 1)
        }
        // name(k="v") to a registered tool.
        Regex("([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*\\(").findAll(body).forEach { m ->
            if (m.groupValues[1] !in names) return@forEach
            val open = m.range.last
            val end = closeParen(body, open)
            val src = body.substring(m.range.first, end) + if (end >= body.length || body[end - 1] != ')') ")" else ""
            pyCall(src, CallFormat.PLAIN_JSON)?.let { return it to m.range.first }
        }
        if (body.contains("<function=", ignoreCase = true)) {
            val x = body.indexOf("<function=", ignoreCase = true)
            xmlCall(body.substring(x))?.let { return it to x }
        }
        glmStart(body)?.let { (start, _) -> glmCall(body.substring(start))?.let { return it to start } }
        knownToolCall(body)?.let { return it }
        return null
    }

    /** Index just past the `)` closing the `(` at [open] (quotes respected), or the text's end. */
    private fun closeParen(s: String, open: Int): Int {
        var depth = 0; var q: Char? = null; var i = open
        while (i < s.length) {
            val c = s[i]
            if (q != null) { if (c == '\\') i++ else if (c == q) q = null }
            else when (c) {
                '"', '\'' -> q = c
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return s.length
    }

    private fun parseStrict(reply: String): ParsedReply {
        val text = outsideThink(reply).trim()

        // 1) Qwen tag (trained format of Agents-A1 / Qwen3): first <tool_call> wins.
        QWEN_OPEN.find(text)?.let { open ->
            val before = text.substring(0, open.range.first).trim()
            val rest = text.substring(open.range.last + 1)
            val body = QWEN_CLOSE.find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
            // 2.2.3: XML (qwen3_coder / LFM) and Python-style bodies.
            if (XML_FN.containsMatchIn(body)) xmlCall(body)?.let { return ParsedReply.Call(it, before) }
            // 2.2.5: GLM-4.5 arg_key / arg_value body.
            if (body.contains("<arg_key>", ignoreCase = true)) glmCall(body)?.let { return ParsedReply.Call(it, before) }
            if (!body.trimStart().startsWith("{")) pyCall(body, CallFormat.QWEN_TAG)?.let { return ParsedReply.Call(it, before) }
            return fromObjectText(body, CallFormat.QWEN_TAG, before, required = true)
        }

        // 1d) 2.2.5: bare GLM call without a <tool_call> open tag: `<name<arg_key>…`, `<name>…`, `name<arg_key>…`.
        glmStart(text)?.let { (start, _) ->
            glmCall(text.substring(start))?.let { return ParsedReply.Call(it, text.substring(0, start).trim()) }
        }
        // 1a) 2.2.3: bare XML function call (no <tool_call> wrapper).
        if (text.contains("<function=", ignoreCase = true)) {
            val i = text.indexOf("<function=", ignoreCase = true)
            xmlCall(text.substring(i))?.let { return ParsedReply.Call(it, text.substring(0, i).trim()) }
        }
        // 1c) 2.2.3: a bare Python-style call to a known tool alone on its line.
        Regex("(?m)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(.*\\)\\s*;?\\s*$").findAll(text).firstOrNull { it.groupValues[1] in POSITIONAL }?.let { m ->
            pyCall(m.value, CallFormat.PLAIN_JSON)?.let { return ParsedReply.Call(it, text.substring(0, m.range.first).trim()) }
        }

        // 1b) 1.13.3: Gemma 4 native call.
        GEMMA_OPEN.find(text)?.let { m ->
            val before = text.substring(0, m.range.first).trim()
            val name = m.groupValues[1]
            if (name.lowercase() in ROLE_NAMES) return ParsedReply.Final(reply.trim())
            val rest = text.substring(m.range.last + 1)
            val body = rest.substringBefore(GEMMA_CLOSE)
            val start = body.indexOf('{')
            if (start < 0) return ParsedReply.Call(ToolCall(name, JsonObject(emptyMap()), CallFormat.FENCED), before)
            val g = gemmaArgs(body.substring(start))
            val el = JsonRepair.parse(JsonRepair.extractObject(g, 0), json) as? JsonObject
                ?: return ParsedReply.Malformed("Gemma 工具调用的参数无法解析", before)
            return ParsedReply.Call(ToolCall(name, el, CallFormat.FENCED), before)
        }

        // 2) Fenced block. ```tool always means a call; ```json / ``` only when the object names a tool.
        var pos = 0
        while (pos < text.length) {
            val m = FENCE_OPEN.find(text, pos) ?: break
            val lang = m.groupValues[1].lowercase()
            val start = (m.range.last + 1).coerceAtMost(text.length)
            val close = text.indexOf("```", start)
            val body = if (close >= 0) text.substring(start, close) else text.substring(start)
            val before = text.substring(0, m.range.first).trim()
            if (lang in TOOL_FENCE_LANGS) return fromObjectText(body, CallFormat.FENCED, before, required = true)
            if (lang == "json" || lang.isEmpty()) {
                val r = fromObjectText(body, CallFormat.FENCED, before, required = false)
                if (r is ParsedReply.Call) return r
            }
            // An ordinary code block: continue after its closing fence (never read it as an opening one).
            if (close < 0) break
            pos = close + 3
        }

// 3) Bare JSON object that is alone on its line.
        val brace = firstLineStartBrace(text)
        if (brace >= 0) {
            val before = text.substring(0, brace).trim()
            val obj = text.substring(brace)
            val r = fromObjectText(obj, CallFormat.PLAIN_JSON, before, required = false)
            if (r is ParsedReply.Call) return r
            // Looks like a call ("tool": … right at the start) but is broken → ask for a retry.
            if (brace == 0 && Regex("^\\{\\s*[\"']?(tool|name)[\"']?\\s*:").containsMatchIn(obj)) {
                return fromObjectText(obj, CallFormat.PLAIN_JSON, before, required = true)
            }
        }
        // 4) 1.10.4: LFM2 native form — tool_call:<name>{"k":v}. The name sits outside the JSON.
        LFM_CALL.find(text)?.let { m ->
            val before = text.substring(0, m.range.first).trim()
            val body = m.groupValues[2]
            val start = body.indexOf('{')
            if (start >= 0) {
                val el = JsonRepair.parse(JsonRepair.extractObject(body, start), json) as? JsonObject
                // 1.10.8: tool_call:system{…} is a role name too — prose, never a call.
                if (m.groupValues[1].lowercase() in ROLE_NAMES) return ParsedReply.Final(reply.trim())
                if (el != null) return ParsedReply.Call(ToolCall(m.groupValues[1], el, CallFormat.FENCED), before)
            }
            return ParsedReply.Malformed("LFM 工具调用的 JSON 无法解析", before)
        }
        // 5) 2.2.5: a registered tool name followed by key/value pairs in any recognised form.
        knownToolCall(text)?.let { (call, start) -> return ParsedReply.Call(call, text.substring(0, start).trim()) }
        // 6) 2.2.5: call markup that could not be read is never the visible answer.
        CALL_MARKUP.find(text)?.let { m ->
            val cut = glmStart(text)?.first?.coerceAtMost(m.range.first) ?: m.range.first
            return ParsedReply.Malformed("工具调用格式无法识别", text.substring(0, cut).trim())
        }
        return ParsedReply.Final(reply.trim())
    }

    /**
     * Index just past the first complete call in a streaming reply, or null. The engine is stopped
     * there: anything after it would be the model inventing the tool's answer.
     */
    fun callEnd(streamed: CharSequence): Int? {
        val s = streamed.toString()
        // 2.2.14: nothing written while thinking is still open ends the turn (calls there are only mentions).
        if (openThinkAt(k2Normalize(s)) >= 0 || s.lastIndexOf("<|channel>") > s.lastIndexOf("<channel|>")) return null
        val noThinkStart = thinkEnd(s).let { e -> if (s.contains("ifm|think")) maxOf(e, k2ThinkEnd(s)) else e }
        // 2.2.13: K2 Horizon `<ifm|tool_call>…</ifm|tool_call>` ends at its own close tag.
        s.indexOf(K2_CALL_OPEN, noThinkStart).let { k ->
            if (k >= 0) {
                val close = s.indexOf(K2_CALL_CLOSE, k + K2_CALL_OPEN.length)
                return if (close >= 0) close + K2_CALL_CLOSE.length else null
            }
        }
        // 2.1.7: inside unfinished thinking the model often *mentions* "<tool_response>" while
        // reading the results ("the <tool_response> shows…"); cutting there ended the turn with
        // an empty answer. Only a complete call ends a turn while thinking is still open.
        val inThink = openThinkAt(s) >= 0
        // 1.10.4: cheap guard — most streamed tokens contain none of the call markers,
        // so skip the full regex scan (the per-token hot path).
        // 1.13.3: Gemma 4 native call ends at <tool_call|>.
        if (s.indexOf("<|tool_call>", noThinkStart) >= 0) GEMMA_OPEN.find(s, noThinkStart)?.let { m ->
            val close = s.indexOf(GEMMA_CLOSE, m.range.last + 1)
            return if (close >= 0) close + GEMMA_CLOSE.length else null
        }
        if (!s.contains("<tool_call>", ignoreCase = true) && s.indexOf("```", noThinkStart) < 0 &&
            s.indexOf("tool_call", noThinkStart, ignoreCase = true) < 0 && s.indexOf("<tool_response", noThinkStart, ignoreCase = true) < 0
        ) return null
        // 1.10.4: LFM2 form ends at the end of its line once a closing brace appeared.
        LFM_CALL.find(s, noThinkStart)?.let { m ->
            if ('}' in m.groupValues[2]) return m.range.last + 1
            return null
        }
        QWEN_OPEN.find(s, noThinkStart)?.let { open ->
            QWEN_CLOSE.find(s, open.range.last + 1)?.let { return it.range.last + 1 }
            return null
        }
        // 2.2.5: GLM call written without its <tool_call> open tag ends at the closing tag.
        if (!inThink) QWEN_CLOSE.find(s, noThinkStart)?.let { return it.range.last + 1 }
        for (m in FENCE_OPEN.findAll(s, noThinkStart)) {
            val lang = m.groupValues[1].lowercase()
            if (lang !in TOOL_FENCE_LANGS) continue
            val close = s.indexOf("```", m.range.last + 1)
            return if (close >= 0) close + 3 else null
        }
        // A hallucinated tool result: cut right before it.
        if (inThink) return null
        val resp = s.indexOf("<tool_response", noThinkStart, ignoreCase = true)
        if (resp >= 0) return resp
        return null
    }

    /** Text to show while streaming: the call block (complete or still being written) is hidden. */
    fun visibleText(raw: String): String {
        var streamed = raw
        thinkEnd(raw).let { f ->
            // 2.2.13: hide a K2 Horizon call section (complete or still being typed) from where it starts.
            val k = raw.indexOf("<ifm|tool_call", f)
            if (k >= 0) streamed = raw.substring(0, k)
            else raw.lastIndexOf('<').let { t -> if (t >= f && t > raw.length - 16 && "<ifm|tool_call".startsWith(raw.substring(t))) streamed = raw.substring(0, t) }
        }
        val from = thinkEnd(streamed)
        var cut = streamed.length
        QWEN_OPEN.find(streamed, from)?.let { cut = minOf(cut, it.range.first) }
        // 2.2.5: GLM / stray call markup (no open tag) is hidden from where the call starts.
        glmStart(streamed, from)?.let { cut = minOf(cut, it.first) }
        CALL_MARKUP.find(streamed, from)?.let { cut = minOf(cut, it.range.first) }
        streamed.indexOf("<|tool_call>", from).let { if (it >= 0) cut = minOf(cut, it) }
        for (m in FENCE_OPEN.findAll(streamed, from)) {
            if (m.groupValues[1].lowercase() in TOOL_FENCE_LANGS) { cut = minOf(cut, m.range.first); break }
        }
        // "```too" still being written at the very end.
        val last = streamed.lastIndexOf("```")
        if (last >= from) {
            val tail = streamed.substring(last + 3)
            if ('\n' !in tail) {
                val lang = tail.trim().lowercase()
                if (lang.isNotEmpty() && TOOL_FENCE_LANGS.any { it.startsWith(lang) }) cut = minOf(cut, last)
            }
        }
        val tag = streamed.lastIndexOf('<')
        if (tag >= from && streamed.substring(tag).lowercase().let { "<tool_call>".startsWith(it) || "<|tool_call>".startsWith(it) }) cut = minOf(cut, tag)
        return com.iris.localai.context.ToolRollup.stripEcho(stripCalls(streamed.substring(0, cut), streaming = true).trimEnd())
    }

    /**
     * 2.1.0: the reply as the user sees it. Every span that reads as a tool call — ```tool / ```json
     * / bare-JSON / `<tool_call>` / Gemma / LFM forms — is removed; the prose around it is kept.
     * With [streaming], a call still being written at the end (an open ```json fence, a `{"tool"`
     * line, a half-typed fence tag) is hidden too. Ordinary code and JSON data stay. Pure.
     */
    fun stripCalls(raw: String, streaming: Boolean = false): String {
        val text = k2Normalize(raw)
        val from = thinkEnd(text)
        val s = text.substring(from)
        val cuts = ArrayList<IntRange>()   // half-open [first, last) as first until last
        val fenced = ArrayList<IntRange>()
        for (m in QWEN_OPEN.findAll(s)) {
            val close = QWEN_CLOSE.find(s, m.range.last + 1)
            cuts += m.range.first until (close?.range?.last?.plus(1) ?: s.length)
        }
        var gi = s.indexOf("<|tool_call>")
        while (gi >= 0) {
            val ce = s.indexOf(GEMMA_CLOSE, gi)
            val end = if (ce >= 0) ce + GEMMA_CLOSE.length else s.length
            cuts += gi until end
            gi = s.indexOf("<|tool_call>", end)
        }
        var pos = 0
        while (pos < s.length) {
            val m = FENCE_OPEN.find(s, pos) ?: break
            if (cuts.any { m.range.first in it }) { pos = m.range.last + 1; continue }
            val lang = m.groupValues[1].lowercase()
            val start = (m.range.last + 1).coerceAtMost(s.length)
            val close = s.indexOf("```", start)
            val end = if (close >= 0) close + 3 else s.length
            val body = s.substring(start, if (close >= 0) close else s.length)
            val open = close < 0
            val call = lang in TOOL_FENCE_LANGS ||
                ((lang == "json" || lang.isEmpty()) && isCallObject(body)) ||
                (streaming && open && (
                    // ```, ```j, ```too … still being typed, nothing in the block yet
                    (body.isBlank() && !m.value.contains('\n') && (lang.isEmpty() || "json".startsWith(lang) || TOOL_FENCE_LANGS.any { it.startsWith(lang) })) ||
                    ((lang == "json" || lang.isEmpty()) && callPrefix(body))))
            if (call) cuts += m.range.first until end else fenced += m.range.first until end
            if (open) break
            pos = end
        }
        // Bare JSON objects alone on their line, outside any code block.
        var ls = 0
        while (ls < s.length) {
            var j = ls
            while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
            val nl = s.indexOf('\n', ls).let { if (it < 0) s.length else it }
            if (j < s.length && s[j] == '{' && cuts.none { j in it } && fenced.none { j in it }) {
                val raw = JsonRepair.extractObject(s, j)
                val closed = s.startsWith(raw, j)
                val end = if (closed) j + raw.length else s.length
                if ((closed || !streaming) && isCallObject(raw)) { cuts += j until end; ls = end; continue }
                if (!closed && streaming && callPrefix(s.substring(j))) { cuts += j until s.length; break }
            }
            ls = nl + 1
        }
        for (m in LFM_CALL.findAll(s)) {
            if (m.groupValues[1].lowercase() in ROLE_NAMES || cuts.any { m.range.first in it }) continue
            cuts += m.range.first until m.range.last + 1
        }
        // 2.2.5: GLM calls and any other call markup outside code blocks: from the call's start to
        // its closing </tool_call> (or the end).
        var gs = 0
        while (gs < s.length) {
            val m = CALL_MARKUP.find(s, gs) ?: break
            if (cuts.any { m.range.first in it } || fenced.any { m.range.first in it }) { gs = m.range.last + 1; continue }
            val start = glmStart(s, gs)?.first?.takeIf { it <= m.range.first && fenced.none { f -> it in f } } ?: m.range.first
            val close = QWEN_CLOSE.find(s, m.range.first + (if (m.value.startsWith("</tool_call", true)) 0 else 1))
            val end = close?.range?.last?.plus(1) ?: s.length
            cuts += start until end
            gs = end
        }
        if (cuts.isEmpty()) return text
        val keep = BooleanArray(s.length) { true }
        cuts.forEach { r -> for (k in r) if (k in keep.indices) keep[k] = false }
        val out = StringBuilder(s.length)
        for (k in s.indices) if (keep[k]) out.append(s[k])
        val body = out.toString().replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return text.substring(0, from) + (if (from > 0 && body.isNotEmpty()) "\n" else "") + body
    }

    /** A complete object that [parse] would execute as a call. */
    private fun isCallObject(body: String): Boolean =
        body.indexOf('{') >= 0 && fromObjectText(body, CallFormat.PLAIN_JSON, "", required = false) is ParsedReply.Call

    private val CALL_HEAD = Regex("^\\{\\s*[\"']?([A-Za-z_]*)([\"']?)\\s*(:?)")
    /** The start of an object that is becoming a call: `{`, `{"to`, `{"tool":` … */
    private fun callPrefix(body: String): Boolean {
        val t = body.trimStart()
        if (t.isEmpty()) return true
        if (!t.startsWith("{")) return false
        val m = CALL_HEAD.find(t) ?: return false
        val key = m.groupValues[1].lowercase()
        if (m.range.last + 1 >= t.length) return NAME_KEYS.any { it.startsWith(key) }
        return m.groupValues[3] == ":" && key in NAME_KEYS
    }

    // ------------------------------------------------------------------ helpers

    /** Index of a `<think>` that is still open (no `</think>` after it), or -1. */
    private fun openThinkAt(s: String): Int {
        val open = s.lastIndexOf("<think>", ignoreCase = true)
        if (open < 0) return -1
        return if (s.indexOf("</think>", open, ignoreCase = true) >= 0) -1 else open
    }

    /**
     * 2.1.7: the canonical block of a complete call written inside thinking that never closed
     * ([callEnd] stops the stream right after it), or null. The answer part of such a turn is
     * empty, so without this the call was neither run nor shown.
     */
    @Deprecated("2.2.14: calls inside thinking are never run")
    fun callInOpenThink(@Suppress("UNUSED_PARAMETER") raw: String): String? = null

    private fun k2ThinkEnd(s: String): Int {
        val m = Regex("</ifm\\|think(?:_fast|_faster)?>").findAll(s).lastOrNull() ?: return 0
        return m.range.last + 1
    }

    /** Where the answer starts: after a closed think block, or 0. An unclosed one hides nothing. */
    private fun thinkEnd(s: String): Int {
        val close = s.lastIndexOf("</think>", ignoreCase = true)
        return if (close >= 0) close + "</think>".length else 0
    }

    private fun firstLineStartBrace(text: String): Int {
        var i = 0
        while (i < text.length) {
            val lineStart = i
            var j = i
            while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
            if (j < text.length && text[j] == '{') return j
            val nl = text.indexOf('\n', lineStart)
            if (nl < 0) return -1
            i = nl + 1
        }
        return -1
    }

    private fun fromObjectText(body: String, format: CallFormat, before: String, required: Boolean): ParsedReply {
        val start = body.indexOf('{')
        if (start < 0) {
            return if (required) ParsedReply.Malformed("没有找到 JSON 对象", before) else ParsedReply.Final(body)
        }
        val raw = JsonRepair.extractObject(body, start)
        val element = JsonRepair.parse(raw, json)
            ?: return if (required) ParsedReply.Malformed("JSON 无法解析：${raw.take(80)}", before) else ParsedReply.Final(body)
        val obj = element as? JsonObject
            ?: return if (required) ParsedReply.Malformed("工具调用必须是 JSON 对象", before) else ParsedReply.Final(body)
        // 1.10.4: {"name":"system"|...} — a role/dialogue JSON the model wrote as prose, never a call.
        val nameRaw = NAME_KEYS.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.trim()?.lowercase()
        if (nameRaw != null && nameRaw in ROLE_NAMES) return ParsedReply.Final((if (before.isEmpty()) body else "$before\n$body").trim())
        return when (val r = toCall(obj, format)) {
            is Result.Ok -> ParsedReply.Call(r.call, before)
            is Result.Err -> if (required) ParsedReply.Malformed(r.message, before) else ParsedReply.Final(body)
        }
    }

    private sealed interface Result {
        data class Ok(val call: ToolCall) : Result
        data class Err(val message: String) : Result
    }

    private fun toCall(obj: JsonObject, format: CallFormat): Result {
        var nameEl: JsonElement? = NAME_KEYS.firstNotNullOfOrNull { obj[it] }
        var argsEl: JsonElement? = ARG_KEYS.firstNotNullOfOrNull { obj[it] }
        // OpenAI style: {"function": {"name": …, "arguments": "…"}}
        (nameEl as? JsonObject)?.let { fn ->
            nameEl = fn["name"]
            if (argsEl == null) argsEl = fn["arguments"] ?: fn["args"]
        }
        val name = (nameEl as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (name.isNullOrEmpty()) return Result.Err("缺少 \"tool\" 字段（工具名）")
        if (!TOOL_NAME.matches(name)) return Result.Err("工具名无效：${name.take(40)}")
        val args: JsonObject = when (val a = argsEl) {
            null, is JsonNull -> {
                // {"tool": "read_file", "path": "a.txt"}: the other keys are the arguments.
                val used = NAME_KEYS.toSet()
                JsonObject(obj.filterKeys { it !in used })
            }
            is JsonObject -> a
            is JsonPrimitive -> if (a.isString) {
                val inner = JsonRepair.parse(a.content.trim().ifEmpty { "{}" }, json)
                inner as? JsonObject ?: return Result.Err("\"args\" 必须是 JSON 对象")
            } else return Result.Err("\"args\" 必须是 JSON 对象")
            else -> return Result.Err("\"args\" 必须是 JSON 对象")
        }
        return Result.Ok(ToolCall(name, args, format))
    }
}

/** Small JSON repairs for what local models actually emit. Pure. */
object JsonRepair {
    /**
     * The balanced `{…}` starting at [start] (quotes and escapes respected). When the text ends
     * first, the open string and brackets are closed so a reply cut off by a token limit still parses.
     */
    fun extractObject(s: String, start: Int): String {
        val stack = ArrayList<Char>()
        var quote: Char? = null
        var esc = false
        var i = start
        while (i < s.length) {
            val ch = s[i]
            if (quote != null) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == quote -> quote = null
                }
            } else when (ch) {
                '"', '\'' -> quote = ch
                '{' -> stack.add('}')
                '[' -> stack.add(']')
                '}', ']' -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    if (stack.isEmpty()) return s.substring(start, i + 1)
                }
            }
            i++
        }
        val sb = StringBuilder(s.substring(start).trimEnd())
        if (quote != null) sb.append(quote)
        for (k in stack.indices.reversed()) sb.append(stack[k])
        return sb.toString()
    }

    /** Parses [raw] as is, then once more after [repair]; null when both fail. */
    fun parse(raw: String, json: Json): JsonElement? {
        // 1.10.8: lenient mode would read “curly” quotes as part of unquoted strings — repair first.
        // 2.1.8: same for 'single quotes' outside "strings": lenient keeps them as part of the value
        // ({"query": '三体'} → `'三体'`), and {'tool': …} must parse too.
        if ('“' in raw || singleQuoteOutside(raw)) runCatching { json.parseToJsonElement(repair(raw)) }.getOrNull()?.let { return it }
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: runCatching { json.parseToJsonElement(repair(raw)) }.getOrNull()
    }

    /** True when a `'` appears outside double-quoted strings. */
    internal fun singleQuoteOutside(s: String): Boolean {
        var inStr = false; var esc = false
        for (ch in s) {
            if (inStr) {
                when { esc -> esc = false; ch == '\\' -> esc = true; ch == '"' -> inStr = false }
            } else if (ch == '"') inStr = true else if (ch == '\'') return true
        }
        return false
    }

    private val LITERAL = Regex("^(?:-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|true|false|null|True|False|None)$")

    /**
     * 2.1.8: a bare (unquoted) value starting at [i] — `诡秘之主 小说` in `"query": 诡秘之主 小说}` —
     * read up to the next `,` or `}` and returned as one JSON string with the index after it.
     * Null when [i] starts a string, object, array, number or true/false/null.
     */
    internal fun bareValue(s: String, i: Int): Pair<String, Int>? {
        if (i >= s.length) return null
        val c = s[i]
        if (c.isWhitespace() || c in "\"'“{[,}]") return null
        var j = i
        while (j < s.length && s[j] != ',' && s[j] != '}') j++
        val raw = s.substring(i, j).trimEnd()
        if (raw.isEmpty() || LITERAL.matches(raw)) return null
        return JsonPrimitive(raw).toString() to (i + raw.length)
    }

    /**
     * A closing `"`: followed (after spaces) by the end or what may follow a string — `,` `}` `]`
     * `:`, the separators [repair] fixes (`;` `=` `：` `，`) or the next `"`.
     */
    private fun closesString(s: String, i: Int): Boolean {
        var j = i + 1
        while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\r' || s[j] == '\n')) j++
        return j >= s.length || s[j] in ",}]:;=：，\""
    }

    /**
     * Raw control characters inside strings → escapes; single-quoted strings → double-quoted;
     * trailing commas dropped; True/False/None → true/false/null (outside strings).
     * 1.10.8: a wrong separator after a key (`"tool"; "x"`, `"tool"= "x"`, full-width `：`) → `:`,
     * and `;` / `，` between members → `,`; “curly quotes” around keys/values → `"`.
     */
    fun repair(s: String): String {
        val out = StringBuilder(s.length + 16)
        var quote: Char? = null
        var esc = false
        var i = 0
        val stack = ArrayList<Char>()
        var expectKey = false     // inside an object, before a key
        var afterKey = false      // a key string just closed; next is its separator
        var expectValue = false   // 2.1.8: right after a key's `:`
        while (i < s.length) {
            val ch = s[i]
            if (quote != null) {
                when {
                    esc -> { esc = false; out.append(ch) }
                    ch == '\\' -> { esc = true; out.append(ch) }
                    // 2.1.8: an unescaped `"` inside a "value" (`""三体" 小说"`) is kept when what
                    // follows cannot come after a string
                    ch == '"' && quote == '"' && !closesString(s, i) -> out.append("\\\"")
                    ch == quote -> {
                        quote = null; out.append('"')
                        if (expectKey && stack.lastOrNull() == '{') { afterKey = true; expectKey = false }
                    }
                    ch == '"' -> out.append("\\\"") // a double quote inside a single-quoted string
                    ch == '\n' -> out.append("\\n")
                    ch == '\r' -> out.append("\\r")
                    ch == '\t' -> out.append("\\t")
                    ch.code < 0x20 -> out.append("\\u%04x".format(ch.code))
                    else -> out.append(ch)
                }
            } else {
                if (!ch.isWhitespace() && afterKey && ch != ':' && ch != ';' && ch != '=' && ch != '：') afterKey = false
                if (expectValue && !ch.isWhitespace()) {
                    expectValue = false
                    val bare = bareValue(s, i)
                    if (bare != null) { out.append(bare.first); i = bare.second; continue }
                }
                when {
                    ch == '"' || ch == '\'' -> { quote = ch; out.append('"') }
                    ch == '“' -> { quote = '”'; out.append('"') }
                    ch == '{' -> { stack.add('{'); expectKey = true; out.append(ch) }
                    ch == '[' -> { stack.add('['); expectKey = false; out.append(ch) }
                    ch == '}' || ch == ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); expectKey = false; out.append(ch) }
                    ch == ':' || ((ch == ';' || ch == '=' || ch == '：') && afterKey) -> {
                        afterKey = false; expectValue = stack.lastOrNull() == '{'; out.append(':')
                    }
                    ch == ';' || ch == '，' -> { expectKey = stack.lastOrNull() == '{'; out.append(',') }
                    ch == ',' -> {
                        expectKey = stack.lastOrNull() == '{'
                        var j = i + 1
                        while (j < s.length && s[j].isWhitespace()) j++
                        if (j < s.length && (s[j] == '}' || s[j] == ']')) Unit else out.append(ch)
                    }
                    s.startsWith("True", i) && boundary(s, i, 4) -> { out.append("true"); i += 3 }
                    s.startsWith("False", i) && boundary(s, i, 5) -> { out.append("false"); i += 4 }
                    s.startsWith("None", i) && boundary(s, i, 4) -> { out.append("null"); i += 3 }
                    else -> out.append(ch)
                }
            }
            i++
        }
        return out.toString()
    }

    private fun boundary(s: String, i: Int, len: Int): Boolean {
        val before = if (i == 0) ' ' else s[i - 1]
        val after = if (i + len >= s.length) ' ' else s[i + len]
        return !before.isLetterOrDigit() && !after.isLetterOrDigit() && before != '_' && after != '_'
    }
}

/** Typed access to tool arguments (models send numbers as strings and vice versa). */
object Args {
    fun str(args: JsonObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
        (args[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }

    fun int(args: JsonObject, vararg keys: String): Int? = str(args, *keys)?.trim()?.toDoubleOrNull()?.toInt()

    fun bool(args: JsonObject, vararg keys: String): Boolean? = str(args, *keys)?.trim()?.lowercase()?.let {
        when (it) { "true", "1", "yes", "y" -> true; "false", "0", "no", "n" -> false; else -> null }
    }

    /** One line for the step card: `path=a.txt, content=(120 字)`. */
    fun summary(args: JsonObject, max: Int = 80): String {
        val s = args.entries.joinToString(", ") { (k, v) ->
            val raw = (v as? JsonPrimitive)?.let { if (it.isString) it.content else it.toString() } ?: v.toString()
            val one = raw.replace('\n', ' ')
            k + "=" + if (one.length > 40) "（${raw.length} 字）" else one
        }
        return if (s.length > max) s.take(max - 1) + "…" else s
    }

    fun primitive(args: JsonObject, key: String): String? = runCatching { args[key]?.jsonPrimitive?.content }.getOrNull()
}
