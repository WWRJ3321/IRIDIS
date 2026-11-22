package com.iris.localai.ui.chat

/**
 * 3.0.0 Beta3 r5：手机指令（手电筒 / 亮度 / 音量 / 静音）只在这一轮带 phone 工具走智能体，
 * 不再像 r4 那样把智能体模式永久打开（之后每句普通对话都带上全部工具说明，预填充变长）。纯逻辑，单测 V300B3R5Test。
 */
object TurnAgent {
    const val PHONE = "phone"

    /** 对话模式下这一轮临时用的工具；null = 不临时开（智能体模式已开、不是手机指令、phone 工具被关、带了附件或引用）。 */
    fun oneShotTools(agentOn: Boolean, phoneCommand: Boolean, phoneEnabled: Boolean, hasAttachOrRefs: Boolean): Set<String>? =
        if (!agentOn && phoneCommand && phoneEnabled && !hasAttachOrRefs) setOf(PHONE) else null

    /** 这一轮走智能体：智能体模式开着、操作手机的屏幕任务、或临时工具。 */
    fun viaAgent(agentOn: Boolean, guiTurn: Boolean, oneShot: Set<String>?): Boolean = agentOn || guiTurn || !oneShot.isNullOrEmpty()

    /** 这一轮交给模型的工具：智能体模式 = 全部已启用的；临时 = 只有那几个。 */
    fun <T> tools(all: List<T>, name: (T) -> String, agentOn: Boolean, oneShot: Set<String>?): List<T> =
        if (agentOn || oneShot.isNullOrEmpty()) all else all.filter { name(it) in oneShot }

    /** 升级到 r5 时一次性关掉 r4 自动打开的智能体模式（返回要写入的新值；null = 不动）。 */
    fun migrateAgentMode(agentOn: Boolean, migrated: Boolean): Boolean? = if (!migrated && agentOn) false else null
}
