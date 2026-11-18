package com.iris.localai.util

import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*
import com.iris.localai.engine.PromptMessage

/** which prompt message carries the newest user message's pictures. Pure, JVM-tested. */
object VisionPrompt {
    /**
     * Index of the last user prompt message that is [text] (the newest user message with its
     * attachment notes), or that ends with it: 2.1.0 「引用知识库」 puts the snippets in front of the
     * user's words, and an exact match then failed, so the picture was silently left out. -1 = none.
     */
    fun targetIndex(messages: List<PromptMessage>, text: String): Int {
        val want = text.trim()
        if (want.isEmpty()) return -1
        val exact = messages.indexOfLast { it.role == PromptMessage.USER && it.content.trim() == want }
        if (exact >= 0) return exact
        val tail = messages.indexOfLast { it.role == PromptMessage.USER && it.content.trimEnd().endsWith(want) }
        if (tail >= 0) return tail
        // 智能体 mode appends the plan note after the user's words (AgentPrompt.withPlanNote).
        return messages.indexOfLast { it.role == PromptMessage.USER && it.content.contains(want) }
    }

    /** Long side of the picture copy for an engine that asked for [engineMax] (0 = default). */
    fun maxSide(engineMax: Int): Int = if (engineMax > 0) engineMax else Attachments.VISION_MAX_SIDE

    /**
     * 「识图清晰度」 [setting] (px, snapped to 384/512/1024), capped by the engine's own limit
     * [engineMax] (MNN 512: its CPU/GPU ViT at 1024 px took minutes), 0 = no engine limit.
     */
    fun maxSide(engineMax: Int, setting: Int): Int {
        val s = VisionQuality.of(setting).side
        return if (engineMax > 0) minOf(engineMax, s) else s
    }
}

/** 「识图清晰度」: how large a picture the vision model reads. Pure. */
enum class VisionQuality(val side: Int, val label: String) {
    FAST(384, "快速"),
    STANDARD(512, "标准"),
    SHARP(1024, "清晰");

    companion object {
        val DEFAULT = STANDARD
        /** Nearest option for a stored px value (unknown / 0 → 标准). */
        fun of(side: Int): VisionQuality = when {
            side <= 0 -> DEFAULT
            side <= 448 -> FAST
            side <= 768 -> STANDARD
            else -> SHARP
        }
    }
}
