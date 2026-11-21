package com.iris.localai.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3.0：桌面快捷方式、小组件、悬浮窗、灵动岛要打开 App 里某一页时，经这里排队，
 * MainActivity 的导航取走（[consume]）。动作名也是 Intent 的 [EXTRA] 值。
 */
object QuickNav {
    const val EXTRA = "iris.quick"

    const val NEW_CHAT = "new_chat"
    const val VOICE = "voice"
    const val IMAGEGEN = "imagegen"
    const val TOOLBOX = "toolbox"
    const val FLOATING = "floating"
    const val GUIDE = "guide"
    const val DOWNLOADS = "downloads"

    val ALL = setOf(NEW_CHAT, VOICE, IMAGEGEN, TOOLBOX, FLOATING, GUIDE, DOWNLOADS)

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** 语音聊天由聊天页自己打开（它管麦克风权限），单独一路。 */
    private val _voice = MutableStateFlow(false)
    val voice: StateFlow<Boolean> = _voice.asStateFlow()

    fun open(action: String?) {
        if (action == null || action !in ALL) return
        if (action == VOICE) _voice.value = true
        _pending.value = action
    }

    fun consume() { _pending.value = null }
    fun consumeVoice() { _voice.value = false }
}
