package com.iris.localai.engine

import com.iris.localai.engine.load.*
import com.iris.localai.engine.resources.*

/**
 * 思考档位: long-press 思考 → 关 / 低 / 中 / 高.
 * Models with their own effort levels (gpt-oss, Spark) get those; the rest (Qwen …) get a cap on
 * the length of the reasoning: 低 1K, 中 4K, 高 none (the answer is then written with thinking off).
 */
object ThinkLevel {
    const val UNSET = 0
    const val LOW = 1
    const val MID = 2
    const val HIGH = 3

    const val LOW_TOKENS = 1024
    const val MID_TOKENS = 4096

    /** Reasoning budget for [level]; [fallback] (设置 › 思考预算) when no level was chosen. */
    fun budget(level: Int, fallback: Int, effortKind: String = ""): Int = when {
        effortKind.isNotEmpty() -> 0
        level == LOW -> LOW_TOKENS
        level == MID -> MID_TOKENS
        level == HIGH -> 0
        else -> fallback
    }

    /** Effort passed to the engine (0 = model default); only for models that have levels. */
    fun effort(level: Int, effortKind: String): Int =
        if (effortKind.isEmpty() || level !in LOW..HIGH) 0 else level
}
