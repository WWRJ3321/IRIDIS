package com.iris.localai.v219gui

import com.iris.localai.agent.AppMatcher
import com.iris.localai.agent.LaunchIntent
import com.iris.localai.agent.PhoneActions
import com.iris.localai.agent.ScreenTool
import com.iris.localai.agent.ToolEnv
import com.iris.localai.agent.UndoStore
import com.iris.localai.agent.WorkspaceSandbox
import com.iris.localai.agent.GuiBlocker
import com.iris.localai.agent.GuiTaskBackend
import com.iris.localai.agent.GuiTaskResult
import com.iris.localai.agent.GuiTaskStatus
import com.iris.localai.agent.gui.GuiAction
import com.iris.localai.agent.gui.GuiActionParser
import com.iris.localai.agent.gui.GuiProfile
import com.iris.localai.agent.gui.GuiPrompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 2.1.9 「直接打开应用」: app matching, pure-launch detection, GUI Launch parsing. */
class AppLaunchTest {
    private val apps = listOf(
        "微信" to "com.tencent.mm", "设置" to "com.android.settings", "相册" to "com.miui.gallery",
        "Chrome" to "com.android.chrome", "哔哩哔哩" to "tv.danmaku.bili", "网易云音乐" to "com.netease.cloudmusic",
    )

    @Test fun matchesChineseEnglishAndPackage() {
        assertEquals("com.tencent.mm", AppMatcher.match("微信", apps)?.second)
        assertEquals("com.tencent.mm", AppMatcher.match("WeChat", apps)?.second)
        assertEquals("com.android.chrome", AppMatcher.match("chrome", apps)?.second)
        assertEquals("tv.danmaku.bili", AppMatcher.match("B站", apps)?.second)
        assertEquals("tv.danmaku.bili", AppMatcher.match("tv.danmaku.bili", apps)?.second)
        assertEquals("com.miui.gallery", AppMatcher.match("图库", apps)?.second)
        assertEquals("com.netease.cloudmusic", AppMatcher.match("网易云", apps)?.second)
        assertEquals("com.tencent.mm", AppMatcher.match("微信app", apps)?.second)
        assertNull(AppMatcher.match("支付宝", apps))
    }

    @Test fun notFoundListsCandidates() {
        val t = AppMatcher.notFound("网易音乐", apps)
        assertTrue(t, t.startsWith("没有找到应用「网易音乐」"))
        assertTrue(t, t.contains("网易云音乐"))
        assertTrue(AppMatcher.notFound("zzzz", apps).contains("没有相近"))
    }

    @Test fun pureLaunchOnly() {
        assertEquals("微信", LaunchIntent.appName("打开微信"))
        assertEquals("微信", LaunchIntent.appName("帮我打开微信"))
        assertEquals("哔哩哔哩", LaunchIntent.appName("启动哔哩哔哩吧"))
        assertNull(LaunchIntent.appName("打开微信给张三发消息"))
        assertNull(LaunchIntent.appName("打开设置把亮度调最高"))
        assertNull(LaunchIntent.appName("打开蓝牙"))
        assertNull(LaunchIntent.appName("今天天气怎么样"))
    }

    @Test fun guiOpenAwakeLaunchAllParseToLaunch() {
        fun mu(a: String) = GuiActionParser.parse("<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"$a\",\"text\":\"微信\"}}</tool_call>", GuiProfile.MAI_UI).action
        for (a in listOf("open", "awake", "launch", "open_app")) assertEquals(a, GuiAction.Launch("微信"), mu(a))
        for (a in listOf("Launch", "open", "open_app")) {
            val p = GuiActionParser.parse("<answer>do(action=\"$a\", app=\"微信\")</answer>", GuiProfile.AUTOGLM).action
            assertEquals(a, GuiAction.Launch("微信"), p)
        }
    }

    @Test fun guiPromptSaysLaunchDirectly() {
        assertTrue(GuiPrompt.system(GuiProfile.AUTOGLM).contains("直接打开应用"))
        assertTrue(GuiPrompt.system(GuiProfile.MAI_UI).contains("launches the app directly"))
    }

    private class FakePhone : PhoneActions {
        var opened: String? = null
        override fun setAlarm(hour: Int, minute: Int, label: String?) = ""
        override fun startTimer(seconds: Int, label: String?) = ""
        override fun addEvent(title: String, startMs: Long, endMs: Long, location: String?, notes: String?) = ""
        override fun apps() = listOf("微信" to "com.tencent.mm")
        override fun openApp(pkg: String): String { opened = pkg; return "ok" }
        override fun share(text: String) = ""
        override fun copy(text: String) = ""
        override fun paste(): String? = null
        override fun openUrl(url: String) = ""
        override fun dial(number: String) = ""
    }

    private class NoGui : GuiTaskBackend {
        var ran = false
        override fun blocker(): GuiBlocker? = null
        override suspend fun run(task: String, progress: (String) -> Unit): GuiTaskResult { ran = true; return GuiTaskResult(GuiTaskStatus.DONE, "", 1) }
    }

    @Test fun screenLaunchesDirectlyWithoutTheLoop() = runBlocking {
        val tmp = { Files.createTempDirectory("gl").toFile() }
        val phone = FakePhone(); val gui = NoGui()
        val env = ToolEnv(WorkspaceSandbox(tmp()), UndoStore(tmp()), phone = phone, gui = gui)
        val r = ScreenTool.runSuspend(buildJsonObject { put("task", "打开微信") }, env) {}
        assertTrue(r.ok); assertEquals("com.tencent.mm", phone.opened); assertFalse(gui.ran)
        val miss = ScreenTool.runSuspend(buildJsonObject { put("task", "打开支付宝") }, env) {}
        assertFalse(miss.ok); assertFalse(gui.ran)
    }
}

class GuiRoutingTest {
    @org.junit.Test fun wechatGroupMessageIsAPhoneTask() {
        val g = com.iris.localai.agent.GuiIntent
        org.junit.Assert.assertTrue(g.looksLikePhoneTask("在微信无人群里发一句你好"))
        org.junit.Assert.assertTrue(g.looksLikePhoneTask("在淘宝里搜一下充电宝"))
        org.junit.Assert.assertFalse(g.looksLikePhoneTask("微信是什么时候发布的"))
        val U = com.iris.localai.engine.PromptMessage.USER
        val m = listOf(com.iris.localai.engine.PromptMessage(U, "在微信无人群里发一句你好"))
        org.junit.Assert.assertTrue(g.withHint(m, true).last().content.endsWith(g.HINT))
        org.junit.Assert.assertEquals(m, g.withHint(m, false))
        org.junit.Assert.assertEquals("你好", g.withHint(listOf(com.iris.localai.engine.PromptMessage(U, "你好")), true).last().content)
    }
}
