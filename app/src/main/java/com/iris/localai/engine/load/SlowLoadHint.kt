package com.iris.localai.engine

/**
 * 1.9.0: what the chat screen says while a GPU load takes long.
 *
 * Why a first GPU load "never ends": LiteRT-LM's `Engine.initialize()` is one blocking native call
 * (shader compile + writing the GPU program cache to filesDir/litert-cache) with no progress
 * callback and no way to cancel it. LiteRtEngine only creeps its bar toward 90 % so it never looks
 * frozen, and EngineManager holds its load lock for the whole call, so nothing else can load
 * meanwhile. There is no timeout in the engine (and native code is not changed in 1.9.0), so the
 * app explains the wait after [NOTICE_SECS] and offers to switch the model to the CPU, which takes
 * effect by reloading as soon as the running load returns. Pure, unit-tested.
 */
object SlowLoadHint {
    const val NOTICE_SECS = 45L
    const val LONG_SECS = 180L

    /** Subtitle under the model name: 「加载中 · GPU · 63 秒」. */
    fun subtitle(backend: Backend?, secs: Long): String {
        val b = backend?.takeIf { it != Backend.CPU }?.let { " · ${it.label}" } ?: ""
        return if (secs > 0) com.iris.localai.I18n.s(com.iris.localai.R.string.engine_fb9f29, b, secs) else com.iris.localai.I18n.s(com.iris.localai.R.string.engine_c94fef, b)
    }

    /** The explanation card, or null while the wait is still normal / not on the GPU. */
    fun notice(backend: Backend?, secs: Long): String? = when {
        backend != Backend.GPU || secs < NOTICE_SECS -> null
        secs < LONG_SECS -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_28da7c)
        else -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_243197, secs / 60, secs % 60)
    }

    val ACTION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_52746f)
    val SWITCHED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_ff73c5)
}
