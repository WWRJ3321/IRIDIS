package com.iris.localai.knowledge

import java.io.File
import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 2.7.2：知识库文本文件的编码识别与流式切片（纯 JVM，单测）。
 * 编码：BOM（UTF-8 / UTF-16LE / UTF-16BE）→ 无 BOM 的 UTF-16（隔字节为 0）→ 严格 UTF-8 → GB18030（含 GBK / GB2312），
 * 若 GB18030 读出来常用简体字比例很低而 Big5 能严格解码，则按 Big5。
 * 大文件：按段读、按段切片，整本书不必先变成一个大字符串；最多收录 [MAX_CHARS] 字，超出的部分标记为截断。
 */
object KbText {
    /** 单个 txt / md 等文本文件的上限（与 EPUB 相同）。 */
    const val MAX_FILE_BYTES = 32_000_000L
    /** 一个文件最多收录这么多字（之后的部分不进索引，界面提示「只收录前 N 万字」）。 */
    const val MAX_CHARS = 8_000_000
    /** 流式切片时一段的字符数（在段落 / 换行处断开）。 */
    const val SEGMENT = 128 * 1024
    private const val SNIFF = 64 * 1024

    enum class Enc(val charset: String, val bomLen: Int = 0) {
        UTF8("UTF-8"), UTF8_BOM("UTF-8", 3), UTF16LE("UTF-16LE", 2), UTF16BE("UTF-16BE", 2),
        UTF16LE_NOBOM("UTF-16LE"), UTF16BE_NOBOM("UTF-16BE"), GB18030("GB18030"), BIG5("Big5"),
    }

    fun bom(b: ByteArray, n: Int = b.size): Enc? = when {
        n >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Enc.UTF8_BOM
        n >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Enc.UTF16LE
        n >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Enc.UTF16BE
        else -> null
    }

    /** 没有 BOM 的 UTF-16：偶数 / 奇数位上大量 0。null = 不像。 */
    fun utf16NoBom(b: ByteArray, n: Int = b.size): Enc? {
        val m = minOf(n, 4096) and 1.inv()
        if (m < 8) return null
        var evenZero = 0; var oddZero = 0
        for (i in 0 until m step 2) { if (b[i] == 0.toByte()) evenZero++; if (b[i + 1] == 0.toByte()) oddZero++ }
        val half = m / 2
        return when {
            oddZero > half * 0.3 && evenZero < half * 0.05 -> Enc.UTF16LE_NOBOM
            evenZero > half * 0.3 && oddZero < half * 0.05 -> Enc.UTF16BE_NOBOM
            else -> null
        }
    }

    /** 流式的严格 UTF-8 校验（不分配字符）。 */
    class Utf8Check {
        private var need = 0
        private var min = 0
        private var cp = 0
        var ok = true
            private set

        fun feed(b: ByteArray, off: Int = 0, len: Int = b.size) {
            if (!ok) return
            for (i in off until off + len) {
                val x = b[i].toInt() and 0xFF
                if (need == 0) {
                    when {
                        x < 0x80 -> {}
                        x in 0xC2..0xDF -> { need = 1; cp = x and 0x1F; min = 0x80 }
                        x in 0xE0..0xEF -> { need = 2; cp = x and 0x0F; min = 0x800 }
                        x in 0xF0..0xF4 -> { need = 3; cp = x and 0x07; min = 0x10000 }
                        else -> { ok = false; return }
                    }
                } else {
                    if (x and 0xC0 != 0x80) { ok = false; return }
                    cp = (cp shl 6) or (x and 0x3F)
                    if (--need == 0 && (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF)) { ok = false; return }
                }
            }
        }

        /** 文件结束：中间断开的多字节字符也算不合法。 */
        fun finish(): Boolean { if (need != 0) ok = false; return ok }
    }

    fun isUtf8(b: ByteArray, n: Int = b.size): Boolean = Utf8Check().apply { feed(b, 0, n) }.finish()

    private fun isCjk(c: Char) = c in '\u4e00'..'\u9fff'

    /** [s] 里的汉字有多少在 GB2312（常用简体）里。没有汉字 = 1。 */
    fun gb2312Share(s: String): Double {
        val enc = runCatching { Charset.forName("GB2312").newEncoder() }.getOrNull() ?: return 1.0
        var all = 0; var ok = 0
        for (c in s) if (isCjk(c)) { all++; if (enc.canEncode(c)) ok++ }
        return if (all == 0) 1.0 else ok.toDouble() / all
    }

    /** 严格解码（不合法字节 = null）；[tail] = 末尾允许丢掉的字节数（采样截断在多字节字符中间时）。 */
    fun strict(b: ByteArray, n: Int, charset: String, tail: Int = 0): String? {
        val cs = runCatching { Charset.forName(charset) }.getOrNull() ?: return null
        for (cut in 0..tail.coerceAtMost(n)) {
            val dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val r = runCatching { dec.decode(ByteBuffer.wrap(b, 0, n - cut)).toString() }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    /**
     * 编码判断。[head] = 文件开头（≤ 64 KB 左右），[utf8Valid] = 整个文件严格 UTF-8 是否成立（小文件可直接用 [isUtf8]）。
     * [truncated] = [head] 只是文件的一部分（末尾可能断在多字节字符中间）。
     */
    fun detect(head: ByteArray, n: Int, utf8Valid: Boolean, truncated: Boolean = false): Enc {
        bom(head, n)?.let { return it }
        utf16NoBom(head, n)?.let { return it }
        if (utf8Valid) return Enc.UTF8
        // 开头是合法的 UTF-8（含非 ASCII），只是后面有坏字节：仍按 UTF-8（坏字节变替换字符）
        val headCheck = Utf8Check().apply { feed(head, 0, n) }
        if ((if (truncated) headCheck.ok else headCheck.finish()) && (0 until n).any { head[it] < 0 }) return Enc.UTF8
        val tail = if (truncated) 2 else 0
        val gb = strict(head, n, "GB18030", tail)
        val gbShare = gb?.let { gb2312Share(it) } ?: 0.0
        if (gb != null && gbShare >= 0.8) return Enc.GB18030
        val big5 = strict(head, n, "Big5", tail)
        if (big5 != null && (gb == null || big5Share(big5) > gbShare)) return Enc.BIG5
        return Enc.GB18030
    }

    /** Big5 读出来的汉字里，常用字（GB2312 或其繁体对应常见于 Big5 常用区）的比例：Big5 常用字区 A440–C67E。 */
    private fun big5Share(s: String): Double {
        val enc = runCatching { Charset.forName("Big5").newEncoder() }.getOrNull() ?: return 0.0
        var all = 0; var ok = 0
        for (c in s) if (isCjk(c)) {
            all++
            val b = runCatching { enc.encode(java.nio.CharBuffer.wrap(charArrayOf(c))) }.getOrNull() ?: continue
            if (b.remaining() == 2) {
                val code = ((b.get(0).toInt() and 0xFF) shl 8) or (b.get(1).toInt() and 0xFF)
                if (code in 0xA440..0xC67E) ok++
            }
        }
        return if (all == 0) 0.0 else ok.toDouble() / all
    }

    private fun charsetOf(e: Enc): Charset = runCatching { Charset.forName(e.charset) }.getOrDefault(Charsets.UTF_8)

    /** 太多替换字符 / 二进制。 */
    fun looksBinary(s: String): Boolean = s.isNotEmpty() && (s.count { it == '\uFFFD' } > s.length / 20 || s.take(4096).count { it == '\u0000' } > 0)

    /** 整块字节 → 文本（小文件 / 网页 / SAF）；null = 不是文本。 */
    fun decode(bytes: ByteArray): String? {
        val n = bytes.size
        val enc = detect(bytes, minOf(n, SNIFF * 4), isUtf8(bytes), truncated = n > SNIFF * 4)
        if (enc.bomLen == 0 && enc != Enc.UTF16LE_NOBOM && enc != Enc.UTF16BE_NOBOM && bytes.take(4096).any { it == 0.toByte() }) return null
        val s = String(bytes, enc.bomLen, n - enc.bomLen, charsetOf(enc)).removePrefix("\uFEFF")
        return if (looksBinary(s)) null else s
    }

    data class Chunked(val chunks: List<String>, val chars: Long, val truncated: Boolean, val encoding: Enc)

    /**
     * 大文件：两遍读 [file]——先流式校验 UTF-8 并取开头判断编码，再按 [SEGMENT] 字一段解码、切片（[KbChunker.chunk]）。
     * 内存只有一段文字 + 结果切片。超过 [maxChars] 字的部分不收录（[Chunked.truncated]）。null = 不是文本。
     */
    fun chunkFile(file: File, maxChars: Int = MAX_CHARS): Chunked? {
        val head = ByteArray(SNIFF)
        var headN = 0
        val check = Utf8Check()
        file.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val k = input.read(buf); if (k < 0) break
                if (headN < SNIFF) { val c = minOf(k, SNIFF - headN); System.arraycopy(buf, 0, head, headN, c); headN += c }
                check.feed(buf, 0, k)
                if (!check.ok && headN >= SNIFF) break
            }
        }
        val enc = detect(head, headN, check.finish(), truncated = file.length() > headN)
        if (enc.bomLen == 0 && enc != Enc.UTF16LE_NOBOM && enc != Enc.UTF16BE_NOBOM && (0 until minOf(headN, 4096)).any { head[it] == 0.toByte() }) return null
        return file.inputStream().use { s ->
            var skip = enc.bomLen.toLong(); while (skip > 0) { val k = s.skip(skip); if (k <= 0) break; skip -= k }
            chunkReader(s.reader(charsetOf(enc)).buffered(1 shl 16), maxChars, enc)
        }
    }

    /** 同上，已打开的流（不能回读，所以只按开头判断编码）。 */
    fun chunkStream(input: InputStream, maxChars: Int = MAX_CHARS): Chunked? {
        val b = input.buffered(1 shl 16)
        b.mark(SNIFF + 16)
        val head = ByteArray(SNIFF)
        var n = 0
        while (n < SNIFF) { val k = b.read(head, n, SNIFF - n); if (k < 0) break; n += k }
        val more = b.read() >= 0
        b.reset()
        val check = Utf8Check().apply { feed(head, 0, n) }
        val enc = detect(head, n, if (more) check.ok else check.finish(), truncated = more)
        if (enc.bomLen == 0 && enc != Enc.UTF16LE_NOBOM && enc != Enc.UTF16BE_NOBOM && (0 until minOf(n, 4096)).any { head[it] == 0.toByte() }) return null
        repeat(enc.bomLen) { b.read() }
        return chunkReader(b.reader(charsetOf(enc)), maxChars, enc)
    }

    /** 按段读 [r]、在换行处断段、每段切片；段间不重叠（段很长，影响可忽略）。 */
    fun chunkReader(r: Reader, maxChars: Int, enc: Enc = Enc.UTF8): Chunked? {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        val buf = CharArray(16 * 1024)
        var total = 0L
        var truncated = false
        var bad = 0L
        fun flush(all: Boolean) {
            if (sb.isEmpty()) return
            var cut = sb.length
            if (!all) {
                val nl = sb.lastIndexOf("\n", sb.length - 1)
                cut = if (nl >= SEGMENT / 2) nl + 1 else sb.length
            }
            out += KbChunker.chunk(sb.substring(0, cut))
            sb.delete(0, cut)
        }
        while (true) {
            val k = r.read(buf); if (k < 0) break
            var take = k
            if (total + k > maxChars) { take = (maxChars - total).toInt().coerceAtLeast(0); truncated = true }
            for (i in 0 until take) if (buf[i] == '\uFFFD') bad++
            sb.append(buf, 0, take); total += take
            if (sb.length >= SEGMENT) flush(false)
            if (truncated) { break }
        }
        flush(true)
        if (total > 0 && bad > total / 20) return null
        return Chunked(out.filter { it.isNotEmpty() }, total, truncated, enc)
    }
}
