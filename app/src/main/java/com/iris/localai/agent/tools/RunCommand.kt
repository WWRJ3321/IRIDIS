package com.iris.localai.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.serialization.json.JsonObject

/** 2.2.3: result of one command run by the terminal plugin. */
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

/** 2.2.3: runs a command in the terminal plugin (injected so the tool stays JVM-testable). */
fun interface CommandRunner {
    fun run(command: String, cwd: String?, timeoutSec: Int): CommandResult
}

/**
 * 2.2.3: the optional IRIDIS Terminal plugin (separate APK com.iridis.terminal, Alpine under PRoot).
 * The main app ships no terminal: `run_command` exists only while the plugin is installed AND signed
 * with the same certificate as IRIDIS. Commands go through the plugin's ContentProvider `call`,
 * guarded by a signature-level permission.
 */
object TerminalPlugin {
    const val PACKAGE = "com.iridis.terminal"
    const val AUTHORITY = "com.iridis.terminal.run"
    const val METHOD = "run_command"
    const val MAX_TIMEOUT_SEC = 600
    const val DEFAULT_TIMEOUT_SEC = 120

    @Volatile private var cached: Boolean? = null
    @Volatile private var checkedAt = 0L

    /** Installed and same signer (re-checked at most every 30 s so the prompt stays stable). */
    fun available(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        cached?.let { if (now - checkedAt < 30_000L) return it }
        checkedAt = now
        return check(ctx).also { cached = it }
    }

    private fun check(ctx: Context): Boolean = runCatching {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info.applicationInfo?.enabled == false) return false
        val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val a = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val b = mine.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        a.isNotEmpty() && a == b && pm.checkSignatures(ctx.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    fun runner(context: Context): CommandRunner {
        val ctx = context.applicationContext
        return CommandRunner { command, cwd, timeoutSec ->
            val extras = Bundle().apply {
                putString("command", command)
                cwd?.let { putString("cwd", it) }
                putInt("timeoutSec", timeoutSec)
            }
            val b = ctx.contentResolver.call(AUTHORITY, METHOD, null, extras)
                ?: return@CommandRunner CommandResult(-1, "", "终端插件没有返回结果")
            CommandResult(b.getInt("exitCode", -1), b.getString("stdout").orEmpty(), b.getString("stderr").orEmpty(), b.getBoolean("timedOut", false))
        }
    }
}

/**
 * 2.2.3 `run_command`: a shell command in the terminal plugin's Alpine Linux (workspace at
 * /workspace = Download/IRIDIS/workspace). Offered only when [TerminalPlugin.available]; asks first
 * by default (not covered by 撤销).
 */
object RunCommandTool : AgentTool {
    override val name = "run_command"
    override val label = "运行命令"
    override val description = "在终端插件的 Alpine Linux 里运行命令（工作区 /workspace；不可撤销）"
    override val promptLine = "run_command(command, cwd?, timeoutSec?): Alpine Linux shell, cwd /workspace (apk add python3 git)"
    override val risk = ToolRisk.DELETE
    override val defaultPermission: Permission = Permission.ASK
    const val MAX_CHARS = 48 * 1024

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val command = Args.str(args, "command", "cmd", "shell", "script")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("缺少参数 command")
        if (command.length > 8000) return ToolResult(false, "Error: command too long", "命令过长")
        val runner = env.terminal ?: return ToolResult(false, "Error: terminal plugin not available", "终端插件不可用")
        val cwd = Args.str(args, "cwd", "dir", "workdir")?.trim()?.takeIf { it.isNotEmpty() }
        val timeout = (Args.str(args, "timeoutSec", "timeout")?.toDoubleOrNull()?.toInt() ?: TerminalPlugin.DEFAULT_TIMEOUT_SEC)
            .coerceIn(1, TerminalPlugin.MAX_TIMEOUT_SEC)
        val r = runner.run(command, cwd, timeout)
        val body = buildString {
            if (r.stdout.isNotBlank()) append(r.stdout.trimEnd().take(MAX_CHARS))
            if (r.stderr.isNotBlank()) { if (isNotEmpty()) append('\n'); append("[stderr]\n").append(r.stderr.trimEnd().take(MAX_CHARS / 2)) }
            if (isEmpty()) append("(no output)")
            if (r.timedOut) append("\n[killed after ${timeout}s]")
            append("\n[exit ${r.exitCode}]")
        }
        val ok = r.exitCode == 0 && !r.timedOut
        return ToolResult(ok, capTokens(body, env.outputTokens), if (r.timedOut) "超时已终止" else "退出码 ${r.exitCode}")
    }
}
