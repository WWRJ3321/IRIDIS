package com.iris.localai.v259

import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.ui.markdown.InlineParser
import com.iris.localai.ui.markdown.MdInline
import com.iris.localai.ui.markdown.TexText
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V259Test {
    private fun src(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }.readText()

    @Test fun mathInBackticksRendersAsMath() {
        listOf("2^n + 3^n = 2^{2m} + 3^{2m}", "(2^m)^2 + (3^m)^2", "k^2", "\\gcd(2^m, 3^m) = 1", "m \\geq 1", "\\gcd(a,b)=1").forEach {
            assertTrue(it, TexText.looksMath(it))
            assertTrue(it, InlineParser.parse("x `$it` y").any { n -> n is MdInline.Math })
        }
        assertEquals("gcd(a,b)=1", TexText.render("\\gcd(a,b)=1"))
        assertEquals("m ≥ 1", TexText.render("m \\geq 1"))
    }

    @Test fun realCodeStaysCode() {
        listOf("x**2", "print(a^b)", "arr[i]", "a == b", "foo.bar", "npm install", "(a,b,c)", "let y = x;", "\"a^b\"").forEach {
            assertFalse(it, TexText.looksMath(it))
        }
        assertTrue(InlineParser.parse("`npm i`").single() is MdInline.Code)
    }

    @Test fun lfmInCatalog() {
        val s = src("src/main/java/com/iris/localai/data/RecommendedCatalog.kt")
        assertTrue(s.contains("LFM2.5-24B-A2B-GSQ-RCO-IQ2_XS.gguf") && s.contains("8_516_829_600L"))
        assertEquals("abenzerps/LFM2.5-24B-A2B-Uncensored-GSQ-RCO-GGUF", RecommendedCatalog.LFM_REPO)
    }

    @Test fun overlayShowsTemp() {
        assertTrue(src("src/main/java/com/iris/localai/ui/chat/PerfOverlay.kt").contains("ThermalGovernor.tempC"))
        assertTrue(src("src/main/res/values/strings.xml").contains("pf259_temp"))
    }
}
