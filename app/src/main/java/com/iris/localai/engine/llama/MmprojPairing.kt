package com.iris.localai.engine.llama

import com.iris.localai.engine.geniex.GenieXVision

/**
 * 2.2.17: which vision projector (mmproj) goes with a GGUF model for llama.cpp mtmd. Pure.
 *
 * Order: the user's pick in 模型配置 ([userPick]: a file name in the same folder; "" = 不使用) →
 * a projector downloaded with the model (`<model>-mmproj-*.gguf`) → a projector sharing the model's
 * repo name (`mmproj-<repo>-f16.gguf`, `<repo>-mmproj.gguf`) → the folder's only projector when the
 * model name looks like a VLM. Null = text-only.
 */
object MmprojPairing {
    /** Value of [com.iris.localai.data.ModelOverrides.mmproj] meaning "never load a projector". */
    const val OFF = ""

    fun isProjector(name: String): Boolean = com.iris.localai.data.isProjectorFile(name) && name.lowercase().endsWith(".gguf")

    fun pick(modelFile: String, siblings: Collection<String>, userPick: String? = null): String? {
        if (isProjector(modelFile) || !modelFile.lowercase().endsWith(".gguf")) return null
        if (userPick != null) return if (userPick == OFF) null else userPick.takeIf { it in siblings && isProjector(it) }
        val projectors = siblings.filter { it != modelFile && isProjector(it) }
        if (projectors.isEmpty()) return null
        val stem = modelFile.replace(com.iris.localai.data.GgufSearch.SPLIT, ".gguf").removeSuffix(".gguf").removeSuffix(".GGUF")
        projectors.firstOrNull { it.startsWith("$stem-") }?.let { return it }
        return GenieXVision.findMmproj(modelFile, projectors) ?: run {
            val lower = modelFile.lowercase()
            val vlm = VLM_HINTS.any { it in lower }
            projectors.singleOrNull()?.takeIf { vlm }
        }
    }

    /** Projector choices offered in 模型配置 for this model (same folder). */
    fun candidates(modelFile: String, siblings: Collection<String>): List<String> =
        siblings.filter { it != modelFile && isProjector(it) }.sorted()

    private val VLM_HINTS = listOf("vl", "vision", "llava", "omni", "gemma-3", "gemma3", "gemma-4", "gemma4",
        "minicpm-v", "smolvlm", "pixtral", "internvl", "moondream", "lfm2-vl")

    /** Index of the message whose pictures go to mtmd: the last one carrying images, or -1. */
    fun imageMessage(images: List<List<String>>): Int = images.indexOfLast { it.isNotEmpty() }

    const val NO_MMPROJ_HINT = "此 GGUF 模型没有配套的 mmproj 视觉文件，只会收到图片的文件名。下载带 mmproj 的版本，或在 模型配置 → 视觉投影 中选择"
}
