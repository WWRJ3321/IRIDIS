@file:Suppress("unused")
package android.util

/**
 * 2.4.5 test-only: android.jar's Log throws "Stub!" on the plain JVM (runtests245.sh has no
 * Robolectric), and AgentLoop logs. No-op replacement, first on the test classpath.
 */
object Log {
    @JvmStatic fun v(tag: String?, msg: String?): Int = 0
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun d(tag: String?, msg: String?): Int = 0
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = 0
    @JvmStatic fun e(tag: String?, msg: String?): Int = 0
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = false
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = ""
    @JvmStatic fun println(priority: Int, tag: String?, msg: String?): Int = 0
}
