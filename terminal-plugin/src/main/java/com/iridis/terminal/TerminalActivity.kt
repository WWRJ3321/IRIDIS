package com.iridis.terminal

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The terminal page (launcher activity of the plugin): install Alpine, grant workspace access, run
 * commands, and see every command IRIDIS' run_command ran. Plain views, no extra libraries.
 */
class TerminalActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var installBtn: Button
    private lateinit var accessBtn: Button
    private var shownRevision = -1
    @Volatile private var running = false

    private val ticker = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 700) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); fitsSystemWindows = true }
        status = TextView(this).apply { textSize = 13f }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        installBtn = Button(this).apply { setOnClickListener { onInstall() } }
        accessBtn = Button(this).apply { text = "授予文件访问"; setOnClickListener { openAccess() } }
        val clearBtn = Button(this).apply { text = "清空"; setOnClickListener { TerminalLog.clear() } }
        row.addView(installBtn); row.addView(accessBtn); row.addView(clearBtn)
        output = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(output) }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply {
            hint = "命令（如 apk add python3）"; typeface = Typeface.MONOSPACE; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { runInput(); true } else false }
        }
        val runBtn = Button(this).apply { text = "运行"; setOnClickListener { runInput() } }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(runBtn)
        root.addView(status)
        root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(inputRow)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); AlpineRootfs.refresh(this); main.post(ticker) }
    override fun onPause() { super.onPause(); main.removeCallbacks(ticker) }

    private fun refresh() {
        val st = AlpineRootfs.state
        val access = Workspace.hasAccess()
        status.text = buildString {
            append("Alpine ").append(AlpineRootfs.VERSION).append("：")
            append(when (st) {
                AlpineRootfs.State.Idle -> if (AlpineRootfs.supported(this@TerminalActivity)) "未安装（约 4 MB）" else "不可用（缺少 proot）"
                is AlpineRootfs.State.Downloading -> "下载中 ${st.done * 100 / st.total.coerceAtLeast(1)}%"
                AlpineRootfs.State.Unpacking -> "解压中…"
                is AlpineRootfs.State.Failed -> "失败：${st.message}"
                AlpineRootfs.State.Ready -> "已安装"
            })
            append("\n工作区 /workspace = ").append(Workspace.dir().path)
            append(if (access) "" else "\n需要「所有文件访问权限」才能读写工作区")
            append("\nIRIDIS 的 run_command 也在这里运行（同一签名才可调用）")
        }
        installBtn.text = if (st == AlpineRootfs.State.Ready) "重装 Alpine" else "安装 Alpine"
        installBtn.isEnabled = st !is AlpineRootfs.State.Downloading && st != AlpineRootfs.State.Unpacking
        accessBtn.visibility = if (access) View.GONE else View.VISIBLE
        if (TerminalLog.revision != shownRevision) {
            shownRevision = TerminalLog.revision
            output.text = TerminalLog.entries.joinToString("\n\n") { e ->
                (if (e.byAgent) "[IRIDIS] $ " else "$ ") + e.command + "\n" +
                    (if (e.exit == null) "…运行中" else e.output.ifBlank { "(无输出)" } + if (e.exit != 0) "\n[退出码 ${e.exit}]" else "")
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun onInstall() {
        Thread {
            if (AlpineRootfs.isInstalled(this)) AlpineRootfs.uninstall(this)
            AlpineRootfs.install(this)
        }.start()
    }

    private fun openAccess() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
    }

    private fun runInput() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty() || running) return
        input.setText("")
        running = true
        Thread {
            val id = TerminalLog.start(cmd, byAgent = false)
            val r = runCatching { ProotShell.run(this, cmd, null, 300_000L) }
                .getOrElse { ShellOutput(-1, "", "错误：${it.message}", false, false) }
            TerminalLog.finish(id, (r.stdout + (if (r.stderr.isNotBlank()) "\n" + r.stderr else "")).trimEnd() + (if (r.timedOut) "\n[超时已终止]" else ""), r.exitCode)
            running = false
        }.start()
    }
}
