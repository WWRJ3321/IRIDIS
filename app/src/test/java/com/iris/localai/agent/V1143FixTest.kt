package com.iris.localai.agent

import com.iris.localai.agent.web.BrowserDriver
import com.iris.localai.agent.web.BrowserTool
import com.iris.localai.agent.tools.HttpWebFetcher
import com.iris.localai.agent.web.HttpWire
import com.iris.localai.agent.web.NetGuard
import com.iris.localai.agent.web.PageSnapshot
import com.iris.localai.agent.web.SafeHttpClient
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.engine.GenOwner
import com.iris.localai.engine.GenerationOwnership
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URL

/**
 * 1.14.3 fixes: (1) web_fetch connects to the address it vetted (no DNS rebinding) and refuses
 * every local / private range; (2) a stop only cancels its own owner's generation.
 * Nothing here touches the network: every resolver is a fake.
 */
class V1143FixTest {

    private fun ip(s: String): InetAddress = NetGuard.literal(s) ?: error("not a literal: $s")
    private fun v6(vararg b: Int) = ByteArray(16) { b.getOrElse(it) { 0 }.toByte() }
    private fun source(path: String): String =
        listOf(path, "app/$path", "../$path").map { File(it) }.first { it.exists() }.readText()

    // ================================================================ 1. NetGuard ranges






    // ================================================================ 1b. the fetcher



    @Test fun wireRequestAndParsing() {
        val req = HttpWire.request(URL("https://例子.example/搜索 a?q=天气"), "xn--fsqu00a.example", 443, true,
            mapOf("User-Agent" to "UA", "Host" to "evil", "X-Bad" to "a\r\nInjected: 1", "Accept-Encoding" to "gzip"))
        assertTrue(req, req.startsWith("GET /%E6%90%9C%E7%B4%A2%20a?q=%E5%A4%A9%E6%B0%94 HTTP/1.1\r\n"))
        assertTrue(req.contains("\r\nHost: xn--fsqu00a.example\r\n"))
        assertFalse(req.contains("evil")); assertFalse(req.contains("Injected")); assertFalse(req.contains("gzip"))
        assertTrue(req.contains("Accept-Encoding: identity\r\n")); assertTrue(req.endsWith("Connection: close\r\n\r\n"))
        assertTrue(HttpWire.request(URL("http://[2606:4700::1]:8080/"), "2606:4700::1", 8080, false, emptyMap()).contains("Host: [2606:4700::1]:8080\r\n"))
        assertEquals("/a%20b/%41", HttpWire.encodeTarget("/a b/%41"))

        fun parse(raw: String, max: Int = 1 shl 20): Pair<Int, String> {
            val input = ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1))
            val head = HttpWire.readHead(input)
            return head.code to String(HttpWire.readBody(input, head, max), Charsets.ISO_8859_1)
        }
        assertEquals(200 to "hello", parse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello world"))
        assertEquals(200 to "Wikipedia in\r\nchunks.", parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n6;ext=1\r\npedia \r\nB\r\nin\r\nchunks.\r\n0\r\n\r\n"))
        assertEquals(404 to "gone", parse("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 404 Not Found\r\n\r\ngone"))
        assertEquals(200 to "abc", parse("HTTP/1.0 200 OK\r\n\r\nabcdef", max = 3))
        assertEquals(200 to "Wik", parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\n\r\n", max = 3))
        try { parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n\r\n"); fail() } catch (_: IOException) {}
        try { parse("SSH-2.0-OpenSSH\r\n\r\n"); fail() } catch (_: IOException) {}
    }

    // ================================================================ 1c. the browser tool

    private class FakeBrowser : BrowserDriver {
        val opened = mutableListOf<String>()
        private fun snap() = PageSnapshot("P", "https://p", listOf("[1] button \"Go\""))
        override fun open(url: String) = snap().also { opened += url }
        override fun snapshot() = snap()
        override fun click(id: Int) = snap()
        override fun type(id: Int, text: String, submit: Boolean) = snap()
        override fun back() = snap()
    }

    @Test fun browserToolRefusesLocalTargetsWithoutDns() {
        val dir = kotlin.io.path.createTempDirectory("v1143").toFile()
        val b = FakeBrowser()
        val env = ToolEnv(WorkspaceSandbox(dir), UndoStore(File(dir, "u")), browser = b)
        for (u in listOf("http://127.0.0.1:8080/v1/models", "http://localhost/", "http://192.168.1.1/", "http://[::1]/", "http://100.64.0.1/")) {
            val r = BrowserTool.run(buildJsonObject { put("action", "open"); put("url", u) }, env)
            assertFalse(u, r.ok)
            assertEquals(NetGuard.REFUSED, r.summary)
        }
        assertTrue(b.opened.isEmpty())
        // a public name is passed on (the real WebViewBrowser resolves and checks it); still offline here
        assertTrue(BrowserTool.run(buildJsonObject { put("action", "open"); put("url", "bing.com") }, env).ok)
        assertEquals(listOf("https://bing.com"), b.opened)
    }

    // ================================================================ 2. cancel ownership

    @Test fun cancelOnlyStopsItsOwnersGeneration() {
        var stops = 0
        val g = GenerationOwnership { stops++ }
        val chat = GenOwner("chat"); val api = GenOwner("API")
        // nothing running: a per-owner cancel does nothing (never touches the engine)
        assertFalse(g.cancel(chat)); assertEquals(0, stops)
        // an API generation runs: chat's 停止 leaves it alone
        val apiRun = g.begin(api)
        assertSame(api, g.currentOwner)
        assertFalse(g.cancel(chat)); assertEquals(0, stops); assertFalse(apiRun.stopRequested)
        // the API's own cancel stops it
        assertTrue(g.cancel(api)); assertEquals(1, stops); assertTrue(apiRun.stopRequested)
        g.end(apiRun); assertNull(g.currentOwner)
        // a chat generation runs: an API-side cancel does nothing, chat's own does
        val chatRun = g.begin(chat)
        assertFalse(g.cancel(api)); assertEquals(1, stops); assertFalse(chatRun.stopRequested)
        assertTrue(g.cancel(chat)); assertEquals(2, stops); assertTrue(chatRun.stopRequested)
        g.end(chatRun)
        // an owner reused for the next generation starts clean (a cancel is not sticky)
        val next = g.begin(chat)
        assertFalse(next.stopRequested)
        // a late end() of an older run does not clear the newer one
        g.end(chatRun); assertSame(chat, g.currentOwner)
        g.end(next)
        assertFalse(g.cancel(chat)); assertEquals(2, stops)
    }

    @Test fun cancelAllStopsWhateverRuns() {
        var stops = 0
        val g = GenerationOwnership { stops++ }
        val api = GenOwner("API")
        val run = g.begin(api)
        assertSame(api, g.cancelAll()); assertEquals(1, stops); assertTrue(run.stopRequested)
        g.end(run)
        // with nothing registered it still asks the engine to stop (old global behaviour for load / unload)
        assertNull(g.cancelAll()); assertEquals(2, stops)
        // a throwing engine cancel never escapes
        val bad = GenerationOwnership { throw IllegalStateException("native") }
        val o = GenOwner("chat"); bad.begin(o)
        assertTrue(bad.cancel(o)); assertSame(o, bad.cancelAll())
    }

}
