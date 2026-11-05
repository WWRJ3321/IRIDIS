package com.iris.localai.engine.geniex

import com.iris.localai.engine.Backend
import com.iris.localai.engine.NpuState
import kotlinx.coroutines.flow.StateFlow

/**
 * 2.2.0: Qualcomm GenieX (com.qualcomm.qti:geniex-android; 0.10.0 = QAIRT 2.50 since 2.4.1) as the fourth engine, full flavour only.
 * Two runtimes inside one SDK:
 *  - `llama_cpp`: GGUF files on the Hexagon NPU / Adreno GPU / CPU (Q4_0 is best on Hexagon);
 *  - `qairt`: Qualcomm AI Hub pre-compiled bundles (a folder: genie_config.json + *.bin + tokenizer.json), NPU only.
 * The APK carries only the SDK's Kotlin classes; its native libraries are an optional download
 * (设置 › 模型与加载), like the LiteRT NPU runtime. Everything in this file is pure and unit-tested.
 */
object GenieX {
    const val VERSION = "0.10.0"
    /** 2.4.1: QAIRT (QNN) version of the runtime GenieX [VERSION] ships (libQnnHtp.so v2.50.40). */
    const val QAIRT_VERSION = "2.50"
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
    val NEED_RUNTIME: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_51a58e)
    val NOT_IN_BUILD: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_f09b89)

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
                if (cm.isNotEmpty() && cm != t.message?.trim()) append(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_1afce1)).append(c.javaClass.simpleName).append(": ").append(cm).append('）')
            }
        }
        return com.iris.localai.I18n.s(com.iris.localai.R.string.engine_22de1b, summary, raw.take(600))
    }

    /**
     * A hint for the SDK's error texts (the JNI throws "Llm create failed: <geniex_get_error_message>",
     * e.g. "File not found or inaccessible", "Parameter not supported by this plugin").
     */
    fun codeHint(message: String?, qairt: Boolean = false): String? {
        val m = message.orEmpty().lowercase()
        // 2.4.1: an AI Hub bundle that fails here is almost always built for another QAIRT version or chip.
        if (qairt && ("model loading failed" in m || "invalid model" in m || "context" in m && "binary" in m))
            return com.iris.localai.I18n.s(com.iris.localai.R.string.geniex241_loadfail, QAIRT_VERSION)
        return when {
            "parameter not supported" in m -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_77a0f4)
            "file not found" in m -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_8931e5)
            "unknown device" in m -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_5d92a6)
            "plugin" in m && ("load" in m || "invalid" in m || "not found" in m) -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_1bcb6b)
            "memory allocation" in m -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_d8e6a6)
            "model loading failed" in m || "invalid model" in m -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_f4bcd9)
            else -> null
        }
    }
}

/**
 * Hexagon generation of a Snapdragon SoC and the GenieX libraries it needs. The 0.10.0 AAR ships
 * 52 libraries (≈ 89 MB); a phone needs at most 20 of them: the HTP skel/stub of its own Hexagon
 * generation only, no HTA / SNPE / LPAI, no profiling readers / Optrace, no PlatformValidator /
 * calculator samples, no NetRun tools, no libQnnHtpPrepare (online graph compiler — GGUF does not use
 * QNN at all and QAIRT bundles are pre-compiled context binaries), no libomp (nothing links it).
 */
object GenieXLibs {
    data class Pin(val size: Long, val sha256: String)

    /** One file to install: [entry] in the AAR → [path] relative to the runtime directory. */
    data class Lib(val entry: String, val path: String, val pin: Pin)

    const val AAR_NAME = "geniex-android-${GenieX.VERSION}.aar"
    const val AAR_BYTES = 89_045_966L
    const val AAR_SHA256 = "3a40def4890fe4991d3547acf8bcfa9f1be491ad7790a1cb5d26bbecb2dde69b"
    val AAR_URLS = listOf(
        "https://repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/${GenieX.VERSION}/$AAR_NAME",
        "https://maven.aliyun.com/repository/central/com/qualcomm/qti/geniex-android/${GenieX.VERSION}/$AAR_NAME",
    )

    /** GenieX's registry scans `<GENIEX_PLUGIN_PATH>/<id>/libgeniex_plugin.so` at init. */
    const val PLUGIN_FILE = "libgeniex_plugin.so"

    /** Size + SHA-256 of every library we may install, taken from the Maven Central 0.10.0 AAR. */
    val PINS: Map<String, Pin> = mapOf(
        "libnpu_jni.so" to Pin(479248L, "13dc28ea6e2254444904346d20c767edf5330c94fdd8370d8d2cbd50ac76bc36"),
        "libgeniex.so" to Pin(5245112L, "deca3df413ac5c4c1f1db8e9e4beaa9060af8fb48448d3649096f7158bd3c569"),
        "libggml-base.so" to Pin(1002072L, "60745471b0af9dc254c56144a1d4a6258f4533c4eed2fb16c57121b4a40d6fe3"),
        "libggml-cpu.so" to Pin(858552L, "a2e15c579e45c2a26b3aec683aea86f94064163a313835f12e44e033ba7d6e0e"),
        "libggml-opencl.so" to Pin(3815776L, "a7c2809b3bee77b0d2b923a079220db2cee505a180b255b042938fa79d51fcf9"),
        "libggml-hexagon.so" to Pin(936160L, "bbbbc30f44312f7784ecdb7a1af1ce36b4cbc3e5b79bb7cb7b805707cff47a01"),
        "libggml.so" to Pin(30432L, "a877f80455f936be2a64d595adf8e0a602ac11c1740315941634a951a49205c4"),
        "libllama.so" to Pin(4037800L, "adda8c9e4f5acb29a6dd32274732543bc5ad524c69fa291ccb23b29341b58bfe"),
        "libllama-common.so" to Pin(3991144L, "8d311ce6fdc6b8b64bbe9f80a20c0ad3ad0b802cc8a7cfc230e3aff36d8d42c2"),
        "libmtmd.so" to Pin(1380056L, "5853aff1bca65a92c19f521ee33a573b55910dd45021dfee4e8e9f71e4a89f62"),
        "libgeniex_plugin_llama_cpp.so" to Pin(289616L, "c116a78f993be75a97415f4d73395cb5b7297f121cf588998a98ba15a7040bb6"),
        "libgeniex-proc.so" to Pin(7174112L, "5fe2bcc8aeefa6c0e0250a77987f4b945b7101c5a382aa4d6d4b739e9c9f5430"),
        "libgeniex-proc-vision.so" to Pin(523480L, "aa70853282e983cd25b1ce2753724ebcb9353c0d89ec3b7dda506b38d089f664"),
        "libgeniex_core.so" to Pin(1189392L, "c576ffdf6264a06e5da4bd74664e8ff14b26a485621716daf946a06f5bea0038"),
        "libgeniex_vlm.so" to Pin(361824L, "6c5f03d54b7ce80232e21c5add830ace241c9932637e0ce59847f2c19e994980"),
        "libgeniex_plugin_qairt.so" to Pin(324040L, "ea24e62179661f9609fbd6eba945493c05234586d8c765f49363098e1c079848"),
        "libQnnSystem.so" to Pin(4068024L, "b30bd9880dc48f6c5349185a7d45b326f8a064b10072e60094354fa23d9a2027"),
        "libQnnHtp.so" to Pin(3979248L, "d299323d79868adbb18997c8e99ae18f961005589ab137f56a51d27e67d6bf0f"),
        "libQnnHtpNetRunExtensions.so" to Pin(1105096L, "2923a52c9c3d4039ee181fc3978aff7546aa34ea970de939b0793954ae9c2345"),
        "libggml-htp-v73.so" to Pin(811536L, "d3effaa2a735edfccd20d594db9fd97a49aa93f2aaf852fc624429e664eb18c3"),
        "libggml-htp-v75.so" to Pin(807440L, "1275155161f45b97c7333d1beb0ab14d77b3b77beb8ba17c49e0d6306f5e4462"),
        "libggml-htp-v79.so" to Pin(827980L, "5c50629ddf2708c7368ef8806e0493fde05cf7de5a6d41adbdaccea6c4989a09"),
        "libggml-htp-v81.so" to Pin(840276L, "bb0139d68e1331d9e7131f4416c81d721ab95af60d0b6ce35d3b8f01896fbfa2"),
        "libQnnHtpV79Stub.so" to Pin(791424L, "166a3bb724c448e9425aa8f98f16b25aa1465eae6334553e408da7256d76fc99"),
        "libQnnHtpV79Skel.so" to Pin(12563756L, "408c1f6a3c6b853bbe34a9862a4054c171e76808a3b3004eac1d3c0fb309fd29"),
        "libQnnHtpV81Stub.so" to Pin(816184L, "8cfb232be5f3f09d732805a8cfe222515460625a00267ed6648667e140be8a31"),
        "libQnnHtpV81Skel.so" to Pin(13542412L, "35a3d4392c8dc15ebe724b57353975614e6eeba59cde2cc3af71b3f4b5d5db0f"),
    )

    /** Hexagon generations GenieX ships a ggml-htp kernel for (0.10.0 adds v85, no phone SoC maps to it yet). */
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
        !isQualcomm(manufacturer) -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_78eb36, manufacturer.orEmpty().ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.engine_1df01e) }, socModel.orEmpty()).trim()
        hexagonArch(manufacturer, socModel) == null -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_2cc6d9, socModel.orEmpty())
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

    /**
     * 2.4.1: version of an older runtime still on disk (sibling folders `rt-<version>-v<arch>`), or null.
     * GenieX 0.8.0 (QAIRT 2.45) cannot run bundles built with QAIRT 2.50, so it is offered as 「重新下载」.
     */
    fun oldRuntime(siblings: Collection<String>, current: String): String? =
        siblings.asSequence().filter { it != current }
            .mapNotNull { Regex("^rt-([0-9][0-9A-Za-z.]*)-v\\d+$").find(it)?.groupValues?.get(1) }
            .firstOrNull { it != GenieX.VERSION }

    fun oldRuntimeReason(old: String): String =
        com.iris.localai.I18n.s(com.iris.localai.R.string.geniex241_oldrt, old, GenieX.VERSION, GenieX.QAIRT_VERSION)

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
        if (header != "geniex=${GenieX.VERSION} arch=v$arch") return Check.Broken(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_6a0843, header))
        for (l in forArch(arch)) {
            val got = sizeOf(l.path) ?: return Check.Broken(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_d99bd6, l.path))
            if (got != l.pin.size) return Check.Broken(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_4a161b, l.path, got, l.pin.size))
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
        names.none { it.lowercase().endsWith(".bin") } -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_0d698d)
        GENIE_CONFIG !in names -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_d99bd6, GENIE_CONFIG)
        TOKENIZER !in names -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_d99bd6, TOKENIZER)
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

/**
 * 2.4.1: what an AI Hub bundle was compiled for, checked before the load so a mismatch gets a clear
 * Chinese reason instead of GenieX's "Model loading failed". `htp_backend_ext_config.json` names the
 * Hexagon generation (`"dsp_arch": "v79"`); `metadata.json` / `genie_config.json` may carry the QAIRT
 * (QNN SDK) version the context binaries were built with. Missing fields = no verdict (load as before).
 */
object QairtCompat {
    const val HTP_EXT_CONFIG = "htp_backend_ext_config.json"

    data class Info(val dspArch: Int?, val qairt: String?)

    private val DSP_ARCH = Regex("\"dsp_arch\"\\s*:\\s*\"?[vV]?(\\d{2,3})\"?")
    private val QAIRT_VER = Regex("\"(?:qairt|qnn|qnn_sdk|qairt_sdk)(?:_version)?\"\\s*:\\s*\"v?(\\d+)\\.(\\d+)")

    fun dspArch(json: String?): Int? = json?.let { DSP_ARCH.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** "2.37" from `"qairt": "2.37.1.250807…"` / `"qnn_sdk_version": "v2.45.0…"`, or null. */
    fun qairtVersion(json: String?): String? = json?.let { QAIRT_VER.find(it)?.let { m -> m.groupValues[1] + "." + m.groupValues[2] } }

    /** [files]: file name → text for the bundle's htp_backend_ext_config.json, metadata.json, genie_config.json (those present). */
    fun info(files: Map<String, String>): Info {
        val order = listOf(HTP_EXT_CONFIG, QairtBundle.GENIE_CONFIG, QairtBundle.METADATA)
        return Info(
            dspArch = order.firstNotNullOfOrNull { dspArch(files[it]) },
            qairt = listOf(QairtBundle.METADATA, QairtBundle.GENIE_CONFIG, HTP_EXT_CONFIG).firstNotNullOfOrNull { qairtVersion(files[it]) },
        )
    }

    /** -1 / 0 / 1 comparing "major.minor" versions. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun chipName(arch: Int): String = when (arch) {
        73 -> "骁龙 8 Gen 2"
        75 -> "骁龙 8 Gen 3"
        79 -> "骁龙 8 Elite"
        81 -> "骁龙 8 Elite Gen 5"
        else -> "Hexagon V$arch"
    }

    /** Why this bundle cannot load on this phone ([deviceArch] null = unknown → no arch verdict), or null. */
    fun problem(info: Info, deviceArch: Int?, runtimeQairt: String = GenieX.QAIRT_VERSION): String? {
        val b = info.dspArch
        if (b != null && deviceArch != null && b != deviceArch) {
            return com.iris.localai.I18n.s(com.iris.localai.R.string.geniex241_arch, chipName(b), b, chipName(deviceArch), deviceArch)
        }
        val q = info.qairt
        if (q != null && compare(q, runtimeQairt) > 0) {
            return com.iris.localai.I18n.s(com.iris.localai.R.string.geniex241_qairt, q, runtimeQairt)
        }
        return null
    }
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
    val EMPTY_MESSAGE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_d792e1)

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
        is NpuState.NeedsRepair -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_af7b98, state.reason)
        is NpuState.Downloading -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_00acad)
        NpuState.NotInstalled, is NpuState.Failed -> GenieX.NEED_RUNTIME
    }

    fun ggufOptions(env: com.iris.localai.engine.BackendEnvironment): List<com.iris.localai.engine.BackendOption> {
        val b = env.geniexBlock
        if (b != null) return Backend.entries.map { com.iris.localai.engine.BackendOption(it, false, b) }
        val noCl = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_2d318f)
        return listOf(
            com.iris.localai.engine.BackendOption(Backend.CPU, true, detail = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_a1a409)),
            com.iris.localai.engine.BackendOption(Backend.GPU, env.hasOpenCl, if (env.hasOpenCl) null else noCl, com.iris.localai.I18n.s(com.iris.localai.R.string.engine_2de3e4)),
            com.iris.localai.engine.BackendOption(Backend.NPU, true, detail = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_95046e)),
        )
    }

    fun qairtOptions(env: com.iris.localai.engine.BackendEnvironment): List<com.iris.localai.engine.BackendOption> {
        val onlyNpu = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_2dca88)
        val npuReason = env.geniexBlock ?: if (!env.geniexQairt) com.iris.localai.I18n.s(com.iris.localai.R.string.engine_b96a2e) else null
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
