package com.iris.localai.engine.edge0

import java.io.File

/**
 * 实验: Edge0-35B (edge0-infer) runs only on arm64 CPUs with i8mm (armv8.6-a: 骁龙 8 Gen 2 /
 * 天玑 9200 及以后). libiris_edge0.so is built for armv8.6-a+dotprod+i8mm+fp16, so it is never even
 * loaded on other phones. Pure parts unit-tested (V265Test).
 */
object Edge0Support {
    /** Features the library needs, as /proc/cpuinfo spells them. */
    val REQUIRED = listOf("asimddp", "i8mm", "bf16", "fphp")

    /** [features] = the "Features" line(s) of /proc/cpuinfo; [abis] = Build.SUPPORTED_ABIS. */
    fun cpuOk(abis: List<String>, features: String): Boolean {
        if ("arm64-v8a" !in abis) return false
        val set = features.split(Regex("\\s+")).toSet()
        return REQUIRED.all { it in set }
    }

    /** Every core's Features line must have them (big.LITTLE phones list them per core). */
    fun cpuinfoOk(abis: List<String>, cpuinfo: String): Boolean {
        val lines = cpuinfo.lines().filter { it.trimStart().startsWith("Features", ignoreCase = true) }
        if (lines.isEmpty()) return false
        return lines.all { cpuOk(abis, it.substringAfter(':')) }
    }

    @Volatile private var cached: Boolean? = null

    fun deviceOk(): Boolean = cached ?: runCatching {
        cpuinfoOk(android.os.Build.SUPPORTED_ABIS.toList(), File("/proc/cpuinfo").readText())
    }.getOrDefault(false).also { cached = it }
}

/** An Edge0 asset directory (make_assets.py output). Pure, unit-tested. */
object Edge0Assets {
    const val ARCH = "edge0_35b"
    val FILES = setOf("assets.tsv", "model.json", "base.gguf", "lora.gguf", "prerouter.gguf", "tokenizer.json")

    fun isFileSet(names: Set<String>): Boolean = FILES.all { it in names }

    /** model.json must name the one architecture compiled into the library. */
    fun modelJsonOk(text: String): Boolean =
        Regex("\"architecture\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) == ARCH

    fun isAssetDir(dir: File): Boolean =
        dir.isDirectory && isFileSet(dir.list().orEmpty().toSet()) &&
            runCatching { modelJsonOk(File(dir, "model.json").readText()) }.getOrDefault(false)

    /** Repo of our converted copy (HF; hf-mirror is tried too through DownloadSources). */
    const val REPO = "WWRJ-2025/Edge0-35B-IRIDIS"
}
