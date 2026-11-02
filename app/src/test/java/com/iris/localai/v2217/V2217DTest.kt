package com.iris.localai.v2217

import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.Citations
import com.iris.localai.agent.SearchHit
import com.iris.localai.agent.SearchText
import com.iris.localai.agent.StepStatus
import com.iris.localai.context.CompactMsg
import com.iris.localai.context.CompactPolicy
import com.iris.localai.context.Compactor
import com.iris.localai.context.ContextAssembly
import com.iris.localai.ui.agent.KnowledgeText
import com.iris.localai.ui.chat.VoiceCapsule
import com.iris.localai.util.HintLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2217DTest {
    // ---------------------------------------------------------------- 1 上下文整理
    private fun convo(rounds: Int, chars: Int = 300): List<CompactMsg> = (0 until rounds).flatMap { i ->
        listOf(
            CompactMsg("u$i", CompactMsg.USER, (if (i == 0) "目标：写一个天气小程序。" else "第${i}轮问题。") + "问".repeat(chars)),
            CompactMsg("a$i", CompactMsg.ASSISTANT, "答".repeat(chars),
                steps = if (i == 1) listOf(AgentStep(tool = "read_file", args = "path=a.txt", result = "3 行", status = StepStatus.OK)) else emptyList()),
        )
    }

    @Test fun autoCompactTriggersNearLimitAndKeepsKeyInfo() {
        val ctx = 4096
        val policy = CompactPolicy(ctx, hardBudget = ctx - 512)
        val msgs = convo(8) // ≈ 8 × 2 × 305 ≈ 4900 tokens > window
        val st = Compactor.maybeCompact(msgs, null, fixedTokens = 400, policy = policy, withTools = true)
        assertNotNull(st)
        st!!
        // first goal pinned, tool step summarised, newest round verbatim
        assertTrue(st.goals.first().startsWith("目标：写一个天气小程序"))
        assertTrue(st.tools.any { it.startsWith("read_file(path=a.txt) ✓") })
        val rest = Compactor.remaining(st, msgs)
        assertEquals("u7", rest[rest.size - 2].id)
        // afterwards the prompt fits the window (system + summary + recent)
        assertTrue(Compactor.estimate(400, st, msgs, true) <= policy.trigger)
    }

    @Test fun noCompactionWellBelowLimit() {
        val policy = CompactPolicy(8192, hardBudget = 8192 - 512)
        assertNull(Compactor.maybeCompact(convo(3, 100), null, 300, policy, withTools = false))
    }

    @Test fun triggerLeavesMarginBelowPlannerBudget() {
        val p = CompactPolicy(8192, hardBudget = 8192 - 4096)
        assertTrue(p.trigger < 8192 - 4096)
        assertEquals((8192 - 4096) * 9 / 10, p.trigger)
    }

    @Test fun attachedBlocksCountTowardTheTrigger() {
        val ctx = 4096
        val policy = CompactPolicy(ctx, hardBudget = ctx - 512)
        val msgs = convo(5, 200) // ≈ 2050 tokens: below the trigger alone
        assertNull(Compactor.maybeCompact(msgs, null, 300, policy, withTools = false))
        // each user turn also carries a knowledge / memory block the planner sends
        val blocks = msgs.filter { it.isUser }.associate { it.id to "资".repeat(400) }
        val withBlocks = ContextAssembly.withExtras(msgs, blocks)
        assertTrue(withBlocks.first().extraTokens >= 400)
        assertNotNull(Compactor.maybeCompact(withBlocks, null, 300, policy, withTools = false))
    }

    // ---------------------------------------------------------------- 2 语音输入
    @Test fun holdEndRules() {
        assertEquals(VoiceCapsule.HoldEnd.CANCEL, VoiceCapsule.holdEnd(armed = true, heldMs = 5000))
        assertEquals(VoiceCapsule.HoldEnd.CANCEL, VoiceCapsule.holdEnd(armed = false, heldMs = 100))
        assertEquals(VoiceCapsule.HoldEnd.SEND, VoiceCapsule.holdEnd(armed = false, heldMs = 2000))
    }

    @Test fun onlyTheTrackedFingerEndsTheHold() {
        assertFalse(VoiceCapsule.trackedLifted(trackedPresent = true, trackedPressed = true))
        assertTrue(VoiceCapsule.trackedLifted(trackedPresent = true, trackedPressed = false))
        assertTrue(VoiceCapsule.trackedLifted(trackedPresent = false, trackedPressed = false))
    }

    @Test fun leavingTheButtonOrLayoutShiftIsNotASlide() {
        val d = 3f
        // finger still, capsule moved down 300 px (keyboard closed): not armed
        assertFalse(VoiceCapsule.cancelArmed(40f, 1500f, 340f, 1200f, d))
        // finger 200 px up, far outside the 48 dp capsule: armed
        assertTrue(VoiceCapsule.cancelArmed(40f, 1500f, -160f, 1500f, d))
    }

    // ---------------------------------------------------------------- 3 最近提示
    @Test fun hintLogMergesRepeatsAndCaps() {
        var l = emptyList<HintLog.Hint>()
        l = HintLog.merge(l, "已改用 CPU", HintLog.Source.MODEL, 1000)
        l = HintLog.merge(l, "下载失败", HintLog.Source.DOWNLOAD, 2000)
        l = HintLog.merge(l, "已改用 CPU", HintLog.Source.MODEL, 3000)
        assertEquals(2, l.size)
        assertEquals("已改用 CPU", l[0].text); assertEquals(2, l[0].count)
        repeat(50) { l = HintLog.merge(l, "n$it", HintLog.Source.CHAT, 4000L + it) }
        assertEquals(HintLog.MAX, l.size)
        assertEquals("n49", l[0].text)
    }

    @Test fun recentHintsMeta() {
        val h = HintLog.Hint("x", HintLog.Source.MODEL, 0, 2)
        assertEquals("模型加载 · 3 分钟前 · ×2", com.iris.localai.ui.hints.RecentHints.meta(h, 180_000))
    }

    // ---------------------------------------------------------------- 4 引用链接
    private val hits = listOf(
        SearchHit("苹果发布会", "https://www.example.com/a?x=1", "摘要一", source = "Example", date = "2026-11-01"),
        SearchHit("Second", "https://news.site.org/b", "two"),
    )
    private fun step() = AgentStep(tool = "web_search", status = StepStatus.OK, detail = SearchText.render("q", hits))

    @Test fun sourcesFromSearchSteps() {
        val s = Citations.fromSteps(listOf(step(), step(), AgentStep(tool = "read_file", status = StepStatus.OK, detail = "1. x\n   https://z.com")))
        assertEquals(2, s.size)
        assertEquals("苹果发布会", s[0].title)
        assertEquals("example.com", s[0].domain)
        assertEquals("news.site.org", s[1].domain)
        assertTrue(SearchText.render("q", hits).contains("[1]"))
    }

    @Test fun linkifyInlineMarkers() {
        val s = Citations.fromSteps(listOf(step()))
        val out = Citations.linkify("据报道[1]，另见【2】和[1, 2]。代码 `a[1]` 与 [3] 不变，[链接](https://x.y) 保留。", s)
        assertTrue(out.contains("[¹](https://www.example.com/a?x=1)"))
        assertTrue(out.contains("[²](https://news.site.org/b)"))
        assertTrue(out.contains("`a[1]`"))
        assertTrue(out.contains("[3]"))
        assertTrue(out.contains("[链接](https://x.y)"))
        assertEquals("无来源[1]", Citations.linkify("无来源[1]", emptyList()))
    }

    // ---------------------------------------------------------------- 5 知识库粘贴文字
    @Test fun pastedTitleAndFileName() {
        assertEquals("会议纪要 11月", KnowledgeText.titleFrom("\n\n# 会议纪要 11月\n内容……"))
        assertEquals("粘贴的文字", KnowledgeText.titleFrom("   \n "))
        assertEquals(KnowledgeText.MAX_TITLE, KnowledgeText.titleFrom("长".repeat(100)).length)
        assertEquals("a b c.md", KnowledgeText.fileName("a/b:c"))
        assertEquals("粘贴的文字.md", KnowledgeText.fileName("  "))
    }
}
