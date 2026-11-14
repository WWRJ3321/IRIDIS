package com.iris.localai.engine

/**
 * Pure rules for Qualcomm NPU (LiteRT-LM AOT) models, shared by the settings / backend picker
 * ([BackendCapabilities]) and the full flavour's LiteRT engine + QNN runtime, so the device chip
 * and the model file are always compared the same way.
 */
object NpuIds {
    /** `sm8750` / `SM8750P` / `sm8750-ab` / `QCS8550` → base family id; anything else → null. */
    private val BASE = Regex("^(sm|qcs|mt)(\\d{4})")

    /** `_sm8750` / `.sm8750.` / `-qcs8550.` (optionally `sm8750p`) in a LiteRT-LM file name. */
    private val FILE_SOC = Regex("(?i)(?:^|[_\\-.])((?:sm|qcs|mt)\\d{4})[a-z]{0,2}(?:[_\\-.]|$)")
    private val FILE_EKV = Regex("(?i)(?:^|[_\\-.])ekv(\\d{3,6})(?:[_\\-.]|$)")

    /**
     * Base SoC id of a chip as reported by `Build.SOC_MODEL` ("SM8750", "SM8750P", "SM8750-AB" →
     * "sm8750"). Variants of one family share the same Hexagon generation and run the same AOT
     * graph. Non-Qualcomm-style ids fall back to their leading letters/digits, lower-case.
     */
    fun baseSocId(raw: String?): String? {
        val s = raw.orEmpty().trim().lowercase()
        if (s.isEmpty()) return null
        BASE.find(s)?.let { val b = it.groupValues[1] + it.groupValues[2]; return SOC_ALIAS[b] ?: b }
        return s.takeWhile { it.isLetterOrDigit() }.ifEmpty { null }
    }

    /**
     * 2.5.5: 同一颗芯片的平板版报另一个型号。用户实机（8.8 英寸平板，系统显示「天玑9500 最高 4.21GHz」）
     * Build.SOC_MODEL = "MT8910"，按 MT6993（天玑 9500）处理：NPU 模型、推荐、机型分级都跟着走。
     */
    val SOC_ALIAS: Map<String, String> = mapOf("mt8910" to "mt6993")

    /** Lower-case base SoC id a file was compiled for ("sm8750"), or null for a portable model. */
    fun targetSoc(fileName: String): String? = FILE_SOC.find(fileName)?.groupValues?.get(1)?.lowercase()

    /** KV-cache size an NPU file was compiled with (`_ekv1280_` → 1280), or null when the name doesn't say. */
    fun kvCapacity(fileName: String): Int? = FILE_EKV.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Can a model compiled for [target] run on a device whose chip is [deviceSoc]? Both sides are
     * reduced to their base id, so an `_sm8750` file runs on SM8750 / SM8750P / SM8750-AB, while
     * another family (sm8650 vs sm8750) is still rejected. `qcs*` targets keep the pre-2.0 rule
     * (not checked against the phone's id; the runtime itself rejects a graph it cannot run).
     */
    fun socMatches(target: String, deviceSoc: String?): Boolean {
        val t = baseSocId(target) ?: return false
        if (t.startsWith("qcs")) return true
        return t == baseSocId(deviceSoc)
    }

    /**
     * 2.4.4: MediaTek Dimensity SoCs with official LiteRT-LM NeuroPilot builds (litert-community
     * Gemma3-1B-IT `_mt6989` / `_mt6991` / `_mt6993`): 9300, 9400, 9500. Null for any other chip.
     */
    fun mediatekNpu(manufacturer: String?, model: String?): String? {
        if (!manufacturer.orEmpty().contains("mediatek", true)) return null
        return baseSocId(model)?.takeIf { it in MEDIATEK_NPU }
    }
    val MEDIATEK_NPU = mapOf("mt6989" to "Dimensity 9300", "mt6991" to "Dimensity 9400", "mt6993" to "Dimensity 9500")
    fun isMediatekTarget(target: String?): Boolean = target.orEmpty().lowercase().startsWith("mt")

    /** Hexagon HTP architecture for Snapdragon SoCs LiteRT ships a dispatch library for. */
    fun hexagonArch(manufacturer: String?, model: String?): Int? {
        if (!(manufacturer.equals("QTI", true) || manufacturer.orEmpty().contains("QUALCOMM", true))) return null
        return when (baseSocId(model)) {
            "sm8850" -> 81  // 8 Elite Gen 5
            "sm8750" -> 79  // 8 Elite
            "sm8650" -> 75  // 8 Gen 3
            "sm8550" -> 73  // 8 Gen 2
            "sm8450", "sm8475" -> 69  // 8 Gen 1 / 8+ Gen 1
            else -> null
        }
    }

    /**
     * History budget for an NPU file whose name has no `_ekvNNNN`. LiteRT-LM 0.18 does not report
     * the compiled KV size after load (Engine exposes only the config we passed, maxNumTokens = null
     * for NPU), so this is a per-family default that does not exceed the published graph:
     * Gemma 4 E2B/E4B NPU builds are compiled with a 4096-token context (litert-community model card:
     * "NPU model is benchmarked with 4096 context length"). Unknown families stay at 1024.
     */
    fun defaultKv(fileName: String): Int {
        val n = fileName.lowercase()
        // 2.4.5: litert-community Qwen3-0.6B.mediatek.mt6993 — model card: a16w8, context 4096.
        return if (GEMMA4.any { it in n } || (n.startsWith("qwen3-0.6b") && "mediatek" in n)) GEMMA4_NPU_KV else UNKNOWN_NPU_KV
    }

    /** History budget of an NPU file: its compiled KV size, else [defaultKv]. */
    fun historyBudget(fileName: String): Int = kvCapacity(fileName) ?: defaultKv(fileName)

    private val GEMMA4 = listOf("gemma-4", "gemma4", "gemma_4")
    const val GEMMA4_NPU_KV = 4096
    const val UNKNOWN_NPU_KV = 1024
}

/**
 * 2.0: what to do when the first reply after a fresh GPU/NPU load ends in ERROR before any output.
 * A generic model is reloaded on the CPU and retried; an NPU-only file (AOT-compiled for one
 * Hexagon) cannot run on the CPU, so the retry would only replace the real native error with
 * "NPU 专用模型不能在 CPU 上运行" — report the NPU error instead.
 */
object FirstReplyFallback {
    sealed interface Decision {
        /** Not a first-reply failure: pass the Done through unchanged. */
        data object PassThrough : Decision
        /** Reload on the CPU and run the same request again. */
        data object RetryOnCpu : Decision
        /** Emit this error instead (no retry possible). */
        data class Report(val message: String) : Decision
    }

    fun decide(
        allowRetry: Boolean,
        guardedBackend: Backend?,
        failedBeforeOutput: Boolean,
        npuOnlyModel: Boolean,
        error: String?,
    ): Decision {
        if (!allowRetry || guardedBackend == null || guardedBackend == Backend.CPU || !failedBeforeOutput) return Decision.PassThrough
        if (npuOnlyModel) return Decision.Report(npuMessage(error))
        return Decision.RetryOnCpu
    }

    /** "NPU 上首次生成失败：<native reason>" (the engine's own "推理失败：" prefix is dropped). */
    fun npuMessage(error: String?): String {
        val reason = error.orEmpty().trim().removePrefix("推理失败：").removePrefix("推理失败:").trim()
        return "NPU 上首次生成失败：" + reason.ifEmpty { "未知错误（详见 设置 › 诊断 导出的 iris_logcat.txt）" }
    }
}

/**
 * 2.0: the on-disk QNN runtime is only "installed" when every library it needs is present and
 * non-empty (and, for markers written by 2.0+, has the size recorded at install time). A marker
 * left over a half-deleted or truncated directory used to report 已安装 and then fail at load.
 */
/** 2.1.1: picks the NPU-relevant lines (QNN / LiteRT dispatch / FastRPC) out of a logcat dump. */
object NpuLog {
    private val KEYS = listOf(
        "qnn", "htp", "dispatch", "adsprpc", "fastrpc", "cdsp", "rpcmem", "litert", "skel",
        // 2.1.2: the warm-up failure itself is logged by tflite / the LM executor, not QNN.
        "tflite", "allocate", "tensor", "enomem", "mmap", "dmabuf",
        // 2.4.4: MediaTek NeuroPilot (Neuron adapter, APU / MDLA driver).
        "neuron", "apusys", "apu", "mdla",
    )
    private val LEVEL = Regex("^[EWF]/")

    fun relevant(lines: List<String>, max: Int): String =
        lines.filter { l -> val low = l.lowercase(); KEYS.any { it in low } && (LEVEL.containsMatchIn(l) || "error" in low || "fail" in low) }
            .map { it.trim().take(240) }
            .distinct()
            .takeLast(max)
            .joinToString("\n")

    /** 2.1.2: when no line matches, the last error / fatal lines of our own process instead of nothing. */
    fun fallback(lines: List<String>, max: Int): String =
        lines.filter { Regex("^[EF]/").containsMatchIn(it) }.map { it.trim().take(240) }.distinct().takeLast(max).joinToString("\n")
}

/**
 * 2.1.2: Gemma 4 sm8750 failed prefill warm-up with 「Failed to allocate tensors」 on a Redmi K90 even
 * with the full runtime. Retry with smaller KV sizes (LiteRT-LM #2018: Gemma 4 needs ≥ 1536 =
 * prefill chunk + sliding window) before giving up. null = the size compiled into the file.
 */
object NpuRetry {
    val SMALLER = listOf(2048, 1536)

    fun isAllocFailure(msg: String): Boolean = msg.lowercase().let { "allocate" in it || "warmup" in it }

    fun kvAttempts(npuKv: Int?): List<Int?> = (listOf(npuKv) + SMALLER.filter { npuKv == null || it < npuKv }).distinct()
}

object NpuRuntimeFiles {
    const val DISPATCH = "libLiteRtDispatch_Qualcomm.so"
    /**
     * 2.1.1: Google's LiteRT-LM NPU guide pushes libQnnHtpPrepare.so with the other QAIRT libraries;
     * without it the Gemma 4 sm8750 graph failed prefill warm-up ("Failed to allocate tensors").
     * Adds ~85 MB on disk; a runtime installed by 2.1.0 (five files) now reports 需要修复.
     */
    const val PREPARE = "libQnnHtpPrepare.so"

    fun expected(arch: Int): List<String> =
        listOf(DISPATCH, "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV${arch}Stub.so", "libQnnHtpV${arch}Skel.so", PREPARE)

    sealed interface Check {
        data object Ok : Check
        data object NotInstalled : Check
        data class Broken(val reason: String) : Check
    }

    /** One `file=<name> size=<bytes> sha256=<hex>` line per library, after the header line. */
    fun markerText(header: String, files: List<Triple<String, Long, String>>): String = buildString {
        append(header.trim()).append('\n')
        for ((name, size, sha) in files) append("file=").append(name).append(" size=").append(size).append(" sha256=").append(sha).append('\n')
    }

    /** name → recorded size, from a marker written by [markerText] (empty for pre-2.0 markers). */
    fun recordedSizes(marker: String): Map<String, Long> =
        marker.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(' ').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val name = parts["file"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val size = parts["size"]?.toLongOrNull() ?: return@mapNotNull null
            name to size
        }.toMap()

    /**
     * [marker] = content of `.installed` (null when absent); [sizeOf] = length of a file in the
     * runtime dir, or null when it does not exist.
     */
    fun check(arch: Int, marker: String?, sizeOf: (String) -> Long?): Check {
        if (marker == null) return Check.NotInstalled
        val recorded = recordedSizes(marker)
        val problems = mutableListOf<String>()
        for (name in expected(arch)) {
            val size = sizeOf(name)
            when {
                size == null -> problems += "缺少 $name"
                size <= 0L -> problems += "$name 为空"
                recorded[name] != null && recorded[name] != size -> problems += "$name 大小不符"
            }
        }
        return if (problems.isEmpty()) Check.Ok else Check.Broken(problems.joinToString("，"))
    }
}
