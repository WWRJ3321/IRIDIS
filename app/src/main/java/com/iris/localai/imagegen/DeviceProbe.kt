package com.iris.localai.imagegen

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Fills [ProbeFacts] for 「生图自检」 from what Java can see: the SoC name, available memory, the QNN
 * runtime folder installed by QualcommNpuRuntime (`filesDir/npu/qnn-<ver>-v<arch>`, full flavor) and the
 * CDSP device nodes; QNN backend init, the CDSP architecture and unsigned-PD support come from
 * libiris_imagegen ([QnnNative.probe]) when it loads (false / null when it does not).
 */
object DeviceProbe {
    const val NATIVE_PENDING = "原生探测待接入"
    const val NATIVE_MISSING = "原生库未加载"

    /** Snapdragon SoC model → Hexagon HTP architecture version. */
    val HTP_ARCH: Map<String, Int> = linkedMapOf(
        "SM8850" to 81, // 8 Elite Gen 5 (2.5.0-beta8: was missing → 「不支持 NPU 生图」 on 8E5)
        "SM8750" to 79, // 8 Elite
        "SM8650" to 75, // 8 Gen 3
        "SM8550" to 73, // 8 Gen 2
    )

    /** HTP arch for [soc] ("SM8750", "sm8750-ab", "SM8650P" …), null when unknown. */
    fun htpArch(soc: String?): Int? {
        val s = soc?.trim()?.uppercase() ?: return null
        return HTP_ARCH.entries.firstOrNull { s.startsWith(it.key) }?.value
    }

    /** A non-empty `qnn-*` folder under `<filesDir>/npu` (QualcommNpuRuntime.dir). */
    fun qnnRuntimePresent(filesDir: File): Boolean =
        File(filesDir, "npu").listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("qnn-") && !it.listFiles().isNullOrEmpty() }

    /**
     * The QNN runtime folder (`<filesDir>/npu/qnn-<ver>-v<arch>`): the one for [htpArch] when given and present,
     * else the newest `qnn-*` folder holding libQnnHtp.so.
     */
    fun qnnRuntimeDir(filesDir: File, htpArch: Int? = null): File? {
        val dirs = File(filesDir, "npu").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("qnn-") && !it.name.endsWith(".tmp") && File(it, "libQnnHtp.so").isFile }
        return dirs.firstOrNull { htpArch != null && it.name.endsWith("-v$htpArch") } ?: dirs.maxByOrNull { it.name }
    }

    /** Native probe, cached per runtime folder (backend init opens a DSP session). */
    @Volatile private var cached: Pair<String, QnnProbe>? = null

    /** Lowest Hexagon arch any image model runs on (SD1.5 AI Hub v73). */
    const val MIN_PROBE_ARCH = 73

    fun nativeProbe(context: Context, refresh: Boolean = false): QnnProbe {
        val arch = htpArch(socModel())
        // 2.2.13: never touch libQnnHtp on a SoC the image models do not support (no DSP session on e.g. 8+ Gen 1).
        if (arch == null || arch < MIN_PROBE_ARCH) return QnnProbe(libLoaded = false, error = "此芯片不支持 NPU 生图，已跳过 QNN 探测")
        val dir = qnnRuntimeDir(context.filesDir, arch)
        val key = dir?.path ?: ""
        cached?.takeIf { !refresh && it.first == key }?.let { return it.second }
        val p = try {
            QnnNative.probe(dir)
        } catch (e: UnsatisfiedLinkError) {
            QnnProbe(libLoaded = false, error = e.message)
        } catch (e: Throwable) {
            QnnProbe(libLoaded = QnnNative.loaded, error = e.message ?: e.javaClass.simpleName)
        }
        cached = key to p
        return p
    }

    fun cdspPresent(): Boolean = listOf("/dev/adsprpc-smd", "/dev/cdsprpc-smd", "/vendor/lib64/libcdsprpc.so")
        .any { runCatching { File(it).exists() }.getOrDefault(false) }

    fun socModel(): String = runCatching { Build.SOC_MODEL }.getOrNull()?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        ?: Build.HARDWARE.orEmpty().ifBlank { "未知" }

    fun freeRamMb(context: Context): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        mi.availMem / (1024L * 1024L)
    }.getOrDefault(0L)

    fun probe(context: Context, downloaded: Set<String>, native: QnnProbe = nativeProbe(context, refresh = true)): ProbeFacts {
        val soc = socModel()
        return ProbeFacts(
            soc = soc,
            htpArch = native.htpArch ?: htpArch(soc),
            qnnRuntimePresent = qnnRuntimePresent(context.filesDir),
            qnnBackendOk = native.backendOk && native.deviceOk,
            cdspPresent = native.cdspPresent || cdspPresent(),
            unsignedPd = native.unsignedPd,
            freeRamMb = freeRamMb(context),
            downloaded = downloaded,
        )
    }

    /** [SelfCheck.report] plus a line on the native probe: not run ([native] null), not loaded, or its error. */
    fun report(facts: ProbeFacts, native: QnnProbe? = null): String {
        val base = SelfCheck.report(facts, SelfCheck.verdicts(facts))
        val note = when {
            native == null -> "注：QNN 后端 / Unsigned PD：$NATIVE_PENDING"
            !native.libLoaded -> "注：QNN 后端 / Unsigned PD：$NATIVE_MISSING（${native.error ?: "?"}）"
            native.error != null -> "注：${native.error}"
            else -> null
        }
        return if (note == null) base else "$base\n$note"
    }
}
