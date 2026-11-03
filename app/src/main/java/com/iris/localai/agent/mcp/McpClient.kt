package com.iris.localai.agent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class McpException(message: String) : IOException(message)

/** One tool a server offers (input schema kept as JSON text, passed through on calls). */
@kotlinx.serialization.Serializable
data class McpToolInfo(val name: String, val description: String = "", val schema: String = "{}") {
    fun schemaJson(): JsonObject? = runCatching { JsonRpc.json.parseToJsonElement(schema) as? JsonObject }.getOrNull()
}

/**
 * 2.3.0 ②: MCP client for one REMOTE server (no stdio / subprocess on Android).
 * Streamable HTTP first (POST, JSON or SSE answer, Mcp-Session-Id, MCP-Protocol-Version); when
 * initialize is refused with 400/404/405 it falls back to the 2024-11-05 HTTP+SSE transport (GET
 * stream → `endpoint` event → POSTs, answers on the stream). A dropped stream / expired session
 * re-initializes once and retries. Blocking: call off the main thread.
 */
class McpClient(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    private val clientVersion: String = "2.3.0",
) {
    enum class Mode { NONE, STREAMABLE, SSE }

    @Volatile var mode = Mode.NONE; private set
    @Volatile var serverName: String? = null; private set
    private var sessionId: String? = null
    private var protocol: String? = null
    private val ids = AtomicLong(1)

    // legacy SSE transport
    private var sseEndpoint: String? = null
    private var sseConn: HttpURLConnection? = null
    private var sseThread: Thread? = null
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonRpc.Message>>()

    @Synchronized
    fun connect() {
        if (mode != Mode.NONE) return
        val init = JsonRpc.initializeParams(clientVersion)
        val id = ids.getAndIncrement()
        val res = try {
            mode = Mode.STREAMABLE
            postStreamable(JsonRpc.request(id, "initialize", init), id, readTimeoutMs.toLong())
        } catch (e: HttpStatus) {
            mode = Mode.NONE
            if (e.code !in setOf(400, 404, 405)) throw McpException(e.describe())
            openSse()
            mode = Mode.SSE
            try { postSse(JsonRpc.request(id, "initialize", init), id, readTimeoutMs.toLong()) } catch (t: Throwable) { reset(); throw t }
        } catch (t: Throwable) {
            reset(); throw t
        }
        res.error?.let { reset(); throw McpException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7fdb2b, it.message)) }
        val r = res.result as? JsonObject
        protocol = (r?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull?.takeIf { it in JsonRpc.SUPPORTED } ?: JsonRpc.PROTOCOL_VERSION
        serverName = ((r?.get("serverInfo") as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
        notify("notifications/initialized")
    }

    fun listTools(): List<McpToolInfo> {
        val out = ArrayList<McpToolInfo>()
        var cursor: String? = null
        repeat(10) {
            val params = cursor?.let { c -> buildJsonObject { put("cursor", c) } }
            val r = request("tools/list", params) as? JsonObject ?: return out
            (r["tools"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEach { t ->
                val name = (t["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                out += McpToolInfo(name, (t["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (t["inputSchema"] as? JsonObject)?.toString() ?: "{}")
            }
            cursor = (r["nextCursor"] as? JsonPrimitive)?.contentOrNull ?: return out
        }
        return out
    }

    fun callTool(name: String, args: JsonObject, timeoutMs: Long = 60_000): McpResult.Text =
        McpResult.of(request("tools/call", buildJsonObject { put("name", name); put("arguments", args) }, timeoutMs))

    /** A request with one reconnect: dropped stream / expired session / I/O error → initialize again, retry once. */
    fun request(method: String, params: JsonObject? = null, timeoutMs: Long = readTimeoutMs.toLong()): JsonElement? {
        var lastErr: Throwable? = null
        for (attempt in 0..1) {
            try {
                connect()
                val id = ids.getAndIncrement()
                val body = JsonRpc.request(id, method, params)
                val res = if (mode == Mode.SSE) postSse(body, id, timeoutMs) else postStreamable(body, id, timeoutMs)
                res.error?.let { throw McpException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8927af, it.message, it.code)) }
                return res.result
            } catch (e: McpException) {
                throw e
            } catch (e: HttpStatus) {
                if (e.code == 404 && sessionId != null && attempt == 0) { lastErr = e; reset(); continue }
                throw McpException(e.describe())
            } catch (e: IOException) {
                lastErr = e
                if (attempt == 0) { reset(); continue }
            }
        }
        throw McpException(describe(lastErr))
    }

    private fun notify(method: String) {
        val body = JsonRpc.notification(method)
        runCatching { if (mode == Mode.SSE) postRaw(sseEndpoint!!, body) else postStreamable(body, null, readTimeoutMs.toLong()) }
    }

    @Synchronized
    fun close() {
        val sid = sessionId
        if (mode == Mode.STREAMABLE && sid != null) runCatching {
            (open(url, "DELETE")).apply { setRequestProperty("Mcp-Session-Id", sid) }.let { it.responseCode; it.disconnect() }
        }
        reset()
    }

    @Synchronized
    private fun reset() {
        mode = Mode.NONE; sessionId = null; protocol = null; sseEndpoint = null
        runCatching { sseConn?.disconnect() }
        sseThread?.interrupt(); sseConn = null; sseThread = null
        pending.values.forEach { it.completeExceptionally(IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8439c1))) }
        pending.clear()
    }

    // ---------------- Streamable HTTP ----------------

    private class HttpStatus(val code: Int, val body: String) : IOException("HTTP $code") {
        fun describe() = when (code) {
            401, 403 -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_240203, code)
            404 -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_38047b)
            else -> "HTTP $code" + body.take(120).takeIf { it.isNotBlank() }?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ae5443, it) }.orEmpty()
        }
    }

    private fun open(target: String, method: String): HttpURLConnection {
        val c = URL(target).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "IRIDIS-MCP/$clientVersion")
        headers.forEach { (k, v) -> if (McpHeaders.valid(k, v)) c.setRequestProperty(k, v) }
        protocol?.let { c.setRequestProperty("MCP-Protocol-Version", it) }
        sessionId?.let { c.setRequestProperty("Mcp-Session-Id", it) }
        return c
    }

    private fun postStreamable(body: String, id: Long?, timeoutMs: Long): JsonRpc.Message {
        val c = open(url, "POST")
        c.readTimeout = timeoutMs.toInt().coerceAtLeast(1000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            c.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (code !in 200..299) throw HttpStatus(code, runCatching { c.errorStream?.use { readCapped(it.reader()) } }.getOrNull().orEmpty())
            if (id == null) return JsonRpc.Message(null, null, null, null)
            val type = c.contentType.orEmpty().lowercase()
            val deadline = System.currentTimeMillis() + timeoutMs
            return if (type.contains("text/event-stream")) {
                val parser = SseParser()
                InputStreamReader(c.inputStream, Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        if (System.currentTimeMillis() > deadline) throw TimeoutException()
                        val n = r.read(buf)
                        val events = if (n < 0) parser.finish() else parser.feed(String(buf, 0, n))
                        for (ev in events) if (ev.event == "message") JsonRpc.responseFor(id, JsonRpc.parse(ev.data))?.let { return it }
                        if (n < 0) throw McpException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8808ff))
                    }
                    @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
                }
            } else {
                val text = c.inputStream.use { readCapped(it.reader()) }
                JsonRpc.responseFor(id, JsonRpc.parse(text)) ?: throw McpException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6a19f3))
            }
        } catch (e: TimeoutException) {
            throw java.net.SocketTimeoutException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f3831e))
        } finally {
            c.disconnect()
        }
    }

    // ---------------- legacy HTTP+SSE (2024-11-05) ----------------

    private fun openSse() {
        val c = open(url, "GET")
        c.setRequestProperty("Accept", "text/event-stream")
        c.readTimeout = 0 // long-lived stream; per-request timeouts are on the futures
        val code = c.responseCode
        if (code !in 200..299) { c.disconnect(); throw McpException(HttpStatus(code, "").describe()) }
        if (!c.contentType.orEmpty().lowercase().contains("text/event-stream")) { c.disconnect(); throw McpException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_64a237)) }
        sseConn = c
        val endpoint = CompletableFuture<String>()
        val t = Thread({
            val parser = SseParser()
            try {
                InputStreamReader(c.inputStream, Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    while (!Thread.currentThread().isInterrupted) {
                        val n = r.read(buf)
                        if (n < 0) break
                        for (ev in parser.feed(String(buf, 0, n))) when (ev.event) {
                            "endpoint" -> endpoint.complete(resolve(url, ev.data.trim()))
                            "message" -> JsonRpc.parse(ev.data).filter { it.isResponse }.forEach { m -> pending.remove(m.id)?.complete(m) }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                endpoint.completeExceptionally(IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e65b1b)))
                pending.values.forEach { it.completeExceptionally(IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7d5406))) }
                pending.clear()
                if (sseConn === c) mode = Mode.NONE
            }
        }, "mcp-sse")
        t.isDaemon = true
        sseThread = t
        t.start()
        sseEndpoint = try { endpoint.get(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS) } catch (e: Exception) {
            reset(); throw McpException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_94ca41))
        }
    }

    private fun postSse(body: String, id: Long, timeoutMs: Long): JsonRpc.Message {
        val f = CompletableFuture<JsonRpc.Message>()
        pending[id] = f
        try {
            postRaw(sseEndpoint ?: throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_fb0ca6)), body)
            return f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw java.net.SocketTimeoutException(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f3831e))
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4379a7))
        } finally {
            pending.remove(id)
        }
    }

    private fun postRaw(target: String, body: String) {
        val c = open(target, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw HttpStatus(code, "")
        } finally { c.disconnect() }
    }

    companion object {
        private const val MAX_CHARS = 2_000_000

        private fun readCapped(r: java.io.Reader): String {
            val sb = StringBuilder(); val buf = CharArray(8192)
            while (sb.length < MAX_CHARS) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
            return sb.toString()
        }

        fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)

        fun describe(t: Throwable?): String = when (t) {
            null -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0745fc)
            is java.net.SocketTimeoutException -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_279f5b, t.message ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_71a0cc))
            is java.net.UnknownHostException -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ddff6b, t.message)
            is java.net.ConnectException -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_90ac91, t.message)
            is javax.net.ssl.SSLException -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9f1e0c, t.message)
            else -> t.message ?: t.javaClass.simpleName
        }
    }
}

/** Header names/values the user typed: RFC 7230 token names, no CR/LF; parsed from "Name: value" lines. */
object McpHeaders {
    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val RESERVED = setOf("content-type", "accept", "content-length", "host", "mcp-session-id", "mcp-protocol-version")

    fun valid(name: String, value: String) = TOKEN.matches(name) && name.lowercase() !in RESERVED && '\r' !in value && '\n' !in value

    fun parse(text: String): Map<String, String> = text.lines().mapNotNull { l ->
        val i = l.indexOf(':'); if (i <= 0) return@mapNotNull null
        val k = l.substring(0, i).trim(); val v = l.substring(i + 1).trim()
        if (valid(k, v)) k to v else null
    }.toMap()

    fun all(token: String, custom: Map<String, String>): Map<String, String> =
        custom + (token.trim().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to if (it.contains(' ')) it else "Bearer $it") } ?: emptyMap())
}
