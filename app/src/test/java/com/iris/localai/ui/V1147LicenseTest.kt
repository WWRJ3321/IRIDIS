package com.iris.localai.ui

import com.iris.localai.ui.settings.LicenseIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.14.7: accurate privacy text, complete open-source list, license page, NPU runtime consent, voice license tags. */
class V1147LicenseTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    private val screens = src("ui/settings/SettingsScreens.kt")
    private val openSource = screens.substringAfter("internal val OPEN_SOURCE = listOf(").substringBefore("\n)\n")

    @Test fun aboutPrivacyTextIsAccurate() {
        val about = screens.substringAfter("internal const val ABOUT_PRIVACY").substringBefore("\n\n")
        assertTrue(about.contains("智能体"))
        assertTrue(about.contains("NPU 运行库"))
        assertTrue(about.contains("对话内容不会上传"))
        assertFalse(screens.contains("对话内容不会离开你的手机"))
        assertTrue(screens.substringAfter("fun AboutPage(").contains("AboutHeroCard()")) // 3.0：关于页改为卡片，隐私说明不再列在页面上
    }




    @Test fun npuRuntimeAsksForConsent() {
        assertTrue(screens.contains("\"高通 NPU 运行库许可\""))
        assertTrue(screens.contains("为高通专有软件，不属于本项目开源部分，将从 Maven Central 下载"))
        assertTrue(screens.contains("\"同意并下载\""))
        assertTrue(screens.contains("\"查看条款\""))
        val row = screens.substringAfter("private fun NpuRuntimeRow(").substringBefore("private fun NpuLicenseDialog(")
        assertTrue(row.contains("(\"下载\" to install)"))
        assertTrue(row.contains("(\"重试\" to install)"))
        assertFalse(row.contains("to onInstall)"))
        assertTrue(row.contains("NpuConsent.accepted"))
        val dialog = screens.substringAfter("private fun NpuLicenseDialog(").substringBefore("private fun BenchmarkDialog(")
        assertTrue("uses the app dialog", dialog.contains("IrisDialog("))
    }

}
