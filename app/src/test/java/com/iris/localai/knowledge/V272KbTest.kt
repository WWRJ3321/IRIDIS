package com.iris.localai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files

/** 2.7.2 D：知识库编码识别、大文件、检索排序；B：向量索引记录嵌入模型。 */
class V272KbTest {
    private val zh = "哈利住在女贞路四号德思礼家楼梯下的储物间里。分院帽是一顶破旧的尖顶巫师帽。"
    private val tw = "哈利住在女貞路四號德思禮家樓梯下的儲物間裡。分院帽是一頂破舊的尖頂巫師帽。"

    private fun tmp(name: String) = Files.createTempDirectory(name).toFile()

    // ---------- 编码 ----------

    @Test fun gbkTxtIsReadable() {
        val t = KbFormats.text("a.txt", (zh.repeat(20)).toByteArray(Charset.forName("GBK")))
        assertNotNull(t); assertTrue(t!!.contains("分院帽"))
        assertEquals(KbText.Enc.GB18030, KbText.detect(zh.toByteArray(Charset.forName("GB18030")).let { it }, zh.toByteArray(Charset.forName("GB18030")).size, false))
    }

    @Test fun bomAndUtf16() {
        val u8bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + zh.toByteArray()
        assertEquals(zh, KbFormats.text("a.txt", u8bom))
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + zh.toByteArray(Charsets.UTF_16LE)
        assertEquals(zh, KbFormats.text("a.txt", le))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + zh.toByteArray(Charsets.UTF_16BE)
        assertEquals(zh, KbFormats.text("a.txt", be))
        // UTF-16 without BOM (mostly ASCII text: every other byte is 0)
        val en = "Harry lived in the cupboard under the stairs at number four."
        assertEquals(en, KbFormats.text("a.txt", en.toByteArray(Charsets.UTF_16LE)))
        assertEquals(en, KbFormats.text("a.txt", en.toByteArray(Charsets.UTF_16BE)))
    }

    @Test fun big5AndUtf8() {
        val b5 = KbFormats.text("a.txt", tw.repeat(10).toByteArray(Charset.forName("Big5")))
        assertNotNull(b5); assertTrue(b5!!.contains("儲物間"))
        assertEquals(zh, KbFormats.text("a.txt", zh.toByteArray()))
        // binary stays unreadable
        assertNull(KbFormats.text("a.txt", ByteArray(64) { if (it % 3 == 0) 0 else 7 }))
    }

    @Test fun strictUtf8Check() {
        assertTrue(KbText.isUtf8("中文 abc".toByteArray()))
        assertFalse(KbText.isUtf8(zh.toByteArray(Charset.forName("GBK"))))
        val c = KbText.Utf8Check(); val b = "中".toByteArray()
        c.feed(b, 0, 2); assertTrue(c.ok); c.feed(b, 2, 1); assertTrue(c.finish())
        assertFalse(KbText.Utf8Check().apply { feed(b, 0, 2) }.finish()) // cut in the middle
    }

    // ---------- 大文件 ----------

    @Test fun streamingChunksGbkFile() {
        val d = tmp("kb272s")
        val f = File(d, "长篇.txt")
        val para = (1..3000).joinToString("\n") { "第${it}段：$zh" }
        f.writeBytes(para.toByteArray(Charset.forName("GB18030")))
        val c = KbText.chunkFile(f)!!
        assertEquals(KbText.Enc.GB18030, c.encoding)
        assertFalse(c.truncated)
        assertTrue(c.chunks.size > 100)
        assertTrue(c.chunks.all { it.length <= KbChunker.SIZE })
        assertTrue(c.chunks.last().contains("第3000段"))
        val cut = KbText.chunkFile(f, maxChars = 10_000)!!
        assertTrue(cut.truncated); assertTrue(cut.chars <= 10_000)
        d.deleteRecursively()
    }

    @Test fun oversizeIsReportedNotSilent() {
        val root = tmp("kb272b")
        val kb = KnowledgeBase({ root }, File(root, "idx/kb.json"))
        assertEquals(KbText.MAX_FILE_BYTES, KbFormats.maxBytes("a.txt", KnowledgeBase.MAX_BYTES))
        assertEquals(KnowledgeBase.MAX_BYTES, KbFormats.maxBytes("a.html", KnowledgeBase.MAX_BYTES))
        // import: streamed, stops at the cap, leaves nothing behind
        val big = object : InputStream() {
            var n = 0L
            override fun read(): Int = if (n++ < KbText.MAX_FILE_BYTES + 10) 'a'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n >= KbText.MAX_FILE_BYTES + 10) return -1
                val k = minOf(len.toLong(), KbText.MAX_FILE_BYTES + 10 - n).toInt(); java.util.Arrays.fill(b, off, off + k, 'a'.code.toByte()); n += k; return k
            }
        }
        val r = kb.addStream("巨大.txt", big)
        assertTrue(r.tooLarge); assertNull(r.path)
        assertFalse(File(kb.dir(), "巨大.txt").exists())
        // a GBK file goes in and gets chunks
        val ok = kb.addStream("书.txt", zh.repeat(30).toByteArray(Charset.forName("GBK")).inputStream())
        assertEquals("书.txt", ok.path)
        // a file already in the folder over the cap: listed in the status
        java.io.RandomAccessFile(File(kb.dir(), "超大.txt"), "rw").use { it.setLength(KbText.MAX_FILE_BYTES + 1) }
        val st = kb.refresh()
        assertEquals(listOf("超大.txt"), st.tooLarge)
        assertTrue(kb.files().first { it.path == "书.txt" }.chunks > 0)
        root.deleteRecursively()
    }

    // ---------- 检索 ----------

    private fun book(vararg topics: String): String {
        val filler = listOf(
            "哈利和罗恩在城堡的走廊里走了很久，一边走一边讨论魁地奇比赛和魔药课的作业。",
            "海格带着他们穿过禁林边上的小路，月光照在湖面上，远处传来猫头鹰的叫声。",
            "礼堂里摆满了长桌，天花板上飘着蜡烛，大家吃着南瓜馅饼聊着天。",
        )
        return topics.mapIndexed { i, t -> t + filler[i % filler.size].repeat(4) }.joinToString("\n\n")
    }

    private fun hpKb(): Pair<KnowledgeBase, File> {
        val root = tmp("kb272hp")
        val kb = KnowledgeBase({ root }, File(root, "idx/kb.json"))
        kb.add("哈利波特与魔法石.txt", book(
            "第一章：女贞路四号的德思礼一家，哈利住在楼梯下的储物间里，后来搬进了达力的第二间卧室。",
            "魁地奇第一场比赛，哈利抓住了金色飞贼。",
            "分院仪式上，分院帽是一顶又旧又脏、打着补丁的尖顶巫师帽，帽檐裂开一道口子像嘴一样唱起歌来，分院帽把哈利分到了格兰芬多。",
            "期末宴会上格兰芬多赢得了学院杯。",
        ).toByteArray(Charset.forName("GBK")))
        kb.add("哈利波特与火焰杯.txt", book(
            "关于三强争霸赛，赫敏描述了很多细节，关于勇士怎么描述的，大家关于这件事讨论了很久，描述得很详细。",
            "关于火焰杯的描述：邓布利多关于规则的描述，大家关于年龄线的描述，关于魔法部的描述。",
            "分院帽今年唱了一首新歌，然后开始分院。",
            "第三个项目在迷宫里进行。",
        ).toByteArray())
        kb.add("哈利波特与密室.txt", book(
            "多比来到女贞路警告哈利不要回学校。",
            "密室的入口在桃金娘的盥洗室里。",
            "汤姆里德尔的日记本记下了五十年前的事。",
        ).toByteArray())
        kb.refresh()
        return kb to root
    }

    @Test fun focusDropsMetaWording() {
        assertEquals(listOf("分院", "院帽"), KbQuery.meaningful(KbQuery.focus("知识库里关于分院帽怎么描述")).distinct())
        assertEquals(listOf("分院帽"), KbQuery.phrases("知识库里关于分院帽怎么描述"))
        assertTrue(KbQuery.focus("押金退还流程").contains("押金退还流程"))
    }

    @Test fun sortingHatFindsTheFirstBook() {
        val (kb, root) = hpKb()
        val q = "知识库里关于分院帽怎么描述"
        val hits = KbChat.hits(kb, q)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.map { it.file }.toString(), hits.any { it.file == "哈利波特与魔法石.txt" && it.text.contains("分院帽") })
        // the chunk that only shares 关于 / 描述 is not picked
        assertTrue(hits.none { it.text.contains("三强争霸赛") })
        // first question of the report still works
        assertEquals("哈利波特与魔法石.txt", KbChat.hits(kb, "哈利波特在德思礼家住哪个房间").first().file)
        root.deleteRecursively()
    }

    @Test fun severalBooksEachGetTheirBest() {
        val (kb, root) = hpKb()
        val hits = kb.search("分院帽", 4)
        val files = hits.map { it.file }
        assertTrue(files.contains("哈利波特与魔法石.txt")); assertTrue(files.contains("哈利波特与火焰杯.txt"))
        // per-file best first: the first two hits come from two different books
        assertNotEquals(hits[0].file, hits[1].file)
        assertTrue(hits.first { it.file == "哈利波特与魔法石.txt" }.cover!!.phraseHit)
        root.deleteRecursively()
    }

    @Test fun diversifyKeepsScoreOrderWithinFiles() {
        val h = listOf(KbHit("a", "1", 9.0, 0), KbHit("a", "2", 8.0, 1), KbHit("b", "3", 5.0, 0), KbHit("a", "4", 4.0, 2))
        assertEquals(listOf("1", "3", "2"), KbRank.diversify(h, 3).map { it.text })
        assertEquals(listOf("1", "2"), KbRank.diversify(h.filter { it.file == "a" }, 2).map { it.text })
        assertTrue(KbRank.diversify(h, 0).isEmpty())
    }

    @Test fun strongKeywordHitSurvivesVectors() {
        val chunks = (0 until 25).map { KbChunk("d$it.md", "t$it", 0) }
        val strong = KbCover(2, 2, 1.0, 0.9, false, false, phraseHit = true)
        val weak = KbCover(2, 2, 0.5, 0.3, false, false)
        // d0: BM25 #1 (whole phrase) but its cosine is outside the vector top 20; d1, d2 are in both lists
        val cos = DoubleArray(25) { if (it == 0) 0.10 else 0.95 - it * 0.01 }
        fun bm(c: KbCover) = listOf(KbHit("d0.md", "t0", 6.0, 0, cover = c), KbHit("d1.md", "t1", 2.0, 0, cover = weak), KbHit("d2.md", "t2", 1.9, 0, cover = weak))
        val out = KbVectors.select(chunks, bm(strong), cos, KbEmbedModels.QWEN3_MIN_COS)
        assertTrue(out.map { it.file }.toString(), out.any { it.file == "d0.md" })
        assertTrue(KbVectors.strongKeyword(bm(strong)[0])); assertFalse(KbVectors.strongKeyword(bm(weak)[0]))
        // without the phrase / high coverage it is judged as before (fused score below 0.6 × best)
        assertTrue(KbVectors.select(chunks, bm(weak), cos, KbEmbedModels.QWEN3_MIN_COS).none { it.file == "d0.md" })
    }

    @Test fun injectNamesTheBook() {
        val block = KbPrompt.inject(listOf(KbHit("书/哈利波特与魔法石.txt", "分院帽唱歌", 1.0, 2), KbHit("火焰杯.txt", "迷宫", 1.0)))
        assertTrue(block.contains("《哈利波特与魔法石》（哈利波特与魔法石.txt · 第 3 段）"))
        assertTrue(block.contains("《火焰杯》（火焰杯.txt）"))
        assertTrue(KbPrompt.toolOutput("q", listOf(KbHit("a.md", "x", 1.0, 0))).contains("《a》"))
    }

    // ---------- B：向量索引记录嵌入模型 ----------

    @Test fun embedStampCompare() {
        val s = EmbedStamp("m", "100-abc", 768)
        assertNull(EmbedStamp.compare(s, EmbedStamp("m", "100-abc", 768)))
        assertNull(EmbedStamp.compare(s, EmbedStamp("m", "100-abc")))      // dimension not known yet
        assertNull(EmbedStamp.compare(EmbedStamp("m", "", 768), EmbedStamp("m", "100-abc"))) // old file without fingerprint
        assertEquals(EmbedStamp.Mismatch.MODEL, EmbedStamp.compare(s, EmbedStamp("n", "100-abc", 768)))
        assertEquals(EmbedStamp.Mismatch.FILE, EmbedStamp.compare(s, EmbedStamp("m", "100-def", 768)))
        assertEquals(EmbedStamp.Mismatch.DIM, EmbedStamp.compare(s, EmbedStamp("m", "100-abc", 256)))
    }

    @Test fun vectorFileCarriesStamp() {
        val d = tmp("kb272v")
        val f = File(d, KbVecStore.FILE)
        KbVecStore("m", 3, "9-aa").apply { put(1L, floatArrayOf(1f, 0f, 0f)) }.write(f)
        assertEquals(EmbedStamp("m", "9-aa", 3), KbVecStore.stamp(f))
        assertEquals(1, KbVecStore.read(f, "m", fingerprint = "9-aa")!!.size)
        assertNull(KbVecStore.read(f, "m", fingerprint = "9-bb"))  // same id, another file → rebuild
        assertNull(KbVecStore.read(f, "m", dim = 4))
        // format 1 (2.5.1–2.7.1, no fingerprint) still loads for the same model
        DataOutputStream(f.outputStream()).use { o ->
            o.writeInt(KbVecStore.MAGIC); o.writeInt(1); o.writeUTF("m"); o.writeInt(2); o.writeInt(1)
            o.writeLong(5L); o.writeFloat(0.5f); o.writeFloat(0.5f)
        }
        assertEquals(EmbedStamp("m", "", 2), KbVecStore.stamp(f))
        val old = KbVecStore.read(f, "m", fingerprint = "9-aa")!!
        assertEquals("9-aa", old.fingerprint) // re-saved as format 2 with the current fingerprint
        assertNull(KbVecStore.read(f, "other"))
        d.deleteRecursively()
    }

    @Test fun modelFingerprintFollowsTheFile() {
        val d = tmp("kb272f")
        val f = File(d, "e.gguf")
        f.writeBytes(ByteArray(3_000_000) { (it % 251).toByte() })
        val a = KbEmbedModels.fingerprint(f)
        assertTrue(a.startsWith("3000000-"))
        assertEquals(a, KbEmbedModels.fingerprint(f))
        f.writeBytes(ByteArray(3_000_000) { (it % 241).toByte() })
        f.setLastModified(f.lastModified() + 2000)
        assertNotEquals(a, KbEmbedModels.fingerprint(f))
        d.deleteRecursively()
    }
}
