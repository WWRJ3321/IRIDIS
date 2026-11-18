package com.iris.localai.chat

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

}
