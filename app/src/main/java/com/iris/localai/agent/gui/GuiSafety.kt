package com.iris.localai.agent.gui

/** What happens with one action before it runs. */
sealed interface GuiVerdict {
    data object Allow : GuiVerdict
    /** Ask the user first (one tap: 允许 / 拒绝). */
    data class Confirm(val reason: String) : GuiVerdict
    /** Never done by the agent; the user is asked to take over. */
    data class Block(val reason: String) : GuiVerdict
}

/**
 * Safety rules for GUI actions. Conservative on purpose: money, sending, deleting, permissions and
 * typing into password fields never happen silently.
 */
object GuiSafety {
    /** Words on a tapped element that mean "this commits something". */
    val SENSITIVE_WORDS = listOf(
        "支付", "付款", "转账", "收款", "提现", "充值", "购买", "下单", "提交订单", "确认订单", "结算",
        "删除", "清空", "卸载", "注销", "退出登录", "格式化", "恢复出厂",
        "发送", "发布", "发表", "转发", "评论",
        "授权", "允许", "同意", "开启权限", "安装",
        "pay", "purchase", "buy", "checkout", "send", "post", "delete", "remove", "uninstall", "install", "allow", "authorize",
    )

    /** Apps the agent never operates by itself (payment and banking) — the user takes over there. */
    val PROTECTED_PACKAGE_HINTS = listOf("com.eg.android.alipaygphone", "bank", "wallet", "com.unionpay")

    /** 2.2.9: words in the model's stated intent (its thought) that make a tap / enter need the user's OK. */
    val INTENT_WORDS = listOf("确认支付", "支付", "付款", "转账", "购买", "下单", "发送", "删除", "pay", "send", "delete")

    fun intentWord(thought: String?): String? {
        val l = thought.orEmpty().lowercase()
        if (l.isBlank()) return null
        return INTENT_WORDS.firstOrNull { w ->
            if (w.all { it in 'a'..'z' }) Regex("(?<![a-z])${Regex.escape(w)}(?![a-z])").containsMatchIn(l) else w in l
        }
    }

    fun review(action: GuiAction, elements: List<UiElement>, screenW: Int, screenH: Int, foregroundPkg: String?, thought: String? = null): GuiVerdict {
        val pkg = foregroundPkg.orEmpty().lowercase()
        val protectedApp = pkg.isNotEmpty() && PROTECTED_PACKAGE_HINTS.any { it in pkg }
        return when (action) {
            is GuiAction.Tap, is GuiAction.LongPress, is GuiAction.DoubleTap -> {
                val at = when (action) { is GuiAction.Tap -> action.at; is GuiAction.LongPress -> action.at; is GuiAction.DoubleTap -> action.at; else -> null }!!
                if (protectedApp) return GuiVerdict.Block("支付/银行类应用由你自己操作")
                (action as? GuiAction.Tap)?.note?.let { return GuiVerdict.Confirm("模型标记为重要操作：$it") }
                val (x, y) = CoordMap.toPixels(at, screenW, screenH)
                val hit = UiTreeSerializer.hit(elements, x, y)
                val word = hit?.label?.let { sensitiveWord(it) }
                if (word != null) return GuiVerdict.Confirm("将点击「${hit.label}」（含「$word」）")
                intentWord(thought)?.let { GuiVerdict.Confirm("将执行「${action.label()}」，意图含「$it」") } ?: GuiVerdict.Allow
            }
            GuiAction.Enter -> intentWord(thought)?.let { GuiVerdict.Confirm("将按回车提交，意图含「$it」") } ?: GuiVerdict.Allow
            is GuiAction.Type -> {
                if (protectedApp) return GuiVerdict.Block("支付/银行类应用由你自己操作")
                val field = elements.firstOrNull { it.node.editable && it.node.focused }?.node
                if (field?.password == true) GuiVerdict.Block("密码由你自己输入") else GuiVerdict.Allow
            }
            is GuiAction.Swipe, is GuiAction.Scroll -> if (protectedApp) GuiVerdict.Block("支付/银行类应用由你自己操作") else GuiVerdict.Allow
            is GuiAction.Launch -> if (PROTECTED_PACKAGE_HINTS.any { it in action.app.lowercase() }) GuiVerdict.Block("支付/银行类应用由你自己操作") else GuiVerdict.Allow
            else -> GuiVerdict.Allow
        }
    }

    fun sensitiveWord(label: String): String? {
        val l = label.lowercase()
        return SENSITIVE_WORDS.firstOrNull { w ->
            if (w.all { it in 'a'..'z' }) Regex("(?<![a-z])${Regex.escape(w)}(?![a-z])").containsMatchIn(l) else w in l
        }
    }
}
