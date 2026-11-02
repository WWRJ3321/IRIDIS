package com.iris.localai.engine.geniex

import com.iris.localai.engine.Backend
import com.iris.localai.engine.NpuState
import kotlinx.coroutines.flow.StateFlow

/**
 * 2.2.0: Qualcomm GenieX (com.qualcomm.qti:geniex-android 0.8.0) as the fourth engine, full flavour only.
 * Two runtimes inside one SDK:
 *  - `llama_cpp`: GGUF files on the Hexagon NPU / Adreno GPU / CPU (Q4_0 is best on Hexagon);
 *  - `qairt`: Qualcomm AI Hub pre-compiled bundles (a folder: genie_config.json + *.bin + tokenizer.json), NPU only.
 * The APK carries only the SDK's Kotlin classes; its native libraries are an optional download
 * (设置 › 模型与加载), like the LiteRT NPU runtime. Everything in this file is pure and unit-tested.
 */
object GenieX {
    const val VERSION = "0.8.0"
    const val LABEL = "GenieX"

    /** Engine ids for GGUF files ([com.iris.localai.data.AppSettings.ggufEngine] / per-model override). */
    const val ENGINE_LLAMA = "llama"
    const val ENGINE_GENIEX = "geniex"

    fun parseEngine(s: String?): String? = when (s?.trim()?.lowercase()) {
        "llama", "llama.cpp", "llamacpp" -> ENGINE_LLAMA
        "geniex" -> ENGINE_GENIEX
        else -> null
    }

    /** "GenieX · NPU" — the tag shown in the model list / picker for models run by GenieX. */
    fun tag(backend: Backend): String = "$LABEL · ${backend.label}"

    /** `compute_unit` of LlmCreateInput for a backend (llama_cpp; qairt ignores it and always uses the NPU). */
    fun computeUnit(backend: Backend): String = when (backend) {
        Backend.NPU -> "npu"
        Backend.GPU -> "gpu"
        Backend.CPU -> "cpu"
    }

    const val RUNTIME_LLAMA_CPP = "llama_cpp"
    const val RUNTIME_QAIRT = "qairt"

    /** Shown when the runtime is not installed. */
    const val NEED_RUNTIME = "需要先在 设置 › 模型与加载 下载 GenieX 运行库"
    const val NOT_IN_BUILD = "此版本不含 GenieX（仅 full 版本）"

    /**
     * User-facing error: a Chinese summary plus the original exception text (class + message, and the
     * cause's message when it adds something), so nothing the SDK said is lost.
     */
    fun error(summary: String, t: Throwable?): String {
        if (t == null) return summary
        val raw = buildString {
            append(t.javaClass.simpleName.ifEmpty { "Exception" })
            t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.trim()) }
            val c = t.cause
            if (c != null && c !== t) {
                val cm = c.message?.trim().orEmpty()
                if (cm.isNotEmpty() && cm != t.message?.trim()) append("（原因：").append(c.javaClass.simpleName).append(": ").append(cm).append('）')
            }
        }
        return "$summary（原始错误：${raw.take(600)}）"
    }

    /**
     * A hint for the SDK's error texts (the JNI throws "Llm create failed: <geniex_get_error_message>",
     * e.g. "File not found or inaccessible", "Parameter not supported by this plugin").
     */
    fun codeHint(message: String?): String? {
        val m = message.orEmpty().lowercase()
        return when {
            "parameter not supported" in m -> "参数不受该运行时支持（QAIRT 包的上下文长度在编译时固定）"
            "file not found" in m -> "找不到模型文件（QAIRT 包需要 genie_config.json、tokenizer.json 和全部 .bin；GGUF 需可读）"
            "unknown device" in m -> "不认识的计算单元"
            "plugin" in m && ("load" in m || "invalid" in m || "not found" in m) -> "GenieX 插件未加载，可在 设置 › 模型与加载 修复运行库"
            "memory allocation" in m -> "内存不足"
            "model loading failed" in m || "invalid model" in m -> "模型加载失败（格式不受支持或文件损坏）"
            else -> null
        }
    }
}

/**
 * Hexagon generation of a Snapdragon SoC and the GenieX libraries it needs. The 0.8.0 AAR ships
 * 51 libraries (≈ 82 MB); a phone needs at most 20 of them: the HTP skel/stub of its own Hexagon
 * generation only, no HTA / SNPE / LPAI, no profiling readers / Optrace, no PlatformValidator /
 * calculator samples, no NetRun tools, no libQnnHtpPrepare (online graph compiler — GGUF does not use
 * QNN at all and QAIRT bundles are pre-compiled context binaries), no libomp (nothing links it).
 */
object GenieXLibs {
    data class Pin(val size: Long, val sha256: String)

    /** One file to install: [entry] in the AAR → [path] relative to the runtime directory. */
    data class Lib(val entry: String, val path: String, val pin: Pin)

    const val AAR_NAME = "geniex-android-${GenieX.VERSION}.aar"
    const val AAR_BYTES = 82_167_359L
    const val AAR_SHA256 = "330d4d20ca27b449ba3e1b588fa2e964124e1682c01502952b483875d902dcc9"
    val AAR_URLS = listOf(
        "https://repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/${GenieX.VERSION}/$AAR_NAME",
        "https://maven.aliyun.com/repository/central/com/qualcomm/qti/geniex-android/${GenieX.VERSION}/$AAR_NAME",
    )

    /** GenieX's registry scans `<GENIEX_PLUGIN_PATH>/<id>/libgeniex_plugin.so` at init. */
    const val PLUGIN_FILE = "libgeniex_plugin.so"

    /** Size + SHA-256 of every library we may install, taken from the Maven Central 0.8.0 AAR. */
    val PINS: Map<String, Pin> = mapOf(
        "libnpu_jni.so" to Pin(465896L, "ee2af03f6ab75e0805aa288864075ff1844b5c2e1121ce3704269749fbe3d3da"),
        "libgeniex.so" to Pin(5265880L, "47ba9feb717ded2dd74d566b1808e3f3515e2824f46d6c42f0e20f47ab63f02b"),
        "libggml-base.so" to Pin(996280L, "448799f31f969b7fef5bb2715a77a1f8850d684c3af6b299da6936c4ce24387e"),
        "libggml-cpu.so" to Pin(867280L, "c43dbd66a1e8c366930c2b02a098956b8e7cd9dce21654ae7501f516e96c1ae7"),
        "libggml-opencl.so" to Pin(3496576L, "d7690ef7de6f69713c76cfdee3de66527473b5be83e5b91a1f96f54332857f46"),
        "libggml-hexagon.so" to Pin(860944L, "d20212fd44f66759a02f31f65286f30d9d51b1161fb3b9b04f26ff2ea81def7a"),
        "libggml.so" to Pin(31336L, "d88d0d5bcb3f75a3d47d0357fd966e301a13f4ef2a5c293b61a619e80b8ffe14"),
        "libllama.so" to Pin(3390392L, "cca26539c63842d9910085a030ab141adfe2218fb22b52d9853d3dbb94ac8c21"),
        "libllama-common.so" to Pin(3738880L, "33f4a8fae630738746b0b5efbd10c3d74186dd9abb86848b5c1d5d9466fb4930"),
        "libmtmd.so" to Pin(1239336L, "6de28b5e23b8307929ba83f54e31ee3f263426644c600ac3cfa55048e5d97980"),
        "libgeniex_plugin_llama_cpp.so" to Pin(228560L, "c4e5f93dd883e3cb4c1bd53b864b2c08146b09f2524a15bd0344f556cd76b78c"),
        "libgeniex-proc.so" to Pin(7092056L, "ab64baa0af53f74c1599902b94f1aefaab8b42f424cd747e070fb8a44c68e40e"),
        "libgeniex-proc-vision.so" to Pin(374032L, "996da37ad72bd345f14cea82ffcb01781eabcfd579509caf0351f0b4355a8b78"),
        "libgeniex_core.so" to Pin(1141400L, "d39778e9ef992c913f2bae3ea243b4c0eb49c6ce0d8533c62b837d28fca372a9"),
        "libgeniex_vlm.so" to Pin(356832L, "38344ab1713c0739514ec55726f73e6573ad46d656aa43476824dfcc78062fff"),
        "libgeniex_plugin_qairt.so" to Pin(277600L, "cbbff6b297d53bc3a86b621edbb03e7d60b08eeab1f3111c7432b8d5538304eb"),
        "libQnnSystem.so" to Pin(3635400L, "f08e9ddc18a40719839cce8bbfb3afd3aef06f5ad486b21934a1c4568c9a6a48"),
        "libQnnHtp.so" to Pin(2952008L, "b5b36b2d5e0cc352b5e5f468f6f668b1585d515854fc1c5890b1365047a36be1"),
        "libQnnHtpNetRunExtensions.so" to Pin(957864L, "0d29d3dac7d82d8eb5bdc47a0649a7d6a5f5b4a2fbc52b2f975b6ae2c16b6213"),
        "libggml-htp-v73.so" to Pin(791136L, "003ec7c9dc4b451773fda44e97816a33dff7ba3b64f2b9db40335986f52e9bc2"),
        "libggml-htp-v75.so" to Pin(741984L, "4fea34565752790266ab653d331640f52be8c446f1d9d15dfb308e4c9671662f"),
        "libggml-htp-v79.so" to Pin(758436L, "4185640aaacd627728e324fa191f5bc071e4ca80f82f81712e362c1e55d68b23"),
        "libggml-htp-v81.so" to Pin(783020L, "4aefc5f6372f966e029c393773efd689f0a3a59cdedaae17514a673c1cedad30"),
        "libQnnHtpV79Stub.so" to Pin(726960L, "16ed6f58965bcabc5cbe4e6c322a4953aefe2c74d6d1c662e70e6234eae0c662"),
        "libQnnHtpV79Skel.so" to Pin(10936696L, "82275aaae3f022387e2c86d5b45eecb390b1b873e6a223a31036bdf5c985ceb1"),
        "libQnnHtpV81Stub.so" to Pin(803416L, "e97898d6772288e202e9cf988b80b39b8336f3dca8b99ee3075d234093582e3c"),
        "libQnnHtpV81Skel.so" to Pin(11788496L, "4b19d3c6ec8b3017f80710ffa1726c3a6578047933aee4cf451b9eb526403ff9"),
    )

    /** Hexagon generations GenieX 0.8.0 ships a ggml-htp kernel for. */
    val LLAMA_ARCHS = setOf(73, 75, 79, 81)
    /** …and a QNN HTP stub/skel for (8 Elite, 8 Elite Gen 5). */
    val QAIRT_ARCHS = setOf(79, 81)

    /** Hexagon arch of a Qualcomm SoC (Build.SOC_MANUFACTURER / SOC_MODEL); null = not a supported Snapdragon. */
    fun hexagonArch(manufacturer: String?, socModel: String?): Int? {
        if (!isQualcomm(manufacturer)) return null
        val id = Regex("^(sm|qcs)(\\d{4})").find(socModel.orEmpty().trim().lowercase())?.let { it.groupValues[1] + it.groupValues[2] }
        return when (id) {
            "sm8850" -> 81
            "sm8750" -> 79
            "sm8650" -> 75
            "sm8550" -> 73
            else -> null
        }
    }

    fun isQualcomm(manufacturer: String?): Boolean =
        manufacturer.equals("QTI", true) || manufacturer.orEmpty().contains("qualcomm", true)

    /** Why GenieX cannot run on this chip, or null when it can. */
    fun unsupportedReason(manufacturer: String?, socModel: String?): String? = when {
        !isQualcomm(manufacturer) -> "GenieX 只支持高通骁龙芯片；本机是 ${manufacturer.orEmpty().ifBlank { "未知厂商" }} ${socModel.orEmpty()}".trim()
        hexagonArch(manufacturer, socModel) == null -> "GenieX 0.8.0 不支持此骁龙型号（${socModel.orEmpty()}）；支持 8 Gen 2 / 8 Gen 3 / 8 Elite / 8 Elite Gen 5，QAIRT 包仅 8 Elite 系列"
        else -> null
    }

    private fun lib(name: String, path: String = name) = Lib("jni/arm64-v8a/$name", path, PINS.getValue(name))

    /** Host libraries in load (dependency) order: every NEEDED entry comes before its user. */
    val CORE_LOAD_ORDER = listOf(
        "libggml-base.so", "libggml-cpu.so", "libggml-opencl.so", "libggml-hexagon.so", "libggml.so",
        "libllama.so", "libllama-common.so", "libmtmd.so", "libgeniex.so",
        "libgeniex-proc.so", "libgeniex-proc-vision.so", "libgeniex_core.so", "libgeniex_vlm.so",
    )

    /** JNI bridge, loaded last: its JNI_OnLoad runs geniex_init(), which scans the plugin folders. */
    const val JNI = "libnpu_jni.so"

    /** QNN host libraries for QAIRT, preloaded (in this order) only when a QAIRT bundle is loaded. */
    fun qnnLoadOrder(arch: Int): List<String> =
        if (arch in QAIRT_ARCHS) listOf("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpV${arch}Stub.so", "libQnnHtpNetRunExtensions.so") else emptyList()

    /** Every file installed for [arch], with where it goes. DSP-side skels are installed but never System.load()ed. */
    fun forArch(arch: Int): List<Lib> {
        require(arch in LLAMA_ARCHS) { "unsupported Hexagon v$arch" }
        val out = ArrayList<Lib>()
        CORE_LOAD_ORDER.forEach { out += lib(it) }
        out += lib(JNI)
        out += lib("libggml-htp-v$arch.so")
        out += lib("libgeniex_plugin_llama_cpp.so", "${GenieX.RUNTIME_LLAMA_CPP}/$PLUGIN_FILE")
        if (arch in QAIRT_ARCHS) {
            out += lib("libgeniex_plugin_qairt.so", "${GenieX.RUNTIME_QAIRT}/$PLUGIN_FILE")
            qnnLoadOrder(arch).forEach { out += lib(it) }
            out += lib("libQnnHtpV${arch}Skel.so")
        }
        return out
    }

    fun installedBytes(arch: Int): Long = forArch(arch).sumOf { it.pin.size }

    // ---------------------------------------------------------------- install marker

    fun markerText(arch: Int, libs: List<Lib>): String = buildString {
        append("geniex=").append(GenieX.VERSION).append(" arch=v").append(arch).append('\n')
        libs.forEach { append(it.path).append('\t').append(it.pin.size).append('\t').append(it.pin.sha256).append('\n') }
    }

    sealed interface Check {
        data object Ok : Check
        data object NotInstalled : Check
        data class Broken(val reason: String) : Check
    }

    /**
     * Installed = marker for this version/arch present and every expected file has its pinned size.
     * (SHA-256 is verified at install time; the per-query check is presence + size.)
     */
    fun check(arch: Int, marker: String?, sizeOf: (String) -> Long?): Check {
        if (marker == null) return Check.NotInstalled
        val header = marker.lineSequence().firstOrNull().orEmpty()
        if (header != "geniex=${GenieX.VERSION} arch=v$arch") return Check.Broken("运行库版本不匹配（$header）")
        for (l in forArch(arch)) {
            val got = sizeOf(l.path) ?: return Check.Broken("缺少 ${l.path}")
            if (got != l.pin.size) return Check.Broken("${l.path} 大小不对（$got ≠ ${l.pin.size}）")
        }
        return Check.Ok
    }
}

/** Qualcomm AI Hub (QAIRT / Genie) bundle folders. */
object QairtBundle {
    const val GENIE_CONFIG = "genie_config.json"
    const val METADATA = "metadata.json"
    const val TOKENIZER = "tokenizer.json"
    const val VISION_ENCODER = "vision_encoder.bin"

    /** A folder is a QAIRT bundle when it has context-binary shards and the Genie config or AI Hub metadata. */
    fun isFileSet(names: Set<String>): Boolean =
        names.any { it.lowercase().endsWith(".bin") } && (GENIE_CONFIG in names || METADATA in names) && "llm.mnn" !in names

    /** What a load needs that is missing, or null. */
    fun missing(names: Set<String>): String? = when {
        names.none { it.lowercase().endsWith(".bin") } -> "没有 .bin 分片"
        GENIE_CONFIG !in names -> "缺少 $GENIE_CONFIG"
        TOKENIZER !in names -> "缺少 $TOKENIZER"
        else -> null
    }

    /** The plugin only uses the parent directory of model_path; point it at genie_config.json. */
    fun modelPathName(names: Set<String>): String =
        if (GENIE_CONFIG in names) GENIE_CONFIG else names.filter { it.lowercase().endsWith(".bin") }.minOrNull() ?: GENIE_CONFIG

    fun isVision(names: Set<String>): Boolean = VISION_ENCODER in names

    /** "Qwen3-4B-Instruct-2507_genie_bundle" → "Qwen3 4B Instruct 2507". */
    fun displayName(folder: String): String {
        val base = folder.replace(Regex("[-_.]?(genie[-_]?bundle|qairt|aihub|geniex)$", RegexOption.IGNORE_CASE), "")
        return base.replace(Regex("[_-]+"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { folder }
    }

    /** Context size compiled into the bundle (`"context": {"size": N}` in genie_config.json), or null. */
    fun contextSize(genieConfig: String): Int? =
        Regex("\"context\"\\s*:\\s*\\{[^{}]*?\"size\"\\s*:\\s*(\\d+)").find(genieConfig)?.groupValues?.get(1)?.toIntOrNull()
}

/** Vision projector (mmproj) next to a GGUF file, for GenieX's VLM path. */
object GenieXVision {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val QUANT = Regex("(?i)[-_.](iq\\d_[a-z]+|q\\d_k_[sml]|q\\d_k|q\\d_\\d|q\\d|f16|bf16|f32)$")

    private fun stem(fileName: String): String = fileName.substringBeforeLast('.').replace(QUANT, "")

    /** True for projector files (`mmproj-*.gguf`, `*.mmproj*.gguf`): never offered as chat models by this engine. */
    fun isProjector(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.endsWith(".gguf") && "mmproj" in n
    }

    /**
     * The projector for [modelFile] among [siblings]: one whose name (without "mmproj" and the quant tag)
     * matches the model's name; else, when the folder holds exactly one projector and the model name
     * looks like a VLM ("vl", "vision", "llava", "omni", "gemma-3"), that one. Null = text-only.
     */
    fun findMmproj(modelFile: String, siblings: Collection<String>): String? {
        if (isProjector(modelFile)) return null
        val projectors = siblings.filter { isProjector(it) && it != modelFile }
        if (projectors.isEmpty()) return null
        val m = norm(stem(modelFile))
        projectors.firstOrNull { p ->
            val ps = norm(stem(p).replace(Regex("(?i)mmproj"), ""))
            ps.isNotEmpty() && (m.contains(ps) || ps.contains(m))
        }?.let { return it }
        val lower = modelFile.lowercase()
        val vlmName = listOf("vl", "vision", "llava", "omni", "gemma-3", "gemma3", "minicpm-v", "smolvlm").any { it in lower }
        return projectors.singleOrNull()?.takeIf { vlmName }
    }
}

/**
 * QAIRT keeps the conversation in its KV cache and templates only the messages after the last
 * assistant turn (qairt llm.cpp apply_chat_template). So a request may continue the cache only when
 * the history it gets is exactly what the cache holds plus new messages after our own last reply.
 */
object GenieXTurns {
    data class Msg(val role: String, val content: String)

    /** True = keep the KV cache and send only the new tail; false = reset first and send everything. */
    fun canContinue(cached: List<Msg>, next: List<Msg>): Boolean {
        if (cached.isEmpty() || next.size <= cached.size) return false
        if (cached.last().role != "assistant") return false
        if (next.subList(0, cached.size) != cached) return false
        val tail = next.subList(cached.size, next.size)
        return tail.none { it.role == "assistant" || it.role == "system" }
    }
}

/** 2.1.2: GenieX GGUF on Hexagon sometimes finished a regenerate with no tokens at all. */
object GenieXRetry {
    const val EMPTY_MESSAGE = "模型这次没有输出任何内容。已清空缓存，再点一次重新生成试试；仍然没有就换 GPU 后端。"

    fun shouldRetryEmpty(attempt: Int, cancelled: Boolean, emptyOutput: Boolean, reason: com.iris.localai.engine.StopReason?): Boolean =
        attempt == 0 && !cancelled && emptyOutput && reason != com.iris.localai.engine.StopReason.CANCELLED
}

/** What the app shows and drives for the downloadable GenieX runtime (implemented in the full flavour). */
interface GenieXSupport {
    /** e.g. "SM8750 · Hexagon V79"; null when this chip cannot run GenieX. */
    val chipLabel: String?
    val state: StateFlow<NpuState>
    /** This chip can run AI Hub QAIRT bundles (8 Elite / 8 Elite Gen 5). */
    val qairtSupported: Boolean
    val downloadBytes: Long
    val installedBytes: Long
    suspend fun install()
    fun delete()
    fun refreshState(): NpuState
}

/** Backend options / features of the two GenieX model kinds (used by [com.iris.localai.engine.BackendCapabilities]). */
object GenieXRules {
    /** Why GenieX cannot be used, from the runtime state (null runtime = not in this build); null = ready. */
    fun block(state: NpuState?): String? = when (state) {
        null -> GenieX.NOT_IN_BUILD
        is NpuState.Unsupported -> state.reason
        NpuState.Installed -> null
        is NpuState.NeedsRepair -> "GenieX 运行库不完整（${state.reason}），请在 设置 › 模型与加载 修复"
        is NpuState.Downloading -> "GenieX 运行库正在下载"
        NpuState.NotInstalled, is NpuState.Failed -> GenieX.NEED_RUNTIME
    }

    fun ggufOptions(env: com.iris.localai.engine.BackendEnvironment): List<com.iris.localai.engine.BackendOption> {
        val b = env.geniexBlock
        if (b != null) return Backend.entries.map { com.iris.localai.engine.BackendOption(it, false, b) }
        val noCl = "本机没有可用的 OpenCL 驱动"
        return listOf(
            com.iris.localai.engine.BackendOption(Backend.CPU, true, detail = "最稳，速度一般"),
            com.iris.localai.engine.BackendOption(Backend.GPU, env.hasOpenCl, if (env.hasOpenCl) null else noCl, "显卡加速"),
            com.iris.localai.engine.BackendOption(Backend.NPU, true, detail = "最快，Q4_0 模型效果最好"),
        )
    }

    fun qairtOptions(env: com.iris.localai.engine.BackendEnvironment): List<com.iris.localai.engine.BackendOption> {
        val onlyNpu = "AI Hub QAIRT 包是为 NPU 预编译的，只能在 NPU 上运行"
        val npuReason = env.geniexBlock ?: if (!env.geniexQairt) "本机芯片没有 GenieX 的 QAIRT 运行库（仅骁龙 8 Elite / 8 Elite Gen 5）" else null
        return listOf(
            com.iris.localai.engine.BackendOption(Backend.CPU, false, onlyNpu),
            com.iris.localai.engine.BackendOption(Backend.GPU, false, onlyNpu),
            com.iris.localai.engine.BackendOption(Backend.NPU, npuReason == null, npuReason, "GenieX · QAIRT · Hexagon"),
        )
    }

    /**
     * llama.cpp inside GenieX: its own sampler (min-p, penalties, seed), batch, and context shift
     * (GenerationConfig.slidingWindow); no KV quant / Flash Attention / mmap switches.
     */
    val GGUF_FEATURES = com.iris.localai.engine.EngineFeatures(
        contextShift = true, presenceFrequency = true, seed = true, batchSize = true,
    )

    /** QAIRT: context and KV fixed in the bundle; sampling still per request. */
    val QAIRT_FEATURES = com.iris.localai.engine.EngineFeatures(
        presenceFrequency = true, seed = true,
    )
}

/** Per-request mapping from the app's sampling settings to GenieX's SamplerConfig / GenerationConfig. */
object GenieXSampling {
    /**
     * GenieX's SamplerConfig treats 0 as "use the bundle / plugin default" for every numeric field,
     * so greedy (temperature 0) becomes top-k 1, and a random seed (-1) becomes 0.
     */
    data class Args(
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val minP: Float,
        val repetitionPenalty: Float,
        val presencePenalty: Float,
        val frequencyPenalty: Float,
        val seed: Int,
    )

    fun args(p: com.iris.localai.engine.SamplingConfig): Args {
        val greedy = p.temperature <= 0f
        return Args(
            temperature = if (greedy) 0f else p.temperature,
            topP = p.topP.coerceIn(0f, 1f),
            topK = if (greedy) 1 else p.topK.coerceAtLeast(0),
            minP = p.minP.coerceIn(0f, 1f),
            repetitionPenalty = p.repeatPenalty.takeIf { it > 0f } ?: 0f,
            presencePenalty = p.presencePenalty,
            frequencyPenalty = p.frequencyPenalty,
            seed = if (p.seed < 0) 0 else p.seed,
        )
    }

    /** ProfilingData.stopReason ("eos" / "stop_sequence" / "user" / "length") → the app's reason. */
    fun stopReason(raw: String?, cancelled: Boolean, generated: Long, maxNewTokens: Int): com.iris.localai.engine.StopReason {
        val r = raw.orEmpty().lowercase()
        return when {
            cancelled || r == "user" -> com.iris.localai.engine.StopReason.CANCELLED
            r == "length" -> if (maxNewTokens > 0 && generated in 0 until maxNewTokens.toLong()) com.iris.localai.engine.StopReason.CONTEXT_FULL
                else com.iris.localai.engine.StopReason.MAX_TOKENS
            else -> com.iris.localai.engine.StopReason.END
        }
    }

    /** Models whose chat template has a thinking switch (Qwen3 hybrids, *-Thinking, R1 distills, QwQ). */
    fun thinkingModel(name: String): Boolean {
        val n = name.lowercase()
        if ("thinking" in n || "qwq" in n || "deepseek-r1" in n || Regex("(^|[-_ ])r1([-_ .]|$)").containsMatchIn(n)) return true
        return "qwen3" in n && "instruct" !in n && "-vl" !in n && "_vl" !in n
    }
}
