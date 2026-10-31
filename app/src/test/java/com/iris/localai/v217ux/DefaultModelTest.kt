package com.iris.localai.v217ux

import com.iris.localai.data.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.7 #6: tap the chat logo → load 「默认模型」 (none set = last used). */
class DefaultModelTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    @Test fun settingDefaultsToNoneAndOldJsonStillLoads() {
        assertEquals("", AppSettings().defaultModelFile)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"lastModelFile":"a.gguf"}""")
        assertEquals("", old.defaultModelFile)
    }

    @Test fun engineFallsBackFromDefaultToLastUsed() {
        val em = src("engine/EngineManager.kt")
        val d = em.substringAfter("fun defaultModel(): LocalModel? =").substringBefore("\n\n")
        assertTrue(d.contains("defaultModelFile") && d.contains("lastModelFile"))
        assertTrue(d.indexOf("defaultModelFile") < d.indexOf("lastModelFile"))
    }

    /** 2.2.5: tap-to-load on the empty-state mark removed at the user's request; the mark still breathes while loading. */
    @Test fun emptyStateMarkIsDecorative() {
        val chat = src("ui/chat/ChatScreen.kt")
        assertFalse(chat.contains("onLogoTap"))
        assertFalse(src("ui/chat/ChatViewModel.kt").contains("fun loadDefaultModel("))
        val empty = chat.substringAfter("private fun ChatEmptyState(").substringBefore("fun loadingSeconds(")
        assertTrue(empty.contains(".pulse(engine is EngineState.Loading"))
        assertFalse(empty.contains("clickable"))
        assertFalse(empty.contains("点按图标加载"))
        assertFalse("animations only via IrisMotion", empty.contains("tween("))
    }

    @Test fun settingsHasDefaultModelPicker() {
        val s = src("ui/settings/SettingsScreens.kt")
        assertTrue(s.contains("DefaultModelRow(vm, s.defaultModelFile)"))
        assertTrue(s.substringAfter("private fun DefaultModelRow(").contains("\"上次使用的模型\""))
        assertTrue(src("ui/settings/SettingsViewModel.kt").contains("fun setDefaultModel(fileName: String)"))
    }
}
