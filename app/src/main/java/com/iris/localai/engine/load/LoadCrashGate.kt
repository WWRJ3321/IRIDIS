package com.iris.localai.engine

/**
 * Stops a native load crash from becoming a startup loop.
 *
 * `autoLoadLastModel` loads the last model as soon as the UI comes up. A SIGSEGV in
 * `MnnNative_nativeLoad` is not a Java exception, and [BackendCrashMemory] does not remember CPU
 * deaths, so the next cold start loads the same model and dies again. The latch stays set until
 * one load actually finishes.
 */
object NativeLoadCrash {
    fun traceShowsLoad(text: String): Boolean =
        "MnnNative_nativeLoad" in text || "LlamaNative_nativeLoad" in text

    /**
     * Breadcrumb written around [EngineManager] load. The file is prefixed with a timestamp.
     * Voice crumbs ("加载语音…") do not match.
     */
    fun breadcrumbShowsLoad(text: String?): Boolean = text?.contains("加载模型 ") == true

    /**
     * A model-load breadcrumb is only a crash when the latest exit really was a native crash
     * and the tombstone did not name `nativeLoad` (stream empty, or the symbol was stripped).
     * A swipe or a low-memory kill during load leaves the same breadcrumb and must not latch.
     */
    fun breadcrumbOnly(trace: String, breadcrumb: String?, latestExitNative: Boolean): Boolean =
        latestExitNative && !traceShowsLoad(trace) && breadcrumbShowsLoad(breadcrumb)

    fun hit(trace: String, breadcrumb: String?, latestExitNative: Boolean): Boolean =
        traceShowsLoad(trace) || breadcrumbOnly(trace, breadcrumb, latestExitNative)
}

object LoadCrashGate {
    val SKIP_NOTICE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_5fe370)
    /** Native crash during load, but the tombstone did not contain the JNI symbol. */
    val SKIP_NOTICE_NO_SYMBOL: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_33b3b1)

    fun notice(breadcrumbOnly: Boolean): String = if (breadcrumbOnly) SKIP_NOTICE_NO_SYMBOL else SKIP_NOTICE

    fun skipPreload(persisted: Boolean, crashedAgain: Boolean): Boolean = persisted || crashedAgain

    /** [loadSucceeded] clears the latch; anything else keeps it once a native load death was seen. */
    fun latch(persisted: Boolean, crashedAgain: Boolean, loadSucceeded: Boolean): Boolean =
        if (loadSucceeded) false else persisted || crashedAgain
}
