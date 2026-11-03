package com.iris.localai.v232

import com.iris.localai.agent.GenerateImageTool
import com.iris.localai.character.CardCodec
import com.iris.localai.character.CardPrompt
import com.iris.localai.character.CardSpec
import com.iris.localai.character.CharacterCard
import com.iris.localai.character.CharacterStore
import com.iris.localai.character.Lorebook
import com.iris.localai.engine.Backend
import com.iris.localai.engine.GgufQuant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CharacterV232Test {
    private val v2Json = """{"spec":"chara_card_v2","spec_version":"2.0","x_top":"keep-top","data":{"name":"Aki","description":"A {{char}} who greets {{user}}.",
        "personality":"cheerful","scenario":"a café","first_mes":"Hi {{user}}, I'm {{char}}!","mes_example":"<START>\n{{user}}: hello\n{{char}}: hey\n<START>\n{{user}}: bye\n{{char}}: see you",
        "creator_notes":"Line one\nmore","system_prompt":"","post_history_instructions":"Stay brief, {{char}}.","alternate_greetings":["Yo {{user}}","  "],
        "tags":["slice"],"creator":"me","character_version":"1","extensions":{"foo":{"bar":1}},"x_custom":"keep-me",
        "character_book":{"scan_depth":2,"token_budget":50,"entries":[
          {"keys":["moon"],"content":"The moon is pink here.","enabled":true,"insertion_order":2},
          {"keys":["Sun"],"content":"SUN entry","enabled":true,"case_sensitive":true,"insertion_order":1},
          {"keys":["x"],"content":"always","enabled":true,"constant":true,"insertion_order":0},
          {"keys":["moon"],"content":"disabled","enabled":false},
          {"keys":["moon"],"secondary_keys":["night"],"selective":true,"content":"moon at night","enabled":true,"insertion_order":3},
          {"keys":["/dra(g|k)on/i"],"content":"dragons exist","enabled":true,"insertion_order":4}
        ]}}}"""

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        fun i32(v: Int) { out.write(v ushr 24 and 0xff); out.write(v ushr 16 and 0xff); out.write(v ushr 8 and 0xff); out.write(v and 0xff) }
        val t = type.toByteArray(Charsets.ISO_8859_1)
        i32(data.size); out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data); i32(crc.value.toInt())
    }

    /** solidPng with extra tEXt chunks inserted before IEND. */
    private fun pngWith(vararg text: Pair<String, String>): ByteArray {
        val base = CardCodec.solidPng(4)
        val out = ByteArrayOutputStream(); out.write(base, 0, 8)
        for (c in CardCodec.chunks(base)) {
            if (c.type == "IEND") for ((k, v) in text) chunk(out, "tEXt", (k + "\u0000" + v).toByteArray(Charsets.ISO_8859_1))
            chunk(out, c.type, c.data)
        }
        return out.toByteArray()
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test fun pngV2ImportNormalisesToV3AndKeepsUnknownFields() {
        val imp = CardCodec.decode(pngWith("chara" to b64(v2Json)))
        assertEquals("PNG V2", imp.format)
        assertEquals(CardSpec.V3, imp.card.raw["spec"]!!.jsonPrimitive.content)
        assertEquals("Aki", imp.card.name)
        assertEquals("keep-me", imp.card.data["x_custom"]!!.jsonPrimitive.content)
        assertEquals("keep-top", imp.card.raw["x_top"]!!.jsonPrimitive.content)
        assertEquals("1", imp.card.data["extensions"]!!.jsonObject["foo"]!!.jsonObject["bar"]!!.jsonPrimitive.content)
        assertTrue(imp.card.data.containsKey("group_only_greetings"))
        assertNotNull(imp.avatar)
    }

    @Test fun pngPrefersCcv3() {
        val v3 = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Three","group_only_greetings":[]}}"""
        val imp = CardCodec.decode(pngWith("chara" to b64(v2Json), "ccv3" to b64(v3)))
        assertEquals("Three", imp.card.name)
        assertEquals("PNG V3", imp.format)
        // a broken ccv3 falls back to chara
        assertEquals("Aki", CardCodec.decode(pngWith("ccv3" to "!!notbase64", "chara" to b64(v2Json))).card.name)
    }

    @Test fun pngRoundTripWritesBothChunksOnce() {
        val card = CardCodec.decode(pngWith("chara" to b64(v2Json))).card
        val png = CardCodec.encodePng(card, CardCodec.encodePng(card, CardCodec.solidPng(4)))
        val texts = CardCodec.chunks(png).filter { it.type == "tEXt" }.map { String(it.data, Charsets.ISO_8859_1).substringBefore('\u0000') }
        assertEquals(1, texts.count { it == "chara" }); assertEquals(1, texts.count { it == "ccv3" })
        val back = CardCodec.decode(png)
        assertEquals(card.raw, back.card.raw)
        val v2 = CardSpec.json.parseToJsonElement(String(Base64.getDecoder().decode(CardCodec.textChunks(png)["chara"]), Charsets.UTF_8)).jsonObject
        assertEquals(CardSpec.V2, v2["spec"]!!.jsonPrimitive.content)
        assertEquals("keep-me", v2["data"]!!.jsonObject["x_custom"]!!.jsonPrimitive.content)
        assertEquals("IEND", CardCodec.chunks(png).last().type)
    }

    @Test fun jsonV1AndV2AndErrors() {
        val v1 = CardCodec.decode("""{"name":"Old","description":"d","first_mes":"hi"}""".toByteArray())
        assertEquals("Old", v1.card.name); assertEquals("hi", v1.card.firstMes); assertEquals("JSON", v1.format)
        assertEquals("Aki", CardCodec.decode(v2Json.toByteArray(), "a.json").card.name)
        try { CardCodec.decode("""{"hello":1}""".toByteArray()); fail() } catch (e: CardCodec.CardFormatException) { assertEquals(CardCodec.CardFormatException.Kind.NOT_A_CARD, e.kind) }
        try { CardCodec.decode("not json".toByteArray()); fail() } catch (e: CardCodec.CardFormatException) { assertEquals(CardCodec.CardFormatException.Kind.BAD_JSON, e.kind) }
        try { CardCodec.decode(CardCodec.solidPng(4)); fail() } catch (e: CardCodec.CardFormatException) { assertEquals(CardCodec.CardFormatException.Kind.PNG_NO_CARD, e.kind) }
    }

    @Test fun charxImportReadsIconAndKeepsAssets() {
        val icon = CardCodec.solidPng(8)
        val card = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Zed","group_only_greetings":[],"x":"y",
            "assets":[{"type":"icon","uri":"embeded://assets/icon/images/main.png","name":"main","ext":"png"},
                      {"type":"background","uri":"embeded://assets/background/images/bg.png","name":"bg","ext":"png"}]}}"""
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("card.json")); z.write(card.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("assets/icon/images/main.png")); z.write(icon); z.closeEntry()
                z.putNextEntry(ZipEntry("assets/background/images/bg.png")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            }
        }.toByteArray()
        val imp = CardCodec.decode(zip, "zed.charx")
        assertEquals("CHARX", imp.format); assertEquals("Zed", imp.card.name); assertEquals("y", imp.card.data["x"]!!.jsonPrimitive.content)
        assertArrayEquals(icon, imp.avatar)
        assertEquals(setOf("assets/background/images/bg.png"), imp.assets.keys)
    }

    @Test fun macros() {
        val r = java.util.Random(1)
        assertEquals("Aki meets Bo. Bo/Aki", CardPrompt.macros("{{char}} meets {{User}}.{{// hidden}} <USER>/<BOT>", "Aki", "Bo", rnd = r))
        assertEquals("orig!", CardPrompt.macros("{{original}}!", "a", "b", original = "orig"))
        repeat(20) { val n = CardPrompt.macros("{{roll:6}}", "a", "b", rnd = r).toInt(); assertTrue(n in 1..6) }
        val p1 = CardPrompt.macros("{{pick:x,y,z}}", "a", "b", seed = 7); assertEquals(p1, CardPrompt.macros("{{pick:x,y,z}}", "a", "b", seed = 7))
        assertTrue(CardPrompt.macros("{{random:a\\,b,c}}", "a", "b", rnd = r) in setOf("a,b", "c"))
        assertEquals("x\ny", CardPrompt.macros("x{{newline}}y", "a", "b"))
    }

    @Test fun systemPromptAssembly() {
        val card = CardSpec.parse(v2Json)
        val s = CardPrompt.system(card, "Bo", "MY PROMPT")
        assertTrue(s.startsWith(CardPrompt.macros(CardPrompt.DEFAULT_SYSTEM, "Aki", "Bo")))
        assertTrue("A Aki who greets Bo." in s); assertTrue("cheerful" in s); assertTrue("a café" in s)
        assertTrue("Bo: hello\nAki: hey" in s && "Bo: bye" in s)
        assertFalse("MY PROMPT" in s)
        val custom = card.withData("system_prompt" to CharacterCard.text("{{original}} Play {{char}}."))
        assertTrue(CardPrompt.system(custom, "Bo", "MY PROMPT").startsWith("MY PROMPT Play Aki."))
        // example cap drops later blocks
        val capped = CardPrompt.system(card, "Bo", "", maxExampleTokens = CardPrompt.estimate("Bo: hello\nAki: hey"))
        assertTrue("hey" in capped); assertFalse("see you" in capped)
        // nickname wins for {{char}}
        assertTrue("Akiko" in CardPrompt.system(card.withData("nickname" to CharacterCard.text("Akiko")), "Bo", ""))
    }

    @Test fun greetingsAndPostHistory() {
        val card = CardSpec.parse(v2Json)
        assertEquals(listOf("Hi Bo, I'm Aki!", "Yo Bo"), CardPrompt.greetings(card, "Bo"))
        assertEquals("Stay brief, Aki.", CardPrompt.postHistory(card, "Bo"))
    }

    @Test fun lorebookTriggersWithinBudget() {
        val card = CardSpec.parse(v2Json)
        val book = Lorebook.of(card)!!
        assertEquals(2, book.scanDepth); assertEquals(50, book.tokenBudget)
        val hits = book.select(listOf("old sun talk", "look at the MOON", "a dragon flies"), 1000).map { it.content }
        assertEquals(listOf("always", "The moon is pink here.", "dragons exist"), hits) // "sun" case-sensitive miss + out of scan depth; selective needs "night"
        assertTrue("moon at night" in book.select(listOf("moon", "night"), 1000).map { it.content })
        assertTrue("SUN entry" in book.select(listOf("Sun rises"), 1000).map { it.content })
        assertEquals(listOf("always"), book.select(listOf("moon"), CardPrompt.estimate("always")).map { it.content })
        assertEquals(50, CardPrompt.loreBudget(card, 8192)); assertEquals(40, CardPrompt.loreBudget(card, 400))
        val suffix = CardPrompt.turnSuffix(card, "Bo", listOf("the moon"), 1000)
        assertTrue(suffix.startsWith("[World info]\n")); assertTrue("pink" in suffix); assertTrue(suffix.endsWith("[Instruction]\nStay brief, Aki."))
        assertEquals("", CardPrompt.turnSuffix(CharacterCard.blank("n"), "Bo", listOf("x"), 100))
        assertNull(Lorebook.of(CharacterCard.blank("n")))
    }

    @Test fun storeSaveListDelete() {
        val dir = Files.createTempDirectory("chars").toFile()
        val store = CharacterStore({ dir }, java.io.File(dir, "fb"))
        val card = CardSpec.parse(v2Json).withBindings(model = "m.gguf", ttsPack = "kokoro", ttsSpeaker = 3, imageStyle = "anime")
        val id = store.save(card, avatar = CardCodec.solidPng(4), assets = mapOf("assets/x/../../evil.txt" to byteArrayOf(1)))
        assertEquals("Aki", id)
        assertEquals("Aki-1", store.save(card))
        val e = store.get(id)!!
        assertEquals(card.raw, e.card.raw); assertEquals("avatar.png", e.avatar!!.name)
        assertEquals("m.gguf", e.card.bindModel); assertEquals(3, e.card.bindTtsSpeaker); assertEquals("anime", e.card.bindImageStyle)
        assertFalse(java.io.File(dir, "evil.txt").exists())
        assertEquals(2, store.list().size)
        assertTrue(store.delete(id)); assertNull(store.get(id)); assertNull(store.get("../x"))
        assertEquals("", e.card.withBindings(model = "", ttsPack = "", imageStyle = "").bindModel)
        assertEquals("Hello-世界", CharacterStore.slug(" Hello, 世界! "))
        assertEquals("png", CharacterStore.imageExt(CardCodec.solidPng(2)))
    }

    @Test fun imageStylePrefix() {
        val p = GenerateImageTool.parse(kotlinx.serialization.json.JsonObject(mapOf("prompt" to JsonPrimitive("a cat"))))
        assertEquals("anime, a cat", GenerateImageTool.withStyle(p, "anime,").prompt)
        assertEquals("a cat", GenerateImageTool.withStyle(p, " ").prompt)
    }

    /** Minimal GGUF v3: one string KV, one string-array KV, then tensor infos with [types]. */
    private fun gguf(vararg types: Int): ByteArray {
        val o = ByteArrayOutputStream()
        fun u32(v: Long) { for (i in 0 until 4) o.write((v ushr (8 * i)).toInt() and 0xff) }
        fun u64(v: Long) { for (i in 0 until 8) o.write((v ushr (8 * i)).toInt() and 0xff) }
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); o.write(b) }
        o.write("GGUF".toByteArray()); u32(3); u64(types.size.toLong()); u64(2)
        str("general.architecture"); u32(8); str("qwen35")
        str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(2); str("a"); str("b")
        types.forEachIndexed { i, t -> str("t$i"); u32(2); u64(4); u64(4); u32(t.toLong()); u64(0) }
        return o.toByteArray()
    }

    @Test fun ggufQuantDetection() {
        val q2 = GgufQuant.scan(gguf(0, 42, 1).inputStream())!!
        assertTrue(q2.lowBit); assertTrue(q2.unknown.isEmpty()); assertEquals("Q2_0", q2.label)
        val prism = GgufQuant.scan(gguf(0, 143).inputStream())!!
        assertTrue(prism.prism); assertEquals(setOf(143), prism.unknown); assertEquals("PTQ1_0", prism.label)
        val normal = GgufQuant.scan(gguf(12, 14, 0).inputStream())!!
        assertFalse(normal.lowBit); assertTrue(normal.unknown.isEmpty())
        assertNull(GgufQuant.scan("nope".toByteArray().inputStream()))
        assertEquals(listOf(Backend.GPU, Backend.CPU), GgufQuant.chainFor(listOf(Backend.NPU, Backend.GPU, Backend.CPU), q2))
        assertEquals(listOf(Backend.CPU), GgufQuant.chainFor(listOf(Backend.NPU), q2))
        assertEquals(listOf(Backend.NPU, Backend.CPU), GgufQuant.chainFor(listOf(Backend.NPU, Backend.CPU), normal))
    }
}
