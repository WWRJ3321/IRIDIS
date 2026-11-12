package com.iris.localai.engine

/**
 * 2.5.1: MoE 专家按需加载（专家张量留 CPU / mmap 按需读盘）与激活专家数的决策，纯函数便于测试。
 *  - 设置 null = 自动：模型是 MoE 且常规内存预检为 SHORT 时开启；true/false = 用户指定（非 MoE 一律不开）。
 *  - 激活专家数 0 = 模型默认；>0 时 clamp 到 [1, min(32, expert_count)]，非 MoE 返回 0。
 */
object MoeLoad {
    const val MAX_EXPERTS_UI = 32

    fun active(setting: Boolean?, info: GgufQuant.Info?, plainLevel: MemoryPreflight.Level): Boolean =
        info?.moe == true && (setting ?: (plainLevel == MemoryPreflight.Level.SHORT))

    fun expertUsed(setting: Int, info: GgufQuant.Info?): Int {
        if (info?.moe != true || setting <= 0) return 0
        return setting.coerceIn(1, minOf(MAX_EXPERTS_UI, info.expertCount))
    }

    /** 非专家张量字节（总张量未知时用文件大小近似）。 */
    fun nonExpertBytes(info: GgufQuant.Info, fileBytes: Long): Long =
        ((if (info.tensorBytes > 0) info.tensorBytes else fileBytes) - info.expsBytes).coerceAtLeast(0)
}
