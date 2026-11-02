/*
 * Portions of this file are adapted from Mika (https://github.com/wangzan101/mika):
 *   core/uistate/ScreenDiagnosis.kt, core/agent/StuckAdvice.kt, core/uistate/TreeQuality.kt
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0
 * (see assets/licenses/apache-2.0.txt and notice-mika.txt). Modified for IRIDIS 2.3.0:
 * works on IRIDIS' UiElement list, Chinese/English hints for the model, ANR / crash dialogs.
 */
package com.iris.localai.agent.gui

/** 2.3.0: what kind of screen this is (from the element list; pure). */
enum class ScreenKind { NORMAL, BLANK, NEEDS_HUMAN, APP_NOT_RESPONDING, APP_CRASHED }

/** [button] = the system dialog's button the loop presses by itself (等待 / 关闭应用). */
data class ScreenCheck(val kind: ScreenKind, val cause: String? = null, val button: UiElement? = null)

/**
 * 2.3.0 screen diagnosis, after Mika's ScreenDiagnosis: captcha / payment / password / SMS-code
 * screens are pointed out to the model (it decides whether to hand over), an empty tree is reported,
 * and the system "app isn't responding" / "keeps stopping" dialogs are recognised so the loop can
 * clear them without spending a model step.
 */
object ScreenDiagnosis {
    private val CAPTCHA = listOf("captcha", "人机验证", "安全验证", "滑块验证", "拖动滑块", "向右滑动完成", "请完成验证", "verify you are human", "not a robot")
    private val PAYMENT = listOf("支付密码", "确认支付", "确认付款", "立即支付", "输入密码支付", "payment password", "confirm payment", "pay now")
    private val SMS_CODE = listOf("获取验证码", "发送验证码", "短信验证码", "get code", "send code", "verification code")
    private val ANR = listOf("没有响应", "无响应", "未响应", "isn't responding", "is not responding", "not responding")
    private val CRASH = listOf("屡次停止运行", "已停止运行", "keeps stopping", "has stopped", "已停止工作")
    private val WAIT_BUTTON = listOf("等待", "wait")
    private val CLOSE_BUTTON = listOf("关闭应用", "关闭", "close app", "ok", "确定")

    fun classify(elements: List<UiElement>, foregroundPkg: String?): ScreenCheck {
        if (elements.isEmpty()) return ScreenCheck(ScreenKind.BLANK, "没有可读的界面元素")
        val texts = elements.map { it.label.lowercase() }
        val all = texts.joinToString("\n")
        fun hit(words: List<String>) = words.firstOrNull { it in all }
        val system = foregroundPkg == null || foregroundPkg == "android" || foregroundPkg.startsWith("com.android.systemui")
        if (system || elements.size <= 8) {
            hit(ANR)?.let { w -> return ScreenCheck(ScreenKind.APP_NOT_RESPONDING, w, button(elements, WAIT_BUTTON)) }
            hit(CRASH)?.let { w -> return ScreenCheck(ScreenKind.APP_CRASHED, w, button(elements, CLOSE_BUTTON)) }
        }
        hit(CAPTCHA)?.let { return ScreenCheck(ScreenKind.NEEDS_HUMAN, "验证码/人机验证（$it）") }
        hit(PAYMENT)?.let { return ScreenCheck(ScreenKind.NEEDS_HUMAN, "支付（$it）") }
        if (elements.any { it.node.editable && it.node.password }) return ScreenCheck(ScreenKind.NEEDS_HUMAN, "密码输入框")
        hit(SMS_CODE)?.let { return ScreenCheck(ScreenKind.NEEDS_HUMAN, "短信验证码（$it）") }
        return ScreenCheck(ScreenKind.NORMAL)
    }

    /** The clickable button whose whole label is one of [words]. */
    private fun button(elements: List<UiElement>, words: List<String>): UiElement? =
        words.firstNotNullOfOrNull { w -> elements.firstOrNull { it.label.trim().lowercase() == w && (it.node.clickable || it.node.cls.endsWith("Button")) } }

    /** The line the model reads (null = nothing to say). Advice only: the model decides. */
    fun hint(c: ScreenCheck): String? = when (c.kind) {
        ScreenKind.NORMAL -> null
        ScreenKind.BLANK -> "提示：界面元素读不到（加载中、游戏/画布或安全页面）。看截图操作；还在加载就 Wait。"
        ScreenKind.NEEDS_HUMAN -> "提示：此屏需要用户本人处理（${c.cause}）。如果任务要过这一步，用 Take_over / ask_user 交给用户，不要自己填写密码或验证码。"
        ScreenKind.APP_NOT_RESPONDING -> "提示：系统提示应用无响应（${c.cause}）。点「等待」，或 Back 后重新打开应用。"
        ScreenKind.APP_CRASHED -> "提示：应用已崩溃（${c.cause}）。关闭对话框后用 Launch 重新打开应用。"
    }
}

/**
 * 2.3.0, after Mika's StuckAdvice: remembers how often the loop guard warned and what was typed
 * where. From the second warning on, the model is told it may honestly give up (or recover with
 * Back / Home + Launch); the same text typed again in the same app is pointed out. Pure.
 */
class StuckTracker {
    var warnings = 0
        private set
    private val typed = HashMap<String, Int>()

    /** Call after each executed action; returns advice for the next prompt or null. */
    fun record(action: GuiAction, pkg: String?, guardWarned: Boolean, changed: Boolean?): String? {
        if (changed == true && !guardWarned && action !is GuiAction.Type) { if (warnings > 0) warnings-- }
        if (guardWarned) warnings++
        if (action is GuiAction.Type) {
            val key = "${pkg.orEmpty()}\u0000${action.text.trim()}"
            val n = (typed[key] ?: 0) + 1
            typed[key] = n
            if (n >= 3) return "注意：已在这个应用里输入「${action.text.trim().take(30)}」$n 次。如果搜不到，换关键词或换入口；确实没有就结束并如实说明。"
        }
        if (guardWarned && warnings >= 2) return GIVE_UP
        return null
    }

    companion object {
        const val GIVE_UP = "注意：已多次卡住。先恢复：Back 返回上一页，或 Home 后 Launch 重新打开应用再试；如果都试过仍做不到，就结束任务（失败）并如实说明原因。"
    }
}

/**
 * 2.3.0 step memory: older steps leave the history window, so they are kept as one summary line
 * (apps visited, text typed, how many steps failed or changed nothing). Pure.
 */
class StepMemory {
    private val apps = LinkedHashSet<String>()
    private val typed = LinkedHashSet<String>()
    var failed = 0
        private set
    var noEffect = 0
        private set
    var actions = 0
        private set

    fun record(action: GuiAction, pkgAfter: String?, ok: Boolean, changed: Boolean?) {
        actions++
        pkgAfter?.takeIf { it.isNotEmpty() }?.let { apps += it }
        if (action is GuiAction.Launch) apps += action.app
        if (action is GuiAction.Type) typed += action.text.trim().take(30)
        if (!ok) failed++ else if (changed == false) noEffect++
    }

    /** The line put before the history window when [hidden] steps were cut (null = nothing cut). */
    fun summary(hidden: Int): String? {
        if (hidden <= 0) return null
        val parts = buildList {
            if (apps.isNotEmpty()) add("到过：" + apps.toList().takeLast(6).joinToString("、"))
            if (typed.isNotEmpty()) add("输入过：" + typed.toList().takeLast(4).joinToString("、") { "「$it」" })
            if (failed > 0) add("失败 $failed 次")
            if (noEffect > 0) add("无效果 $noEffect 次")
        }
        return "（前 $hidden 步已省略" + (if (parts.isNotEmpty()) "；" + parts.joinToString("；") else "") + "）"
    }
}

/**
 * 2.3.0 task-done check (Mika verifies answers before accepting them; here the model still judges):
 * a "done" right after an action that failed or changed nothing is sent back once with a request
 * to look at the current screen and confirm. Pure.
 */
object FinishCheck {
    const val MAX_CHECKS = 1

    fun needsCheck(lastOk: Boolean?, lastChanged: Boolean?, checksSoFar: Int): Boolean =
        checksSoFar < MAX_CHECKS && (lastOk == false || lastChanged == false)

    fun note(lastLabel: String): String =
        "你说任务已完成，但上一步「$lastLabel」失败或画面没有变化。请看当前截图确认：确实完成就再次结束任务（finish / terminate），否则继续操作。"
}

/** 2.3.0: the foreground app changed although the action was not meant to switch apps (a mis-tap opened something). Pure. */
object AppJump {
    fun note(action: GuiAction, before: String?, after: String?): String? {
        if (before.isNullOrEmpty() || after.isNullOrEmpty() || before == after) return null
        if (action is GuiAction.Launch || action is GuiAction.Home || action is GuiAction.Back) return null
        // Leaving the launcher / notification shade for an app is the normal way in.
        val b = before.lowercase()
        if ("launcher" in b || b.endsWith(".home") || "systemui" in b || b == "android") return null
        return "注意：前台应用从 $before 变成了 $after。如果这不是任务需要的，用 Back 返回（或 Launch 回到原应用）。"
    }
}
