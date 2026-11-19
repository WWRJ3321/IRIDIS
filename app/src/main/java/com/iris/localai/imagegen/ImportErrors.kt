package com.iris.localai.imagegen

import com.iris.localai.I18n
import com.iris.localai.R

/** 导入失败时给用户看的话：压缩包 / 文件不完整（下载没完成、损坏）说清楚怎么办，其余照原文。纯判断部分单测。 */
object ImportErrors {
    private val BROKEN = Regex(
        "invalid (block type|distance|stored block|literal|code|bit length|entry)|unexpected end|end of (zlib|stream|file)|" +
            "truncated|corrupt|crc|incorrect header check|too many length|invalid loc header|invalid cen header|zip end header",
        RegexOption.IGNORE_CASE,
    )

    /** 压缩包损坏 / 没下载完整（沿 cause 链看）。 */
    fun isBroken(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is java.util.zip.ZipException || t is java.io.EOFException || t is java.util.zip.DataFormatException) return true
            if (t.message?.let { BROKEN.containsMatchIn(it) } == true) return true
            t = t.cause
        }
        return false
    }

    fun message(e: Throwable): String = if (isBroken(e)) I18n.s(R.string.ig27e_zip_bad) else e.message ?: e.toString()
}
