package com.iris.localai.v258

import com.iris.localai.ui.markdown.InlineParser
import com.iris.localai.ui.markdown.MarkdownParser
import com.iris.localai.ui.markdown.MdBlock
import com.iris.localai.ui.markdown.MdInline
import com.iris.localai.ui.markdown.TexText
import com.iris.localai.ui.models.ModelGuide
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V258Test {
    private fun src(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }.readText()

    @Test fun texToUnicode() {
        assertEquals("α + β", TexText.render("\\alpha + \\beta"))
        assertEquals("x² + y²", TexText.render("x^2 + y^2"))
        assertEquals("a/b", TexText.render("\\frac{a}{b}"))
        assertEquals("(a+1)/(2b)", TexText.render("\\frac{a+1}{2b}"))
        assertEquals("√x", TexText.render("\\sqrt{x}"))
        assertEquals("xᵢ", TexText.render("x_i"))
        assertEquals("ℝⁿ", TexText.render("\\mathbb{R}^n"))
        assertEquals("E = mc²", TexText.render("E = mc^2"))
        assertEquals("∑ᵢ₌₁ⁿ i", TexText.render("\\sum_{i=1}^{n} i"))
        assertEquals("(x)", TexText.render("\\left( x \\right)").replace(" ", ""))
        assertEquals("面积 = π r²", TexText.render("\\text{面积} = \\pi r^2"))
        // 认不出的命令不吞内容
        assertTrue(TexText.render("\\foo{x}").contains("foo"))
        assertTrue(TexText.render("x^{ab+}").startsWith("x"))
    }

    @Test fun mathParsing() {
        val b = MarkdownParser.parse("前文\n\n$$\n\\frac{1}{2}\n$$\n\n\\[ x^2 \\]\n\n\$\$a+b\$\$")
        val maths = b.filterIsInstance<MdBlock.Math>()
        assertEquals(3, maths.size)
        assertEquals("\\frac{1}{2}", maths[0].tex)
        assertEquals("a+b", maths[2].tex)
        val inl = InlineParser.parse("设 \$x^2\$ 和 \\(y_1\\) 为")
        assertEquals(2, inl.filterIsInstance<MdInline.Math>().size)
        // 金额不当公式
        assertTrue(InlineParser.parse("花了 \$5 和 \$10").none { it is MdInline.Math })
    }

    @Test fun codeFoldAndGuide() {
        val v = src("src/main/java/com/iris/localai/ui/markdown/MarkdownView.kt")
        assertTrue(v.contains("CODE_FOLD_LINES = 40") && v.contains("block.closed && lines > CODE_FOLD_LINES"))
        assertTrue(ModelGuide.sections().size >= 6)
        assertTrue(src("src/main/java/com/iris/localai/ui/MainActivity.kt").contains("Route.MODEL_GUIDE -> com.iris.localai.ui.models.ModelGuidePage"))
    }

    @Test fun imageGenProgress() {
        assertEquals(0.15f, com.iris.localai.ui.agent.ImageGenLive.progressOf("SD 生成中 3/20")!!, 1e-4f)
        assertEquals(1f, com.iris.localai.ui.agent.ImageGenLive.progressOf("FLUX 生成中 20 / 20")!!, 1e-4f)
        assertNull(com.iris.localai.ui.agent.ImageGenLive.progressOf("正在卸载文本模型…"))
        assertNull(com.iris.localai.ui.agent.ImageGenLive.progressOf("x 0/0"))
        assertTrue(src("src/main/java/com/iris/localai/ui/agent/AgentUi.kt").contains("ImageGenLiveCard("))
    }

    @Test fun mcpToggleFix() {
        val m = src("src/main/java/com/iris/localai/agent/mcp/McpManager.kt")
        assertTrue(m.contains("saver.execute") && m.contains("fun setAllTools"))
        val p = src("src/main/java/com/iris/localai/ui/agent/McpPage.kt")
        assertTrue(p.contains("remember(s.tools)") && p.contains("key(t.name)") && p.contains("McpText.ALL_OFF"))
    }

    @Test fun storageInApp() {
        val s = src("src/main/java/com/iris/localai/ui/settings/StoragePage.kt")
        assertTrue(s.contains("R.string.st258_in_app") && s.contains("f.heldBytes > 0"))
        listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi").forEach { d ->
            val x = src("src/main/res/$d/strings.xml")
            listOf("st258_in_app", "st258_held", "mcp258_all_off", "md258_unfold").forEach { k -> assertTrue("$d $k", x.contains("name=\"$k\"")) }
        }
    }
}
