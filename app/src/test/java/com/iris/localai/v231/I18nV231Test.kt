package com.iris.localai.v231

import com.iris.localai.I18nPure
import com.iris.localai.translate.LangDetect
import com.iris.localai.translate.TransLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 2.3.1 多语言界面: every locale has every key with the same placeholders; picker / locales_config agree. */
class I18nV231Test {
    private val res = listOf("app/src/main/res", "src/main/res").map(::File).first { it.isDirectory }
    private val locales = listOf("zh-rTW", "en", "ja", "de", "es", "hi")

    private fun load(folder: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$folder/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sd]|%%").findAll(s).map { it.value }.sorted().toList()

    @Test fun everyKeyInEveryLocale() {
        val base = load("values")
        assertTrue("base strings", base.size > 500)
        for (l in locales) {
            val t = load("values-$l")
            assertEquals("missing in $l", emptySet<String>(), base.keys - t.keys)
            assertEquals("extra in $l", emptySet<String>(), t.keys - base.keys)
        }
    }

    @Test fun placeholdersMatch() {
        val base = load("values")
        for (l in locales) {
            val t = load("values-$l")
            val bad = base.filter { (k, v) -> placeholders(v) != placeholders(t.getValue(k)) }.keys
            assertEquals("placeholder mismatch in $l", emptySet<String>(), bad)
        }
    }

    @Test fun noEmptyTranslations() {
        for (l in locales) {
            val empty = load("values-$l").filter { it.value.isEmpty() }.keys
            assertEquals("empty in $l", emptySet<String>(), empty)
        }
    }

    @Test fun langCodeAndLocaleConfig() {
        assertEquals("zh-CN", load("values")["lang_code"])
        val codes = locales.map { load("values-$it")["lang_code"] }
        assertEquals(listOf("zh-TW", "en", "ja", "de", "es", "hi"), codes)
        val cfg = File(res, "xml/locales_config.xml").readText()
        listOf("zh-CN", "zh-TW", "zh-HK", "zh-MO", "en", "ja", "de", "es", "hi").forEach { assertTrue(it, cfg.contains("\"$it\"")) }
        // picker list (I18n.LANGS) mirrors the folders
        val src = File(res, "../java/com/iris/localai/I18n.kt").readText()
        listOf("zh-CN", "zh-TW", "en", "ja", "de", "es", "hi").forEach { assertTrue(it, src.contains("\"$it\" to")) }
    }

    @Test fun normalize() {
        assertEquals("zh-TW", I18nPure.normalize("zh-Hant-HK"))
        assertEquals("zh-TW", I18nPure.normalize("zh-HK"))
        assertEquals("zh-TW", I18nPure.normalize("zh-MO"))
        assertEquals("zh-CN", I18nPure.normalize("zh-Hans-CN"))
        assertEquals("en", I18nPure.normalize("en-US"))
        assertEquals("hi", I18nPure.normalize("hi-IN"))
    }

    @Test fun replyLine() {
        assertNull(I18nPure.replyLine("zh-CN"))
        assertNull(I18nPure.replyLine("zh-TW"))
        assertTrue(I18nPure.replyLine("en")!!.contains("English"))
        assertTrue(I18nPure.replyLine("hi")!!.contains("Hindi"))
        assertEquals("sys", I18nPure.withReplyLine("sys", "zh-CN"))
        assertTrue(I18nPure.withReplyLine("sys", "ja").startsWith("sys\n\n") && I18nPure.withReplyLine("sys", "ja").contains("Japanese"))
        assertEquals(I18nPure.replyLine("de"), I18nPure.withReplyLine("  ", "de"))
    }

    @Test fun translateTargetFollowsUi() {
        assertEquals(TransLang.EN, LangDetect.target("你好世界", "auto"))
        assertEquals(TransLang.ZH, LangDetect.target("hello world", "auto"))
        assertEquals(TransLang.EN, LangDetect.target("你好世界", "auto", "en"))
        assertEquals(TransLang.ZH, LangDetect.target("hello world", "auto", "en"))
        assertEquals(TransLang.ZH, LangDetect.target("hello", "zh", "en"))
    }
}
