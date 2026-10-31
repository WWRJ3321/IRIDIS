package com.iridis.terminal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * run_command for IRIDIS. `ContentResolver.call("com.iridis.terminal.run", "run_command", null,
 * {command, cwd?, timeoutSec?})` → {exitCode, stdout, stderr, timedOut}. Every call is checked: the
 * caller must hold com.iridis.terminal.permission.RUN_COMMAND (protectionLevel=signature) or be
 * signed with the same certificate as this plugin; anything else gets a SecurityException.
 */
class CommandProvider : ContentProvider() {
    companion object {
        const val PERMISSION = "com.iridis.terminal.permission.RUN_COMMAND"
        const val METHOD = "run_command"
        const val MAX_TIMEOUT_SEC = 600
    }

    override fun onCreate(): Boolean = true

    private fun enforceCaller() {
        val ctx = context ?: throw SecurityException("no context")
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val granted = ctx.checkCallingPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        val sameSigner = ctx.packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
        // The signature permission may be missing when IRIDIS was installed before this plugin;
        // the signer check alone is then enough (it is what protectionLevel=signature means).
        if (!granted && !sameSigner) throw SecurityException("caller is not signed with the IRIDIS certificate")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD) return null
        enforceCaller()
        val command = extras?.getString("command")?.trim().orEmpty()
        val out = Bundle()
        if (command.isEmpty()) { out.putInt("exitCode", -1); out.putString("stderr", "命令为空"); return out }
        val cwd = extras?.getString("cwd")
        val timeoutSec = (extras?.getInt("timeoutSec", 120) ?: 120).coerceIn(1, MAX_TIMEOUT_SEC)
        val ctx = context!!
        val logId = TerminalLog.start(command, byAgent = true)
        val r = try {
            val token = Binder.clearCallingIdentity()
            try { ProotShell.run(ctx, command, cwd, timeoutSec * 1000L) } finally { Binder.restoreCallingIdentity(token) }
        } catch (t: Throwable) {
            ShellOutput(-1, "", "错误：${t.message ?: t.javaClass.simpleName}", false, false)
        }
        TerminalLog.finish(logId, (r.stdout + (if (r.stderr.isNotBlank()) "\n" + r.stderr else "")).trimEnd() + (if (r.timedOut) "\n[超时已终止]" else ""), r.exitCode)
        out.putInt("exitCode", r.exitCode)
        out.putString("stdout", r.stdout)
        out.putString("stderr", r.stderr)
        out.putBoolean("timedOut", r.timedOut)
        out.putBoolean("truncated", r.truncated)
        return out
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
