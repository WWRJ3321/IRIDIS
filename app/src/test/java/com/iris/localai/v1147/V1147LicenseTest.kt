package com.iris.localai.v1147

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
        assertTrue(screens.substringAfter("fun AboutPage(").contains("ABOUT_PRIVACY"))
    }

    @Test fun openSourceListComplete() {
        assertTrue(openSource.contains("\"PRoot\" to \"GPL-2.0-or-later"))
        assertTrue(openSource.contains("github.com/termux/proot"))
        assertTrue(openSource.contains("\"MNN\" to \"Apache-2.0（已修改）"))
        for (name in listOf(
            "llama.cpp", "Google LiteRT-LM", "LiteRT Dispatch (Qualcomm)", "sherpa-onnx", "ONNX Runtime",
            "Khronos OpenCL", "AndroidX / Jetpack Compose", "Kotlin / kotlinx", "Apache Commons Compress",
            "talloc", "libandroid-shmem", "Silero VAD", "SenseVoice", "Kokoro", "Zipformer CTC",
            "ZipVoice", "Matcha baker", "Piper xiao_ya",
        )) assertTrue("missing $name", openSource.contains("\"$name"))
        assertTrue(openSource.contains("LGPL-3.0"))
        assertTrue(openSource.contains("BSD-3-Clause"))
        assertTrue(openSource.contains("CC BY-NC 4.0"))
    }

    @Test fun aboutLinksToLicensesPage() {
        val about = screens.substringAfter("fun AboutPage(").substringBefore("internal const val ABOUT_PRIVACY")
        assertTrue(about.contains("\"许可证全文\""))
        assertTrue(about.contains("本应用许可证 · Apache-2.0"))
        assertTrue(about.contains("LicensesPage(onBack"))
        val page = src("ui/settings/LicensesPage.kt")
        assertTrue(page.contains("licenses/index.json") || page.contains("\$DIR/index.json"))
        assertTrue(page.contains("FontFamily.Monospace") || page.contains("IrisType.mono"))
        assertFalse("animations only via IrisMotion", page.contains("tween("))
    }

    @Test fun licenseIndexParsesAndTolerates() {
        val list = LicenseIndex.parse("""[{"name":"llama.cpp","license":"MIT","file":"llama.cpp.txt","url":"https://github.com/ggml-org/llama.cpp"},{"license":"x"}]""")
        assertEquals(1, list.size)
        assertEquals("llama.cpp.txt", list[0].file)
        assertEquals(1, LicenseIndex.parse("""{"licenses":[{"name":"a","license":"MIT","file":"a.txt"}]}""").size)
        assertTrue(LicenseIndex.parse("").isEmpty())
        assertTrue(LicenseIndex.parse("not json").isEmpty())
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

    @Test fun voicePacksCarryLicenseTags() {
        val voice = src("ui/settings/VoiceAndApiPages.kt")
        val tag = voice.substringAfter("val VoicePack.licenseTag").substringBefore("@Composable")
        assertTrue(tag.contains("VoicePack.CLONE_ZIPVOICE") && tag.contains("VoicePack.TTS_MATCHA_BAKER") && tag.contains("R.string.settings_855f7b"))
        assertTrue(tag.contains("VoicePack.TTS_PIPER_XIAOYA -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7e41cf)"))
        // 2.5.3: the pack row subtitle is the blurb only (音色·特点); license tags are no longer appended.
        assertFalse(voice.substringAfter("internal fun PackRow(").substringBefore("private fun TranslateSection(").contains("pack.licenseTag"))
    }
}
