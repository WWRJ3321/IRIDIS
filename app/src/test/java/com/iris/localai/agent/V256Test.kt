package com.iris.localai.agent

import com.iris.localai.engine.GgufQuant
import com.iris.localai.engine.MoeLoad
import com.iris.localai.util.io.HttpDownload
import com.iris.localai.util.io.ParallelDownload
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.random.Random

class V256Test {
    private fun src(rel: String) = listOf(rel, "../$rel").map { File(it) }.first { it.exists() }.readText()

    @Test fun chunksCoverRange() {
        val c = ParallelDownload.chunks(10, 45, 10)
        assertEquals(listOf(10L until 20L, 20L until 30L, 30L until 40L, 40L until 45L), c)
        assertTrue(ParallelDownload.chunks(45, 45, 10).isEmpty())
        assertEquals(35L, c.sumOf { it.last - it.first + 1 })
    }

    @Test fun shaFromEtagOnlyHex64() {
        val h = "2bdd8a71387f4f673bc5d5058d17b2069da303610ed92cea1ece0642b264f0c5"
        assertEquals(h, HttpDownload.shaFromEtag("\"$h\""))
        assertEquals(h, HttpDownload.shaFromEtag("W/\"${h.uppercase()}\""))
        assertNull(HttpDownload.shaFromEtag("\"d41d8cd98f00b204e9800998ecf8427e\"")) // md5
        assertNull(HttpDownload.shaFromEtag(null))
    }

    private fun serve(data: ByteArray, sha: String?, ranges: Boolean = true): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/f") { ex ->
            val r = ex.requestHeaders.getFirst("Range")
            if (sha != null) ex.responseHeaders.add("X-Linked-Etag", "\"$sha\"")
            val m = r?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (ranges && m != null) {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toIntOrNull() ?: (data.size - 1)
                val body = data.copyOfRange(a, b + 1)
                ex.responseHeaders.add("Content-Range", "bytes $a-$b/${data.size}")
                ex.sendResponseHeaders(206, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { it.write(data) }
            }
        }
        s.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        s.start()
        return s
    }

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun parallelDownloadWithSha() = runBlocking {
        val data = Random(7).nextBytes(5 * 1024 * 1024 + 123)
        val s = serve(data, hex(data))
        try {
            val dir = kotlin.io.path.createTempDirectory().toFile()
            val t = File(dir, "m.gguf")
            // half a chunk already there (resume)
            HttpDownload.partFile(t).writeBytes(data.copyOfRange(0, 1 shl 20))
            var last = 0L
            ParallelDownload.fetch("http://127.0.0.1:${s.address.port}/f", t, data.size.toLong(), { last = it }, threads = 4, chunk = 512 * 1024, minSize = 0)
            assertArrayEquals(data, t.readBytes())
            assertEquals(data.size.toLong(), last)
            assertFalse(HttpDownload.partFile(t).exists())
            assertTrue(dir.listFiles()!!.none { it.name.contains(".part.s") })
        } finally { s.stop(0) }
    }

    @Test fun badShaDeletesPart() = runBlocking {
        val data = Random(3).nextBytes(2 * 1024 * 1024)
        val s = serve(data, "0".repeat(64))
        try {
            val t = File(kotlin.io.path.createTempDirectory().toFile(), "m.gguf")
            try {
                ParallelDownload.fetch("http://127.0.0.1:${s.address.port}/f", t, data.size.toLong(), {}, threads = 3, chunk = 300 * 1024, minSize = 0)
                fail("expected checksum mismatch")
            } catch (e: HttpDownload.ChecksumMismatch) { /* ok */ }
            assertFalse(t.exists())
            assertFalse(HttpDownload.partFile(t).exists())
        } finally { s.stop(0) }
    }

    @Test fun noRangeFallsBackToSingle() = runBlocking {
        val data = Random(5).nextBytes(700_000)
        val s = serve(data, hex(data), ranges = false)
        try {
            val t = File(kotlin.io.path.createTempDirectory().toFile(), "m.gguf")
            ParallelDownload.fetch("http://127.0.0.1:${s.address.port}/f", t, data.size.toLong(), {}, threads = 4, chunk = 100_000, minSize = 0)
            assertArrayEquals(data, t.readBytes())
        } finally { s.stop(0) }
    }

    @Test fun lesAutoOffOnGpu() {
        val q35 = GgufQuant.Info(types = emptySet(), arch = MoeLoad.LES_ARCH, expertCount = 256, expertUsed = 8)
        assertTrue(MoeLoad.lesActive(null, q35, moeActive = true, gpu = false))
        assertFalse(MoeLoad.lesActive(null, q35, moeActive = true, gpu = true))
        assertTrue(MoeLoad.lesActive(true, q35, moeActive = false, gpu = true))
    }


    @Test fun contextChoicesCappedAroundCurrent() {
        val o = com.iris.localai.ui.chat.ContextChoices.options(8192, 32768)
        assertEquals(listOf(2048, 4096, 8192, 16384, 32768), o)
        val big = com.iris.localai.ui.chat.ContextChoices.options(65536, 262144)
        assertEquals(5, big.size); assertTrue(65536 in big); assertTrue(131072 in big)
        assertTrue(com.iris.localai.ui.chat.ContextChoices.options(6000, 0).contains(6000))
    }

    @Test fun ctxScale512k() {
        val sc = com.iris.localai.data.CtxScale
        assertEquals(524288, sc.fromPos(19f, 1024)); assertEquals(512, sc.fromPos(9f, 512))
        assertEquals(8192, sc.fromPos(sc.toPos(8192), 512))
        assertEquals(524288, com.iris.localai.data.ModelOverrides(contextLength = 900000).sanitized().contextLength)
        assertTrue(524288 in com.iris.localai.ui.chat.ContextChoices.options(262144, 0))
    }


    @Test fun agentToolRowsShort() {
        for (t in com.iris.localai.ui.agent.AgentText.listedTools()) assertTrue(t.name, com.iris.localai.ui.agent.AgentText.shortDesc(t).length <= 20)
    }


    @Test fun searchPickerAndCardAreClean() {
        assertEquals(3, com.iris.localai.agent.web.SearchProvider.LISTED.size)
        assertEquals(com.iris.localai.agent.web.SearchProvider.BING_BROWSER, com.iris.localai.agent.web.SearchProvider.shownAs(com.iris.localai.agent.web.SearchProvider.AUTO))
        val d = "Search results for \"x\" (today is 2026-11-14; ...):\n1. A\n   https://a"
        assertEquals("1. A\n   https://a", com.iris.localai.agent.web.SearchText.forPeople("web_search", d))
        val hits = listOf(com.iris.localai.agent.web.SearchHit("old", "u1", "", date = "1 Jan 2025"), com.iris.localai.agent.web.SearchHit("new", "u2", "", date = "14 Nov 2026"))
        assertEquals("new", com.iris.localai.agent.web.SearchText.newestFirst(hits).first().title)
    }
}
