package com.iris.localai.v30

import com.iris.localai.agent.gui.OcrLine
import com.iris.localai.agent.gui.PxRect
import com.iris.localai.agent.gui.UiNode
import com.iris.localai.floating.FloatingLogic
import com.iris.localai.prompts.PromptKey
import com.iris.localai.prompts.Prompts
import com.iris.localai.translate.TransLang
import com.iris.localai.translate.TranslatePrompt
import com.iris.localai.ui.feedback.HapticKind
import com.iris.localai.ui.feedback.HapticRecipe
import com.iris.localai.ui.quips.IdleQuips
import com.iris.localai.ui.toolbox.FindScore
import com.iris.localai.voice.VoiceStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 Beta1：提示词改写、暖手宝安全线、找文件打分、闲言碎语、悬浮窗小工具、振动配方、源码接线。 */
class V300Test {
    private fun src(p: String) = File("src/main/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    @After fun reset() { Prompts.overrides = emptyMap() }

    @Test fun promptDefaultsMatchOldText() {
        assertEquals(VoiceStyle.NOTE, Prompts.get(PromptKey.VOICE_NOTE))
        assertTrue(TranslatePrompt.system(TransLang.ZH).contains("Simplified Chinese"))
        assertEquals(PromptKey.entries.size, PromptKey.entries.map { it.id }.toSet().size)
        PromptKey.entries.forEach { assertTrue(it.id, Prompts.default(it).isNotBlank()) }
    }

    @Test fun promptOverrideAndPlaceholder() {
        Prompts.overrides = mapOf(PromptKey.TRANSLATE_SYSTEM.id to "译成 {lang}，只给译文")
        assertTrue(TranslatePrompt.system(TransLang.ZH).startsWith("译成 "))
        assertFalse(TranslatePrompt.system(TransLang.ZH).contains("{lang}"))
        assertTrue(Prompts.isCustom(PromptKey.TRANSLATE_SYSTEM))
        Prompts.overrides = mapOf(PromptKey.VOICE_NOTE.id to "   ")
        assertEquals(VoiceStyle.NOTE, Prompts.get(PromptKey.VOICE_NOTE))
        assertNull(Prompts.normalize(PromptKey.VOICE_NOTE, "  " + VoiceStyle.NOTE + "\n"))
        assertEquals("x", Prompts.normalize(PromptKey.VOICE_NOTE, " x "))
    }


    @Test fun findScore() {
        val t = FindScore.terms("猫的照片")
        assertTrue("照" in t && "猫的照片" in t)
        val photo = FindScore.keyword(t, "IMG_1.jpg " + FindScore.kindWords("jpg"), "/DCIM")
        val doc = FindScore.keyword(t, "report.pdf " + FindScore.kindWords("pdf"), "/Download")
        assertTrue(photo > doc)
        assertEquals(0.0, FindScore.keyword(emptyList(), "a", "b"), 0.0)
        assertEquals(0.5, FindScore.total(0.5, Double.NaN), 1e-9)
    }

    @Test fun quipsRotateWithoutRepeat() {
        val lines = IdleQuips.lines(IdleQuips.Scene.IMAGE, "zh-CN", 12)
        assertTrue(lines.size >= 5)
        for (seed in 0..50) for (k in 0..20) assertTrue(IdleQuips.at(lines, k, seed) != IdleQuips.at(lines, k + 1, seed))
        assertTrue(IdleQuips.lines(IdleQuips.Scene.LOAD, "en", 2).any { it.contains("late") })
        assertEquals("", IdleQuips.at(emptyList(), 3, 1))
    }

    @Test fun floatingHelpers() {
        val tree = UiNode(children = listOf(
            UiNode(text = "下面", bounds = PxRect(0, 500, 10, 520)),
            UiNode(text = "上面", bounds = PxRect(0, 10, 10, 30)),
            UiNode(text = "密码", password = true, bounds = PxRect(0, 40, 10, 60)),
            UiNode(text = "上面", bounds = PxRect(0, 600, 10, 620)),
        ))
        assertEquals("上面\n下面", FloatingLogic.screenText(tree))
    }

    @Test fun hapticRecipesNonEmpty() {
        HapticKind.entries.forEach { k -> assertTrue(HapticRecipe.of(k).isNotEmpty()); HapticRecipe.of(k).forEach { assertTrue(it.scale in 0f..1f) } }
    }

    @Test fun wiring() {
        val manifest = src("AndroidManifest.xml")
        assertTrue(manifest.contains(".floating.FloatingService"))
        assertTrue(manifest.contains(".widget.QuickWidget"))
        assertTrue(manifest.contains("SYSTEM_ALERT_WINDOW"))
        val main = src("java/com/iris/localai/ui/MainActivity.kt")
        assertTrue(main.contains("Route.TOOLBOX ->") && main.contains("Route.PROMPTS ->"))
        assertTrue(main.contains("publishShortcuts"))
        assertTrue(src("java/com/iris/localai/data/SettingsRepository.kt").contains("Prompts.overrides = next.promptOverrides"))
        assertTrue(src("java/com/iris/localai/agent/core/AgentLoop.kt").contains("PromptKey.AGENT_RULES"))
    }

    /** aapt2 字符串池单串上限 32767：超长 pathData 会被截坏，运行时 PathParser 抛 Unknown command（3.0 Beta1 关于页闪退）。 */
    @org.junit.Test fun vectorPathDataUnderAaptLimit() {
        val re = Regex("pathData=\"([^\"]*)\"")
        java.io.File("app/src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("drawable") }.forEach { f ->
            re.findAll(f.readText()).forEach { m -> org.junit.Assert.assertTrue("${f.path} pathData ${m.groupValues[1].length}", m.groupValues[1].length < 30000) }
        }
    }
}
