package com.iris.localai.ui

import com.iris.localai.data.AppSettings
import com.iris.localai.data.ChatTextScale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对话文字大小（2.7.6 vc283）：百分比映射、滑块吸附、持久化往返、旧数据默认值。 */
class ChatTextScaleTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun defaultIsHundredAndFactorOne() {
        assertEquals(100, AppSettings().chatTextScale)
        assertEquals(1f, ChatTextScale.factor(AppSettings().chatTextScale), 0f)
    }

    @Test fun rangeIsClampedAndSnappedToFivePercent() {
        assertEquals(85, ChatTextScale.sanitize(10))
        assertEquals(130, ChatTextScale.sanitize(400))
        assertEquals(115, ChatTextScale.sanitize(114))
        assertEquals(110, ChatTextScale.sanitize(112))
        assertEquals(85, ChatTextScale.sanitize(Int.MIN_VALUE))
        assertEquals(130, ChatTextScale.sanitize(Int.MAX_VALUE))
        for (p in 85..130 step 5) assertEquals(p, ChatTextScale.sanitize(p))
    }

    @Test fun sliderMapping() {
        assertEquals(8, ChatTextScale.sliderSteps) // 85,90,…,130 = 10 档，中间 8 个刻度
        assertEquals(105, ChatTextScale.fromSlider(104.6f))
        assertEquals(100, ChatTextScale.fromSlider(Float.NaN))
        assertEquals(130, ChatTextScale.fromSlider(131f))
        assertEquals(0.85f, ChatTextScale.factor(85), 1e-6f)
        assertEquals(1.3f, ChatTextScale.factor(130), 1e-6f)
        assertEquals("115%", ChatTextScale.label(115))
    }

    @Test fun persistsAndOldBlobsGetDefault() {
        // AppSettings.sanitized() 会加载 Compose 主题类（单测类路径里没有），这里直接测它调用的 ChatTextScale.sanitize
        val s = AppSettings(chatTextScale = 120)
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), s))
        assertEquals(120, ChatTextScale.sanitize(back.chatTextScale))
        // 2.7.6 vc282 及更早保存的设置里没有这个键 → 100%
        val old = json.decodeFromString(AppSettings.serializer(), """{"temperature":0.5}""")
        assertEquals(100, old.chatTextScale)
        // 越界值（如 API 写入）被拉回
        val bad = json.decodeFromString(AppSettings.serializer(), """{"chatTextScale":999}""")
        assertEquals(130, ChatTextScale.sanitize(bad.chatTextScale))
        assertTrue(ChatTextScale.sanitize(87) % 5 == 0)
    }

    @Test fun sanitizedWiresChatTextScale() {
        // 源码检查：设置整体夹取里接了 ChatTextScale.sanitize
        val p = "src/main/java/com/iris/localai/data/SettingsRepository.kt"
        val f = listOf(java.io.File("app/$p"), java.io.File(p)).first { it.exists() }
        assertTrue(f.readText().contains("chatTextScale = ChatTextScale.sanitize(chatTextScale)"))
    }
}
