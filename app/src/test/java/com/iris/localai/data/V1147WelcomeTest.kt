package com.iris.localai.data

import com.iris.localai.data.AppSettings
import com.iris.localai.data.migrated
import com.iris.localai.data.sanitized
import com.iris.localai.ui.welcome.WelcomeText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.14.7: first-launch welcome screen with the disclaimer, shown once (fresh installs and upgrades). */
class V1147WelcomeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun source(path: String): String =
        listOf(path, "app/$path").map { File(it) }.first { it.exists() }.readText()

    private val welcome get() = source("src/main/java/com/iris/localai/ui/welcome/WelcomeScreen.kt")

    @Test fun flagDefaultsToFalseAlsoForUpgrades() {
        assertFalse(AppSettings().welcomeAccepted)
        // a settings blob written by an older version has no key → the upgrade sees the welcome once
        val old = json.decodeFromString(AppSettings.serializer(), """{"resourceTier":1,"showStatusRack":true}""").migrated().sanitized()
        assertFalse(old.welcomeAccepted)
        // once accepted it persists
        val accepted = AppSettings(welcomeAccepted = true)
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), accepted)).migrated().sanitized()
        assertTrue(back.welcomeAccepted)
    }

    @Test fun textsAndDisclaimer() {
        assertEquals("开始使用", WelcomeText.START)
        assertEquals("完全运行在你手机上的 AI", WelcomeText.TAGLINE)
        assertTrue(WelcomeText.DISCLAIMER.startsWith("本应用按原样提供，不提供任何担保。"))
        assertTrue(WelcomeText.DISCLAIMER.contains("请勿用于医疗、法律或金融决策"))
        assertTrue(WelcomeText.DISCLAIMER.contains("智能体操作的后果由你自行承担"))
        assertEquals(listOf("本地推理", "智能体", "开源"), WelcomeText.FEATURES.map { it.first })
        val src = welcome
        assertTrue(src.contains("\"开始使用\""))
        assertTrue(src.contains("本应用按原样提供，不提供任何担保。AI 生成内容可能有误"))
        assertTrue(src.contains("c.textTertiary"))
        assertTrue(src.contains("R.drawable.iridis_wordmark_ink") && src.contains("R.drawable.iridis_wordmark_accent"))
        assertTrue(src.contains("R.drawable.ic_iris_mark"))
        assertTrue(src.contains("IrisIcons."))
    }


}
