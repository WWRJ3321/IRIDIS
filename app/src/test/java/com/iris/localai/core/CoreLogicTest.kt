package com.iris.localai.core

import com.iris.localai.data.AppSettings
import com.iris.localai.data.ModelFiles
import com.iris.localai.data.sanitized
import com.iris.localai.server.HttpServer
import com.iris.localai.util.streaming.SentenceSplitter
import com.iris.localai.util.streaming.SpeechText
import com.iris.localai.util.streaming.StopTokenFilter
import com.iris.localai.util.thinking.StreamingThinkSplitter
import com.iris.localai.util.thinking.ThinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

class ThinkParserRegressionTest {
    @Test fun answerMentioningThinkTagAfterBareClose() {
        // Prefilled reasoning model: "reasoning</think>answer", and the answer talks about "<think>".
        val p = ThinkParser.parse("推理过程</think>答案里提到 <think> 标签")
        assertEquals("推理过程", p.thinking)
        assertEquals("答案里提到 <think> 标签", p.answer)
        assertFalse(p.thinkingOpen)
    }

    @Test fun normalShapesUnchanged() {
        assertEquals("ok", ThinkParser.parse("<think>x</think>ok").answer)
        assertEquals("x", ThinkParser.parse("<think>x</think>ok").thinking)
        assertTrue(ThinkParser.parse("<think>still").thinkingOpen)
        assertEquals("ans", ThinkParser.parse("r</think>ans").answer)
    }
}

class StreamingThinkSplitterRegressionTest {
    @Test fun bareCloseAtStartEmitsNoEmptyPiece() {
        val s = StreamingThinkSplitter()
        val pieces = s.push("</think>答案")
        assertTrue(pieces.none { it.text.isEmpty() })
        assertEquals(listOf(StreamingThinkSplitter.Piece(false, "答案")), pieces)
    }
}

class StopTokenFilterStreamingTest {
    private fun charByChar(text: String): Pair<String, Boolean> {
        val f = StopTokenFilter()
        val out = StringBuilder()
        text.forEach { out.append(f.push(it.toString())) }
        out.append(f.flush())
        return out.toString() to f.stopped
    }

    @Test fun tokenSplitIntoSingleCharacters() {
        val (out, stopped) = charByChar("答案<end_of_turn>垃圾")
        assertEquals("答案", out)
        assertTrue(stopped)
    }

    @Test fun skipTokensAreRemovedNotStopping() {
        val (out, stopped) = charByChar("<bos>你好<s>世界")
        assertEquals("你好世界", out)
        assertFalse(stopped)
    }

    @Test fun unrelatedTagsPassThrough() {
        val (out, stopped) = charByChar("x <b>bold</b> y < z")
        assertEquals("x <b>bold</b> y < z", out)
        assertFalse(stopped)
    }
}

class SentenceSplitterIncrementalTest {
    @Test fun charByCharMatchesWholeText() {
        val text = "第一句。Pi is 3.14 roughly! 第二句，带逗号；还有\n- 列表项。结尾没有标点"
        val whole = SentenceSplitter().let { it.push(text) + it.flush() }
        val streamed = SentenceSplitter().let { s -> text.flatMap { s.push(it.toString()) } + s.flush() }
        assertEquals(whole, streamed)
        assertEquals("结尾没有标点", streamed.last())
    }

    @Test fun trailingDotWaitsForNextChar() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.push("Pi is 3."))
        assertEquals(emptyList<String>(), s.push("14"))
        assertEquals(listOf("Pi is 3.14 ok."), s.push(" ok. "))
    }
}

class SpeechTextTest {
    @Test fun speakableStripsMarkupAndShortTags() {
        assertEquals("你好 世界", SpeechText.speakable("**你好** <|im_end|> 世界"))
        assertEquals("a < b", SpeechText.speakable("a < b"))
        assertEquals("", SpeechText.speakable("```"))
    }
}

class SettingsSanitizeTest {
    @Test fun nanAndOutOfRangeValuesAreRepaired() {
        val d = AppSettings()
        val s = AppSettings(
            temperature = Float.NaN, topP = 5f, minP = -1f, repeatPenalty = Float.NaN,
            ttsSpeed = Float.NaN, maxNewTokens = 1, contextLength = 10, apiPort = 80,
            asrPack = "", ttsPack = " ", lastModelFile = "",
        ).sanitized()
        assertEquals(d.temperature, s.temperature)
        assertEquals(1f, s.topP)
        assertEquals(0f, s.minP)
        assertEquals(d.repeatPenalty, s.repeatPenalty)
        assertEquals(d.ttsSpeed, s.ttsSpeed)
        assertEquals(64, s.maxNewTokens)
        assertEquals(512, s.contextLength)
        assertEquals(1024, s.apiPort)
        assertEquals(d.asrPack, s.asrPack)
        assertEquals(d.ttsPack, s.ttsPack)
        assertEquals(null, s.lastModelFile)
    }

    @Test fun validSettingsAreUntouched() {
        val s = AppSettings(temperature = 0.3f, llamaGpu = true, kvQuant = true)
        assertEquals(s, s.sanitized())
    }
}

class ModelFilesTest {
    @Test fun longNamesKeepTheirExtension() {
        val n = ModelFiles.sanitizeFileName("a".repeat(300) + ".gguf")
        assertEquals(180, n.length)
        assertTrue(n.endsWith(".gguf"))
    }

    @Test fun illegalCharactersAndHiddenNames() {
        assertEquals("a_b_c.gguf", ModelFiles.sanitizeFileName("a:b*c.gguf"))
        assertEquals("x.gguf", ModelFiles.sanitizeFileName("dir/x.gguf"))
        assertEquals("gguf", ModelFiles.sanitizeFileName(".gguf"))
        assertEquals("model", ModelFiles.sanitizeFileName("..."))
    }
}

class HttpServerHardeningTest {
    @Test fun queryWithMalformedEscapesDoesNotThrow() {
        assertEquals(mapOf("a" to "%zz", "b" to "c d"), HttpServer.parseQuery("a=%zz&b=c+d"))
        assertEquals(emptyMap<String, String>(), HttpServer.parseQuery(""))
    }

    private fun raw(port: Int, request: String): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().flush()
            readAll(s.getInputStream())
        }

    private fun readAll(ins: InputStream) = ins.readBytes().decodeToString()

    @Test fun garbageRequestsGetErrorsAndServerSurvives() {
        val port = 20000 + (Math.random() * 2000).toInt()
        val server = HttpServer(InetAddress.getLoopbackAddress(), port) { req, res ->
            res.json(200, """{"q":${HttpServer.jsonString(req.query["a"].orEmpty())}}""")
        }
        server.start()
        try {
            val neg = raw(port, "POST /x HTTP/1.1\r\nContent-Length: -5\r\n\r\n")
            assertTrue(neg, neg.startsWith("HTTP/1.1 400"))
            val bad = raw(port, "GET /x?a=%zz HTTP/1.1\r\n\r\n")
            assertTrue(bad, bad.startsWith("HTTP/1.1 200") && bad.contains("%zz"))
            val nonsense = raw(port, "HELLO\r\n\r\n")
            assertTrue(nonsense, nonsense.startsWith("HTTP/1.1 400"))
            // Still serving after all of the above.
            val ok = raw(port, "GET /x?a=1 HTTP/1.1\r\n\r\n")
            assertTrue(ok, ok.startsWith("HTTP/1.1 200") && ok.contains("\"q\":\"1\""))
        } finally {
            server.stop()
        }
    }
}
