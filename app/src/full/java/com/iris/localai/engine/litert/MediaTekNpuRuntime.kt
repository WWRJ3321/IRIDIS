package com.iris.localai.engine.litert

import android.content.Context
import android.os.Build
import com.iris.localai.engine.NpuIds
import com.iris.localai.engine.NpuState
import com.iris.localai.engine.NpuSupport
import com.iris.localai.engine.NpuVendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 2.4.4 (Issue #6): MediaTek NeuroPilot NPU for LiteRT-LM. Only one library is ours to ship:
 * libLiteRtDispatch_MediaTek.so (Apache-2.0, ~330 KB) from Google Maven
 * com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.2, bundled in assets/npu/. The NeuroPilot
 * driver itself (libneuron_adapter / libneuronusdk_adapter.mtk.so …) comes from the phone's /vendor,
 * opened through the uses-native-library entries in the full manifest. "Installing" copies the
 * bundled file into app storage (LiteRT-LM wants a directory), so there is nothing to download.
 *
 * Only Dimensity 9300 / 9400 / 9500 (MT6989 / MT6991 / MT6993) have official NPU model builds.
 * Unverified on a real phone at release: the dispatch library is not the one Google built together
 * with litertlm 0.18.0 (Gallery ships no MediaTek build), and an AOT graph compiled with a newer
 * NeuroPilot SDK than the phone's driver fails at init (LiteRT-LM #1268, Gallery #1230).
 */
class MediaTekNpuRuntime private constructor(context: Context) : NpuSupport {

    private val appContext: Context = context.applicationContext
    private val soc: String = Build.SOC_MODEL.orEmpty()

    /** "mt6991" when this phone has official NPU builds, else null. */
    val socId: String? = NpuIds.mediatekNpu(Build.SOC_MANUFACTURER, soc)

    /** Directory handed to LiteRT-LM as Backend.NPU(nativeLibraryDir). */
    val dir: File = File(context.filesDir, "npu/mtk-$LITERT_VERSION")

    override val vendor: NpuVendor get() = NpuVendor.MEDIATEK

    override val chipLabel: String? = socId?.let { "${soc.uppercase()} · ${NpuIds.MEDIATEK_NPU[it]} · NeuroPilot" }

    private val _state = MutableStateFlow(diskState())
    override val state: StateFlow<NpuState> = _state.asStateFlow()

    val isInstalled: Boolean get() = File(dir, DISPATCH).length() == DISPATCH_BYTES

    private fun diskState(): NpuState = when {
        socId == null -> NpuState.Unsupported(
            com.iris.localai.I18n.s(com.iris.localai.R.string.mtk244_unsupported, "${Build.SOC_MANUFACTURER} $soc".trim()),
        )
        isInstalled -> NpuState.Installed
        else -> NpuState.NotInstalled
    }

    fun refreshState(): NpuState = diskState().also { _state.value = it }

    /** Copies the bundled dispatch library into [dir]; instant, so load() calls it on demand. */
    fun ensureInstalled() {
        if (socId == null || isInstalled) return
        val tmp = File(dir.path + ".tmp").apply { deleteRecursively(); mkdirs() }
        appContext.assets.open(DISPATCH_ASSET).use { ins -> File(tmp, DISPATCH).outputStream().use { ins.copyTo(it, 256 * 1024) } }
        if (File(tmp, DISPATCH).length() != DISPATCH_BYTES) throw IOException("$DISPATCH 复制不完整")
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("无法写入 NPU 运行库目录")
        dir.parentFile?.listFiles()?.forEach { if (it != dir && it.name.startsWith("mtk-")) it.deleteRecursively() }
    }

    override suspend fun install() = withContext(Dispatchers.IO) {
        try {
            ensureInstalled()
            _state.value = diskState()
        } catch (t: Throwable) {
            _state.value = NpuState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    override fun delete() {
        dir.deleteRecursively()
        _state.value = diskState()
    }

    companion object {
        const val LITERT_VERSION = "2.3.2"
        const val DISPATCH = "libLiteRtDispatch_MediaTek.so"
        private const val DISPATCH_ASSET = "npu/$DISPATCH"
        const val DISPATCH_BYTES = 330_968L

        /** MediaTek phones get this runtime; everything else keeps the Qualcomm one. */
        fun isMediatek(): Boolean = Build.SOC_MANUFACTURER.orEmpty().contains("mediatek", true)

        @Volatile private var instance: MediaTekNpuRuntime? = null
        fun get(context: Context): MediaTekNpuRuntime =
            instance ?: synchronized(this) { instance ?: MediaTekNpuRuntime(context.applicationContext).also { instance = it } }
    }
}
