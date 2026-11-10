package com.iris.localai.engine.llama

import java.util.Locale

/**
 * 2.5.0-beta9: MTP (multi-token prediction) speculative decoding bookkeeping. Pure Kotlin (unit-tested).
 * The native engine reports how many draft tokens it sent for verification and how many the target
 * model accepted; generatedTokens already counts only the tokens shown to the user.
 */
object MtpStats {
    /** Accepted / drafted in [0, 1]; null when nothing was drafted (MTP off, other engines). */
    fun acceptRate(drafted: Int, accepted: Int): Float? =
        if (drafted <= 0) null else (accepted.coerceIn(0, drafted).toFloat() / drafted)

    /** One IrisResource log line per reply, or null without MTP. */
    fun logLine(drafted: Int, accepted: Int, generated: Int, generateUs: Long): String? {
        val r = acceptRate(drafted, accepted) ?: return null
        val tps = if (generateUs > 0) generated * 1_000_000.0 / generateUs else 0.0
        return String.format(Locale.US, "MTP reply: accepted %d/%d drafts (%.1f%%), %d tok, %.2f tok/s", accepted, drafted, r * 100, generated, tps)
    }

    /** Native status string → MTP really running (anything else = normal decoding). */
    fun active(status: String?): Boolean = status?.startsWith("active") == true
}
