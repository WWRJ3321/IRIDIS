package com.iris.localai.v30

import com.iris.localai.context.ContextAssembly
import com.iris.localai.engine.PromptMessage
import com.iris.localai.floating.FloatingLogic
import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageModel
import com.iris.localai.imagegen.ImageRequest
import com.iris.localai.imagegen.ImageSizes
import com.iris.localai.imagegen.LoraArch
import com.iris.localai.imagegen.PromptOrder
import com.iris.localai.imagegen.Tier
import com.iris.localai.translate.HyMt
import com.iris.localai.translate.TransLang
import com.iris.localai.translate.TranslatePrompt
import com.iris.localai.translate.TranslateStop
import com.iris.localai.ui.chat.ThinkToggle
import com.iris.localai.ui.chat.TurnAgent
import com.iris.localai.ui.imagegen.ImageZoom
import com.iris.localai.ui.island.IslandLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0.0 Beta3 r5：思考开关、手机指令临时工具、混元翻译收尾、屏幕翻译去重/分批/防抖、生图尺寸、提示词整理、看图缩放。 */
class V300B3R5Test {

    // ---- 2. 思考开关 ----

    /** 模拟「全局 + 模型自己的设置」两处存储，按 setDeepThinking 的写法走。 */
    private class Store(var global: Boolean, var model: Boolean?) {
        fun toggle(on: Boolean) { val w = ThinkToggle.write(on, model); global = w.global; if (w.model != null) model = w.model }
        val shown get() = ThinkToggle.shown(global, model)
    }

    @Test fun thinkCanTurnOffAndOnAgain() {
        // 模型自己设了「开」：关掉后不再被模型设置盖回去
        val s = Store(global = true, model = true)
        s.toggle(false); assertFalse(s.shown); assertFalse(s.global); assertEquals(false, s.model)
        s.toggle(true); assertTrue(s.shown)
        s.toggle(false); assertFalse(s.shown)
        // 跟随全局的模型
        val g = Store(global = true, model = null)
        g.toggle(false); assertFalse(g.shown); assertNull(g.model)
        g.toggle(true); assertTrue(g.shown)
    }

    @Test fun thinkOffBeforeLoadSurvivesLoad() {
        // 没加载模型（启动不预加载）：目标是上次用的模型；关掉后它加载完仍是关
        val target = ThinkToggle.targetId(ready = null, loading = null, failed = null, last = "Qwen3-4B-Q4_K_M.gguf")
        assertEquals("Qwen3-4B-Q4_K_M.gguf", target)
        val s = Store(global = true, model = true)
        s.toggle(false)
        assertFalse(ThinkToggle.shown(s.global, s.model))
        assertEquals("a", ThinkToggle.targetId("a", "b", "c", "d"))
        assertEquals("b", ThinkToggle.targetId(null, "b", "c", "d"))
        assertNull(ThinkToggle.targetId(null, null, null, " "))
    }

    @Test fun longPressReleaseWithoutSlideKeepsSwitch() {
        assertNull(ThinkToggle.onRelease(moved = false, level = 2))
        assertEquals(3, ThinkToggle.onRelease(moved = true, level = 3))
        assertEquals(1, ThinkToggle.onRelease(moved = true, level = 0))
    }

    @Test fun thinkButtonEnabled() {
        assertTrue(ThinkToggle.enabled(ready = false, supports = null, thinkAlways = false))
        assertTrue(ThinkToggle.enabled(true, true, false))
        assertTrue(ThinkToggle.enabled(true, false, true))
        assertFalse(ThinkToggle.enabled(true, false, false))
        assertFalse(ThinkToggle.enabled(true, null, false))
    }

    // ---- 3. 手机指令不再永久打开智能体模式 ----

    @Test fun phoneCommandIsOneTurnOnly() {
        var agentMode = false
        // 第一句「打开手电筒」：本轮临时带 phone
        val t1 = TurnAgent.oneShotTools(agentMode, phoneCommand = true, phoneEnabled = true, hasAttachOrRefs = false)
        assertEquals(setOf("phone"), t1)
        assertTrue(TurnAgent.viaAgent(agentMode, guiTurn = false, oneShot = t1))
        assertEquals(listOf("phone"), TurnAgent.tools(listOf("read_file", "phone", "web_search"), { it }, agentMode, t1))
        // 智能体模式没被改
        assertFalse(agentMode)
        // 第二句普通对话：不走智能体（提示里不带工具）
        val t2 = TurnAgent.oneShotTools(agentMode, phoneCommand = false, phoneEnabled = true, hasAttachOrRefs = false)
        assertNull(t2)
        assertFalse(TurnAgent.viaAgent(agentMode, false, t2))
        // 其他情况不临时开
        assertNull(TurnAgent.oneShotTools(true, true, true, false))
        assertNull(TurnAgent.oneShotTools(false, true, false, false))
        assertNull(TurnAgent.oneShotTools(false, true, true, true))
        // 智能体模式开着：全部工具照旧
        assertEquals(3, TurnAgent.tools(listOf("a", "b", "phone"), { it }, true, null).size)
        agentMode = true
        assertTrue(TurnAgent.viaAgent(agentMode, false, null))
    }

    @Test fun r4AutoAgentModeResetOnce() {
        assertEquals(false, TurnAgent.migrateAgentMode(agentOn = true, migrated = false))
        assertNull(TurnAgent.migrateAgentMode(agentOn = true, migrated = true))
        assertNull(TurnAgent.migrateAgentMode(agentOn = false, migrated = false))
    }

    @Test fun plainChatSystemHasNoToolSchema() {
        val sys = ContextAssembly.system("你是 IRIDIS。", "")
        assertFalse(sys.contains("# Tool calls"))
        assertFalse(sys.contains("<tool_call>"))
        assertFalse(sys.contains("phone("))
    }

    // ---- 4. 混元翻译 ----

    @Test fun hyMtUsesOfficialTemplate() {
        assertTrue(HyMt.matches("HY-MT1.5-1.8B-Q4_K_M.gguf"))
        assertTrue(HyMt.matches("Hy-MT2-1.8B-Q8_0.gguf"))
        assertTrue(HyMt.matches("Hunyuan-MT-7B.gguf"))
        assertFalse(HyMt.matches("Qwen3-4B-Q4_K_M.gguf"))
        assertFalse(HyMt.matches(null))
        val m = TranslatePrompt.messages("你好，世界", TransLang.EN, "Hy-MT2-1.8B-Q4_K_M.gguf")
        assertEquals(1, m.size)
        assertEquals(PromptMessage.USER, m[0].role)
        assertTrue(m[0].content.startsWith("将以下文本翻译为英语，注意只需要输出翻译后的结果，不要额外解释："))
        assertTrue(m[0].content.endsWith("\n\n你好，世界"))
        assertTrue(TranslatePrompt.messages("hello", TransLang.ZH, "hy-mt1.5").single().content.contains("翻译为中文"))
        // 其他模型仍是 system + user
        assertEquals(2, TranslatePrompt.messages("hello", TransLang.ZH, "Qwen3").size)
        assertEquals(0.6f, HyMt.sampling("Hy-MT2-1.8B").topP, 0.001f)
        assertEquals(20, HyMt.sampling("Hy-MT2-1.8B").topK)
        assertEquals(0.2f, HyMt.sampling("Qwen3").temperature, 0.001f)
    }

    @Test fun translationStopsAtMarkersAndRepeats() {
        val a = TranslateStop.finish("Hello world.<|hy_place▁holder▁no▁2|>Hello world.", "你好世界。")
        assertEquals("Hello world.", a.text); assertTrue(a.stop)
        val b = TranslateStop.finish("Hello world. Translate the following segment into English", "你好世界。")
        assertEquals("Hello world.", b.text); assertTrue(b.stop)
        val c = TranslateStop.finish("Good morning. How are you? Good morning. How are you?", "早上好。你好吗？")
        assertEquals("Good morning. How are you?", c.text); assertTrue(c.stop)
        val d = TranslateStop.finish("好的好的好的好的好的好的", "OK")
        assertEquals("好的", d.text); assertTrue(d.stop)
        // 正常译文不截、不停
        val e = TranslateStop.finish("The weather is nice today.", "今天天气很好。")
        assertEquals("The weather is nice today.", e.text); assertFalse(e.stop)
        // 原文本身就有重复行：不当复读截
        val f = TranslateStop.cutSentenceRepeat("Save\nSave\n", "保存\n保存\n")
        assertFalse(f.stop)
        val g = TranslateStop.cutSentenceRepeat("Settings saved.\nSettings saved.\n", "设置已保存。\n设置已保存。\n")
        assertFalse(g.stop)
    }

    @Test fun overlayDedupeChunkAlign() {
        val (uniq, map) = FloatingLogic.dedupe(listOf("OK", "Cancel", " OK "))
        assertEquals(listOf("OK", "Cancel"), uniq)
        assertEquals(listOf(0, 1, 0), map.toList())
        val chunks = FloatingLogic.chunks((1..20).map { "line $it" }, maxLines = 8)
        assertEquals(listOf(0 until 8, 8 until 16, 16 until 20), chunks)
        assertEquals(2, FloatingLogic.chunks(listOf("a".repeat(300), "b".repeat(300)), maxChars = 480).size)
        assertEquals(listOf(0 until 1), FloatingLogic.chunks(listOf("x".repeat(900))))
        assertEquals(listOf("a", "b"), FloatingLogic.align("a\n\nb\n", 2))
        assertNull(FloatingLogic.align("a\nb\nc", 2))
        assertNull(FloatingLogic.align("a", 2))
        assertEquals("a b", FloatingLogic.oneLine(" a \n b "))
    }

    @Test fun runGateIgnoresRepeatTrigger() {
        val g = FloatingLogic.RunGate(windowMs = 1200)
        assertTrue(g.tryStart("TRANSLATE|", 0))
        assertFalse(g.tryStart("TRANSLATE|", 100))      // 正在跑
        assertTrue(g.tryStart("ASK|hi", 150))           // 别的事照常
        g.end("ASK|hi", 400)
        assertFalse(g.tryStart("ASK|hi", 900))          // 刚跑完的重复点击
        assertTrue(g.tryStart("ASK|hi", 2000))
    }

    // ---- 7. 生图尺寸 ----

    private fun imported(arch: Boolean = false) = ImageModel(
        id = "imp-x", tier = Tier.MID, name = "my model", backend = if (arch) Backend.QNN else Backend.SDCPP,
        files = emptyList(), fixedSize = null, defaultSteps = 20, defaultCfg = 7f, imported = true, animaQnn = arch,
    )

    @Test fun sizesFollowModelType() {
        // 固定尺寸 NPU 包：只有那一档
        val fixed = ImageSizes.options(ImageCatalog.SD15_QNN)
        assertEquals(1, fixed.size); assertEquals(512, fixed[0].width); assertEquals(512, fixed[0].height)
        // 1024 基（Z-Image 等）：默认 1024×1024，含常用比例
        val z = ImageCatalog.byId("z-image-turbo")!!
        val xl = ImageSizes.options(z)
        assertEquals(1024 to 1024, ImageSizes.default(xl).let { it.width to it.height })
        assertTrue(listOf("1:1", "3:4", "4:3", "2:3", "3:2", "9:16", "16:9").all { r -> xl.any { it.ratio == r } })
        // 导入的 sd.cpp：读出 SD1.5 结构 → 512 基；SDXL → 1024 基
        val m = imported()
        assertEquals(ImageSizes.Base.SD15, ImageSizes.base(m, LoraArch.SD1))
        assertEquals(512 to 512, ImageSizes.default(ImageSizes.options(m, LoraArch.SD1)).let { it.width to it.height })
        assertEquals(ImageSizes.Base.XL, ImageSizes.base(m, LoraArch.SDXL))
        // NPU 多尺寸包：只列包里的
        val pack = ImageSizes.options(imported(arch = true), packSizes = listOf(512 to 512, 576 to 1024, 1024 to 576))
        assertEquals(listOf("1:1", "9:16", "16:9"), pack.map { it.ratio })
        assertEquals(listOf(512 to 512), ImageSizes.options(imported(arch = true)).map { it.width to it.height })
        // 全部尺寸是 16 的倍数、不超过上限
        (ImageSizes.options(z) + ImageSizes.options(m, LoraArch.SD1)).forEach {
            assertEquals(0, it.width % 16); assertEquals(0, it.height % 16)
            assertTrue(it.width <= ImageRequest.MAX_DIM && it.height <= ImageRequest.MAX_DIM)
        }
    }

    @Test fun sizePickOnModelSwitch() {
        val xl = ImageSizes.options(ImageCatalog.byId("z-image-turbo")!!)
        assertEquals(768 to 1024, ImageSizes.pick(768, 1024, xl, sameBase = true))      // 同类且可用：留着
        assertEquals(1024 to 1024, ImageSizes.pick(512, 768, xl, sameBase = false))     // 换类：新默认
        assertEquals(1024 to 1024, ImageSizes.pick(640, 640, xl, sameBase = true))      // 不在选项里：新默认
        val txt = ImageSizes.formatPack(listOf(512 to 512, 576 to 1024))
        assertEquals(listOf(512 to 512, 576 to 1024), ImageSizes.parsePack(txt))
        assertEquals(emptyList<Pair<Int, Int>>(), ImageSizes.parsePack("junk"))
    }

    // ---- 6. 提示词整理 ----

    @Test fun promptArrangeFollowsTagOrder() {
        val p = PromptOrder.arrange("1girl, masterpiece, @someartist, blue_hair, best quality, newest, safe, blue hair, a girl standing in the rain under neon lights.")
        assertEquals("masterpiece, best quality, newest, safe, 1girl, @someartist, blue hair, a girl standing in the rain under neon lights.", p)
        assertEquals("score_9", PromptOrder.normalizeTag("score_9"))
        assertEquals(PromptOrder.Slot.COUNT, PromptOrder.slotOf("2girls"))
        assertEquals(PromptOrder.Slot.META, PromptOrder.slotOf("year 2025"))
        val q = PromptOrder.addAll("1girl, solo", PromptOrder.quality(true), tagStyle = true)
        assertTrue(q.startsWith("masterpiece, best quality"))
        assertEquals(q, PromptOrder.addAll(q, PromptOrder.quality(true), tagStyle = true))  // 不重复
        assertTrue(PromptOrder.negative(true).contains("worst quality"))
    }

    // ---- 7. 看图缩放 ----

    @Test fun viewerZoom() {
        assertEquals(5f, ImageZoom.scale(4f, 2f), 0.001f)
        assertEquals(1f, ImageZoom.scale(1f, 0.5f), 0.001f)
        assertEquals(0f to 0f, ImageZoom.clampPan(100f, 100f, 1f, 1000f, 1000f))
        assertEquals(500f to -500f, ImageZoom.clampPan(900f, -900f, 2f, 1000f, 1000f))
        assertEquals(ImageZoom.DOUBLE_TAP, ImageZoom.doubleTap(1f), 0.001f)
        assertEquals(1f, ImageZoom.doubleTap(2.5f), 0.001f)
    }

    // ---- 1. 灵动岛形态 ----

    @Test fun islandModes() {
        assertEquals(IslandLogic.Mode.PILL, IslandLogic.mode(expanded = false, big = false))
        assertEquals(IslandLogic.Mode.EXPANDED, IslandLogic.mode(true, false))
        assertEquals(IslandLogic.Mode.BIG, IslandLogic.mode(true, true))
    }
}
