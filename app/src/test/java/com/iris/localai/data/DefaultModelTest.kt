package com.iris.localai.data

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



}
