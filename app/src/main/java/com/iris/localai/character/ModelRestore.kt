package com.iris.localai.character

/**
 * 2.3.4: leaving a card chat whose card loaded its bound model no longer leaves the user guessing:
 * a one-time hint offers to switch back to the model that was loaded before the card (no auto switch).
 */
object ModelRestore {
    /** Path to offer when leaving the card chat ([fromCard] → [toCard]); null = no hint (same model / none remembered / not leaving). */
    fun offer(fromCard: String?, toCard: String?, previousPath: String?, currentPath: String?): String? =
        if (fromCard == null || toCard != null || previousPath.isNullOrBlank() || previousPath == currentPath) null else previousPath
}
