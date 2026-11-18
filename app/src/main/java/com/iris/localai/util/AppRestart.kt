package com.iris.localai.util

import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * restarts the app process (used when a native GPU load hangs: the stuck JNI call
 * cannot be interrupted and holds the engine thread). [RestartActivity] runs in its own
 * process, kills the old one and starts the launcher activity again.
 */
object AppRestart {
    fun restart(context: Context) {
        val i = Intent(context, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
        context.startActivity(i)
    }
}

class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0) android.os.Process.killProcess(pid)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(it)
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object { const val EXTRA_PID = "pid" }
}
