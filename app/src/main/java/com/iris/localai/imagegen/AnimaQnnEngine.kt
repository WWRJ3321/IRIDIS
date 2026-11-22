package com.iris.localai.imagegen

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 用户导入的 Anima NPU 包（QNN context binary）放在一个文件夹里，认两种布局：
 * - 8 Elite 布局（HTP v79）：文本编码器 text.bin（Qwen3-0.6B + 6 层 adapter → 256×1024 上下文），
 *   扩散模型四段 part0..3.bin（每段 7 层），解码器 dec.bin（taew2_1），以及 CPU 上查的两张词表和两个分词器。
 * - 另一种常见布局（[AnimaLdFiles]，QNN 2.28 / HTP v75 起）：clip.bin、unet_part1/2.bin、vae_decoder.bin、token_emb.bin 等。
 */
data class AnimaQnnFiles(val dir: File) {
    val text = File(dir, "text.bin")
    val parts = (0..3).map { File(dir, "part$it.bin") }
    val dec = File(dir, "dec.bin")
    val qwenF16 = File(dir, "qwen_embed_f16.raw")
    val qwenQ8 = File(dir, "qwen_embed_q8.raw")
    val t5 = File(dir, "t5_embed_f16.raw")
    val qwenTok = File(dir, "qwen_tokenizer.json")
    val t5Tok = File(dir, "t5_tokenizer.json")

    /** 文件夹是 [AnimaLdFiles] 布局。 */
    val ld: Boolean get() = AnimaLdFiles.looksLike(dir)

    fun missing(): List<String> {
        if (ld) return AnimaLdFiles(dir).missing()
        val need = listOf(text, dec, t5, qwenTok, t5Tok) + parts
        val m = need.filter { !it.isFile }.map { it.name }
        return if (!qwenF16.isFile && !qwenQ8.isFile) m + qwenF16.name else m
    }

    companion object {
        val ELITE_NAMES = setOf(
            "text.bin", "part0.bin", "part1.bin", "part2.bin", "part3.bin", "dec.bin",
            "qwen_embed_f16.raw", "qwen_embed_q8.raw", "t5_embed_f16.raw", "qwen_tokenizer.json", "t5_tokenizer.json",
        )
        /** 导入时收的文件名（两种布局；其它一律跳过）。 */
        val NAMES = ELITE_NAMES + AnimaLdFiles.NAMES
        /** 单独出现时不足以说明是 NPU 包的通用文件名。 */
        val WEAK = setOf("config.json", "tokenizer.json", "ANIMA")
    }
}

/** 纯计算部分（单测）。 */
object AnimaMath {
    const val NQ = 256; const val NT = 256; const val H = 1024; const val D = 2048; const val CDIM = 32
    const val QWEN_ROWS = 151936; const val T5_ROWS = 32128; const val QWEN_PAD = 151643
    const val NEG = -30000f

    const val DEFAULT_SHIFT = 3f
    const val MIN_SHIFT = 1f
    const val MAX_SHIFT = 6f

    /** 调度偏移：限制在 1.0–6.0，按 0.5 取整（非法值回到默认）。 */
    fun clampShift(v: Float): Float =
        if (v.isNaN() || v.isInfinite()) DEFAULT_SHIFT else Math.round(v.coerceIn(MIN_SHIFT, MAX_SHIFT) * 2f) / 2f

    /** 流匹配时间表：σ(t)=s·t/(1+(s−1)t)（s = 调度偏移，默认 3），在 1000 级表上均匀取步，最后补 0。 */
    fun sigmas(steps: Int, shiftF: Float = DEFAULT_SHIFT): FloatArray {
        val shift = clampShift(shiftF).toDouble()
        val tab = FloatArray(1000) { i -> val t = (i + 1) / 1000.0; (shift * t / (1.0 + (shift - 1.0) * t)).toFloat() }
        val ss = 1000.0 / steps
        return FloatArray(steps + 1) { x -> if (x == steps) 0f else tab[999 - (x * ss).toInt()] }
    }

    /** Cosmos predict2 的时间步嵌入：[cos | sin]。 */
    fun sinusoid(sigma: Float, out: FloatArray = FloatArray(D)): FloatArray {
        val half = D / 2
        for (i in 0 until half) {
            val a = sigma * exp(-ln(10000.0) * i / half).toFloat()
            out[i] = cos(a); out[half + i] = sin(a)
        }
        return out
    }

    /** (16,2hp,2wp) → (hp*wp,68)，第 17 通道为 0。 */
    fun patchify(lat: FloatArray, x: FloatArray, hp: Int, wp: Int) {
        val lh = 2 * hp; val lw = 2 * wp
        for (h in 0 until hp) for (w in 0 until wp) {
            val row = (h * wp + w) * 68
            for (c in 0 until 17) for (m in 0..1) for (n in 0..1)
                x[row + c * 4 + m * 2 + n] = if (c < 16) lat[(c * lh + 2 * h + m) * lw + 2 * w + n] else 0f
        }
    }

    /** (hp*wp,64) → (16,2hp,2wp)。 */
    fun unpatchify(o: FloatArray, lat: FloatArray, hp: Int, wp: Int) {
        val lh = 2 * hp; val lw = 2 * wp
        for (h in 0 until hp) for (w in 0 until wp) {
            val row = (h * wp + w) * 64
            for (p1 in 0..1) for (p2 in 0..1) for (c in 0 until 16)
                lat[(c * lh + 2 * h + p1) * lw + 2 * w + p2] = o[row + p1 * 32 + p2 * 16 + c]
        }
    }

    /** 包里的尺寸里挑最接近请求（先比宽高比，再比面积）。 */
    fun pickSize(sizes: List<Pair<Int, Int>>, w: Int, h: Int): Pair<Int, Int> {
        if (sizes.isEmpty()) return 512 to 512
        val ar = ln(w.toDouble() / h)
        return sizes.minBy { (sw, sh) -> abs(ln(sw.toDouble() / sh) - ar) * 10 + abs(ln(sw.toDouble() * sh / (w.toDouble() * h))) }
    }

    fun parseSizes(graphs: List<String>): List<Pair<Int, Int>> = graphs.mapNotNull { n ->
        Regex("^s(\\d+)x(\\d+)$").find(n)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }
}

class AnimaQnnEngine(
    private val modelDir: (ImageModel) -> File?,
    private val loader: QnnGraphLoader,
    private val perf: HtpPerf = HtpPerf.NONE,
) : ImageGenEngine {
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()

    private var dir: File? = null
    private var tok: AnimaTokenizer? = null
    private var qwenTable: ByteBuffer? = null
    private var qwenQ8 = false
    private var t5Table: ByteBuffer? = null
    private var sizes: List<Pair<Int, Int>> = emptyList()
    private var multi = false
    private var loadedSize: Pair<Int, Int>? = null
    private var parts: List<QnnGraph> = emptyList()
    private var dec: QnnGraph? = null

    private val ldRunner = AnimaLdRunner(loader) { cancelled.get() }

    class CancelledException : RuntimeException("已取消")

    override fun cancel() = cancelled.set(true)

    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> =
        runCatching {
            cancelled.set(false)
            synchronized(lock) {
                val d = modelDir(model) ?: error(QnnSdEngine.NOT_DOWNLOADED)
                if (AnimaQnnFiles(d).ld) {
                    if (dir != null) releaseLocked(keepTables = false)
                    ldRunner.prepare(d)
                    runCatching { perf.burst() }
                    try {
                        val (w, h, argb) = ldRunner.generate(req.prompt, req.steps.coerceIn(1, 50), req.seed, req.lowMem, onProgress, model.animaShift)
                        // r5：LD 布局的包尺寸由包决定——记下来，生图页只列这一档。
                        runCatching { File(d, ImageSizes.PACK_FILE).writeText(ImageSizes.formatPack(listOf(w to h))) }
                        return@synchronized encodePng(argb, w, h)
                    } finally {
                        runCatching { perf.relax() }
                    }
                }
                ldRunner.release()
                prepare(d)
                val (w, h) = AnimaMath.pickSize(sizes, req.width, req.height)
                val t = tok!!.encode(req.prompt)
                require(t.qwen.isNotEmpty()) { "提示词为空" }
                require(t.qwen.size <= AnimaMath.NQ && t.t5.size <= AnimaMath.NT - 1) { "提示词太长（最多约 255 个词元）" }
                runCatching { perf.burst() }
                try {
                    val ctx = runText(t, req.lowMem)
                    checkCancel()
                    loadDraw(w, h, req.lowMem)
                    val argb = draw(ctx, w, h, req.steps.coerceIn(1, 50), req.seed, onProgress, model.animaShift, req.lowMem)
                    if (req.lowMem) releaseLocked(keepTables = true)
                    encodePng(argb, w, h)
                } finally {
                    runCatching { perf.relax() }
                }
            }
        }

    private fun checkCancel() { if (cancelled.get()) throw CancelledException() }

    private fun prepare(d: File) {
        if (d == dir && tok != null) return
        releaseLocked(keepTables = false)
        val f = AnimaQnnFiles(d)
        f.missing().takeIf { it.isNotEmpty() }?.let { throw IllegalStateException("模型文件缺失：${it.joinToString()}") }
        tok = AnimaTokenizer(QwenBpe.fromJson(f.qwenTok.readText()), T5Unigram.fromJson(f.t5Tok.readText()))
        qwenQ8 = f.qwenQ8.isFile
        qwenTable = map(if (qwenQ8) f.qwenQ8 else f.qwenF16)
        t5Table = map(f.t5)
        val wantQ = if (qwenQ8) AnimaMath.QWEN_ROWS.toLong() * (AnimaMath.H + 4) else AnimaMath.QWEN_ROWS.toLong() * AnimaMath.H * 2
        check(qwenTable!!.capacity().toLong() == wantQ) { "Qwen 词表大小不对" }
        check(t5Table!!.capacity().toLong() == AnimaMath.T5_ROWS.toLong() * AnimaMath.H * 2) { "T5 词表大小不对" }
        // 多尺寸包：每个文件里有 s<宽>x<高> 若干个图；单尺寸包只有一个 512×512 的图
        sizes = AnimaMath.parseSizes(loader.graphNames(f.parts[0]))
        multi = sizes.isNotEmpty()
        if (!multi) sizes = listOf(512 to 512)
        // r5：把包里编好的尺寸写进模型目录，生图页据此只列这几档（主进程读 ImageSizes.readPack）。
        runCatching { File(d, ImageSizes.PACK_FILE).writeText(ImageSizes.formatPack(sizes)) }
        dir = d
    }

    private fun map(file: File): ByteBuffer = RandomAccessFile(file, "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun spec(list: List<QnnTensorSpec>, name: String): QnnTensorSpec =
        list.firstOrNull { it.name == name } ?: throw IllegalStateException("模型缺少张量 $name")

    private fun enc(s: QnnTensorSpec, data: FloatArray): ByteBuffer {
        if (data.size != s.elements) throw IllegalStateException("${s.name}: 形状不符（${s.dims}）")
        return TensorCodec.encode(s, data, QParams(1f, 0))
    }

    private fun decf(s: QnnTensorSpec, buf: ByteBuffer): FloatArray = TensorCodec.decode(s, buf, QParams(1f, 0))

    /** 一个图的输出接到另一个图的同名输入：类型一致直接复用缓冲。 */
    private fun pass(from: QnnTensorSpec, buf: ByteBuffer, to: QnnTensorSpec): ByteBuffer =
        if (from.dtype == to.dtype && from.bytes == to.bytes && from.scale == to.scale && from.offset == to.offset) buf.also { it.rewind() }
        else enc(to, decf(from, buf))

    private fun runText(t: AnimaTokens, lowMem: Boolean): Pair<QnnTensorSpec, ByteBuffer> {
        val H = AnimaMath.H; val NQ = AnimaMath.NQ; val NT = AnimaMath.NT
        val qe = FloatArray(NQ * H); val te = FloatArray(NT * H)
        val sb = FloatArray(NQ); val tb = FloatArray(NT); val keep = FloatArray(NT)
        val qt = qwenTable!!; val tt = t5Table!!
        for (r in 0 until NQ) {
            val id = if (r < t.qwen.size) t.qwen[r] else AnimaMath.QWEN_PAD
            require(id in 0 until AnimaMath.QWEN_ROWS) { "bad Qwen id" }
            if (qwenQ8) {
                val sc = qt.getFloat(AnimaMath.QWEN_ROWS * H + id * 4)
                val base = id * H
                for (k in 0 until H) qe[r * H + k] = qt.get(base + k) * sc
            } else {
                val base = id.toLong() * H * 2
                for (k in 0 until H) qe[r * H + k] = Half.toFloat(qt.getShort((base + k * 2).toInt()))
            }
            sb[r] = if (r < t.qwen.size) 0f else AnimaMath.NEG
        }
        for (r in 0 until NT) {
            if (r < t.t5.size) {
                val id = t.t5[r]
                require(id in 0 until AnimaMath.T5_ROWS) { "bad T5 id" }
                val base = id * H * 2
                for (k in 0 until H) te[r * H + k] = Half.toFloat(tt.getShort(base + k * 2))
            }
            tb[r] = if (r < t.t5.size) 0f else AnimaMath.NEG
            keep[r] = if (r < t.t5.size) t.t5Weights[r] else 0f
        }
        val g = loader.open(AnimaQnnFiles(dir!!).text)
        try {
            val vals = mapOf("qwen_emb" to qe, "src_bias" to sb, "t5_emb" to te, "tgt_bias" to tb, "keep" to keep)
            val ins = g.inputs.map { s -> enc(s, vals[s.name] ?: throw IllegalStateException("文本编码器多出输入 ${s.name}")) }.toTypedArray()
            val os = spec(g.outputs, "ctx")
            val out = TensorCodec.alloc(os)
            g.execute(ins, arrayOf(out))
            return os to out
        } finally {
            g.close()
        }
    }

    /** 低内存模式：去噪时不开解码器（[draw] 结束前关掉四段扩散图再开），峰值少一个解码器上下文。 */
    private fun loadDraw(w: Int, h: Int, lowMem: Boolean = false) {
        if (loadedSize == (w to h) && parts.size == 4 && (dec != null || lowMem)) {
            if (lowMem) { dec?.let { runCatching { it.close() } }; dec = null }
            return
        }
        closeGraphs()
        val f = AnimaQnnFiles(dir!!)
        val g = if (multi) "s${w}x$h" else ""
        val opened = ArrayList<QnnGraph>(5)
        try {
            val decHas = multi && g in loader.graphNames(f.dec)
            if (multi && !decHas && (w to h) != (512 to 512)) throw IllegalStateException("dec.bin 和扩散文件不是同一套（多尺寸包要用同一套的 dec.bin）")
            if (!lowMem) opened += loader.open(f.dec, if (decHas) g else "")
            for (p in f.parts) { checkCancel(); opened += loader.open(p, g) }
            val first = if (lowMem) 0 else 1
            val x = spec(opened[first].inputs, "x")
            if (x.elements != (w / 16) * (h / 16) * 68) throw IllegalStateException("NPU 包尺寸不符（${x.dims}）")
        } catch (t: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw t
        }
        if (lowMem) { dec = null; parts = opened } else { dec = opened[0]; parts = opened.drop(1) }
        loadedSize = w to h
    }

    /** 低内存模式解码前：关掉扩散图，单独开解码器。 */
    private fun openDecoderOnly(w: Int, h: Int) {
        parts.forEach { runCatching { it.close() } }
        parts = emptyList(); loadedSize = null
        val f = AnimaQnnFiles(dir!!)
        val g = if (multi) "s${w}x$h" else ""
        val decHas = multi && g in loader.graphNames(f.dec)
        dec = loader.open(f.dec, if (decHas) g else "")
    }

    private fun draw(ctx: Pair<QnnTensorSpec, ByteBuffer>, w: Int, h: Int, steps: Int, seed: Long, onProgress: (Int, Int) -> Unit, shift: Float = AnimaMath.DEFAULT_SHIFT, lowMem: Boolean = false): IntArray {
        val hp = h / 16; val wp = w / 16; val L = hp * wp
        val nl = 16 * (h / 8) * (w / 8)
        val sig = AnimaMath.sigmas(steps, shift)
        val rng = Random(if (seed < 0) System.nanoTime() else seed)
        val lat = FloatArray(nl) { rng.nextGaussian().toFloat() * sig[0] }
        val x = FloatArray(L * 68); val emb = FloatArray(AnimaMath.D); val v = FloatArray(nl)
        val p0 = parts[0]
        // 每段图的固定输入（上下文、控制图嵌入、强度）按段转换一次
        val fixed = parts.map { g ->
            val m = HashMap<String, ByteBuffer>()
            g.inputs.forEach { s ->
                when (s.name) {
                    "ctx" -> m["ctx"] = pass(ctx.first, ctx.second, s)
                    "cemb" -> m["cemb"] = enc(s, FloatArray(s.elements))
                    "st" -> m["st"] = enc(s, FloatArray(s.elements))
                }
            }
            m
        }
        val outBufs = parts.map { g -> g.outputs.associate { it.name to TensorCodec.alloc(it) } }
        for (i in 0 until steps) {
            checkCancel()
            val s = sig[i]; val sn = sig[i + 1]
            AnimaMath.sinusoid(s, emb)
            AnimaMath.patchify(lat, x, hp, wp)
            // part0: x, emb, ctx → x_out, emb_out, lora_out
            run {
                val ins = p0.inputs.map { sp ->
                    when (sp.name) { "x" -> enc(sp, x); "emb" -> enc(sp, emb); else -> fixed[0][sp.name]?.also { it.rewind() } ?: error("part0 多出输入 ${sp.name}") }
                }.toTypedArray()
                p0.execute(ins, p0.outputs.map { outBufs[0].getValue(it.name).also { b -> b.clear() } }.toTypedArray())
            }
            val prevSpecs = HashMap<String, Pair<QnnTensorSpec, ByteBuffer>>()
            p0.outputs.forEach { prevSpecs[it.name] = it to outBufs[0].getValue(it.name) }
            val embN = prevSpecs["emb_out"] ?: error("part0 缺 emb_out")
            val lora = prevSpecs["lora_out"] ?: error("part0 缺 lora_out")
            var hx = prevSpecs["x_out"] ?: error("part0 缺 x_out")
            for (k in 1..3) {
                checkCancel()
                val g = parts[k]
                val ins = g.inputs.map { sp ->
                    when (sp.name) {
                        "x" -> pass(hx.first, hx.second, sp)
                        "emb" -> pass(embN.first, embN.second, sp)
                        "lora" -> pass(lora.first, lora.second, sp)
                        else -> fixed[k][sp.name]?.also { it.rewind() } ?: error("part$k 多出输入 ${sp.name}")
                    }
                }.toTypedArray()
                g.execute(ins, g.outputs.map { outBufs[k].getValue(it.name).also { b -> b.clear() } }.toTypedArray())
                if (k < 3) { val os = spec(g.outputs, "x_out"); hx = os to outBufs[k].getValue("x_out") }
            }
            val os = spec(parts[3].outputs, "out")
            val o = decf(os, outBufs[3].getValue("out").also { it.rewind() })
            AnimaMath.unpatchify(o, v, hp, wp)
            // 实时预览：x0 = x − σ·v，线性映射成低清图（不跑 VAE）
            if (PreviewHub.wanted) PreviewHub.anima?.takeIf { it.channels == 16 }?.let { m ->
                runCatching { PreviewHub.emit(m.render(LatentPreview.x0Flow(lat, v, s), h / 8, w / 8)) }
            }
            val dt = sn - s
            for (k in 0 until nl) lat[k] += dt * v[k]
            onProgress(i + 1, steps)
        }
        if (lowMem) { checkCancel(); openDecoderOnly(w, h) }
        val dg = dec!!
        val li = spec(dg.inputs, "lat")
        val ro = dg.outputs.first()
        val out = TensorCodec.alloc(ro)
        dg.execute(arrayOf(enc(li, lat)), arrayOf(out))
        val rgb = decf(ro, out.also { it.rewind() })
        val n = w * h
        fun q(f: Float) = (f.coerceIn(0f, 1f) * 255f).roundToInt()
        return IntArray(n) { p -> (0xFF shl 24) or (q(rgb[p]) shl 16) or (q(rgb[n + p]) shl 8) or q(rgb[2 * n + p]) }
    }

    private fun closeGraphs() {
        parts.forEach { runCatching { it.close() } }
        dec?.let { runCatching { it.close() } }
        parts = emptyList(); dec = null; loadedSize = null
    }

    private fun releaseLocked(keepTables: Boolean) {
        closeGraphs()
        if (!keepTables) { tok = null; qwenTable = null; t5Table = null; dir = null }
    }

    /** 释放 NPU 上的图（约 2 GB）。 */
    fun release() = synchronized(lock) { releaseLocked(keepTables = false); ldRunner.release() }

    private fun encodePng(argb: IntArray, w: Int, h: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(argb, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}
