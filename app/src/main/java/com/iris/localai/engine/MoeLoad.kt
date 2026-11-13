package com.iris.localai.engine

/**
 * 2.5.1: MoE 专家按需加载（专家张量留 CPU / mmap 按需读盘）与激活专家数的决策，纯函数便于测试。
 *  - 设置 null = 自动：模型是 MoE 且常规内存预检不是 OK（TIGHT 或 SHORT）时开启（2.5.2：原先只在 SHORT 开，TIGHT 时会直接 OOM）；true/false = 用户指定（非 MoE 一律不开）。
 *  - 激活专家数 0 = 模型默认；>0 时 clamp 到 [1, min(32, expert_count)]，非 MoE 返回 0。
 */
object MoeLoad {
    const val MAX_EXPERTS_UI = 32

    fun active(setting: Boolean?, info: GgufQuant.Info?, plainLevel: MemoryPreflight.Level): Boolean =
        info?.moe == true && (setting ?: (plainLevel != MemoryPreflight.Level.OK))

    fun expertUsed(setting: Int, info: GgufQuant.Info?): Int {
        if (info?.moe != true || setting <= 0) return 0
        return setting.coerceIn(1, minOf(MAX_EXPERTS_UI, info.expertCount))
    }

    /** 2.5.4: LES 只支持 qwen35moe（MiniCPM-V 4.7 / Qwen3.5 MoE 架构）。 */
    const val LES_ARCH = "qwen35moe"

    fun lesSupported(info: GgufQuant.Info?): Boolean = info?.moe == true && info.arch == LES_ARCH

    /** 2.5.4: LES 设置 null = 自动：专家按需加载生效时开；true/false = 用户指定（架构不符一律不开）。 */
    fun lesActive(setting: Boolean?, info: GgufQuant.Info?, moeActive: Boolean): Boolean =
        lesSupported(info) && (setting ?: moeActive)

    /** 非专家张量字节（总张量未知时用文件大小近似）。 */
    fun nonExpertBytes(info: GgufQuant.Info, fileBytes: Long): Long =
        ((if (info.tensorBytes > 0) info.tensorBytes else fileBytes) - info.expsBytes).coerceAtLeast(0)
}
