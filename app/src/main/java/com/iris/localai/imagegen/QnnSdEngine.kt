package com.iris.localai.imagegen

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Scale / zero point of one uint16 tensor (zp = −QNN offset). */
data class QParams(val scale: Float, val zp: Int)

/**
 * Quantisation of the AI Hub SD1.5 w8a16 context binaries (release v0.64.0, read from the binaries' tensor
 * info). Used only when the binary info carries none for a tensor.
 */
object SdQuantDefaults {
    val TE_OUT = QParams(0.00093036f, 30063)
    val UNET_TIMESTEP = QParams(0.0147707f, 0)
    val UNET_LATENT = QParams(0.000241763f, 33983)
    val UNET_TEXT_EMB = QParams(0.000933156f, 30103)
    val UNET_OUT = QParams(0.000188174f, 32340)
    val VAE_IN = QParams(0.000340037f, 34382)
    /** VAE output is an image in [0,1]; 1/65535 is what a [0,1] uint16 tensor maps to. */
    val VAE_OUT = QParams(1f / 65535f, 0)
}

/** Converts float data to/from a tensor's on-wire form (uint16 asymmetric, int32, float32, uint8). */
object TensorCodec {
    fun alloc(spec: QnnTensorSpec): ByteBuffer =
        ByteBuffer.allocateDirect(maxOf(spec.bytes, 1L).toInt()).order(ByteOrder.nativeOrder())

    fun qparams(spec: QnnTensorSpec, fallback: QParams): QParams =
        if (spec.scale != null && spec.scale > 0f && spec.offset != null) QParams(spec.scale, -spec.offset) else fallback

    /** Writes [data] into [buf] in [spec]'s data type. */
    fun encode(spec: QnnTensorSpec, data: FloatArray, fallback: QParams, buf: ByteBuffer = alloc(spec)): ByteBuffer {
        require(data.size == spec.elements) { "${spec.name}: ${data.size} values for ${spec.dims}" }
        buf.clear()
        when (spec.dtype) {
            "ufixed16", "uint16" -> {
                val q = qparams(spec, fallback)
                for (x in data) buf.putShort(Quant.quantize(x, q.scale, q.zp).toShort())
            }
            "ufixed8", "uint8" -> {
                val q = qparams(spec, fallback)
                for (x in data) buf.put(((x / q.scale).roundToInt() + q.zp).coerceIn(0, 255).toByte())
            }
            "float32" -> for (x in data) buf.putFloat(x)
            "float16" -> for (x in data) buf.putShort(Half.fromFloat(x))
            "int32" -> for (x in data) buf.putInt(x.roundToInt())
            else -> throw IllegalArgumentException("${spec.name}: unsupported dtype ${spec.dtype}")
        }
        buf.flip()
        return buf
    }

    fun encodeInts(spec: QnnTensorSpec, data: IntArray, buf: ByteBuffer = alloc(spec)): ByteBuffer {
        require(data.size == spec.elements) { "${spec.name}: ${data.size} values for ${spec.dims}" }
        buf.clear()
        when (spec.dtype) {
            "int32", "uint32" -> for (x in data) buf.putInt(x)
            "float32" -> for (x in data) buf.putFloat(x.toFloat())
            else -> throw IllegalArgumentException("${spec.name}: token ids need int32, got ${spec.dtype}")
        }
        buf.flip()
        return buf
    }

    /** Reads [buf] (filled by the graph) as floats. */
    fun decode(spec: QnnTensorSpec, buf: ByteBuffer, fallback: QParams): FloatArray {
        val b = buf.duplicate().order(ByteOrder.nativeOrder()).also { it.clear() }
        val n = spec.elements
        return when (spec.dtype) {
            "ufixed16", "uint16" -> {
                val q = qparams(spec, fallback)
                FloatArray(n) { Quant.dequantize(b.getShort(it * 2).toInt() and 0xFFFF, q.scale, q.zp) }
            }
            "ufixed8", "uint8" -> {
                val q = qparams(spec, fallback)
                FloatArray(n) { ((b.get(it).toInt() and 0xFF) - q.zp) * q.scale }
            }
            "float32" -> FloatArray(n) { b.getFloat(it * 4) }
            "float16" -> FloatArray(n) { Half.toFloat(b.getShort(it * 2)) }
            "int32" -> FloatArray(n) { b.getInt(it * 4).toFloat() }
            else -> throw IllegalArgumentException("${spec.name}: unsupported dtype ${spec.dtype}")
        }
    }
}

/** IEEE 754 binary16 ⇄ float (pure Kotlin, so it runs in JVM tests; 2.2.12 for float16 graph I/O). */
object Half {
    fun toFloat(h: Short): Float {
        val x = h.toInt() and 0xFFFF
        val sign = (x ushr 15) and 1; val exp = (x ushr 10) and 0x1F; val man = x and 0x3FF
        val v = when (exp) {
            0 -> man / 1024f * 6.1035156e-5f
            31 -> if (man == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> Math.scalb(1f + man / 1024f, exp - 15)
        }
        return if (sign == 1) -v else v
    }

    fun fromFloat(f: Float): Short {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val a = bits and 0x7FFFFFFF
        if (a >= 0x7F800000) return (sign or 0x7C00 or (if (a > 0x7F800000) 0x200 else 0)).toShort()
        if (a >= 0x477FF000) return (sign or 0x7C00).toShort() // overflow → inf
        if (a < 0x38800000) { // subnormal / zero
            val v = (java.lang.Float.intBitsToFloat(a) / 5.9604645e-8f).roundToInt()
            return (sign or v).toShort()
        }
        val e = ((a ushr 23) - 112) shl 10
        val m = (a ushr 13) and 0x3FF
        val round = (a ushr 12) and 1
        return (sign or (e + m + round)).toShort()
    }
}

/**
 * The SD1.5 txt2img loop over three QNN graphs (AI Hub reference pipeline):
 * CLIP ids (int32 [1,77]) → text encoder → [1,77,768]; Euler; latent NHWC [1,64,64,4];
 * UNet(latent, timestep, text_emb) once for the negative/empty prompt and once for the prompt,
 * noise = uncond + cfg·(cond − uncond); VAE (divides by 0.18215 itself) → [1,512,512,3] in [0,1].
 * 2.2.12: also Local Dream's graphs (Anything V5): latent NCHW [1,4,64,64] (the loop is element-wise, so
 * layout only matters at the VAE), VAE input = latent × [vaeInScale] (1/0.18215), output NCHW [1,3,H,W] in
 * [-1,1] when [vaeOutSigned].
 * Pure Kotlin over [QnnGraph], so it is unit-tested with fake graphs.
 */
class SdPipeline(
    private val textEncoder: QnnGraph,
    private val unet: QnnGraph,
    private val vae: QnnGraph,
    private val vaeInScale: Float = 1f,
    private val vaeOutSigned: Boolean = false,
) {
    val teIn: QnnTensorSpec = textEncoder.inputs.single()
    val teOut: QnnTensorSpec = textEncoder.outputs.first()
    val unetLatent: QnnTensorSpec = pick(unet.inputs, "latent") { it.elements == LATENT_ELEMENTS }
    val unetTimestep: QnnTensorSpec = pick(unet.inputs, "timestep") { it.elements == 1 }
    val unetText: QnnTensorSpec = pick(unet.inputs, "text_emb") { it.elements == teOut.elements }
    val unetOut: QnnTensorSpec = unet.outputs.first()
    val vaeIn: QnnTensorSpec = vae.inputs.single()
    val vaeOut: QnnTensorSpec = vae.outputs.first()

    /** VAE output channel-first ([1,3,H,W]) rather than [1,H,W,3]. */
    val vaeNchw: Boolean get() = vaeOut.dims.size == 4 && vaeOut.dims[1] == 3 && vaeOut.dims[3] != 3

    /** Image size from the VAE output. */
    val height: Int get() = vaeOut.dims.getOrElse(if (vaeNchw) 2 else 1) { 512 }
    val width: Int get() = vaeOut.dims.getOrElse(if (vaeNchw) 3 else 2) { 512 }

    init {
        require(unet.inputs.size == 3) { "UNet expects 3 inputs, binary has ${unet.inputs.size}" }
        require(unetOut.elements == LATENT_ELEMENTS && vaeIn.elements == LATENT_ELEMENTS) { "latent size mismatch" }
        require(vaeOut.dims.lastOrNull() == 3 || vaeNchw) { "VAE output is not RGB: ${vaeOut.dims}" }
    }

    // UNet I/O buffers, reused across steps (inputs in the binary's order).
    private val unetBufs: Array<ByteBuffer> = Array(unet.inputs.size) { TensorCodec.alloc(unet.inputs[it]) }
    private val unetOutBuf: ByteBuffer = TensorCodec.alloc(unetOut)

    /** Text encoder output, dequantised: [1,77,768] floats. */
    fun encodeText(ids: IntArray): FloatArray {
        val inBuf = TensorCodec.encodeInts(teIn, ids)
        val out = TensorCodec.alloc(teOut)
        textEncoder.execute(arrayOf(inBuf), arrayOf(out))
        return TensorCodec.decode(teOut, out, SdQuantDefaults.TE_OUT)
    }

    /** Text embedding requantised to the UNet's text_emb tensor (encoded once per prompt, reused every step). */
    fun textEmbedding(ids: IntArray): ByteBuffer =
        TensorCodec.encode(unetText, encodeText(ids), SdQuantDefaults.UNET_TEXT_EMB)

    /** One UNet pass: noise prediction (floats, same NHWC order as [latent]). */
    fun runUnet(latent: FloatArray, timestep: Float, textEmb: ByteBuffer): FloatArray {
        for ((i, spec) in unet.inputs.withIndex()) {
            when (spec) {
                unetLatent -> TensorCodec.encode(spec, latent, SdQuantDefaults.UNET_LATENT, unetBufs[i])
                unetTimestep -> TensorCodec.encode(spec, floatArrayOf(timestep), SdQuantDefaults.UNET_TIMESTEP, unetBufs[i])
                unetText -> unetBufs[i] = textEmb.duplicate().order(ByteOrder.nativeOrder())
            }
        }
        unetOutBuf.clear()
        unet.execute(unetBufs, arrayOf(unetOutBuf))
        return TensorCodec.decode(unetOut, unetOutBuf, SdQuantDefaults.UNET_OUT)
    }

    /** VAE decode of the final latent → RGB floats in [0,1], NHWC. */
    fun decode(latent: FloatArray): FloatArray {
        val x = if (vaeInScale == 1f) latent else FloatArray(latent.size) { latent[it] * vaeInScale }
        val inBuf = TensorCodec.encode(vaeIn, x, SdQuantDefaults.VAE_IN)
        val out = TensorCodec.alloc(vaeOut)
        vae.execute(arrayOf(inBuf), arrayOf(out))
        val raw = TensorCodec.decode(vaeOut, out, SdQuantDefaults.VAE_OUT)
        return toNhwc01(raw, width, height, vaeNchw, vaeOutSigned)
    }

    /**
     * Full txt2img. [isCancelled] is checked before every step; returns ARGB pixels (width*height).
     * cfg ≤ 1 skips the unconditional pass.
     */
    fun generate(
        condIds: IntArray,
        uncondIds: IntArray,
        steps: Int,
        cfg: Float,
        seed: Long,
        isCancelled: () -> Boolean = { false },
        onStep: (step: Int, total: Int) -> Unit = { _, _ -> },
    ): IntArray {
        val sched = EulerScheduler().apply { setTimesteps(steps) }
        val cond = textEmbedding(condIds)
        val uncond = if (cfg > 1f) textEmbedding(uncondIds) else null
        var x = initialLatent(seed, sched.initNoiseSigma)
        for (i in 0 until steps) {
            if (isCancelled()) throw CancelledException()
            val xIn = sched.scaleModelInput(x, i)
            val t = sched.timesteps[i]
            val noise = if (uncond != null) cfgCombine(runUnet(xIn, t, uncond), runUnet(xIn, t, cond), cfg)
                        else runUnet(xIn, t, cond)
            x = sched.step(noise, i, x)
            onStep(i + 1, steps)
        }
        if (isCancelled()) throw CancelledException()
        return toArgb(decode(x), width, height)
    }

    class CancelledException : RuntimeException("已取消")

    companion object {
        const val LATENT_ELEMENTS = 64 * 64 * 4

        private fun pick(specs: List<QnnTensorSpec>, name: String, fallback: (QnnTensorSpec) -> Boolean): QnnTensorSpec =
            specs.firstOrNull { it.name == name } ?: specs.firstOrNull { it.name.contains(name, ignoreCase = true) }
            ?: specs.firstOrNull(fallback) ?: throw IllegalArgumentException("UNet input '$name' not found in ${specs.map { it.name }}")

        /** randn(seed) · σ_init, NHWC order. */
        fun initialLatent(seed: Long, initSigma: Float, n: Int = LATENT_ELEMENTS): FloatArray {
            val r = java.util.Random(seed)
            return FloatArray(n) { (r.nextGaussian() * initSigma).toFloat() }
        }

        /** Classifier-free guidance: uncond + cfg·(cond − uncond). */
        fun cfgCombine(uncond: FloatArray, cond: FloatArray, cfg: Float): FloatArray {
            require(uncond.size == cond.size)
            return FloatArray(cond.size) { uncond[it] + cfg * (cond[it] - uncond[it]) }
        }

        /** VAE output → NHWC RGB in [0,1] (from NCHW and/or [-1,1] when asked). */
        fun toNhwc01(raw: FloatArray, width: Int, height: Int, nchw: Boolean, signed: Boolean): FloatArray {
            if (!nchw && !signed) return raw
            val px = width * height
            return FloatArray(raw.size) { i ->
                val v = if (nchw) raw[(i % 3) * px + i / 3] else raw[i]
                if (signed) (v + 1f) * 0.5f else v
            }
        }

        /** NHWC RGB floats in [0,1] → opaque ARGB ints. */
        fun toArgb(rgb: FloatArray, width: Int, height: Int): IntArray {
            require(rgb.size == width * height * 3) { "image is ${rgb.size / 3} px, expected ${width}x$height" }
            fun c(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()
            return IntArray(width * height) { p ->
                val i = p * 3
                (0xFF shl 24) or (c(rgb[i]) shl 16) or (c(rgb[i + 1]) shl 8) or c(rgb[i + 2])
            }
        }
    }
}

/** What an sd15-qnn model folder holds (flattened AI Hub zip + CLIP tokenizer). */
data class SdQnnFiles(val dir: File) {
    val textEncoder = File(dir, "text_encoder.bin")
    val unet = File(dir, "unet.bin")
    val vae = File(dir, "vae.bin")
    val metadata = File(dir, "metadata.json")
    val vocab = File(dir, "tokenizer/vocab.json")
    val merges = File(dir, "tokenizer/merges.txt")
    fun missing(): List<String> = listOf(textEncoder, unet, vae, vocab, merges).filter { !it.isFile }.map { it.name }
}

/**
 * SD1.5 on the Hexagon NPU through QNN context binaries ([ImageCatalog.SD15_QNN]). The three graphs and
 * the tokenizer are loaded on first use and kept until [release]. Generation runs on the caller's thread
 * (call from Dispatchers.Default); [cancel] stops it between steps.
 */
class QnnSdEngine(
    private val modelDir: (ImageModel) -> File?,
    private val loader: QnnGraphLoader,
    private val perf: HtpPerf = HtpPerf.NONE,
) : ImageGenEngine {
    companion object {
        /** Same wording as the 生图 page's ImageGenText.NOT_DOWNLOADED_ERROR. */
        const val NOT_DOWNLOADED = "模型未下载，请先点「下载」"
    }

    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private var loadedDir: File? = null
    private var graphs: List<QnnGraph> = emptyList()
    @Volatile private var pipeline: SdPipeline? = null
    private var tokenizer: ClipTokenizer? = null
    /** metadata.json as shipped in the AI Hub zip (kept for diagnostics; tensor quant comes from the binaries). */
    var metadata: String? = null; private set

    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> =
        runCatching {
            cancelled.set(false)
            val pixels = synchronized(lock) {
                val (p, tok) = ensureLoaded(model)
                if (cancelled.get()) throw SdPipeline.CancelledException()
                // HTP to max clocks for the denoising loop only; back to DCVS right after (also on failure/cancel).
                runCatching { perf.burst() }
                try {
                    p.generate(
                        condIds = tok.encode(req.prompt),
                        uncondIds = tok.encode(req.effectiveNegative),
                        steps = req.steps,
                        cfg = req.cfg,
                        seed = req.seed,
                        isCancelled = { cancelled.get() },
                        onStep = onProgress,
                    ) to (p.width to p.height)
                } finally {
                    runCatching { perf.relax() }
                }
            }
            encodePng(pixels.first, pixels.second.first, pixels.second.second)
        }

    override fun cancel() = cancelled.set(true)

    private fun ensureLoaded(model: ImageModel): Pair<SdPipeline, ClipTokenizer> {
        val dir = modelDir(model) ?: error(NOT_DOWNLOADED)
        val p = pipeline; val t = tokenizer
        if (p != null && t != null && dir == loadedDir) return p to t
        releaseLocked()
        val files = SdQnnFiles(dir)
        files.missing().takeIf { it.isNotEmpty() }?.let { throw IllegalStateException("模型文件缺失：${it.joinToString()}") }
        metadata = runCatching { files.metadata.readText() }.getOrNull()
        val tok = ClipTokenizer.fromText(files.vocab.readText(), files.merges.readText())
        val opened = ArrayList<QnnGraph>(3)
        try {
            for (f in listOf(files.textEncoder, files.unet, files.vae)) {
                if (cancelled.get()) throw SdPipeline.CancelledException()
                opened.add(loader.open(f))
            }
            val pipe = SdPipeline(opened[0], opened[1], opened[2], model.vaeInScale, model.vaeOutSigned)
            graphs = opened; pipeline = pipe; tokenizer = tok; loadedDir = dir
            return pipe to tok
        } catch (t: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw t
        }
    }

    /** Frees the NPU contexts (≈1.1 GB). */
    fun release() = synchronized(lock) { releaseLocked() }

    /** True while the three graphs are loaded. */
    val isLoaded: Boolean get() = pipeline != null

    private fun releaseLocked() {
        graphs.forEach { runCatching { it.close() } }
        graphs = emptyList(); pipeline = null; tokenizer = null; loadedDir = null
    }

    private fun encodePng(argb: IntArray, w: Int, h: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(argb, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}
