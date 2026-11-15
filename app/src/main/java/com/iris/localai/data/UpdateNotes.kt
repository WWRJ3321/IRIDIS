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
        Release("2.6.1", listOf(
            f("思考：短按开/关，长按选 低 / 中 / 高。"),
            f("思考档位胶囊加弹出动画。"),
            b("下了嵌入模型后知识库几乎不引用。"),
            b("思考后补写回答时 tok/s 不显示。"),
            b("知识库页两张卡片贴在一起。"),
        )),
        Release("2.6.0", listOf(
            x("引擎升级到 llama.cpp 1.0（测试版）。"),
            f("长按「思考」选档位：关 / 低 / 中 / 高，滑动选择、松手生效。"),
            b("Qwen3.5 2B 等小模型开思考没反应。"),
        )),
        Release("2.5.9", listOf(
            f("开发者性能浮窗显示温度（电池温度，42 °C 起标红）。"),
            f("下载推荐新增 LFM2.5 24B-A2B 无审查版（IQ2_XS，8.5 GB）。"),
            b("模型把公式写在反引号里（如 `2^{2m}`、`\\gcd(a,b)=1`）时显示成原始 LaTeX；现在按公式渲染。"),
        )),
        Release("2.5.8", listOf(
            f("模型与加载 › 看懂模型：模型名、格式、参数量、量化、稠密/MoE、手机能跑多大。"),
            f("对话里的公式（$…$、$$…$$、\\(…\\)、\\[…\\]）显示成可读符号，不再是一串 LaTeX。"),
            f("超过 40 行的代码块默认折叠，可展开。"),
            f("智能体生图时显示方形流动色占位框和进度条。"),
            f("存储空间：应用内的模型标「在应用内」；正在用的模型会提示先卸载再移到下载文件夹。"),
            b("MCP 服务工具多时开关点不动；工具列表可折叠，新增「全部开 / 全部关」。"),
        )),
        Release("2.5.7", listOf(
            f("设置 › 智能体 › 生图：可指定智能体生图用的模型，默认自动。"),
            f("生图时内存不够会先卸载文本模型，生完再装回（可关）。"),
            b("生图页模型卡片高度不一。"),
            f("性能与资源的说明文字精简。"),
        )),
        Release("2.5.6", listOf(
            f("下载改为多线程分块（4 路）：断线只重下出错的那一块；下完按 sha256 校验（Hugging Face / hf-mirror），不符自动删掉重下。"),
            f("智能体工具列表的说明改短，少说废话。"),
            f("联网搜索只留谷歌、必应、自建 SearXNG；问新闻直接查必应新闻，按时间排序，卡片不再显示英文说明。"),
            f("加载选项从「模型与加载」移到「生成」，并带自己的「恢复默认」。"),
            f("模型位置收成一行；智能体页技能说明改中文，工作区区域精简。"),
            f("克隆音色和朗读模型合成一组单选：点哪个就用哪个，去掉了「不用克隆音色」。"),
            f("外观 › 配色 新增「烟雨」「云霄」两套配色（可选，默认不变）。"),
            f("上下文长度上限放到 512K（模型设置、全局设置都改了，滑块按倍数走）。"),
            f("聊天输入框上方的上下文菜单里可以直接改上下文长度（2k–512k，不超过模型训练长度），改完自动重新加载模型。"),
            f("设置 › 显示 新增「风险提示弹窗」开关：关掉后加载前内存不足、生图内存偏紧不再弹窗，直接加载 / 生成。"),
            b("开了专家按需加载后 GPU 失效：选了 GPU 时前瞻专家调度（LES）不再自动开启；LES 开关注明「开启后只用 CPU」。"),
        )),
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
