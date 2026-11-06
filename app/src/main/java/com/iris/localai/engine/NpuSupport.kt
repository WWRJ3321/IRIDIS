package com.iris.localai.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Optional NPU runtime (Qualcomm Hexagon via LiteRT-LM). The vendor libraries are ~26 MB, so they
 * are not in the APK: the user downloads them once, from the official sources, into app storage.
 */
interface NpuSupport {
    /** e.g. "SM8750 · Hexagon V79"; null when this SoC has no supported NPU. */
    val chipLabel: String?
    val state: StateFlow<NpuState>
    suspend fun install()
    fun delete()
    /** 2.4.4: whose NPU this runtime drives. MediaTek's dispatch library ships in the APK (no download, no QNN licence). */
    val vendor: NpuVendor get() = NpuVendor.QUALCOMM
}

enum class NpuVendor { QUALCOMM, MEDIATEK }

sealed interface NpuState {
    data class Unsupported(val reason: String) : NpuState
    data object NotInstalled : NpuState
    data class Downloading(val downloaded: Long, val total: Long) : NpuState
    data object Installed : NpuState
    /** 2.0: marker present but a library is missing / empty / wrong size: shown as 需要修复, reinstall fixes it. */
    data class NeedsRepair(val reason: String) : NpuState
    data class Failed(val message: String) : NpuState
}
