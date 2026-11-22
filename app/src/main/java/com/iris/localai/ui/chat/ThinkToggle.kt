package com.iris.localai.ui.chat

/**
 * 3.0.0 Beta3 r5：输入框「思考」开关的纯逻辑（单测 V300B3R5Test）。
 *
 * r4 之前的坑：① 没加载模型时点「关」只写了全局，模型一加载、它自己的思考设置（开）又盖回来；
 * ② 长按档位条松手（哪怕没滑动、只是按久了一点）会选中当前档并把思考重新打开。
 */
object ThinkToggle {
    /** 写入结果：[global] 总写；[model] 非空 = 目标模型有自己的思考设置，也改成同一个值。 */
    data class Write(val global: Boolean, val model: Boolean?)

    fun write(on: Boolean, modelOverride: Boolean?): Write = Write(on, if (modelOverride != null) on else null)

    /** 按钮显示的状态：目标模型自己的设置优先，否则全局。 */
    fun shown(global: Boolean, modelOverride: Boolean?): Boolean = modelOverride ?: global

    /** 开关作用在哪个模型上：已就绪 → 正在加载 → 加载失败的 → 上次用的（启动预加载会加载它）。 */
    fun targetId(ready: String?, loading: String?, failed: String?, last: String?): String? =
        ready ?: loading ?: failed ?: last?.takeIf { it.isNotBlank() }

    /** 长按档位条松手：没滑动 = 只是看一眼，开关不变（null）；滑到某档 = 用这档（并打开思考）。 */
    fun onRelease(moved: Boolean, level: Int): Int? = if (moved) level.coerceIn(1, 3) else null

    /** 按钮可点：没有就绪模型时可点（先记住）；就绪时模型支持思考或模板强制思考（关 = 隐藏思考过程）。 */
    fun enabled(ready: Boolean, supports: Boolean?, thinkAlways: Boolean): Boolean = !ready || supports == true || thinkAlways
}
