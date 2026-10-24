package com.iris.localai.engine

import android.content.Context
import com.iris.localai.util.CrashReporter

/** Persists [LoadCrashGate] across process deaths. The native crash itself never returns to Kotlin. */
class StartupLoadGuard(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True when the fresh signal was only the model-load breadcrumb (native exit, no JNI symbol).
     * Valid after [blockAutoLoad]. A latch left from an earlier start uses the symbol notice.
     */
    private var breadcrumbOnly = false

    /** True when this start must not auto-load the last model. */
    fun blockAutoLoad(): Boolean {
        val trace = CrashReporter.nativeCrashText(app)
        val nativeExit = CrashReporter.latestExitWasNativeCrash(app)
        val crumb = CrashReporter.lastBreadcrumb
        val hit = NativeLoadCrash.hit(trace, crumb, nativeExit)
        breadcrumbOnly = NativeLoadCrash.breadcrumbOnly(trace, crumb, nativeExit)
        if (hit) prefs.edit().putBoolean(KEY, true).commit()
        return LoadCrashGate.skipPreload(prefs.getBoolean(KEY, false), hit)
    }

    fun skipNotice(): String = LoadCrashGate.notice(breadcrumbOnly)

    fun noteLoadSucceeded() {
        if (prefs.getBoolean(KEY, false)) prefs.edit().putBoolean(KEY, false).commit()
    }

    private companion object {
        const val PREFS = "iris_load_guard"
        const val KEY = "skip_preload"
    }
}
