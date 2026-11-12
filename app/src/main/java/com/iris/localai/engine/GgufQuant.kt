package com.iris.localai.engine

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * 2.3.2 Bonsai: tensor types of a GGUF read from its header, checked before a load.
 *  - Q1_0 (41) / Q2_0 (42) — PrismML Bonsai 1-bit / ternary — run on CPU or OpenCL only: the NPU
 *    paths (GenieX, built-in Hexagon runtime) have no kernels for them, so NPU is dropped from the chain.
 *  - Types our ggml does not know (≥ [TYPE_COUNT]), e.g. PrismML's private PQ2_0 = 142 / PTQ1_0 = 143
 *    (PrismML-Eng/llama.cpp `prism` branch, ggml.h), are refused with a clear message instead of
 *    letting the native loader fail or crash.
 * Pure java.io (unit-tested).
 */
object GgufQuant {
    const val Q1_0 = 41
    const val Q2_0 = 42
    const val PQ2_0 = 142
    const val PTQ1_0 = 143
    /** GGML_TYPE_COUNT of the bundled llama.cpp (785322d). */
    const val TYPE_COUNT = 44

    /**
     * 2.5.0-beta9: [arch] = general.architecture, [nextn] = `<arch>.nextn_predict_layers` (MTP / NextN
     * heads; 0 when absent). A model with nextn > 0 can use MTP speculative decoding.
     */
    data class Info(
        val types: Set<Int>, val arch: String = "", val nextn: Int = 0,
        /** 2.5.1: `<arch>.expert_count` / `<arch>.expert_used_count`（非 MoE 为 0）。 */
        val expertCount: Int = 0, val expertUsed: Int = 0,
        /** 2.5.1: 名字含 `_exps` 的张量字节数与全部张量字节数（按偏移差估算；未知为 0）。 */
        val expsBytes: Long = 0, val tensorBytes: Long = 0,
    ) {
        val mtp: Boolean get() = nextn > 0
        /** 2.5.1: MoE 模型（多于 1 个专家）。 */
        val moe: Boolean get() = expertCount > 1
        val lowBit: Boolean get() = Q1_0 in types || Q2_0 in types
        val unknown: Set<Int> get() = types.filterTo(HashSet()) { it >= TYPE_COUNT || it < 0 }
        val prism: Boolean get() = PQ2_0 in types || PTQ1_0 in types
        val label: String get() = when { PTQ1_0 in types -> "PTQ1_0"; PQ2_0 in types -> "PQ2_0"; Q1_0 in types -> "Q1_0"; Q2_0 in types -> "Q2_0"; else -> "" }
    }

    private val cache = HashMap<String, Pair<Long, Info?>>()

    /** Cached by path + size + mtime; null when the file is not a readable GGUF. */
    fun of(f: File): Info? = synchronized(cache) {
        val key = f.path; val stamp = f.length() * 31 + f.lastModified()
        cache[key]?.takeIf { it.first == stamp }?.let { return it.second }
        val info = runCatching { f.inputStream().use { scan(it, f.length()) } }.getOrNull()
        cache[key] = stamp to info
        info
    }

    /** Reads the header and the tensor infos (not the tensor data). */
    fun scan(input: InputStream, fileLength: Long = -1): Info? = CountingStream(BufferedInputStream(input, 1 shl 16)).let { counter ->
        val inp = DataInputStream(counter)
        fun u32(): Long = Integer.reverseBytes(inp.readInt()).toLong() and 0xffffffffL
        fun u64(): Long = java.lang.Long.reverseBytes(inp.readLong())
        fun skipN(n: Long) { var left = n; while (left > 0) { val s = inp.skip(left); if (s <= 0) { inp.readByte(); left-- } else left -= s } }
        fun skip(type: Long) {
            when (type.toInt()) {
                0, 1, 7 -> skipN(1); 2, 3 -> skipN(2); 4, 5, 6 -> skipN(4); 10, 11, 12 -> skipN(8)
                8 -> skipN(u64())
                9 -> { val t = u32(); val n = u64(); val w = when (t.toInt()) { 0, 1, 7 -> 1L; 2, 3 -> 2L; 4, 5, 6 -> 4L; 10, 11, 12 -> 8L; else -> 0L }
                    if (w > 0) skipN(w * n) else for (i in 0 until n) skip(t) }
                else -> throw IllegalStateException("gguf type $type")
            }
        }
        fun int(type: Long): Long = when (type.toInt()) {
            0, 1, 7 -> inp.readUnsignedByte().toLong()
            2, 3 -> (java.lang.Short.reverseBytes(inp.readShort()).toInt() and 0xffff).toLong()
            4, 5 -> u32()
            10, 11 -> u64()
            else -> { skip(type); 0L }
        }
        if (inp.readInt() != 0x47475546) return null // "GGUF"
        if (u32() < 2) return null
        val tensors = u64()
        val kvs = u64()
        var arch = ""
        val nextnByArch = HashMap<String, Int>()
        val expertsByArch = HashMap<String, Int>()      // 2.5.1
        val expertUsedByArch = HashMap<String, Int>()   // 2.5.1
        var alignment = 32L
        for (i in 0 until kvs) {
            val keyLen = u64()
            if (keyLen !in 1..256) { skipN(keyLen); skip(u32()); continue }
            val key = ByteArray(keyLen.toInt()).also { inp.readFully(it) }.toString(Charsets.UTF_8)
            val type = u32()
            when {
                key == "general.architecture" && type == 8L -> {
                    val n = u64()
                    if (n in 0..256) arch = ByteArray(n.toInt()).also { inp.readFully(it) }.toString(Charsets.UTF_8) else skipN(n)
                }
                key.endsWith(".nextn_predict_layers") && type in 0L..12L && type != 8L && type != 9L -> {
                    nextnByArch[key.removeSuffix(".nextn_predict_layers")] = int(type).coerceIn(0, 64).toInt()
                }
                // 2.5.1: MoE 专家数（用于「专家按需加载」与激活专家数）
                key.endsWith(".expert_count") && type in 0L..12L && type != 8L && type != 9L -> {
                    expertsByArch[key.removeSuffix(".expert_count")] = int(type).coerceIn(0, 4096).toInt()
                }
                key.endsWith(".expert_used_count") && type in 0L..12L && type != 8L && type != 9L -> {
                    expertUsedByArch[key.removeSuffix(".expert_used_count")] = int(type).coerceIn(0, 4096).toInt()
                }
                key == "general.alignment" && type in 0L..12L && type != 8L && type != 9L -> {
                    alignment = int(type).takeIf { it > 0 } ?: 32L
                }
                else -> skip(type)
            }
        }
        val types = HashSet<Int>()
        // 2.5.1: (偏移, 是否专家张量)，按偏移差估算每个张量大小
        val offsets = ArrayList<Pair<Long, Boolean>>()
        for (i in 0 until tensors) {
            val nameLen = u64()        // name
            val exps = if (nameLen in 1..512) ByteArray(nameLen.toInt()).also { inp.readFully(it) }.toString(Charsets.UTF_8).contains("_exps")
                else { skipN(nameLen); false }
            val dims = u32()
            skipN(8 * dims)            // shape
            types += u32().toInt()     // ggml type
            offsets += u64() to exps   // offset（相对数据区）
        }
        val dataStart = (counter.count + alignment - 1) / alignment * alignment
        val sizes = tensorSizes(offsets, if (fileLength > 0) fileLength - dataStart else -1)
        Info(
            types, arch, nextnByArch[arch] ?: 0,
            expertCount = expertsByArch[arch] ?: 0, expertUsed = expertUsedByArch[arch] ?: 0,
            expsBytes = sizes.first, tensorBytes = sizes.second,
        )
    }

    /** 2.5.1: (专家张量字节, 全部张量字节)；[dataBytes] < 0 时最后一个张量记 0。 */
    fun tensorSizes(offsets: List<Pair<Long, Boolean>>, dataBytes: Long): Pair<Long, Long> {
        val sorted = offsets.sortedBy { it.first }
        var exps = 0L; var all = 0L
        for (i in sorted.indices) {
            val end = if (i + 1 < sorted.size) sorted[i + 1].first else dataBytes
            val sz = (end - sorted[i].first).coerceAtLeast(0)
            all += sz
            if (sorted[i].second) exps += sz
        }
        return exps to all
    }

    private class CountingStream(inner: InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
    }

    /** 2.5.0-beta9: MTP draft tokens per step when MTP 加速 is on and the model has MTP layers (else 0). */
    const val MTP_DRAFT = 3
    /** 2.5.0-beta10: llama.cpp backends only — CPU, and GPU (OpenCL / Vulkan, experimental). Never the NPU. */
    fun mtpBackend(backend: Backend): Boolean = backend == Backend.CPU || backend == Backend.GPU
    fun mtpDraft(enabled: Boolean, info: Info?, backend: Backend): Int =
        if (enabled && info?.mtp == true && mtpBackend(backend)) MTP_DRAFT else 0

    /** The backend chain for a model with [info]: low-bit types never try the NPU (CPU is kept as the floor). */
    fun chainFor(chain: List<Backend>, info: Info?): List<Backend> {
        if (info?.lowBit != true) return chain
        val out = chain.filter { it != Backend.NPU }
        return if (Backend.CPU in out) out else out + Backend.CPU
    }
}
