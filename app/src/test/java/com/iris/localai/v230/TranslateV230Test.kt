package com.iris.localai.v230

import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.data.RecStatus
import com.iris.localai.engine.PromptMessage
import com.iris.localai.translate.BergamotConfig
import com.iris.localai.translate.EngineChoice
import com.iris.localai.translate.EngineSelect
import com.iris.localai.translate.LangDetect
import com.iris.localai.translate.Paragraphs
import com.iris.localai.translate.TransLang
import com.iris.localai.translate.TranslateEngineKind
import com.iris.localai.translate.TranslateOutput
import com.iris.localai.translate.TranslatePack
import com.iris.localai.translate.TranslatePrompt
import com.iris.localai.translate.TranslateSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.3.0 ③ 本地翻译: language detection, prompt, think-strip, engine selection, packs. */
class TranslateV230Test {

    @Test fun detectsChineseAndEnglish() {
        assertEquals(TransLang.ZH, LangDetect.detect("今天天气怎么样？"))
        assertEquals(TransLang.EN, LangDetect.detect("How is the weather today?"))
        assertEquals(TransLang.ZH, LangDetect.detect("用 Kotlin 写一个 Android app"))
        assertEquals(TransLang.EN, LangDetect.detect("The word 中文 means Chinese language in English"))
        assertEquals(TransLang.EN, LangDetect.detect("12345 !!"))
        assertEquals(TransLang.EN, LangDetect.detect(""))
    }

    @Test fun autoTargetIsZhEnSwap() {
        assertEquals(TransLang.EN, LangDetect.target("你好", TranslateSettings.TARGET_AUTO))
        assertEquals(TransLang.ZH, LangDetect.target("hello", TranslateSettings.TARGET_AUTO))
        assertEquals(TransLang.ZH, LangDetect.target("你好", "zh"))
        assertEquals(TransLang.EN, LangDetect.target("hello", "en"))
        // source never equals target
        assertEquals(TransLang.EN, LangDetect.source("你好", TransLang.ZH))
        assertEquals(TransLang.ZH, LangDetect.source("你好", TransLang.EN))
    }

    @Test fun promptIsShortAndContextFree() {
        val m = TranslatePrompt.messages("你好，世界", TransLang.EN)
        assertEquals(2, m.size)
        assertEquals(PromptMessage.SYSTEM, m[0].role)
        assertTrue(m[0].content.contains("English"))
        assertTrue(m[0].content.contains("Output only the translation"))
        assertTrue(m[0].content.length < 400)
        assertFalse(m[0].content.contains("tool", ignoreCase = true))
        assertEquals(PromptMessage.USER, m[1].role)
        assertEquals("你好，世界", m[1].content)
        assertTrue(TranslatePrompt.system(TransLang.ZH).contains("Simplified Chinese"))
        assertEquals(64, TranslatePrompt.maxTokens(""))
        assertEquals(2048, TranslatePrompt.maxTokens("x".repeat(5000)))
    }

    @Test fun stripsThinkingAndLabels() {
        assertEquals("Hello world", TranslateOutput.clean("<think>user wants English</think>\nHello world"))
        assertEquals("Hello", TranslateOutput.clean("prefilled thoughts...</think>Hello"))
        assertEquals("", TranslateOutput.clean("<think>still thinking"))
        assertEquals("Hi", TranslateOutput.clean("Hi<think>"))
        assertEquals("Hello", TranslateOutput.clean("Translation: Hello"))
        assertEquals("你好", TranslateOutput.clean("译文：你好"))
        assertEquals("Hello", TranslateOutput.clean("\"Hello\"", source = "你好"))
        assertEquals("\"Hello\"", TranslateOutput.clean("\"Hello\"", source = "\"你好\""))
        assertEquals("Hello", TranslateOutput.clean("<|channel|>analysis thinking<|end|>Hello"))
        assertEquals("Line 1\nLine 2", TranslateOutput.clean("  Line 1\nLine 2  "))
    }

    @Test fun engineSelection() {
        val zh = TransLang.ZH; val en = TransLang.EN
        fun pick(pref: String, model: Boolean, pack: Boolean, native: Boolean = true) =
            EngineSelect.choose(pref, model, pack, native, zh, en)
        val model = EngineChoice.Use(TranslateEngineKind.MODEL)
        val offline = EngineChoice.Use(TranslateEngineKind.OFFLINE)
        assertEquals(model, pick(TranslateSettings.ENGINE_AUTO, true, true))
        assertEquals(offline, pick(TranslateSettings.ENGINE_AUTO, false, true))
        assertEquals(EngineChoice.Unavailable(EngineSelect.NEED_EITHER), pick(TranslateSettings.ENGINE_AUTO, false, false))
        assertEquals(EngineChoice.Unavailable(EngineSelect.NEED_MODEL), pick(TranslateSettings.ENGINE_AUTO, false, true, native = false))
        assertEquals(model, pick(TranslateSettings.ENGINE_MODEL, true, false))
        assertEquals(EngineChoice.Unavailable(EngineSelect.NEED_MODEL), pick(TranslateSettings.ENGINE_MODEL, false, true))
        assertEquals(offline, pick(TranslateSettings.ENGINE_OFFLINE, true, true))
        val miss = pick(TranslateSettings.ENGINE_OFFLINE, true, false) as EngineChoice.Unavailable
        assertTrue(miss.message.contains("中文→英文"))
        assertEquals(EngineChoice.Unavailable(EngineSelect.OFFLINE_UNSUPPORTED), pick(TranslateSettings.ENGINE_OFFLINE, true, true, native = false))
        assertEquals("请先加载一个模型", EngineSelect.NEED_MODEL)
    }

    @Test fun packsAndConfig() {
        assertEquals(TranslatePack.ZH_EN, TranslatePack.of(TransLang.ZH, TransLang.EN))
        assertEquals(TranslatePack.EN_ZH, TranslatePack.of(TransLang.EN, TransLang.ZH))
        assertEquals(3, TranslatePack.ZH_EN.files.size) // shared vocabulary listed once
        assertEquals(4, TranslatePack.EN_ZH.files.size)
        TranslatePack.entries.forEach { assertTrue(it.totalBytes in 20_000_000L..60_000_000L) }
        val y = BergamotConfig.yaml(TranslatePack.EN_ZH, File("/x"), 2)
        assertTrue(y.contains("  - /x/model.enzh.intgemm.alphas.bin"))
        assertTrue(y.contains("  - /x/srcvocab.enzh.spm\n  - /x/trgvocab.enzh.spm"))
        assertTrue(y.contains("shortlist:\n  - /x/lex.50.50.enzh.s2t.bin\n  - false"))
        assertTrue(y.contains("gemm-precision: int8shiftAlphaAll"))
        assertTrue(y.contains("cpu-threads: 2"))
    }

    @Test fun catalogHasTranslateSection() {
        val items = RecommendedCatalog.section(RecommendedCatalog.Section.TRANSLATE)
        assertEquals(2, items.size)
        assertTrue(items.all { it.kind is RecommendedCatalog.Kind.Translate && it.licence == "MPL-2.0" })
        assertEquals("已下载", RecStatus.primaryLabel(RecStatus.Installed(), RecommendedCatalog.Section.TRANSLATE))
    }

    @Test fun paragraphs() {
        assertEquals(listOf("a", "", "b"), Paragraphs.split("a\n\nb"))
        assertTrue(Paragraphs.passThrough(""))
        assertTrue(Paragraphs.passThrough("```kotlin"))
        assertTrue(Paragraphs.passThrough("---"))
        assertFalse(Paragraphs.passThrough("Hello"))
    }
}
