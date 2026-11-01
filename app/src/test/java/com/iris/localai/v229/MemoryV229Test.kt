package com.iris.localai.v229

import com.iris.localai.memory.ItemsEdit
import com.iris.localai.memory.MemoryDocs
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemoryItem.Kind
import com.iris.localai.memory.MemoryItem.Source
import com.iris.localai.memory.MemoryMerge
import com.iris.localai.memory.MemoryMigration
import com.iris.localai.memory.MemoryOps
import com.iris.localai.memory.MemoryStore
import com.iris.localai.memory.MemorySuggest
import com.iris.localai.memory.MemoryTarget
import com.iris.localai.memory.kindFor
import com.iris.localai.memory.kinds
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Role
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MemoryV229Test {
    private fun item(text: String, kind: Kind = Kind.FACT, t: Long = 1) = MemoryItem(text = text, kind = kind, source = Source.USER, createdAt = t)
    private fun ok(r: ItemsEdit) = (r as? ItemsEdit.Ok) ?: throw AssertionError("expected Ok, got $r")
    private fun err(r: ItemsEdit) = (r as? ItemsEdit.Err)?.message ?: throw AssertionError("expected Err, got $r")

    // ---- A: migration ----

    @Test fun migrationKeepsEveryLine() {
        val user = "- 我叫小明\n- 喜欢简短回答\n\n* 住在香港"
        val mem = "- 项目用 Kotlin\n- 手机是骁龙 8 Elite"
        val items = MemoryMigration.fromDocs(user, mem, now = 100)
        assertEquals(listOf("我叫小明", "喜欢简短回答", "住在香港", "项目用 Kotlin", "手机是骁龙 8 Elite"), items.map { it.text })
        assertEquals(listOf(Kind.ABOUT, Kind.ABOUT, Kind.ABOUT, Kind.FACT, Kind.FACT), items.map { it.kind })
        assertTrue(items.all { it.source == Source.MIGRATED })
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    @Test fun storeMigratesOnceAndRenamesToBak() {
        val dir = Files.createTempDirectory("mem").toFile()
        File(dir, "USER.md").writeText("- 我叫小明\n- 住在香港")
        File(dir, "MEMORY.md").writeText("- 项目代号 IRIDIS")
        val docs = MemoryDocs(dir)
        assertEquals(3, docs.store.items().size)
        assertTrue(File(dir, "memory.json").isFile)
        assertTrue(File(dir, "USER.md.bak").isFile && File(dir, "MEMORY.md.bak").isFile)
        assertFalse(File(dir, "USER.md").exists())
        // 2.2.9 part 3: the frozen snapshot is ABOUT + PREF only, newest first, plain labels; FACTs are per turn.
        val snap = docs.snapshot()
        assertTrue(snap, snap.startsWith("【关于用户】\n- 住在香港\n- 我叫小明"))
        assertFalse(snap.contains("项目代号 IRIDIS"))
        assertTrue(docs.turn("IRIDIS 项目代号是什么", 500).contains("项目代号 IRIDIS"))
        // Reloaded from json; no second migration.
        assertEquals(3, MemoryStore(dir).items().size)
        dir.deleteRecursively()
    }

    // ---- A: limits / merge ----

    @Test fun capOf200Items() {
        var items = emptyList<MemoryItem>()
        for (i in 1..200) items = ok(MemoryOps.add(items, Kind.FACT, "事实编号$i 号为唯一值$i", Source.AGENT, now = i.toLong())).items
        assertEquals(200, items.size)
        assertTrue(err(MemoryOps.add(items, Kind.FACT, "再多一条完全不同的事", Source.AGENT)).startsWith("memory is full"))
        assertTrue(err(MemoryOps.add(emptyList(), Kind.FACT, "x".repeat(301), Source.AGENT)).startsWith("entry too long"))
    }

    @Test fun duplicatesMerge() {
        val items = listOf(item("我住在香港", Kind.ABOUT))
        assertEquals("already saved: nothing changed", err(MemoryOps.add(items, Kind.ABOUT, "住在香港。", Source.USER)))
        val d = MemoryMerge.merge(items, item("我住在香港！", t = 9), now = 9)
        assertTrue(d is MemoryMerge.Duplicate)
        assertEquals(9L, d.items.single().updatedAt)
        // A negation is not a duplicate.
        assertFalse(MemoryMerge.sameFact("我喜欢简短回答", "我不喜欢简短回答"))
    }

    @Test fun slotConflictReplaces() {
        val items = listOf(item("我住香港", Kind.ABOUT))
        val r = ok(MemoryOps.add(items, Kind.ABOUT, "我搬到深圳了", Source.USER, now = 5))
        assertEquals(listOf("我搬到深圳了"), r.items.map { it.text })
        assertEquals(items.single().id, r.items.single().id)
        assertEquals("我住香港", r.replaced)
        assertEquals("name", MemoryMerge.slot("我叫小明"))
        assertEquals("name", MemoryMerge.slot("我的名字是阿强"))
        assertEquals("device", MemoryMerge.slot("我用小米手机"))
        assertEquals(MemoryMerge.slot("以后代码注释用中文"), MemoryMerge.slot("以后代码注释用英文"))
        assertNull(MemoryMerge.slot("我在做本地 AI"))
        val rules = ok(MemoryOps.add(listOf(item("以后代码注释用中文", Kind.PREF)), Kind.PREF, "以后代码注释都用英文", Source.USER))
        assertEquals(listOf("以后代码注释都用英文"), rules.items.map { it.text })
    }

    @Test fun agentToolOldCallsStillWork() {
        val dir = Files.createTempDirectory("mem").toFile()
        val docs = MemoryDocs(dir)
        val u = MemoryTarget.parse("user")!!
        val m = MemoryTarget.parse("memory")!!
        assertTrue(ok(docs.edit { MemoryOps.add(it, u.kindFor("我是做安卓的"), "我是做安卓的", Source.AGENT) }).message.startsWith("added"))
        ok(docs.edit { MemoryOps.add(it, m.kindFor("项目代号 IRIDIS"), "项目代号 IRIDIS", Source.AGENT) })
        assertEquals("- 我是做安卓的", docs.read(MemoryTarget.USER))
        assertEquals("- 项目代号 IRIDIS", docs.read(MemoryTarget.MEMORY))
        assertTrue(ok(docs.edit { MemoryOps.replace(it, m.kinds, "IRIDIS", "项目代号 IRIS") }).message.startsWith("replaced"))
        assertEquals("no entry contains \"不存在\"", err(docs.edit { MemoryOps.remove(it, m.kinds, "不存在") }))
        ok(docs.edit { MemoryOps.add(it, Kind.FACT, "项目二用 Rust", Source.AGENT) })
        assertEquals("old text matches 2 entries; quote more of the entry", err(docs.edit { MemoryOps.remove(it, m.kinds, "项目") }))
        assertTrue(ok(docs.edit { MemoryOps.remove(it, m.kinds, "Rust") }).message.startsWith("removed"))
        assertEquals(Kind.PREF, u.kindFor("我喜欢简短的回答"))
        assertEquals(2, MemoryStore(dir).items().size)
        dir.deleteRecursively()
    }

    @Test fun pendingAcceptAndDismiss() {
        val dir = Files.createTempDirectory("mem").toFile()
        val s = MemoryStore(dir)
        assertTrue(s.addPending("以后代码注释都用中文", Kind.PREF))
        assertFalse(s.addPending("以后代码注释都用中文", Kind.PREF))
        ok(s.acceptPending(s.pending().single().id))
        assertTrue(s.pending().isEmpty())
        assertEquals(Source.USER, s.items().single().source)
        assertFalse(s.addPending("以后代码注释都用中文", Kind.PREF))
        s.addPending("我喜欢猫", Kind.PREF); s.dismissPendingText("我喜欢猫")
        assertTrue(s.pending().isEmpty())
        dir.deleteRecursively()
    }

    // ---- B: suggestions ----

    @Test fun suggestPositives() {
        val cases = mapOf(
            "我是做安卓的" to Kind.ABOUT,
            "我住在香港。" to Kind.ABOUT,
            "我叫小明" to Kind.ABOUT,
            "我喜欢简短的回答" to Kind.PREF,
            "我不喜欢太长的解释" to Kind.PREF,
            "以后代码注释用中文" to Kind.PREF,
            "别再用表情符号了" to Kind.PREF,
            "每次都先给结论" to Kind.PREF,
            "我的生日是 7 月 8 日" to Kind.ABOUT,
        )
        for ((t, k) in cases) {
            val s = MemorySuggest.detect(t)
            assertEquals(t, 1, s.size)
            assertEquals(t, k, s.single().kind)
        }
        assertEquals("我是做安卓的", MemorySuggest.detect("好的，谢谢。我是做安卓的。帮我看下这段代码").single().text)
    }

    @Test fun suggestNegatives() {
        listOf(
            "我是做什么的？", "我住在哪里吗", "我是不是说错了", "如果我住在深圳呢", "假如我叫小明", "比如我喜欢猫",
            "他说他住在上海", "我朋友说我喜欢猫", "我有点累", "你好", "我用", "帮我写个脚本", "以后再说",
        ).forEach { assertTrue(it, MemorySuggest.detect(it).isEmpty()) }
        assertTrue(MemorySuggest.detect("我" + "很".repeat(130) + "喜欢猫").isEmpty())
    }

    @Test fun suggestSkipsDuplicatesAndIgnored() {
        assertTrue(MemorySuggest.detect("我住在香港", listOf(item("住在香港", Kind.ABOUT))).isEmpty())
        assertTrue(MemorySuggest.detect("我喜欢猫", ignored = setOf(MemoryMerge.key("我喜欢猫"))).isEmpty())
        assertEquals(1, MemorySuggest.detect("我喜欢猫", ignored = setOf(MemoryMerge.key("我喜欢狗"))).size)
    }

    @Test fun chatMessageFieldIsSerializationCompatible() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"id":"a","role":"ASSISTANT","content":"hi","createdAt":1}"""
        assertNull(json.decodeFromString(ChatMessage.serializer(), old).memorySuggestion)
        val m = ChatMessage(id = "b", role = Role.ASSISTANT, content = "x", memorySuggestion = "我喜欢猫")
        assertEquals("我喜欢猫", json.decodeFromString(ChatMessage.serializer(), json.encodeToString(ChatMessage.serializer(), m)).memorySuggestion)
        assertTrue(com.iris.localai.memory.MemorySettings().suggest)
    }
}
