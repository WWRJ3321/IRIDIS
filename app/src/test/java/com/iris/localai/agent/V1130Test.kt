package com.iris.localai.agent

import com.iris.localai.agent.RunCommandTool
import com.iris.localai.agent.CommandRules
import com.iris.localai.context.ToolRollup
import com.iris.localai.ui.components.MenuGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V1130Test {
    @Test fun menuOpensAboveAnchorNearBottomWithShadowMargin() {
        // window 1000x2000, paperclip at bottom-right, card 300x200 (+ 2*50 margin)
        val p = MenuGeometry.place(900, 1800, 960, 1860, 1000, 2000, 400, 300, 50, 12)
        assertFalse(p.below)
        assertEquals(1800 - 12 - 200 - 50, p.y)   // card bottom 12 px above anchor top
        assertEquals(960 - 300 - 50, p.x)          // card right edge = anchor right
        assertEquals(1f, p.originX); assertEquals(1f, p.originY)
        val q = MenuGeometry.place(20, 100, 80, 160, 1000, 2000, 400, 300, 50, 12)
        assertTrue(q.below); assertEquals(160 + 12 - 50, q.y); assertEquals(20 - 50, q.x)
    }

    @Test fun fakeToolLogIsDetectedAndStripped() {
        val t = "〔本轮已执行的工具〕\n- web_search(query=Anthropic) ✓ 必应 · 10.5 万亿次搜索\n我为您查找了 Anthropic。"
        assertTrue(ToolRollup.hasEcho(t))
        assertEquals("我为您查找了 Anthropic。", ToolRollup.stripEcho(t))
        assertEquals("普通回答", ToolRollup.stripEcho("普通回答"))
    }

    @Test fun refusesQuotesInsideWordsBackslashAndBackground() {
        assertNotNull(RunCommandTool.refusal("x\"args\" cat a"))
        assertNotNull(RunCommandTool.refusal("ev''al ls"))
        assertNotNull(RunCommandTool.refusal("printf 'a\\057b'"))
        assertNotNull(RunCommandTool.refusal("sleep 99 &"))
        assertNull(RunCommandTool.refusal("grep \"a b\" notes.txt"))
        assertNull(RunCommandTool.refusal("ls && echo 'done'"))
    }

    @Test fun writingReadOnlyCommandsAsk() {
        val base = com.iris.localai.agent.core.Permission.ASK
        assertEquals(com.iris.localai.agent.core.Permission.ASK, CommandRules.decide("sort -o out.txt in.txt", base, emptyMap()))
        assertEquals(com.iris.localai.agent.core.Permission.ASK, CommandRules.decide("uniq a.txt b.txt", base, emptyMap()))
        assertEquals(com.iris.localai.agent.core.Permission.ALLOW, CommandRules.decide("sort -r in.txt", base, emptyMap()))
    }
}
