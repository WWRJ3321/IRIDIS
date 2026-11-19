package com.iris.localai.imagegen

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Random

/**
 * Anima NPU 包的另一种常见布局（QNN 2.28 context binary，HTP v75 编译，1024×1024）。各文件的输入输出取自文件本身的图信息：
 * - clip.bin：input_embedding [1,512,1024]（Qwen3 词嵌入，CPU 查 token_emb.bin）、qwen_mask [512]、t5_ids [512] int32、
 *   t5_mask [512]（掩码 1 = 有词元，0 = 填充）→ context [1,512,1024]（Qwen3 + adapter，含 T5 词表）。
 * - unet_part1.bin：sample [1,16,1,128,128]、timestamp [1]（= σ）、encoder_hidden_states → hidden、rope_sin/cos、adaln、context。
 * - unet_part2.bin：上面五个同名张量 → output [1,16,1,128,128]（流匹配速度）。
 * - vae_decoder.bin：Wan2.1 VAE 解码器，input [1,16,128,128]（未归一化潜变量）→ output [1,3,1024,1024]（-1..1）。
 * - token_emb.bin：Qwen3 词嵌入表 fp16（151936×1024）；tokenizer.json（Qwen BPE）、tokenizer_t5.json（T5 unigram）。
 * - 可选：vae_encoder.bin、config.json、空文件 ANIMA。
 */
data class AnimaLdFiles(val dir: File) {
    val clip = File(dir, "clip.bin")
    val unet1 = File(dir, "unet_part1.bin")
    val unet2 = File(dir, "unet_part2.bin")
    val vaeDec = File(dir, "vae_decoder.bin")
    val tokenEmb = File(dir, "token_emb.bin")
    val qwenTok = File(dir, "tokenizer.json")
    val t5Tok = File(dir, "tokenizer_t5.json")
    val config = File(dir, "config.json")

    fun missing(): List<String> = listOf(clip, unet1, unet2, vaeDec, tokenEmb, qwenTok, t5Tok).filter { !it.isFile }.map { it.name }

    /** config.json 的 default_steps（没有或读不出为 null）。 */
    fun defaultSteps(): Int? = runCatching {
        Regex("\"default_steps\"\\s*:\\s*(\\d+)").find(config.readText())?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..50 }
    }.getOrNull()

    companion object {
        val NAMES = setOf(
            "clip.bin", "unet_part1.bin", "unet_part2.bin", "vae_decoder.bin", "vae_encoder.bin",
            "token_emb.bin", "tokenizer.json", "tokenizer_t5.json", "config.json", "ANIMA",
        )
        /** 有这几个文件之一就按本布局处理。 */
        fun looksLike(dir: File): Boolean = File(dir, "unet_part1.bin").isFile || File(dir, "clip.bin").isFile || File(dir, "ANIMA").isFile
        /** 该布局最低 Hexagon 架构（包按 v75 编译）。 */
        const val MIN_ARCH = 75
    }
}

/** 本布局的纯计算部分（单测）。 */
object AnimaLdMath {
    const val H = 1024
    const val QWEN_PAD = 151643

    /** Wan2.1 VAE 的潜变量统计（diffusers AutoencoderKLWan 配置），解码前 z·std + mean。 */
    val LAT_MEAN = floatArrayOf(-0.7571f, -0.7089f, -0.9113f, 0.1075f, -0.1745f, 0.9653f, -0.1517f, 1.5508f, 0.4134f, -0.0715f, 0.5517f, -0.3632f, -0.1922f, -0.9497f, 0.2503f, -0.2921f)
    val LAT_STD = floatArrayOf(2.8184f, 1.4541f, 2.3275f, 2.6558f, 1.2196f, 1.7708f, 2.6052f, 2.0743f, 3.2687f, 2.1526f, 2.8652f, 1.5579f, 1.6382f, 1.1253f, 2.8251f, 1.916f)

    /** (16, n) 的潜变量逐通道反归一化。 */
    fun denormalize(lat: FloatArray): FloatArray {
        val n = lat.size / 16
        return FloatArray(lat.size) { i -> val c = i / n; lat[i] * LAT_STD[c] + LAT_MEAN[c] }
    }

    /** 文本侧输入：Qwen 行号（填充用 pad）、Qwen 掩码、T5 ids（填充 0）、T5 掩码。 */
    data class TextIn(val qwenRows: IntArray, val qwenMask: FloatArray, val t5Ids: IntArray, val t5Mask: FloatArray)

    fun textInputs(qwen: IntArray, t5: IntArray, n: Int): TextIn {
        require(qwen.isNotEmpty()) { "提示词为空" }
        require(qwen.size <= n && t5.size <= n) { "提示词太长（最多约 ${n - 1} 个词元）" }
        return TextIn(
            IntArray(n) { if (it < qwen.size) qwen[it] else QWEN_PAD },
            FloatArray(n) { if (it < qwen.size) 1f else 0f },
            IntArray(n) { if (it < t5.size) t5[it] else 0 },
            FloatArray(n) { if (it < t5.size) 1f else 0f },
        )
    }

    /** (3,h,w) 的 -1..1 → ARGB。 */
    fun toArgb(rgb: FloatArray, w: Int, h: Int): IntArray {
        val n = w * h
        require(rgb.size == 3 * n) { "解码输出尺寸不符" }
        fun q(f: Float) = (((f + 1f) * 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return IntArray(n) { p -> (0xFF shl 24) or (q(rgb[p]) shl 16) or (q(rgb[n + p]) shl 8) or q(rgb[2 * n + p]) }
    }

    /** 潜变量的 (高, 宽)：sample 维度的最后两维。 */
    fun latentHw(dims: List<Int>): Pair<Int, Int> {
        require(dims.size >= 2) { "sample 维度不对：$dims" }
        return dims[dims.size - 2] to dims[dims.size - 1]
    }
}

/**
 * 跑 [AnimaLdFiles] 布局：文本编码器每次用完就关；两段扩散与解码器常驻（lowMem 时分阶段：去噪只开扩散图，解码前换成解码器，用完即放）。
 * 由 [AnimaQnnEngine] 调用，锁在外面。
 */
internal class AnimaLdRunner(private val loader: QnnGraphLoader, private val cancelled: () -> Boolean) {
    private var dir: File? = null
    private var tok: AnimaTokenizer? = null
    private var emb: ByteBuffer? = null
    private var embRows = 0
    private var u1: QnnGraph? = null
    private var u2: QnnGraph? = null
    private var dec: QnnGraph? = null

    private fun check() { if (cancelled()) throw AnimaQnnEngine.CancelledException() }

    fun prepare(d: File) {
        if (d == dir && tok != null) return
        release()
        val f = AnimaLdFiles(d)
        f.missing().takeIf { it.isNotEmpty() }?.let { throw IllegalStateException("模型文件缺失：${it.joinToString()}") }
        tok = AnimaTokenizer(QwenBpe.fromJson(f.qwenTok.readText()), T5Unigram.fromJson(f.t5Tok.readText()))
        val e = RandomAccessFile(f.tokenEmb, "r").use { raf -> raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN) }
        check(e.capacity() % (AnimaLdMath.H * 2) == 0) { "token_emb.bin 大小不对" }
        emb = e; embRows = e.capacity() / (AnimaLdMath.H * 2)
        dir = d
    }

    private fun spec(list: List<QnnTensorSpec>, name: String) = list.firstOrNull { it.name == name } ?: throw IllegalStateException("模型缺少张量 $name")
    private fun enc(s: QnnTensorSpec, data: FloatArray) = TensorCodec.encode(s, data, QParams(1f, 0))
    private fun decf(s: QnnTensorSpec, b: ByteBuffer) = TensorCodec.decode(s, b.also { it.rewind() }, QParams(1f, 0))

    /** 一个图的输出接到另一个图的同名输入：类型、字节数一致直接复用缓冲。 */
    private fun pass(from: QnnTensorSpec, buf: ByteBuffer, to: QnnTensorSpec): ByteBuffer =
        if (from.dtype == to.dtype && from.bytes == to.bytes && from.scale == to.scale && from.offset == to.offset) buf.also { it.rewind() }
        else enc(to, decf(from, buf))

    private fun runText(prompt: String): Pair<QnnTensorSpec, ByteBuffer> {
        val d = dir!!
        val g = loader.open(AnimaLdFiles(d).clip)
        try {
            val n = spec(g.inputs, "qwen_mask").elements
            val t = tok!!.encode(prompt)
            val ti = AnimaLdMath.textInputs(t.qwen, t.t5, n)
            val H = AnimaLdMath.H
            val e = emb!!
            val qe = FloatArray(n * H)
            for (r in 0 until n) {
                val id = ti.qwenRows[r]
                require(id in 0 until embRows) { "bad Qwen id" }
                val base = id * H * 2
                for (k in 0 until H) qe[r * H + k] = Half.toFloat(e.getShort(base + k * 2))
            }
            val ins = g.inputs.map { s ->
                when (s.name) {
                    "input_embedding" -> enc(s, qe)
                    "qwen_mask" -> enc(s, ti.qwenMask)
                    "t5_mask" -> enc(s, ti.t5Mask)
                    "t5_ids" -> TensorCodec.encodeInts(s, ti.t5Ids)
                    else -> throw IllegalStateException("文本编码器多出输入 ${s.name}")
                }
            }.toTypedArray()
            val os = g.outputs.firstOrNull { it.name == "context" } ?: g.outputs.first()
            val outs = g.outputs.map { TensorCodec.alloc(it) }
            g.execute(ins, outs.toTypedArray())
            return os to outs[g.outputs.indexOf(os)]
        } finally {
            g.close()
        }
    }

    /**
     * 低内存模式分阶段：去噪时只开两段扩散图，解码前关掉它们再开解码器，用完即关；
     * 峰值从「两段扩散 + 解码器」降到两者中较大的一边（多一次解码器加载）。
     */
    private fun loadDraw(lowMem: Boolean) {
        if (lowMem) {
            dec?.let { runCatching { it.close() } }; dec = null
            if (u1 != null && u2 != null) return
        } else if (u1 != null && u2 != null && dec != null) return
        closeGraphs()
        val f = AnimaLdFiles(dir!!)
        val opened = ArrayList<QnnGraph>(3)
        try {
            for (b in if (lowMem) listOf(f.unet1, f.unet2) else listOf(f.vaeDec, f.unet1, f.unet2)) { check(); opened += loader.open(b) }
        } catch (t: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw t
        }
        if (lowMem) { u1 = opened[0]; u2 = opened[1] } else { dec = opened[0]; u1 = opened[1]; u2 = opened[2] }
    }

    /** 返回 (宽, 高, ARGB)。 */
    fun generate(prompt: String, steps: Int, seed: Long, lowMem: Boolean, onProgress: (Int, Int) -> Unit, shift: Float = AnimaMath.DEFAULT_SHIFT): Triple<Int, Int, IntArray> {
        val ctx = runText(prompt)
        check()
        loadDraw(lowMem)
        val p1 = u1!!; val p2 = u2!!
        val sampleSpec = spec(p1.inputs, "sample")
        val (lh, lw) = AnimaLdMath.latentHw(sampleSpec.dims)
        val nl = sampleSpec.elements
        require(nl == 16 * lh * lw) { "sample 形状不符（${sampleSpec.dims}）" }
        val sig = AnimaMath.sigmas(steps, shift)
        val rng = Random(if (seed < 0) System.nanoTime() else seed)
        val lat = FloatArray(nl) { rng.nextGaussian().toFloat() * sig[0] }
        val out1 = p1.outputs.associate { it.name to TensorCodec.alloc(it) }
        val out2 = p2.outputs.map { TensorCodec.alloc(it) }
        val vSpec = p2.outputs.firstOrNull { it.name == "output" } ?: p2.outputs.first()
        require(vSpec.elements == nl) { "unet_part2 输出形状不符（${vSpec.dims}）" }
        val ehs = p1.inputs.firstOrNull { it.name == "encoder_hidden_states" }?.let { pass(ctx.first, ctx.second, it) }
        for (i in 0 until steps) {
            check()
            val s = sig[i]; val sn = sig[i + 1]
            val ins1 = p1.inputs.map { sp ->
                when (sp.name) {
                    "sample" -> enc(sp, lat)
                    "timestamp", "timestep" -> enc(sp, FloatArray(sp.elements) { s })
                    "encoder_hidden_states" -> ehs!!.also { it.rewind() }
                    else -> throw IllegalStateException("unet_part1 多出输入 ${sp.name}")
                }
            }.toTypedArray()
            p1.execute(ins1, p1.outputs.map { out1.getValue(it.name).also { b -> b.clear() } }.toTypedArray())
            check()
            val ins2 = p2.inputs.map { sp ->
                val from = p1.outputs.firstOrNull { it.name == sp.name }
                when {
                    from != null -> pass(from, out1.getValue(from.name), sp)
                    sp.name == "context" || sp.name == "encoder_hidden_states" -> pass(ctx.first, ctx.second, sp)
                    else -> throw IllegalStateException("unet_part2 多出输入 ${sp.name}")
                }
            }.toTypedArray()
            p2.execute(ins2, out2.onEach { it.clear() }.toTypedArray())
            val v = decf(vSpec, out2[p2.outputs.indexOf(vSpec)])
            // 实时预览：x0 = x − σ·v，线性映射成低清图（不跑 VAE）
            if (PreviewHub.wanted) PreviewHub.anima?.takeIf { it.channels == 16 }?.let { m ->
                runCatching { PreviewHub.emit(m.render(LatentPreview.x0Flow(lat, v, s), lh, lw)) }
            }
            val dt = sn - s
            for (k in 0 until nl) lat[k] += dt * v[k]
            onProgress(i + 1, steps)
        }
        if (lowMem) {
            check()
            listOfNotNull(u1, u2).forEach { runCatching { it.close() } }
            u1 = null; u2 = null
            dec = loader.open(AnimaLdFiles(dir!!).vaeDec)
        }
        val dg = dec!!
        val li = dg.inputs.first()
        require(li.elements == nl) { "vae_decoder 输入形状不符（${li.dims}）" }
        val ro = dg.outputs.first()
        val out = TensorCodec.alloc(ro)
        dg.execute(arrayOf(enc(li, AnimaLdMath.denormalize(lat))), arrayOf(out))
        val rgb = decf(ro, out)
        val w = lw * 8; val h = lh * 8
        if (lowMem) closeGraphs()
        return Triple(w, h, AnimaLdMath.toArgb(rgb, w, h))
    }

    private fun closeGraphs() {
        listOfNotNull(u1, u2, dec).forEach { runCatching { it.close() } }
        u1 = null; u2 = null; dec = null
    }

    fun release() {
        closeGraphs()
        tok = null; emb = null; dir = null
    }
}
