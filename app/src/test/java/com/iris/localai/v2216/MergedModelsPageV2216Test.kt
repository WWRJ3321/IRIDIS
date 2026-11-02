package com.iris.localai.v2216

import com.iris.localai.ui.chat.ModelMenuText
import com.iris.localai.ui.models.ModelsText
import com.iris.localai.ui.settings.SettingsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.2.16: one 「模型与加载」 entry everywhere (home model menu, 设置, 存储空间). */
class MergedModelsPageV2216Test {
    private val src = File("app/src/main/java/com/iris/localai")

    @Test fun oneNameEverywhere() {
        assertEquals("模型与加载", ModelMenuText.MODELS_AND_LOADING)
        assertEquals(ModelMenuText.MODELS_AND_LOADING, ModelsText.TITLE)
        assertEquals("模型与加载", SettingsText.HOME_GROUPS.first().second.first())
    }

    @Test fun noLeftoverEntriesOrPages() {
        val all = src.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        // user-visible strings only (comments may still mention the old names)
        val strings = Regex("\"[^\"\\n]*\"").findAll(all).map { it.value }.toList()
        for (old in listOf("档位与加载", "管理模型", "模型管理", "模型与后端")) {
            assertFalse("leftover $old: " + strings.filter { old in it }, strings.any { old in it })
        }
        assertFalse(File(src, "ui/chat/ModelPickerSheet.kt").exists())
        assertFalse("ModelBackendPage(" in all)
        assertFalse("Route.MODEL_BACKEND" in all)
        assertTrue("SettingsPage.MODEL_BACKEND -> Route.MODELS" in all)
        val menu = File(src, "ui/chat/ChatScreen.kt").readText()
        assertEquals(1, Regex("CompactMenuRow\\(ModelMenuText.MODELS_AND_LOADING").findAll(menu).count())
    }
}
