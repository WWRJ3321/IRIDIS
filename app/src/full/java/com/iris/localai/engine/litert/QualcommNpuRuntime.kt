package com.iris.localai.engine.litert

import android.content.Context
import android.os.Build
import com.iris.localai.engine.NpuIds
import com.iris.localai.engine.NpuRuntimeFiles
import com.iris.localai.engine.NpuState
import com.iris.localai.engine.NpuSupport
import com.iris.localai.util.io.HttpDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Qualcomm Hexagon NPU runtime for LiteRT-LM, installed on demand into app storage:
 *  - libLiteRtDispatch_Qualcomm.so — Google's LiteRT dispatch library, bundled in the APK
 *    (assets/npu/), taken from Google AI Edge Gallery 1.0.20, which ships it with the same
 *    litertlm-android 0.18.0 this app uses (the dispatch library must match the LiteRT runtime);
 *  - libQnnHtp / libQnnSystem / libQnnHtpV{arch}Stub / libQnnHtpV{arch}Skel — Qualcomm AI Engine
 *    Direct (QNN / QAIRT) 2.50.0 from Maven Central (com.qualcomm.qti:qnn-runtime:2.50.0,
 *    aliyun mirror as fallback), downloaded only after the user accepts Qualcomm's license.
 * Only the six files this SoC needs are kept (~113 MB, incl. libQnnHtpPrepare); the downloaded archive is deleted.
 * "installed" means all five are present, non-empty and match the sizes recorded in the
 * marker ([NpuRuntimeFiles.check]); otherwise the state is NeedsRepair and install() redoes it.
 */
class QualcommNpuRuntime private constructor(context: Context) : NpuSupport {

    private val appContext: Context = context.applicationContext

    val arch: Int? = NpuIds.hexagonArch(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
    private val soc: String = Build.SOC_MODEL.orEmpty()

    /** Directory handed to LiteRT-LM as Backend.NPU(nativeLibraryDir). */
    val dir: File = File(context.filesDir, "npu/qnn-$QNN_VERSION-v${arch ?: 0}")
    private val cache = File(context.cacheDir, "npu-dl")

    override val chipLabel: String? = arch?.let { "$soc · Hexagon V$it" }

    private val _state = MutableStateFlow(diskState())
    override val state: StateFlow<NpuState> = _state.asStateFlow()

    /** All libraries present, non-empty and the size recorded at install time (not just the marker). */
    val isInstalled: Boolean get() = check() == NpuRuntimeFiles.Check.Ok

    private fun check(): NpuRuntimeFiles.Check {
        val a = arch ?: return NpuRuntimeFiles.Check.NotInstalled
        val marker = File(dir, MARKER).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrDefault("") }
        return NpuRuntimeFiles.check(a, marker) { name -> File(dir, name).takeIf { it.isFile }?.length() }
    }

    /** Base "sm8750"-style id of this phone (SM8750P / SM8750-AB → sm8750), used to match NPU model files. */
    val socId: String get() = NpuIds.baseSocId(soc).orEmpty()

    private fun diskState(): NpuState = when {
        arch == null -> NpuState.Unsupported(
            if (!Build.SOC_MANUFACTURER.equals("QTI", true) && !Build.SOC_MANUFACTURER.contains("qualcomm", true))
                "此芯片（${Build.SOC_MANUFACTURER} $soc）不是高通骁龙" else "此骁龙型号（$soc）暂无 LiteRT NPU 支持",
        )
        else -> when (val c = check()) {
            NpuRuntimeFiles.Check.Ok -> NpuState.Installed
            NpuRuntimeFiles.Check.NotInstalled -> NpuState.NotInstalled
            is NpuRuntimeFiles.Check.Broken -> NpuState.NeedsRepair(c.reason)
        }
    }

    /** Current on-disk state (e.g. before a load): a runtime broken since start is reported as 需要修复. */
    fun refreshState(): NpuState = diskState().also { if (_state.value !is NpuState.Downloading) _state.value = it }

    override suspend fun install() = withContext(Dispatchers.IO) {
        val a = arch ?: return@withContext
        if (isInstalled) { _state.value = NpuState.Installed; return@withContext }
        try {
            cache.mkdirs()
            val tmp = File(dir.path + ".tmp").apply { deleteRecursively(); mkdirs() }
            val qnnAar = File(cache, "qnn-runtime-$QNN_VERSION.aar")
            fetch(QNN_URLS, qnnAar, QNN_AAR_BYTES) { _state.value = NpuState.Downloading(it, QNN_AAR_BYTES) }

            // The dispatch library must match the LiteRT runtime inside litertlm-android exactly;
            // the GitHub v2.2.0 build did not ("Failed to allocate tensors" at prefill warm-up).
            // We bundle the one Google AI Edge Gallery 1.0.20 ships with the same litertlm 0.18.0.
            appContext.assets.open(DISPATCH_ASSET).use { ins ->
                File(tmp, NpuRuntimeFiles.DISPATCH).outputStream().use { ins.copyTo(it, 256 * 1024) }
            }
            for (name in NpuRuntimeFiles.expected(a).filter { it != NpuRuntimeFiles.DISPATCH }) {
                extract(qnnAar, "jni/arm64-v8a/$name", tmp)
            }
            // record each library's size + SHA-256 (one pass over ~113 MB, install time only);
            // isInstalled checks presence + size on every query.
            val recorded = NpuRuntimeFiles.expected(a).map { name ->
                val f = File(tmp, name)
                if (f.length() <= 0L) throw IOException("$name 解压后为空")
                Triple(name, f.length(), sha256(f))
            }
            File(tmp, MARKER).writeText(NpuRuntimeFiles.markerText("litert=$LITERT_VERSION qnn=$QNN_VERSION arch=v$a", recorded))
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("无法写入 NPU 运行库目录")
            qnnAar.delete()
            // Drop runtimes from older app versions (mismatched libraries, ~70 MB each).
            dir.parentFile?.listFiles()?.forEach { if (it != dir && it.name.startsWith("qnn-")) it.deleteRecursively() }
            _state.value = NpuState.Installed
        } catch (t: Throwable) {
            _state.value = if (t is kotlinx.coroutines.CancellationException) diskState()
            else NpuState.Failed(t.message ?: t.javaClass.simpleName)
            if (t is kotlinx.coroutines.CancellationException) throw t
        }
    }

    override fun delete() {
        dir.deleteRecursively()
        cache.deleteRecursively()
        _state.value = diskState()
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(zip: File, entry: String, into: File) {
        ZipFile(zip).use { z ->
            val e = z.getEntry(entry) ?: throw IOException("压缩包中缺少 $entry")
            val out = File(into, entry.substringAfterLast('/'))
            z.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it, 256 * 1024) } }
            out.setReadable(true, false)
        }
    }

    /** Resumable download, trying each mirror in turn. */
    private suspend fun fetch(urls: List<String>, target: File, expected: Long, onBytes: (Long) -> Unit) {
        if (target.exists() && target.length() == expected) { onBytes(expected); return }
        var lastError: IOException? = null
        for (url in urls) {
            repeat(3) { attempt ->
                try {
                    HttpDownload.fetchOnce(url, target, expected, onBytes)
                    return
                } catch (e: IOException) {
                    lastError = e
                    delay(1200L * (attempt + 1))
                }
            }
            HttpDownload.partFile(target).delete() // next mirror starts clean
        }
        throw lastError ?: IOException("下载失败")
    }

    companion object {
        /** Label only: where the bundled dispatch library comes from. */
        const val LITERT_VERSION = "litertlm-0.18.0/gallery-1.0.20"
        /** Must match what Gallery 1.0.20 ships with litertlm 0.18.0 (QAIRT v2.50.0.260828). */
        const val QNN_VERSION = "2.50.0"
        private const val DISPATCH_ASSET = "npu/libLiteRtDispatch_Qualcomm.so"
        private const val MARKER = ".installed"
        private const val QNN_AAR_BYTES = 71_270_746L
        private val QNN_URLS = listOf(
            "https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/$QNN_VERSION/qnn-runtime-$QNN_VERSION.aar",
            "https://maven.aliyun.com/repository/central/com/qualcomm/qti/qnn-runtime/$QNN_VERSION/qnn-runtime-$QNN_VERSION.aar",
        )

        /** Hexagon HTP architecture for Snapdragon SoCs LiteRT ships a dispatch library for (see [NpuIds.hexagonArch]). */
        fun hexagonArch(manufacturer: String?, model: String?): Int? = NpuIds.hexagonArch(manufacturer, model)

        @Volatile private var instance: QualcommNpuRuntime? = null
        fun get(context: Context): QualcommNpuRuntime =
            instance ?: synchronized(this) { instance ?: QualcommNpuRuntime(context.applicationContext).also { instance = it } }
    }
}
