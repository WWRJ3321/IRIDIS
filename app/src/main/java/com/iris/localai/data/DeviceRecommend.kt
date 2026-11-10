package com.iris.localai.data

/**
 * 2.5.0 Beta6: 按机型推荐模型 — one chat model for this phone, from its total RAM and chip class, shown at
 * the top of 下载模型 › 推荐 for a new user. Pure (unit-tested by source checks); the app passes
 * ActivityManager.MemoryInfo.totalMem and Build.SOC_MANUFACTURER / SOC_MODEL.
 */
object DeviceRecommend {
    enum class Chip { FLAGSHIP, MID, ENTRY, UNKNOWN }
    enum class Vendor { QUALCOMM, MEDIATEK, OTHER }

    private const val GIB = 1024L * 1024 * 1024

    fun vendor(manufacturer: String?, soc: String?): Vendor {
        val m = manufacturer.orEmpty().lowercase()
        val s = soc.orEmpty().lowercase()
        return when {
            "qualcomm" in m || "qti" in m || s.startsWith("sm") || s.startsWith("sdm") -> Vendor.QUALCOMM
            "mediatek" in m || s.startsWith("mt") -> Vendor.MEDIATEK
            else -> Vendor.OTHER
        }
    }

    /** Chip class from Build.SOC_MODEL ("SM8750", "MT6991"…); UNKNOWN when it says nothing useful. */
    fun chip(manufacturer: String?, soc: String?): Chip {
        val s = soc.orEmpty().lowercase().trim()
        val num = Regex("\\d{3,4}").find(s)?.value?.toIntOrNull()
        return when (vendor(manufacturer, soc)) {
            Vendor.QUALCOMM -> when {
                num == null -> Chip.UNKNOWN
                s.startsWith("sm8") && num >= 8450 -> Chip.FLAGSHIP   // 骁龙 8 Gen 1 及以后
                s.startsWith("sm8") || s.startsWith("sm7") -> Chip.MID
                else -> Chip.ENTRY
            }
            Vendor.MEDIATEK -> when {
                num == null -> Chip.UNKNOWN
                num in setOf(6983, 6985, 6989, 6991, 6993) -> Chip.FLAGSHIP // 天玑 9000 / 9200 / 9300 / 9400 / 9500
                num in 6877..6899 -> Chip.MID                            // 天玑 1080–8400
                else -> Chip.ENTRY
            }
            Vendor.OTHER -> when {
                s.startsWith("tensor") || s.startsWith("gs") || s.startsWith("zuma") || s.startsWith("s5e99") -> Chip.FLAGSHIP
                else -> Chip.UNKNOWN
            }
        }
    }

    /** Box size of the RAM (totalMem is a bit below it: a 12 GB phone reports ~11.2 GiB). */
    fun ramGb(totalBytes: Long): Int {
        if (totalBytes <= 0) return 0
        val gib = totalBytes.toDouble() / GIB
        return listOf(2, 3, 4, 6, 8, 12, 16, 24, 32).firstOrNull { it >= gib } ?: kotlin.math.ceil(gib).toInt()
    }

    /** Catalog id of the recommended chat model; null when RAM is unknown. */
    fun pickId(totalBytes: Long, chip: Chip): String? {
        if (totalBytes <= 0) return null
        val gib = totalBytes.toDouble() / GIB
        val strong = chip == Chip.FLAGSHIP
        val weak = chip == Chip.ENTRY
        val repo = when {
            gib >= 14.5 && strong -> "Qwen3.5-9B-MNN"
            gib >= 10.5 && !weak -> "Qwen3.5-4B-MNN"
            gib >= 7.0 && strong -> "Qwen3.5-4B-MNN"
            gib >= 7.0 -> "Qwen3.5-2B-MNN"
            gib >= 5.0 && !weak -> "Qwen3.5-2B-MNN"
            else -> "Qwen3.5-0.8B-MNN"
        }
        return "taobao-mnn/$repo"
    }

    fun pick(totalBytes: Long, chip: Chip): RecommendedCatalog.Item? = pickId(totalBytes, chip)?.let(RecommendedCatalog::byId)
}
