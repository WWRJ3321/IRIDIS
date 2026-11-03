package com.iris.localai.voice

/**
 * 2.3.3: a card's bound voice is a per-conversation override, never written to the global settings.
 * [speaker] null = keep the global speaker; [clone] "" = no cloned voice while the card speaks.
 */
data class VoiceOverride(val pack: String, val speaker: Int?, val clone: String)

/** The voice actually used (pack, speaker, cloned voice). */
data class VoiceSel(val pack: String, val speaker: Int, val clone: String)

object VoiceBinding {
    /** Override for a card chat; null when the card binds no voice or the bound pack is not installed. */
    fun forCard(bindPack: String, bindSpeaker: Int, bindClone: String, packInstalled: Boolean, cloneExists: Boolean): VoiceOverride? =
        if (bindPack.isBlank() || !packInstalled) null
        else VoiceOverride(bindPack, bindSpeaker.takeIf { it >= 0 }, bindClone.takeIf { it.isNotBlank() && cloneExists } ?: "")

    /** The global choice, with [override] (card chat on screen) on top. Leaving the card chat = null = global. */
    fun effective(global: VoiceSel, override: VoiceOverride?): VoiceSel =
        if (override == null) global else VoiceSel(override.pack, override.speaker ?: global.speaker, override.clone)
}
