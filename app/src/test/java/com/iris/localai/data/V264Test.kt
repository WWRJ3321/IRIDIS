package com.iris.localai.data

import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.data.UpdateNotes
import com.iris.localai.engine.PromptMessage
import com.iris.localai.server.ApiImages
import com.iris.localai.server.ChatRequestParser
import com.iris.localai.server.HttpError
import com.iris.localai.ui.models.ModelGuide
import com.iris.localai.ui.models.ModelGuideNav
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class V264Test {
    private fun src(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }.readText()
    private fun main(p: String) = src("src/main/java/com/iris/localai/$p")
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String) = json.parseToJsonElement(s) as JsonObject
    private fun status(block: () -> Unit): Int = try { block(); 0 } catch (e: HttpError) { e.status }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val pngUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)


    // ---- 6. 科普页
    @Test fun guideHasSectionsExampleAndGlossary() {
        val s = ModelGuide.sections()
        assertTrue(s.size >= 7)
        assertEquals(s.size, s.map { it.key }.toSet().size)
        assertTrue(s.first { it.key == ModelGuide.NAME }.example.size >= 4)
        assertEquals(ModelGuide.GLOSSARY, ModelGuide.keys().last())
        val g = ModelGuide.glossary()
        assertTrue(g.size >= 25)
        assertEquals(g.map { it.term.lowercase() }.sorted(), g.map { it.term.lowercase() })
        assertEquals(g.size, g.map { it.term.lowercase() }.toSet().size)
        assertFalse(g.any { it.term.startsWith("例") || it.term.startsWith("Example") })
    }

    @Test fun guideTextsAreShort() {
        ModelGuide.sections().flatMap { it.items }.forEach { assertTrue(it.term + ": " + it.text, it.text.length <= 90) }
    }

    @Test fun guideLinksPointToRealCards() {
        ModelGuide.LINKS.forEach { assertNotNull(it, RecommendedCatalog.byId(it)) }
        val used = ModelGuide.sections().flatMap { it.items }.mapNotNull { it.link }
        assertTrue(used.size >= 8)
        used.forEach { assertNotNull(it, ModelGuide.linked(it)) }
        assertNull(ModelGuide.linked("nope/nope"))
    }


    // ---- 7. API 多模态
    @Test fun chatParsesImageUrlParts() {
        val body = """{"messages":[{"role":"user","content":[{"type":"text","text":"这是什么"},{"type":"image_url","image_url":{"url":"$pngUri"}},{"type":"image_url","image_url":"https://x.test/a.jpg"}]}]}"""
        val cr = ChatRequestParser.parse(body, false, json)
        assertEquals("这是什么", cr.messages[0].content)
        assertEquals(listOf(pngUri, "https://x.test/a.jpg"), cr.messages[0].images)
        // Responses-style input_image
        val r = ChatRequestParser.parse("""{"messages":[{"role":"user","content":[{"type":"input_image","image_url":"$pngUri"}]}]}""", false, json)
        assertEquals(1, r.messages[0].images.size)
        // Ollama images
        val o = ChatRequestParser.parse("""{"messages":[{"role":"user","content":"看图","images":["${Base64.getEncoder().encodeToString(png)}"]}]}""", true, json)
        assertEquals(1, o.messages[0].images.size)
        // plain text still works, no images
        assertTrue(ChatRequestParser.parse("""{"messages":[{"role":"user","content":"hi"}]}""", false, json).messages[0].images.isEmpty())
    }

    @Test fun chatRejectsBadImages() {
        assertEquals(400, status { ChatRequestParser.parse("""{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"file:///etc/passwd"}}]}]}""", false, json) })
        assertEquals(400, status { ChatRequestParser.parse("""{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{}}]}]}""", false, json) })
        assertEquals(400, status { ChatRequestParser.parse("""{"messages":[{"role":"user","content":[{"type":"audio","x":1}]}]}""", false, json) })
        val five = (1..5).joinToString(",") { """{"type":"image_url","image_url":{"url":"$pngUri"}}""" }
        assertEquals(400, status { ChatRequestParser.parse("""{"messages":[{"role":"user","content":[$five]}]}""", false, json) })
        // assistant images are ignored rather than sent
        val a = ChatRequestParser.parse("""{"messages":[{"role":"assistant","content":[{"type":"image_url","image_url":{"url":"$pngUri"}}]},{"role":"user","content":"x"}]}""", false, json)
        assertTrue(a.messages.all { it.images.isEmpty() })
    }

    @Test fun decodeDataUriAndBareBase64() {
        assertArrayEquals(png, ApiImages.decode(pngUri))
        assertArrayEquals(png, ApiImages.decode(Base64.getEncoder().encodeToString(png)))
        assertEquals(400, status { ApiImages.decode("data:text/plain;base64,aGVsbG8=") })
        assertEquals(400, status { ApiImages.decode("data:image/png,raw") })
        assertEquals(400, status { ApiImages.decode("data:image/png;base64,aGVsbG8=") }) // not a picture
        assertEquals(400, status { ApiImages.decode("%%%not base64%%%") })
        assertEquals(413, status { ApiImages.decode("A".repeat(ApiImages.MAX_BYTES / 3 * 4 + 100)) })
    }

    @Test fun sniffTypes() {
        assertEquals("png", ApiImages.sniff(png))
        assertEquals("jpg", ApiImages.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("webp", ApiImages.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("gif", ApiImages.sniff("GIF89a".toByteArray()))
        assertNull(ApiImages.sniff("hello".toByteArray()))
        assertTrue(ApiImages.isRemote("HTTPS://a.b/c.png"))
        assertFalse(ApiImages.isRemote("ftp://a"))
    }

    @Test fun onlyNewestImageMessageKeepsPictures() {
        val m = listOf(
            PromptMessage(PromptMessage.USER, "a", listOf("x")),
            PromptMessage(PromptMessage.ASSISTANT, "b"),
            PromptMessage(PromptMessage.USER, "c", listOf("y", "z")),
            PromptMessage(PromptMessage.USER, "d"),
        )
        val k = ApiImages.keepNewest(m)
        assertEquals(listOf(emptyList(), emptyList(), listOf("y", "z"), emptyList<String>()), k.map { it.images })
        assertEquals(m.map { it.content }, k.map { it.content })
        assertFalse(ApiImages.hasImages(ApiImages.keepNewest(listOf(PromptMessage(PromptMessage.USER, "q")))))
    }

    @Test fun imageGenerationsRequest() {
        val p = ApiImages.parseImageGen(obj("""{"prompt":" a cat ","size":"512x768","n":2,"seed":7,"steps":200,"response_format":"url","model":"default"}"""))
        assertEquals("a cat", p.prompt); assertEquals(512, p.width); assertEquals(768, p.height)
        assertEquals(2, p.n); assertEquals(7L, p.seed); assertEquals(100, p.steps); assertEquals("url", p.format); assertNull(p.model)
        val d = ApiImages.parseImageGen(obj("""{"prompt":"x","size":"auto"}"""))
        assertNull(d.width); assertEquals("b64_json", d.format); assertEquals(1, d.n); assertEquals(-1L, d.seed)
        assertEquals(400, status { ApiImages.parseImageGen(obj("""{"size":"512x512"}""")) })
        assertEquals(400, status { ApiImages.parseImageGen(obj("""{"prompt":"x","n":9}""")) })
        assertEquals(400, status { ApiImages.parseImageGen(obj("""{"prompt":"x","size":"big"}""")) })
        assertEquals(400, status { ApiImages.parseImageGen(obj("""{"prompt":"x","size":"10x10"}""")) })
        assertEquals(400, status { ApiImages.parseImageGen(obj("""{"prompt":"x","response_format":"jpeg"}""")) })
    }

    @Test fun speechRequestAndWav() {
        val s = ApiImages.parseSpeech(obj("""{"model":"tts-1","voice":"alloy","input":"你好","speed":3}"""))
        assertEquals("你好", s.input); assertEquals(2f, s.speed, 0f); assertEquals("wav", s.format)
        assertEquals("pcm", ApiImages.parseSpeech(obj("""{"input":"x","response_format":"PCM"}""")).format)
        assertEquals(400, status { ApiImages.parseSpeech(obj("""{"input":""}""")) })
        assertEquals(400, status { ApiImages.parseSpeech(obj("""{"input":"x","response_format":"mp3"}""")) })
        assertEquals(400, status { ApiImages.parseSpeech(obj("""{"input":"x","speed":9}""")) })
        val w = ApiImages.wav(floatArrayOf(0f, 1f, -1f, 2f), 24000)
        assertEquals(44 + 8, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals("data", String(w, 36, 4))
        val bb = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(24000, bb.getInt(24)); assertEquals(8, bb.getInt(40))
        assertEquals(32767.toShort(), bb.getShort(46)); assertEquals((-32767).toShort(), bb.getShort(48)); assertEquals(32767.toShort(), bb.getShort(50))
    }

}
