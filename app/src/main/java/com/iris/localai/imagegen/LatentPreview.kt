package com.iris.localai.imagegen

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 一帧预览：ARGB 像素。 */
class PreviewFrame(val width: Int, val height: Int, val argb: IntArray)

/**
 * 潜变量 → 低清 RGB 的线性映射（不跑 VAE，每步几毫秒）。
 * 每个潜变量位置变成 [block]×[block] 个像素：rgb = Σ_c z[c]·W[c][k] + bias[k]（k 遍历 3·block² 个输出），
 * 输出 −1..1；再做 [blur] 次 [1 2 1] 模糊盖住块状。
 * 文件格式与系数取自 hexanima Preview.java / assets/preview_*.bin（MIT，见 third_party/NOTICE）：
 * int32 C、B、模糊次数，fp32 W[C][3·B·B]，fp32 bias[3·B·B]，小端。
 */
class LatentRgbMap(val channels: Int, val block: Int, val blur: Int, private val w: FloatArray, private val bias: FloatArray) {
    init {
        require(channels > 0 && block > 0 && w.size == channels * 3 * block * block && bias.size == 3 * block * block) { "预览映射尺寸不符" }
    }

    /** [z] = (C, h, w) 平面排布；返回 (w·B)×(h·B) 的 ARGB。 */
    fun render(z: FloatArray, h: Int, wd: Int): PreviewFrame {
        val k = 3 * block * block
        val n = h * wd
        require(z.size >= channels * n) { "潜变量尺寸不符" }
        val w2 = wd * block; val h2 = h * block
        val px = IntArray(w2 * h2)
        val acc = FloatArray(k)
        for (t in 0 until n) {
            System.arraycopy(bias, 0, acc, 0, k)
            for (c in 0 until channels) {
                val v = z[c * n + t]
                val o = c * k
                for (j in 0 until k) acc[j] += v * w[o + j]
            }
            val ty = t / wd; val tx = t % wd
            for (py in 0 until block) for (pxx in 0 until block) {
                val j = (py * block + pxx) * 3
                px[(ty * block + py) * w2 + tx * block + pxx] = (0xFF shl 24) or (q(acc[j]) shl 16) or (q(acc[j + 1]) shl 8) or q(acc[j + 2])
            }
        }
        repeat(blur) { blur121(px, w2, h2) }
        return PreviewFrame(w2, h2, px)
    }

    companion object {
        fun q(v: Float): Int = Math.round((v + 1f) * 127.5f).coerceIn(0, 255)

        /** hexanima 的 preview_*.bin。 */
        fun parse(bytes: ByteArray): LatentRgbMap {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val c = bb.int; val b = bb.int; val passes = bb.int
            require(c in 1..256 && b in 1..8 && passes in 0..8) { "预览映射头不对" }
            val k = 3 * b * b
            require(bytes.size >= 12 + 4 * (c + 1) * k) { "预览映射文件太短" }
            val w = FloatArray(c * k) { bb.float }
            val bias = FloatArray(k) { bb.float }
            return LatentRgbMap(c, b, passes, w, bias)
        }

        /** 每通道 3 个系数 + 偏置（block = 1）。 */
        fun simple(proj: Array<FloatArray>, bias: FloatArray): LatentRgbMap =
            LatentRgbMap(proj.size, 1, 0, FloatArray(proj.size * 3) { proj[it / 3][it % 3] }, bias.copyOf())

        /**
         * SD1.x / SD2 的 4 通道系数（stable-diffusion.cpp latent-preview.h 的 sd_latent_rgb_proj，MIT），
         * 作用在缩放后的潜变量（×0.18215 的那一侧）。
         */
        val SD1: LatentRgbMap by lazy {
            simple(
                arrayOf(
                    floatArrayOf(0.337366f, 0.216344f, 0.257386f),
                    floatArrayOf(0.165636f, 0.386828f, 0.046994f),
                    floatArrayOf(-0.267803f, 0.237036f, 0.223517f),
                    floatArrayOf(-0.178022f, -0.200862f, -0.678514f),
                ),
                floatArrayOf(-0.017478f, -0.055834f, -0.105825f),
            )
        }

        private fun blur121(px: IntArray, w: Int, h: Int) {
            val tmp = IntArray(px.size)
            for (y in 0 until h) for (x in 0 until w) {
                tmp[y * w + x] = mix(px[y * w + maxOf(x - 1, 0)], px[y * w + x], px[y * w + minOf(x + 1, w - 1)])
            }
            for (y in 0 until h) for (x in 0 until w) {
                px[y * w + x] = mix(tmp[maxOf(y - 1, 0) * w + x], tmp[y * w + x], tmp[minOf(y + 1, h - 1) * w + x])
            }
        }

        private fun mix(a: Int, b: Int, c: Int): Int {
            fun ch(s: Int) = ((((a shr s) and 255) + 2 * ((b shr s) and 255) + ((c shr s) and 255) + 2) shr 2)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** 预览的纯计算（单测）。 */
object LatentPreview {
    /** 流匹配（Anima）：x0 = x − σ·v。 */
    fun x0Flow(lat: FloatArray, v: FloatArray, sigma: Float): FloatArray = FloatArray(lat.size) { lat[it] - sigma * v[it] }

    /** ε 预测（SD1.5，k-diffusion 尺度）：x0 = x − σ·ε。 */
    fun x0Eps(x: FloatArray, eps: FloatArray, sigma: Float): FloatArray = FloatArray(x.size) { x[it] - sigma * eps[it] }

    /** NHWC → (C,H,W)。 */
    fun nhwcToChw(src: FloatArray, h: Int, w: Int, c: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) for (k in 0 until c) out[(k * h + y) * w + x] = src[(y * w + x) * c + k]
        return out
    }

    /** 长边缩到不超过 [max]（最近邻），IPC 一帧不超过约 256 KB。 */
    fun fit(f: PreviewFrame, max: Int = MAX_SIDE): PreviewFrame {
        val long = maxOf(f.width, f.height)
        if (long <= max) return f
        val s = long.toDouble() / max
        val w = maxOf(1, (f.width / s).toInt()); val h = maxOf(1, (f.height / s).toInt())
        val out = IntArray(w * h) { i ->
            val x = minOf(f.width - 1, ((i % w) * s).toInt()); val y = minOf(f.height - 1, ((i / w) * s).toInt())
            f.argb[y * f.width + x]
        }
        return PreviewFrame(w, h, out)
    }

    /** sd.cpp 回来的 RGB 字节 → ARGB。 */
    fun fromRgb(rgb: ByteArray, w: Int, h: Int): PreviewFrame? {
        if (w <= 0 || h <= 0 || rgb.size < w * h * 3) return null
        return PreviewFrame(w, h, IntArray(w * h) { p ->
            (0xFF shl 24) or ((rgb[p * 3].toInt() and 255) shl 16) or ((rgb[p * 3 + 1].toInt() and 255) shl 8) or (rgb[p * 3 + 2].toInt() and 255)
        })
    }

    const val MAX_SIDE = 256
}

/**
 * 进程内的预览出口：生成前由 [ImageGenService]（或本进程）设置，引擎每步调用 [emit]。
 * Anima 的映射由 [ImageGenEngines] 从 assets/imagegen/preview_anima.bin 读进来。
 */
object PreviewHub {
    @Volatile var sink: ((PreviewFrame) -> Unit)? = null
    @Volatile var anima: LatentRgbMap? = null

    val wanted: Boolean get() = sink != null

    fun emit(f: PreviewFrame?) {
        val s = sink ?: return
        if (f == null) return
        runCatching { s(LatentPreview.fit(f)) }
    }
}
