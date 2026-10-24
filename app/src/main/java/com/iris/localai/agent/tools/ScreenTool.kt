package com.iris.localai.agent

import kotlinx.serialization.json.JsonObject

/** 2.1.9: why the GUI agent cannot run now (each one has its own guide in the chat). */
enum class GuiBlocker { A11Y_OFF, NO_MODEL, NO_VISION, BUSY }

/** 2.1.9: how a `screen` run ended, as told to the model and shown on the step card. */
enum class GuiTaskStatus(val label: String) { DONE("完成"), FAILED("失败"), TAKEN_OVER("用户接管"), STOPPED("已停止") }

data class GuiTaskResult(val status: GuiTaskStatus, val message: String, val steps: Int = 0)

/** 2.1.9: the backing of `screen` (Android: GuiAgentRunner). Null in [ToolEnv] = not available. */
interface GuiTaskBackend {
    /** Why a run cannot start now, or null. Showing the matching guide in the chat is the backend's job. */
    fun blocker(): GuiBlocker?
    /** Runs the GUI loop on the phone's screen; [progress] gets one line per step. */
    suspend fun run(task: String, progress: (String) -> Unit): GuiTaskResult
}

/**
 * 2.1.9 `screen`: 在聊天里直接说「打开设置把亮度调最高」 → the GUI agent operates the phone with the
 * loaded vision model (看屏幕 → 思考 → 点击/输入). The GUI loop asks before 支付/发送/删除 and hands
 * password screens to the user itself, so the call is not asked again (默认允许).
 */
object ScreenTool : SuspendTool {
    override val name = "screen"
    override val label = "操控手机"
    override val description = "用识图模型看屏幕、代你点击输入完成任务（需开启无障碍）"
    override val promptLine = "screen(task): operate apps on screen for the user"
    override val risk = ToolRisk.WRITE
    override val defaultPermission: Permission get() = Permission.ALLOW
    override val group = ToolGroup.OTHER

    /** Chinese guide for the chat and the model (the model repeats it to the user). */
    fun blockerText(b: GuiBlocker): String = when (b) {
        GuiBlocker.A11Y_OFF -> "无障碍服务未开启：请在 系统设置 › 无障碍 里开启「IRIDIS GUI 智能体」（已弹出开启引导），开好后再说一次"
        GuiBlocker.NO_MODEL -> "还没有加载模型：请先加载一个能识图的模型（如 Qwen3-VL MNN），再说一次"
        GuiBlocker.NO_VISION -> "当前模型不能读图：操控手机需要识图模型（如 Qwen3-VL MNN），请在顶部切换后再说一次"
        GuiBlocker.BUSY -> "已有一个手机操控任务在运行，等它结束或点悬浮条「停止」"
    }

    /** What the model reads after a run. */
    fun output(r: GuiTaskResult): String = when (r.status) {
        GuiTaskStatus.DONE -> "Done on screen: ${r.message}"
        GuiTaskStatus.FAILED -> "Not finished: ${r.message}"
        GuiTaskStatus.TAKEN_OVER -> "Handed to the user: ${r.message}"
        GuiTaskStatus.STOPPED -> "Stopped by the user: ${r.message}"
    }

    override suspend fun runSuspend(args: JsonObject, env: ToolEnv, progress: (String) -> Unit): ToolResult {
        val task = Args.str(args, "task", "goal", "instruction", "text", "query")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("缺少参数 task")
        val gui = env.gui ?: return ToolResult(false, "Error: screen control is not available", "不可用")
        gui.blocker()?.let { b -> val t = blockerText(b); return ToolResult(false, "Error: $t", t.substringBefore('：')) }
        val r = gui.run(task, progress)
        val summary = "${r.status.label}：${r.message}".take(80)
        return ToolResult(r.status == GuiTaskStatus.DONE, output(r), summary)
    }
}

/**
 * 2.1.9: whether a message in 对话 mode (智能体 off) asks to operate the phone; such a turn goes through
 * the 智能体 with `screen` once (智能体 mode stays off). Narrow on purpose: false positives cost a turn.
 */
object GuiIntent {
    private const val APPS = "系统设置|设置|微信|支付宝|淘宝|京东|抖音|快手|小红书|美团|饿了么|QQ|微博|B站|哔哩哔哩|高德|百度地图|相册|相机|浏览器|应用商店|网易云|日历|时钟"
    private val PHONE_UI = Regex(
        "(在|用)手机上|手机上(帮我|给我|替我)|" +
            "(打开|进入|去)($APPS).{0,24}(把|调|点|发|搜|找|开启|关闭|关掉|设置|设成|切换|改成|播放)|" +
            "(在|用|通过)微信(上|里)?(给|跟|向|和).{1,12}(发|说)|给.{1,12}发(一条|个|条)?(微信|QQ)|" +
            "(亮度|音量)(调|开|设)(到|成)?(最高|最低|最大|最小)",
    )

    fun looksLikePhoneTask(text: String): Boolean = text.length <= 200 && PHONE_UI.containsMatchIn(text.trim())

    const val CHAT_NOTICE = "这是操控手机的请求，本条已交给智能体执行（智能体模式未开启）"
}
