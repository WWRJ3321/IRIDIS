package com.iris.localai.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * How glass surfaces are drawn right now.
 *
 * 1.7.3: the AGSL liquid lens was removed (on the phone it drew a thick black rim, blue dispersion
 * fringes and a square white block that ignored the rounded corners). Only frosted glass remains.
 */
enum class GlassMode {
    /** Semi-opaque tint + rim. No offscreen work at all. */
    TRANSLUCENT,
    /** Backdrop blurred (downsampled) + vibrancy + tint. No shaders. */
    FROSTED,
}

/**
 * Everything a glass surface needs to know about the current budget. Resolved once per
 * composition from the user's 视觉效果 level and the device state ([GlassPolicy.resolve]).
 */
@Immutable
data class GlassQuality(
    val mode: GlassMode,
    /** Downsampling for small surfaces (composer, buttons, bars) and large ones (drawer, full-screen). */
    val downsampleSmall: Int,
    val downsampleLarge: Int,
    /** Multiplier on each surface's nominal blur radius. */
    val blurScale: Float,
    /** System cross-window blur behind dialogs and sheets (华丽 only, 1.7.3). */
    val windowBlur: Boolean,
    /** Why this was chosen, for the settings subtitle ("温度较高，已降级" …). */
    val downgradedBy: Downgrade = Downgrade.NONE,
) {
    val blurs: Boolean get() = mode != GlassMode.TRANSLUCENT

    enum class Downgrade { NONE, POWER_SAVE, THERMAL, USER_OFF }
}

object GlassPolicy {
    const val LEVEL_ECO = 0
    const val LEVEL_BALANCED = 1
    const val LEVEL_RICH = 2
    val LEVEL_LABELS = listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.glass_95f6bb), com.iris.localai.I18n.s(com.iris.localai.R.string.glass_f07d8f), com.iris.localai.I18n.s(com.iris.localai.R.string.glass_83bf12))

    /** PowerManager.THERMAL_STATUS_* (kept as ints so this stays a plain unit-tested object). */
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    private val TRANSLUCENT = GlassQuality(GlassMode.TRANSLUCENT, 1, 1, 1f, windowBlur = false)

    /**
     * @param level user setting 0 省电 / 1 均衡 / 2 华丽
     * @param glassSwitch the pre-1.7.1 "毛玻璃" switch (off → 省电)
     *
     * 均衡 and 华丽 are both frosted glass; they differ only in blur resolution
     * (华丽 downsample 1 / 2, 均衡 2 / 4) and blur strength, and only 华丽 blurs behind dialogs.
     */
    fun resolve(
        level: Int,
        glassSwitch: Boolean = true,
        powerSave: Boolean = false,
        thermalStatus: Int = 0,
        generating: Boolean = false,
    ): GlassQuality {
        val lvl = if (!glassSwitch) LEVEL_ECO else level.coerceIn(LEVEL_ECO, LEVEL_RICH)
        if (lvl == LEVEL_ECO) return TRANSLUCENT.copy(downgradedBy = if (!glassSwitch) GlassQuality.Downgrade.USER_OFF else GlassQuality.Downgrade.NONE)
        if (powerSave) return TRANSLUCENT.copy(downgradedBy = GlassQuality.Downgrade.POWER_SAVE)
        if (thermalStatus >= THERMAL_SEVERE) return TRANSLUCENT.copy(downgradedBy = GlassQuality.Downgrade.THERMAL)

        val rich = lvl == LEVEL_RICH
        val base = GlassQuality(
            mode = GlassMode.FROSTED,
            downsampleSmall = if (rich) 1 else 2,
            downsampleLarge = if (rich) 2 else 4,
            blurScale = if (rich) 1f else 0.85f,
            windowBlur = rich,
        )
        return when {
            thermalStatus >= THERMAL_MODERATE ->
                base.copy(downsampleSmall = 3, downsampleLarge = 4, windowBlur = false, downgradedBy = GlassQuality.Downgrade.THERMAL)
            // 1.13.0: while the model runs (often on the same GPU) the blur works on a quarter-size
            // copy: the frosted look stays, its GPU cost drops ~4×.
            generating -> base.copy(downsampleSmall = maxOf(base.downsampleSmall * 2, 3), downsampleLarge = maxOf(base.downsampleLarge * 2, 4))
            else -> base
        }
    }
}

/** Current glass budget; provided at the theme root. */
val LocalGlassQuality = compositionLocalOf { GlassPolicy.resolve(GlassPolicy.LEVEL_BALANCED) }
