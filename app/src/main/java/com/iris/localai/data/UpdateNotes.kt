package com.iris.localai.data

/**
 * 2.5.5: 「此次更新」与「更新历史」。每版分新功能（正式 / 实验）与 Bug 修复，内容摘自 CHANGELOG.md（中文）。
 * 纯数据 + 纯函数，单元测试覆盖。新版本：在 [RELEASES] 最前面加一条，版本号与 versionName 一致。
 */
object UpdateNotes {
    enum class Tag { FEATURE, EXPERIMENTAL, FIX }
    data class Item(val tag: Tag, val text: String)
    data class Release(val version: String, val items: List<Item>) {
        fun of(tag: Tag) = items.filter { it.tag == tag }
    }

    private fun f(t: String) = Item(Tag.FEATURE, t)
    private fun x(t: String) = Item(Tag.EXPERIMENTAL, t)
    private fun b(t: String) = Item(Tag.FIX, t)

    val RELEASES: List<Release> = listOf(
        Release("2.5.5", listOf(
            f("新增「此次更新」与「更新历史」：升级后首次打开弹出一次，设置 › 关于与调试 › 更新历史 可随时查看。"),
            f("模型可自定义显示名称：模型设置 › 名称，只改显示，不改文件名；清空恢复原名。"),
            f("下载 › 推荐 新增南北阁 Nanbeige 4.2 3B（Q4_K_M）：循环结构，比同尺寸模型慢。"),
            x("天玑 9500 平板（芯片报 MT8910）识别为天玑 9500：可安装联发科 NPU 运行库、下载官方 NPU 模型；平板版是否能跑 NPU 尚未实测。"),
            b("天玑 9500 平板被识别为未知芯片：NPU 显示「暂无联发科 NPU 模型」，推荐按入门机处理。"),
            f("「专家按需加载」「前瞻专家调度」「激活专家数」从「采样、性能与缓存」折叠里移出，MoE 模型单独成组显示。"),
        )),
        Release("2.5.4", listOf(
            x("前瞻专家调度（LES）：仅 Qwen3.5 MoE 架构，预读下一层专家、缓存常用专家；开启时在 CPU 上运行。"),
            f("下载源统一为设置里的一处，所有下载共用。"),
            b("Three.js 等网页预览偶尔空白。"),
        )),
        Release("2.5.3", listOf(
            f("新手引导改为聚光灯式界面导览（22 步），可跳过、上一步、重看。"),
            f("设置、回收站、下载列表等加入展开 / 收起与淡入淡出动画。"),
            f("界面风格「柔光」更名「简约」。"),
        )),
        Release("2.5.2", listOf(
            f("MCP 不再限制 20 个工具。"),
            f("知识库嵌入模型新增 Q4 版本。"),
            b("GBK 等非 UTF-8 网页乱码；搜索结果跳转链接未解包。"),
            b("MoE 专家按需加载在内存「偏紧」时未开启，可能崩溃。"),
            b("网页预览全屏转屏后超出屏幕。"),
        )),
        Release("2.5.1", listOf(
            x("专家按需加载（MoE）：专家留在 CPU 从存储按需读取，省内存但较慢。"),
            f("激活专家数可调（MoE）。"),
            f("推荐新增 Gemma 4 19B REAP。"),
        )),
    )

    fun find(version: String): Release? = RELEASES.firstOrNull { it.version == base(version) }

    /** "2.5.5-full" / "2.5.5 beta" → "2.5.5"。 */
    fun base(version: String): String = Regex("^\\d+(\\.\\d+)+").find(version.trim())?.value ?: version.trim()

    /**
     * 升级后首次打开弹一次：欢迎页、新手引导都结束后，看过的版本 ≠ 当前版本且当前版本有内容。
     * 全新安装在引导结束时直接记为已看（见 MainActivity），不会紧接着再弹。
     */
    fun shouldShow(welcomeAccepted: Boolean, tourDone: Boolean, seen: String, current: String): Boolean =
        welcomeAccepted && tourDone && base(seen) != base(current) && find(current) != null
}
