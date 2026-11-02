package com.iris.localai.v230

import com.iris.localai.agent.mcp.*
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class McpV230Test {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // ---- JSON-RPC framing ----
    @Test fun requestAndNotificationFraming() {
        val r = obj(JsonRpc.request(7, "tools/list"))
        assertEquals("2.0", r["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(7L, r["id"]!!.jsonPrimitive.long)
        assertEquals("tools/list", r["method"]!!.jsonPrimitive.content)
        assertFalse("params" in r)
        val n = obj(JsonRpc.notification("notifications/initialized"))
        assertFalse("id" in n)
        val i = obj(JsonRpc.request(1, "initialize", JsonRpc.initializeParams("2.3.0")))["params"]!!.jsonObject
        assertEquals(JsonRpc.PROTOCOL_VERSION, i["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("IRIDIS", i["clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun parsesResponsesErrorsAndBatches() {
        val ms = JsonRpc.parse("""[{"jsonrpc":"2.0","method":"notifications/progress","params":{}},{"jsonrpc":"2.0","id":"3","result":{"x":1}},{"jsonrpc":"2.0","id":4,"error":{"code":-32601,"message":"nope"}}]""")
        assertEquals(3, ms.size)
        assertEquals(1, JsonRpc.responseFor(3, ms)!!.result!!.jsonObject["x"]!!.jsonPrimitive.int)
        assertEquals(-32601, JsonRpc.responseFor(4, ms)!!.error!!.code)
        assertNull(JsonRpc.responseFor(9, ms))
        assertTrue(JsonRpc.parse("not json").isEmpty())
    }

    // ---- SSE ----
    @Test fun sseParsing() {
        val p = SseParser()
        val a = p.feed(": comment\r\nevent: endpoint\r\ndata: /messages?s=1\r\n\r\nda")
        assertEquals(listOf(SseParser.Event("endpoint", "/messages?s=1", null)), a)
        val b = p.feed("ta: line1\ndata:line2\nid: 5\n\n")
        assertEquals("message", b.single().event)
        assertEquals("line1\nline2", b.single().data)
        assertEquals("5", p.lastId)
        assertTrue(p.feed("event: x\n\n").isEmpty()) // no data → no event
        p.feed("data: tail")
        assertEquals("tail", p.finish().single().data)
    }

    // ---- names ----
    @Test fun namespacing() {
        assertEquals("mcp_github_create_issue", McpNames.namespaced("GitHub", "create-issue"))
        assertEquals("mcp_x_x", McpNames.namespaced("中文", "工具"))
        val long = McpNames.namespaced("a".repeat(40), "b".repeat(80))
        assertTrue(long.length <= McpNames.MAX && long.matches(Regex("[a-z0-9_]+")))
        val names = McpNames.assign(listOf("s" to "a.b", "s" to "a-b", "s" to "a_b"))
        assertEquals(listOf("mcp_s_a_b", "mcp_s_a_b_2", "mcp_s_a_b_3"), names)
    }

    // ---- schema ----
    private val schema = obj("""{"type":"object","required":["query","opts"],"properties":{
        "limit":{"type":"integer"},"query":{"type":"string","description":"q"},
        "mode":{"type":"string","enum":["fast","full"]},"tags":{"type":["array","null"]},
        "opts":{"type":"object","required":["lang"],"properties":{"lang":{"type":"string"},"safe":{"type":"boolean"}}}}}""")

    @Test fun schemaFlattening() {
        assertEquals("query, opts.lang, limit?:int, mode?:fast|full, tags?:list, opts.safe?:bool", McpSchema.signature(schema))
        assertEquals("", McpSchema.signature(null))
        val line = McpSchema.promptLine("mcp_s_find", schema, "Find things.   " + "x".repeat(200))
        assertTrue(line.startsWith("mcp_s_find(query, "))
        assertTrue(line.substringAfter("): ").length <= McpSchema.DESC_MAX + 1)
        assertEquals("a".repeat(60) + ".", McpSchema.trimDescription("a".repeat(60) + ". " + "y".repeat(120)))
        assertTrue(McpSchema.trimDescription("Find things. " + "y".repeat(120)).endsWith("…"))
    }

    @Test fun unflattenAndCoerce() {
        val args = obj("""{"query":"hi","limit":"5","opts.lang":"zh","opts.safe":"yes","tags":"[\"a\"]","extra.key":"v"}""")
        val out = McpSchema.unflatten(args, schema)
        assertEquals(5L, out["limit"]!!.jsonPrimitive.long)
        assertEquals("zh", out["opts"]!!.jsonObject["lang"]!!.jsonPrimitive.content)
        assertEquals(true, out["opts"]!!.jsonObject["safe"]!!.jsonPrimitive.boolean)
        assertEquals("a", out["tags"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("v", out["extra.key"]!!.jsonPrimitive.content) // unknown dotted key kept as is
    }

    @Test fun resultsHeadersAndCap() {
        val t = McpResult.of(obj("""{"content":[{"type":"text","text":"a"},{"type":"image","mimeType":"image/png","data":"x"}],"isError":true}"""))
        assertFalse(t.ok); assertEquals("a\n[image image/png]", t.text)
        assertEquals(mapOf("X-Key" to "1"), McpHeaders.parse("X-Key: 1\nbad header: 2\nHost: h\nnocolon"))
        assertEquals("Bearer abc", McpHeaders.all("abc", emptyMap())["Authorization"])
        assertEquals("Token abc", McpHeaders.all("Token abc", emptyMap())["Authorization"])
        val pick = McpCap.select((1..25).toList())
        assertEquals(20, pick.injected.size); assertEquals(5, pick.dropped)
    }

    // ---- transports against a local server ----
    private fun reply(id: Long, method: String): String = when (method) {
        "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"t"}}}"""
        "tools/list" -> """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}}"""
        else -> """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"pong"}]}}"""
    }

    @Test fun streamableHttpWithSseAnswer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var auth: String? = null
        srv.createContext("/mcp") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val m = JsonRpc.parse(body).single()
            if (m.id == null) { ex.sendResponseHeaders(202, -1); ex.close(); return@createContext }
            val sse = m.method == "tools/call"
            ex.responseHeaders.add("Mcp-Session-Id", "sess1")
            ex.responseHeaders.add("Content-Type", if (sse) "text/event-stream" else "application/json")
            val out = if (sse) "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: ${reply(m.id!!, m.method!!)}\n\n" else reply(m.id!!, m.method!!)
            val b = out.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        srv.start()
        try {
            val c = McpClient("http://127.0.0.1:${srv.address.port}/mcp", McpHeaders.all("tok", emptyMap()))
            val tools = c.listTools()
            assertEquals(McpClient.Mode.STREAMABLE, c.mode)
            assertEquals("echo", tools.single().name)
            assertEquals("Bearer tok", auth)
            assertEquals("pong", c.callTool("echo", obj("{}")).text)
        } finally { srv.stop(0) }
    }

    @Test fun legacySseFallback() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        srv.executor = java.util.concurrent.Executors.newCachedThreadPool()
        srv.createContext("/sse") { ex ->
            if (ex.requestMethod == "POST") { ex.sendResponseHeaders(405, -1); ex.close(); return@createContext }
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val os = ex.responseBody
            os.write("event: endpoint\ndata: /messages?sid=1\n\n".toByteArray()); os.flush()
            try { while (true) { val s = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: break; os.write("event: message\ndata: $s\n\n".toByteArray()); os.flush() } } catch (_: Exception) {}
            runCatching { os.close() }
        }
        srv.createContext("/messages") { ex ->
            val m = JsonRpc.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).single()
            ex.sendResponseHeaders(202, -1); ex.close()
            if (m.id != null) queue.put(reply(m.id!!, m.method!!))
        }
        srv.start()
        try {
            val c = McpClient("http://127.0.0.1:${srv.address.port}/sse")
            assertEquals(1, c.listTools().size)
            assertEquals(McpClient.Mode.SSE, c.mode)
            assertEquals("pong", c.callTool("echo", obj("{}")).text)
            c.close()
        } finally { srv.stop(0) }
    }
}
