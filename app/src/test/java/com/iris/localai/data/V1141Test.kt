package com.iris.localai.data

import com.iris.localai.data.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V1141Test {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").readText()

    @Test fun appearanceDefaults() {
        val s = AppSettings()
        assertFalse("top-bar capsules off by default", s.topBarCapsules)
        assertTrue(s.showStatusRack)
        // settings saved by 1.14.0 (no new keys) still load
        val old = json.decodeFromString(AppSettings.serializer(), "{}")
        assertFalse(old.topBarCapsules)
    }


}
