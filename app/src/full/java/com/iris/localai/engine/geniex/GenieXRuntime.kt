package com.iris.localai.engine.geniex

import android.content.Context
import android.os.Build
import android.util.Log
import com.iris.localai.engine.NpuState
import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 2.2.0: the GenieX native runtime ([GenieX.VERSION]; 0.10.0 = QAIRT 2.50 since 2.4.1), installed on demand (the APK carries only the SDK's
 * Kotlin classes; every GenieX .so is excluded from packaging in app/build.gradle.kts).
 *
 * Install: Maven Central (aliyun mirror as fallback) `geniex-android-<VERSION>.aar` → size + SHA-256 of
 * the archive → only this chip's libraries ([GenieXLibs.forArch], ≈ 55 MB of the 89 MB) → size +
 * SHA-256 of each → marker → the archive is deleted. The plugin libraries go to
 * `<dir>/llama_cpp/libgeniex_plugin.so` and `<dir>/qairt/libgeniex_plugin.so`, the layout GenieX's
 * registry scans at init.
 *
 * Load ([ensureLoaded]): GenieX's Kotlin `GenieXSdk` class hard-codes `System.loadLibrary("npu_jni")`
 * and plugin registration from nativeLibraryDir, so it is never touched. Instead:
 *  1. `GENIEX_PLUGIN_PATH = <dir>` (registry scan root; the llama.cpp plugin also points
 *     ADSP_LIBRARY_PATH at it so FastRPC finds libggml-htp-vNN.so), `GENIEX_QAIRT_LIB = <dir>`
 *     (the qairt plugin's documented runtime override: libQnnHtp/System/NetRunExtensions by absolute
 *     path, ADSP_LIBRARY_PATH = <dir> for the QNN skel), `ADSP_LIBRARY_PATH = <dir>`;
 *  2. `System.load` of every host library in dependency order, so each NEEDED soname is already
 *     loaded when its user is;
 *  3. `System.load(libnpu_jni.so)` last: its JNI_OnLoad runs geniex_init(), which registers the two
 *     plugins from <dir>. The JNI natives of com.geniex.sdk.jni.Llm / Vlm then resolve through the
 *     app class loader, which now owns libnpu_jni.
 * QNN host libraries are loaded only before the first QAIRT model ([ensureQnnLoaded]).
 */
class GenieXRuntime private constructor(context: Context) : GenieXSupport {

    private val appContext = context.applicationContext
    private val manufacturer: String = Build.SOC_MANUFACTURER.orEmpty()
    private val soc: String = Build.SOC_MODEL.orEmpty()
    val arch: Int? = GenieXLibs.hexagonArch(manufacturer, soc)

    /** Installed libraries for this chip. */
    val dir: File = File(context.filesDir, "geniex/rt-${GenieX.VERSION}-v${arch ?: 0}")
    private val cache = File(context.cacheDir, "geniex-dl")

    override val chipLabel: String? = arch?.let { "$soc · Hexagon V$it" }
    override val qairtSupported: Boolean = arch != null && arch in GenieXLibs.QAIRT_ARCHS
    override val downloadBytes: Long = GenieXLibs.AAR_BYTES
    override val installedBytes: Long = arch?.let { GenieXLibs.installedBytes(it) } ?: 0L

    private val _state = MutableStateFlow(diskState())
    override val state: StateFlow<NpuState> = _state.asStateFlow()

    private fun check(): GenieXLibs.Check {
        val a = arch ?: return GenieXLibs.Check.NotInstalled
        val marker = File(dir, MARKER).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        return GenieXLibs.check(a, marker) { path -> File(dir, path).takeIf { it.isFile }?.length() }
    }

    val isInstalled: Boolean get() = check() == GenieXLibs.Check.Ok

    private fun diskState(): NpuState {
        GenieXLibs.unsupportedReason(manufacturer, soc)?.let { return NpuState.Unsupported(it) }
        return when (val c = check()) {
            GenieXLibs.Check.Ok -> NpuState.Installed
            // 2.4.1: an older runtime (0.8.0 = QAIRT 2.45) on disk → 「重新下载」 instead of a plain first install.
            GenieXLibs.Check.NotInstalled -> GenieXLibs.oldRuntime(dir.parentFile?.list().orEmpty().toList(), dir.name)
                ?.let { NpuState.NeedsRepair(GenieXLibs.oldRuntimeReason(it)) } ?: NpuState.NotInstalled
            is GenieXLibs.Check.Broken -> NpuState.NeedsRepair(c.reason)
        }
    }

    override fun refreshState(): NpuState = diskState().also { if (_state.value !is NpuState.Downloading) _state.value = it }

    override suspend fun install() = withContext(Dispatchers.IO) {
        val a = arch ?: return@withContext
        if (isInstalled) { _state.value = NpuState.Installed; return@withContext }
        if (loadedFrom != null) {
            // The libraries in use cannot be replaced inside this process.
            _state.value = NpuState.Failed("GenieX 运行库已在使用中，请彻底退出 IRIDIS 后再修复")
            return@withContext
        }
        try {
            cache.mkdirs()
            val aar = File(cache, GenieXLibs.AAR_NAME)
            fetch(aar) { _state.value = NpuState.Downloading(it, GenieXLibs.AAR_BYTES) }
            val aarSha = sha256(aar)
            if (aar.length() != GenieXLibs.AAR_BYTES || aarSha != GenieXLibs.AAR_SHA256) {
                aar.delete()
                throw IOException("下载的 ${GenieXLibs.AAR_NAME} 校验失败（大小 ${aar.length()}，SHA-256 ${aarSha.take(16)}…），已删除，请重试")
            }
            val tmp = File(dir.path + ".tmp").apply { deleteRecursively(); mkdirs() }
            val libs = GenieXLibs.forArch(a)
            ZipFile(aar).use { z ->
                for (l in libs) {
                    val e = z.getEntry(l.entry) ?: throw IOException("压缩包中缺少 ${l.entry}")
                    val out = File(tmp, l.path).apply { parentFile?.mkdirs() }
                    z.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it, 256 * 1024) } }
                    if (out.length() != l.pin.size) throw IOException("${l.path} 大小不符（${out.length()} ≠ ${l.pin.size}）")
                    val sha = sha256(out)
                    if (sha != l.pin.sha256) throw IOException("${l.path} SHA-256 不符（$sha）")
                    out.setReadable(true, false)
                }
            }
            File(tmp, MARKER).writeText(GenieXLibs.markerText(a, libs))
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("无法写入 GenieX 运行库目录")
            aar.delete()
            // Runtimes of other versions / arches (a restored backup from another phone).
            dir.parentFile?.listFiles()?.forEach { if (it != dir && it.name.startsWith("rt-")) it.deleteRecursively() }
            _state.value = NpuState.Installed
        } catch (t: Throwable) {
            File(dir.path + ".tmp").deleteRecursively()
            _state.value = if (t is kotlinx.coroutines.CancellationException) diskState()
            else NpuState.Failed(GenieX.error("GenieX 运行库安装失败", t))
            if (t is kotlinx.coroutines.CancellationException) throw t
        }
    }

    /** Libraries already mapped into this process keep working until it exits (unlinking a mapped file is safe). */
    override fun delete() {
        dir.deleteRecursively()
        dir.parentFile?.listFiles()?.forEach { if (it.name.startsWith("rt-")) it.deleteRecursively() }
        cache.deleteRecursively()
        _state.value = diskState()
    }

    /** Resumable download, each mirror up to three times. */
    private suspend fun fetch(target: File, onBytes: (Long) -> Unit) {
        if (target.exists() && target.length() == GenieXLibs.AAR_BYTES) { onBytes(GenieXLibs.AAR_BYTES); return }
        var last: IOException? = null
        for (url in GenieXLibs.AAR_URLS) {
            repeat(3) { attempt ->
                try {
                    HttpDownload.fetchOnce(url, target, GenieXLibs.AAR_BYTES, onBytes)
                    return
                } catch (e: IOException) {
                    last = e
                    delay(1200L * (attempt + 1))
                }
            }
            HttpDownload.partFile(target).delete()
        }
        throw last ?: IOException("下载失败")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- native loading

    /** Loads the runtime into this process once. Throws an IllegalStateException with a Chinese message. */
    fun ensureLoaded(): Unit = synchronized(loadLock) {
        if (loadedFrom != null) return@synchronized
        diskState().let { st ->
            if (st !is NpuState.Installed) {
                _state.value = st
                throw IllegalStateException(GenieXRules.block(st) ?: GenieX.NEED_RUNTIME)
            }
        }
        val path = dir.absolutePath
        try {
            android.system.Os.setenv("GENIEX_PLUGIN_PATH", path, true)
            android.system.Os.setenv("GENIEX_QAIRT_LIB", path, true)
            android.system.Os.setenv("ADSP_LIBRARY_PATH", adspPath(path, System.getenv("ADSP_LIBRARY_PATH")), true)
        } catch (t: Throwable) {
            throw IllegalStateException(GenieX.error("设置 GenieX 环境变量失败", t), t)
        }
        for (name in GenieXLibs.CORE_LOAD_ORDER + GenieXLibs.JNI) {
            val f = File(dir, name)
            try {
                System.load(f.absolutePath)
            } catch (t: Throwable) {
                throw IllegalStateException(GenieX.error("加载 GenieX 运行库 $name 失败，可在 设置 › 模型与后端 修复运行库", t), t)
            }
        }
        loadedFrom = path
        Log.i(TAG, "GenieX ${GenieX.VERSION} loaded from $path (v$arch)")
        Unit
    }

    /** QNN host libraries for QAIRT (after [ensureLoaded]). Refuses when LiteRT's QNN is already in this process. */
    fun ensureQnnLoaded(): Unit = synchronized(loadLock) {
        if (qnnLoaded) return@synchronized
        val a = arch ?: throw IllegalStateException("本机芯片不支持 GenieX")
        if (a !in GenieXLibs.QAIRT_ARCHS) throw IllegalStateException("本机芯片（Hexagon V$a）没有 GenieX 的 QAIRT 运行库（仅骁龙 8 Elite / 8 Elite Gen 5）")
        foreignQnn()?.let { other ->
            throw IllegalStateException(
                "本进程已加载另一套 QNN 运行库（$other，LiteRT NPU 使用），与 GenieX 的 QNN 版本不同，混用会出错。请彻底退出 IRIDIS 后先加载 QAIRT 模型",
            )
        }
        android.system.Os.setenv("ADSP_LIBRARY_PATH", adspPath(dir.absolutePath, System.getenv("ADSP_LIBRARY_PATH")), true)
        for (name in GenieXLibs.qnnLoadOrder(a)) {
            try {
                System.load(File(dir, name).absolutePath)
            } catch (t: Throwable) {
                throw IllegalStateException(GenieX.error("加载 QNN 库 $name 失败", t), t)
            }
        }
        qnnLoaded = true
    }

    /** A libQnnHtp.so mapped from somewhere other than our runtime directory, or null. */
    private fun foreignQnn(): String? = runCatching {
        File("/proc/self/maps").useLines { lines ->
            lines.map { it.substringAfterLast(' ') }
                .firstOrNull { it.endsWith("/libQnnHtp.so") && !it.startsWith(dir.absolutePath) }
        }
    }.getOrNull()

    companion object {
        private const val TAG = "GenieX"
        private const val MARKER = ".installed"
        private val loadLock = Any()
        @Volatile private var loadedFrom: String? = null
        @Volatile private var qnnLoaded = false

        /** FastRPC splits ADSP_LIBRARY_PATH on ';'. Ours first, keep what was there. */
        fun adspPath(ours: String, existing: String?): String {
            val rest = existing.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() && it != ours }
            return (listOf(ours) + rest).joinToString(";")
        }

        @Volatile private var instance: GenieXRuntime? = null
        fun get(context: Context): GenieXRuntime =
            instance ?: synchronized(this) { instance ?: GenieXRuntime(context.applicationContext).also { instance = it } }
    }
}
