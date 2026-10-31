package com.iridis.terminal

import android.content.Context
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Result of one command. */
data class ShellOutput(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean, val truncated: Boolean)

/**
 * Output collector (from IRIDIS 1.12.0): keeps the first and the last half of [maxBytes] (an
 * error at the end of a long build log is not lost), and says how much was cut.
 */
class HeadTailBuffer(private val maxBytes: Int) {
    private val head = java.io.ByteArrayOutputStream()
    private val tail = ArrayDeque<Byte>()
    private val half = maxBytes / 2
    var total = 0L; private set

    @Synchronized fun write(b: ByteArray, n: Int) {
        for (i in 0 until n) {
            total++
            if (head.size() < half) head.write(b[i].toInt()) else {
                tail.addLast(b[i]); if (tail.size > maxBytes - half) tail.removeFirst()
            }
        }
    }

    val truncated: Boolean @Synchronized get() = total > maxBytes

    @Synchronized fun text(): String {
        val h = head.toString(Charsets.UTF_8.name())
        val t = String(tail.toByteArray(), Charsets.UTF_8)
        if (!truncated) return h + t
        return h + "\n…[省略中间 ${total - maxBytes} 字节]…\n" + t
    }
}

/** Runs one process with separate stdout / stderr, a timeout and a head+tail cap. */
object ProcessRun {
    private fun pump(s: java.io.InputStream, buf: HeadTailBuffer) = Thread {
        runCatching { s.use { val b = ByteArray(8192); while (true) { val n = it.read(b); if (n < 0) break; buf.write(b, n) } } }
    }.apply { isDaemon = true; start() }

    fun run(cmd: List<String>, cwd: File, env: Map<String, String>, timeoutMs: Long, maxBytes: Int): ShellOutput {
        val pb = ProcessBuilder(cmd).directory(cwd)
        pb.environment().keys.retainAll(setOf("ANDROID_ROOT", "ANDROID_DATA"))
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close()
        val out = HeadTailBuffer(maxBytes)
        val err = HeadTailBuffer(maxBytes / 2)
        val t1 = pump(p.inputStream, out)
        val t2 = pump(p.errorStream, err)
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) p.destroyForcibly()
        t1.join(1_000); t2.join(1_000)
        return ShellOutput(if (finished) p.exitValue() else -1, out.text(), err.text(), !finished, out.truncated || err.truncated)
    }
}

/** The workspace shared with IRIDIS: Download/IRIDIS/workspace (bound at /workspace). */
object Workspace {
    fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "IRIDIS/workspace")
    fun hasAccess(): Boolean = Environment.isExternalStorageManager()
}

/**
 * Alpine minirootfs under PRoot (from IRIDIS 1.12.0). Android forbids exec of files in app data
 * (W^X), but not of the app's own native libraries, so proot ships in jniLibs as `libproot.so`
 * with its loader as `libproot-loader.so` (Termux's proot, GPL-2.0, NEEDED patched to the jniLibs
 * names). The rootfs (~4 MB download) is downloaded on request, checked against its SHA-256 and
 * unpacked into filesDir/alpine/rootfs.
 */
object AlpineRootfs {
    const val VERSION = "3.24.2"
    const val URL = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz"
    const val SHA256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
    const val SIZE = 4_028_030L

    fun base(ctx: Context) = File(ctx.filesDir, "alpine")
    fun rootfs(ctx: Context) = File(base(ctx), "rootfs")
    private fun marker(ctx: Context) = File(base(ctx), "installed-$VERSION")

    fun isInstalled(ctx: Context): Boolean = marker(ctx).isFile && File(rootfs(ctx), "bin/busybox").exists()

    fun proot(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
    fun loader(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    fun supported(ctx: Context): Boolean = proot(ctx).isFile && loader(ctx).isFile

    sealed interface State {
        data object Idle : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Unpacking : State
        data class Failed(val message: String) : State
        data object Ready : State
    }

    @Volatile var state: State = State.Idle; private set

    fun refresh(ctx: Context) { if (state !is State.Downloading && state != State.Unpacking) state = if (isInstalled(ctx)) State.Ready else State.Idle }

    /** Downloads, verifies and unpacks the rootfs. Blocking; call off the main thread. */
    @Synchronized fun install(ctx: Context) {
        if (state is State.Downloading || state == State.Unpacking) return
        val dir = base(ctx).apply { mkdirs() }
        val tgz = File(dir, "minirootfs.tar.gz")
        try {
            state = State.Downloading(0, SIZE)
            download(tgz)
            if (!sha256(tgz).equals(SHA256, true)) throw SecurityException("校验失败（SHA-256 不一致），已删除下载的文件")
            state = State.Unpacking
            val root = rootfs(ctx)
            val tmp = File(dir, "rootfs.tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            TarUnpack.unpackGz(tgz, tmp)
            setupNetwork(tmp)
            root.deleteRecursively()
            if (!tmp.renameTo(root)) throw IllegalStateException("无法移动 rootfs")
            File(dir, "tmp").mkdirs()
            dir.listFiles()?.filter { it.name.startsWith("installed-") }?.forEach { it.delete() }
            marker(ctx).writeText(SHA256)
            state = State.Ready
        } catch (t: Throwable) {
            android.util.Log.w("IridisTerminal", "install failed", t)
            state = State.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            tgz.delete()
        }
    }

    fun uninstall(ctx: Context) { base(ctx).deleteRecursively(); state = State.Idle }

    private fun download(to: File) {
        val c = (java.net.URL(URL).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 30_000 }
        try {
            if (c.responseCode != 200) throw java.io.IOException("下载失败：HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: SIZE
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val b = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(b); if (n < 0) break
                        out.write(b, 0, n); done += n
                        if (done > 64L * 1024 * 1024) throw java.io.IOException("文件过大")
                        state = State.Downloading(done, total)
                    }
                }
            }
        } finally { c.disconnect() }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** DNS for apk/pip (Android has no /etc/resolv.conf for the rootfs to inherit). */
    fun setupNetwork(root: File) {
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "workspace").mkdirs()
        File(root, "root").mkdirs()
    }
}

/** Unpacks a .tar.gz with symlinks and modes kept (commons-compress). */
object TarUnpack {
    fun unpackGz(tgz: File, into: File) {
        val canon = into.canonicalPath
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            java.util.zip.GZIPInputStream(tgz.inputStream().buffered()),
        ).use { tar ->
            val hardLinks = ArrayList<Pair<File, String>>()
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(into, e.name)
                if (!out.canonicalPath.startsWith(canon) && !e.isSymbolicLink) continue
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs(); out.delete()
                        runCatching { android.system.Os.symlink(e.linkName, out.path) }
                    }
                    e.isLink -> hardLinks += out to e.linkName
                    else -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
                if (!e.isSymbolicLink && !e.isLink) runCatching { android.system.Os.chmod(out.path, e.mode and 0x1FF or (if (e.isDirectory) 0x1C0 else 0x180)) }
            }
            hardLinks.forEach { (out, target) ->
                val src = File(into, target)
                if (src.isFile) { out.parentFile?.mkdirs(); src.copyTo(out, overwrite = true); runCatching { android.system.Os.chmod(out.path, android.system.Os.stat(src.path).st_mode and 0x1FF) } }
            }
        }
    }
}

/** Keeps cwd and exported variables between commands (from IRIDIS 1.12.0). Pure. */
object ProotSession {
    const val DIR = "/root/.iris"

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun wrap(command: String, cwd: String? = null): String = buildString {
        append("mkdir -p $DIR; ")
        append("[ -f $DIR/env ] && . $DIR/env 2>/dev/null; ")
        append("cd \"\$(cat $DIR/cwd 2>/dev/null || echo /workspace)\" 2>/dev/null || cd /workspace; ")
        if (!cwd.isNullOrBlank()) append("cd ").append(quote(cwd.trim())).append(" || exit 1; ")
        append("{ ").append(command.trim().trimEnd(';')).append("\n} ; __iris_rc=\$?; ")
        append("pwd > $DIR/cwd; export -p > $DIR/env 2>/dev/null; ")
        append("exit \$__iris_rc")
    }
}

/**
 * Commands inside Alpine. Each command runs in a fresh `proot … /bin/sh`; the working directory and
 * exported variables carry over between commands. Background jobs end with the command
 * (--kill-on-exit). One command at a time.
 */
object ProotShell {
    const val MAX_BYTES = 64 * 1024
    private val lock = Any()

    fun run(context: Context, command: String, cwd: String?, timeoutMs: Long): ShellOutput {
        val ctx = context.applicationContext
        if (!AlpineRootfs.supported(ctx)) return ShellOutput(127, "", "内置 Linux 不可用：安装包里缺少 proot（只支持 arm64）", false, false)
        if (!AlpineRootfs.isInstalled(ctx)) return ShellOutput(127, "", "Alpine 还没安装：打开「IRIDIS 终端」点「安装 Alpine」", false, false)
        if (!Workspace.hasAccess()) return ShellOutput(126, "", "IRIDIS 终端没有「所有文件访问权限」：打开「IRIDIS 终端」授权后再试", false, false)
        val ws = Workspace.dir().apply { mkdirs() }
        if (!ws.isDirectory) return ShellOutput(126, "", "无法访问工作区 ${ws.path}", false, false)
        val tmp = File(AlpineRootfs.base(ctx), "tmp").apply { mkdirs() }
        val args = listOf(
            AlpineRootfs.proot(ctx).path,
            "--kill-on-exit", "--link2symlink", "-0",
            "-r", AlpineRootfs.rootfs(ctx).path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${ws.path}:/workspace",
            "-w", "/workspace",
            "/bin/sh", "-c", ProotSession.wrap(command, cwd),
        )
        val env = mapOf(
            "PROOT_TMP_DIR" to tmp.path,
            "PROOT_LOADER" to AlpineRootfs.loader(ctx).path,
            "LD_LIBRARY_PATH" to ctx.applicationInfo.nativeLibraryDir,
            "HOME" to "/root",
            "USER" to "root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
        )
        return synchronized(lock) { ProcessRun.run(args, ws, env, timeoutMs, MAX_BYTES) }
    }
}
