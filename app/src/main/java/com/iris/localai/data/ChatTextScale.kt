package com.iris.localai.data

import kotlin.math.roundToInt

/**
 * 对话文字大小（2.7.6 vc283）：设置里一个滑块，按百分比只缩放对话正文（消息气泡、助手 Markdown 与
 * 代码块、思考块、输入框），其余界面不变。存为 [AppSettings.chatTextScale]（百分比整数）。
 * 纯函数，单测覆盖；Compose 侧见 ui/theme/ChatTextScaleLocal.kt。
 */
object ChatTextScale {
    const val MIN = 85
    const val MAX = 130
    const val STEP = 5
    const val DEFAULT = 100

    /** 滑块的中间刻度数（Slider steps 不含两端）。 */
    val sliderSteps: Int get() = (MAX - MIN) / STEP - 1

    /** 任意值（旧数据、API、滑块浮点）→ 合法百分比：夹在 [MIN, MAX]，吸附到 [STEP]。 */
    fun sanitize(percent: Int): Int {
        val c = percent.coerceIn(MIN, MAX)
        return (MIN + ((c - MIN).toFloat() / STEP).roundToInt() * STEP).coerceIn(MIN, MAX)
    }

    /** 滑块浮点值 → 百分比。NaN 回到默认。 */
    fun fromSlider(v: Float): Int = if (v.isNaN()) DEFAULT else sanitize(v.roundToInt())

    /** 百分比 → 字号倍数（1.0 = 原样）。 */
    fun factor(percent: Int): Float = sanitize(percent) / 100f

    /** 设置里显示的数值，如 「115%」。 */
    fun label(percent: Int): String = "${sanitize(percent)}%"
}
