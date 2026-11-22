package com.iris.localai.data

/**
 * r4 下载页精简：同名同用途、只是格式不同（GGUF / MNN …）的推荐模型合成一张卡，卡上点格式切换。纯 JVM，单测。
 */
object FormatPairs {
    /** 按「名字 + 用途」分组，保持原顺序。 */
    fun groups(run: List<RecommendedCatalog.Item>): List<List<RecommendedCatalog.Item>> =
        run.groupBy { it.name + "\u0000" + it.purpose }.values.toList()

    /** 卡上显示哪一个：用户点过的 → 已下载/下载中的 → GGUF（通常更小）→ 第一个。 */
    fun pick(group: List<RecommendedCatalog.Item>, chosen: String?, busy: (RecommendedCatalog.Item) -> Boolean): RecommendedCatalog.Item =
        group.firstOrNull { it.id == chosen }
            ?: group.firstOrNull(busy)
            ?: group.firstOrNull { it.engine == RecommendedCatalog.Engine.GGUF }
            ?: group.first()
}
