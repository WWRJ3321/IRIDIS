package com.iris.localai.engine

/**
 * 2.5.0-beta10: LiteRT-LM speculative decoding (Gemma 4 .litertlm files ship an MTP drafter since
 * 2026-05) behind the same 「MTP 加速（实验）」 switch. Pure Kotlin (unit-tested).
 */
object LiteRtMtp {
    /** Requested, not after a crash (safe mode), LiteRT-LM file, CPU / GPU only (NPU files are AOT-compiled). */
    fun enabled(requested: Boolean, safeMode: Boolean, isLitert: Boolean, backend: Backend): Boolean =
        requested && !safeMode && isLitert && backend != Backend.NPU

    private val GEMMA4_SMALL = Regex("gemma[-_ ]?4.*e[24]b", RegexOption.IGNORE_CASE)

    /** 「MTP」 tag without probing the file: only Gemma 4 E2B / E4B .litertlm (NPU-compiled builds excluded). */
    fun tag(fileName: String): Boolean =
        fileName.endsWith(".litertlm", ignoreCase = true) && GEMMA4_SMALL.containsMatchIn(fileName) &&
            NpuIds.targetSoc(fileName) == null

    /** Init attempts in order: with the drafter first when asked, then always plain decoding. */
    fun attempts(wanted: Boolean): List<Boolean> = if (wanted) listOf(true, false) else listOf(false)
}
