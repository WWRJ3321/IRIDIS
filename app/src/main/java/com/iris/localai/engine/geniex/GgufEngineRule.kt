package com.iris.localai.engine.geniex

import com.iris.localai.data.ModelOverrides
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.BackendOption

/**
 * 2.1.3 (界面简化): a GGUF model no longer has its own 「推理引擎」 switch. The backend decides:
 * NPU → GenieX (Qualcomm, needs its downloaded runtime), GPU / CPU → the built-in llama.cpp.
 * The user only picks 自动 / CPU / GPU / NPU. QAIRT bundles always run on GenieX (unchanged).
 * Everything here is pure and unit-tested.
 */
object GgufEngineRule {
    /** Short reason on the greyed-out NPU option when the GenieX runtime is missing. */
    const val NEED_GENIEX = "需先在 设置 › 模型与加载 下载 GenieX"

    /** The engine that runs a GGUF model on [backend]. */
    fun engineFor(backend: Backend, geniexReady: Boolean): String =
        if (backend == Backend.NPU && geniexReady) GenieX.ENGINE_GENIEX else GenieX.ENGINE_LLAMA

    /**
     * 2.2.15: when GenieX cannot load a GGUF on the NPU (e.g. an architecture its older bundled
     * llama.cpp lacks, like K2 Horizon / LFM3), the built-in llama.cpp Hexagon runtime retries on the
     * NPU before any GPU/CPU fallback. Without GenieX the built-in runtime is the NPU engine.
     */
    fun npuEngines(geniexReady: Boolean, htpReady: Boolean): List<String> = buildList {
        if (geniexReady) add(GenieX.ENGINE_GENIEX)
        if (htpReady) add(GenieX.ENGINE_LLAMA)
    }

    /** Why the NPU option is greyed out (null = GenieX or the built-in Hexagon runtime is ready). */
    fun npuReason(env: BackendEnvironment): String? = if (env.htpReady) null else when (val b = env.geniexBlock) {
        null -> null
        GenieX.NEED_RUNTIME -> NEED_GENIEX
        else -> b
    }

    /** GGUF backends: CPU / GPU on llama.cpp, NPU on GenieX (only when it is usable). */
    fun options(env: BackendEnvironment): List<BackendOption> {
        val noCl = "本机没有可用的 OpenCL 驱动"
        val npu = npuReason(env)
        return listOf(
            BackendOption(Backend.CPU, true, detail = "内置 llama.cpp · 最稳"),
            BackendOption(Backend.GPU, env.ggufGpu, if (env.ggufGpu) null else noCl,
                if (env.hasVulkan) "Vulkan（Mali / Immortalis）" else "Adreno · OpenCL（Q4_0 / Q8_0 最快）"),
            BackendOption(Backend.NPU, npu == null, npu,
                if (env.geniexBlock == null) "GenieX（不支持的模型改用内置 llama.cpp Hexagon）· Q4_0 最快" else "内置 llama.cpp · Hexagon · Q4_0 / Q8_0 最快"),
        )
    }

    /**
     * Reads an old per-model override safely: 「GenieX」 with no backend meant GenieX's default (NPU),
     * so it becomes backend = NPU; the engine field itself is always dropped.
     */
    fun migrate(o: ModelOverrides): ModelOverrides {
        if (o.engine == null) return o
        val backend = if (o.backend == null && GenieX.parseEngine(o.engine) == GenieX.ENGINE_GENIEX) Backend.NPU.name else o.backend
        return o.copy(engine = null, backend = backend)
    }

    /** The global 「GGUF 默认引擎 = GenieX」 becomes the GGUF default backend NPU (unless one was set). */
    fun migrateDefaultBackend(ggufEngine: String, current: String?): String? =
        current ?: if (GenieX.parseEngine(ggufEngine) == GenieX.ENGINE_GENIEX) Backend.NPU.name else null
}
