package com.iris.localai.util.io

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.iris.localai.data.Attachment
import com.iris.localai.data.AttachmentKind
import java.io.File
import java.util.UUID

/**
 * files and pictures sent with a message. Each is copied into app storage
 * (filesDir/attachments) so the conversation keeps working when the original moves. Text files
 * are read once and their text rides along in the prompt; pictures go to the model only when it
 * can see (ModelDetails.supportsVision).
 */
object Attachments {
    const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    /** Characters of a text file kept for the prompt. */
    const val MAX_TEXT_CHARS = 32_000
    const val MAX_PER_MESSAGE = 20

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
        "log", "html", "htm", "css", "js", "ts", "kt", "kts", "java", "py", "c", "h", "cpp", "hpp", "cc", "go", "rs",
        "swift", "sh", "bat", "sql", "gradle", "properties", "srt", "vtt", "tex", "rtf", "env", "gitignore",
    )
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String, mime: String?): AttachmentKind {
        val m = mime.orEmpty().lowercase()
        val e = ext(name)
        return when {
            m.startsWith("image/") || e in IMAGE_EXT -> AttachmentKind.IMAGE
            m.startsWith("text/") || e in TEXT_EXT || m in setOf("application/json", "application/xml", "application/x-yaml", "application/javascript") -> AttachmentKind.TEXT
            else -> AttachmentKind.FILE
        }
    }

    fun looksBinary(bytes: ByteArray): Boolean = bytes.take(4096).any { it == 0.toByte() }

    /** Text of a file for the prompt, capped, or null when it is not readable text. */
    fun textOf(bytes: ByteArray): String? {
        if (looksBinary(bytes)) return null
        val s = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        // Too many replacement chars = not UTF-8 text (GBK etc. are rare in files people attach here).
        if (s.isNotEmpty() && s.count { it == '\uFFFD' } > s.length / 20) return null
        return if (s.length <= MAX_TEXT_CHARS) s else s.substring(0, MAX_TEXT_CHARS)
    }

    /** What the model reads for a message's attachments (appended to the user's words). */
    fun promptBlock(list: List<Attachment>, vision: Boolean = false): String {
        if (list.isEmpty()) return ""
        return list.joinToString("\n\n", prefix = "\n\n") { a ->
            when {
                a.kind == AttachmentKind.TEXT && a.text != null -> buildString {
                    append("<attachment name=\"").append(a.name).append("\">\n").append(a.text.trimEnd())
                    if (a.truncated) append("\n…[文件较长，只附上前 ${a.text.length} 字]")
                    append("\n</attachment>")
                }
                a.kind == AttachmentKind.IMAGE -> if (vision) "[图片：${a.name}]" else "[用户附上了图片 ${a.name}，但当前模型看不到图片内容]"
                else -> "[用户附上了文件 ${a.name}（${sizeLabel(a.sizeBytes)}），这种格式无法读取内容]"
            }
        }
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    fun dir(context: Context): File = File(context.filesDir, "attachments").apply { mkdirs() }

    /** Copies [uri] into app storage. Throws IllegalArgumentException with a Chinese reason. */
    fun import(context: Context, uri: Uri): Attachment {
        val cr = context.contentResolver
        var name = "附件"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
            }
        }
        val mime = cr.getType(uri)
        val kind = kindOf(name, mime)
        val limit = if (kind == AttachmentKind.IMAGE) MAX_IMAGE_BYTES else MAX_FILE_BYTES
        if (size > limit) throw IllegalArgumentException("「$name」太大（${sizeLabel(size)}），上限 ${sizeLabel(limit)}")
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").take(80).ifBlank { "file" }
        val out = File(dir(context), UUID.randomUUID().toString().take(8) + "-" + safe)
        var copied = 0L
        cr.openInputStream(uri)?.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > limit) { o.close(); out.delete(); throw IllegalArgumentException("「$name」太大，上限 ${sizeLabel(limit)}") }
                    o.write(buf, 0, n)
                }
            }
        } ?: throw IllegalArgumentException("无法打开「$name」")
        var text: String? = null
        var truncated = false
        var finalKind = kind
        if (kind != AttachmentKind.IMAGE) {
            val bytes = out.readBytes()
            text = textOf(bytes)
            if (text != null) { finalKind = AttachmentKind.TEXT; truncated = text.length < bytes.toString(Charsets.UTF_8).length }
            else if (kind == AttachmentKind.TEXT) finalKind = AttachmentKind.FILE
        }
        return Attachment(name = name, path = out.path, mime = mime, sizeBytes = copied, kind = finalKind, text = text, truncated = truncated)
    }

    fun delete(list: List<Attachment>) {
        list.forEach { a ->
            runCatching {
                val f = File(a.path)
                f.delete()
                // Every scaled copy (one per long-side limit, see visionPathFor).
                f.parentFile?.listFiles { c -> isVisionCopyOf(f.name, c.name) }?.forEach { it.delete() }
            }
        }
    }

    /**
     * Longest side of a picture handed to a vision model when nothing else asks. 2.0.2–2.1.6: 1024;
     * 512 (「识图清晰度 · 标准」) — 1024 px kept vision prefill busy for tens of seconds on a phone.
     */
    const val VISION_MAX_SIDE = 512

    /** 2.0.2's 1024 px copy keeps its old file name (`.vis.jpg`); every other size is `.vis<N>.jpg`. */
    const val LEGACY_VISION_SIDE = 1024

    /**
     * MNN (Qwen2/2.5/3/3.5-VL) feeds the picture at its own size to a CPU/GPU ViT: 1024 px is
     * ~3000 patches and kept 「预填充」 busy for minutes on a phone. 512 px ≈ 200–350 vision tokens.
     */
    const val MNN_VISION_MAX_SIDE = 512

    /** Scaled copy of [path] for a long side of [max] px (2.0.2's name kept for 1024). */
    fun visionPathFor(path: String, max: Int = VISION_MAX_SIDE): String =
        if (max == LEGACY_VISION_SIDE) "$path.vis.jpg" else "$path.vis$max.jpg"

    /** True when [candidate] is a scaled copy of the attachment file named [original]. Pure. */
    fun isVisionCopyOf(original: String, candidate: String): Boolean {
        if (!candidate.startsWith("$original.vis") || !candidate.endsWith(".jpg")) return false
        val mid = candidate.substring(original.length + 4, candidate.length - 4)
        return mid.all { it.isDigit() }
    }

    /** Target size so the longest side is at most [max]; null when no scaling is needed. */
    fun visionTarget(w: Int, h: Int, max: Int = VISION_MAX_SIDE): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val long = maxOf(w, h)
        if (long <= max) return null
        val k = max.toDouble() / long
        return Pair(maxOf(1, Math.round(w * k).toInt()), maxOf(1, Math.round(h * k).toInt()))
    }

    /** Largest power-of-two decode subsample that still leaves at least [tw]×[th]. Pure. */
    fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        return sample
    }

    /**
     * files every engine decodes as they are: baseline/progressive JPEG and PNG. MNN's imread
     * (stb_image) cannot open WebP / HEIC / HEIF, so such a small picture used to reach it unconverted
     * and was dropped. Pure (first bytes of the file).
     */
    fun decodableEverywhere(head: ByteArray): Boolean {
        fun b(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        val jpeg = b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF
        val png = b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47
        return jpeg || png
    }

    /**
     * a full-size photo or screenshot made the vision encoder (CPU on MNN) cut it into
     * thousands of patches, so prefill sat for minutes. The model now gets a copy whose longest
     * side is [max], EXIF rotation applied, cached next to the original. 2.1.1: also re-encoded when
     * the original is not JPEG/PNG (WebP, HEIC…), transparency flattened onto white. Falls back to
     * the original on any failure.
     */
    fun visionCopy(path: String, max: Int = VISION_MAX_SIDE): String = runCatching {
        val src = File(path)
        if (!src.isFile) return path
        val cached = File(visionPathFor(path, max))
        if (cached.isFile && cached.length() > 0) return cached.path
        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, b)
        val rot = runCatching {
            when (android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90; 3 -> 180; 8 -> 270; else -> 0
            }
        }.getOrDefault(0)
        val head = ByteArray(8).also { buf -> src.inputStream().use { it.read(buf) } }
        val target = visionTarget(b.outWidth, b.outHeight, max)
        if (target == null && rot == 0 && decodableEverywhere(head)) return path
        val sample = if (target == null) 1 else sampleSize(b.outWidth, b.outHeight, target.first, target.second)
        val bmp = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return path
        val (tw, th) = target ?: Pair(bmp.width, bmp.height)
        val m = android.graphics.Matrix().apply {
            postScale(tw.toFloat() / bmp.width, th.toFloat() / bmp.height)
            if (rot != 0) postRotate(rot.toFloat())
        }
        val scaled = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        // JPEG has no alpha: transparent PNG / WebP pixels would turn black.
        val out = if (!scaled.hasAlpha()) scaled else android.graphics.Bitmap.createBitmap(scaled.width, scaled.height, android.graphics.Bitmap.Config.ARGB_8888).also { flat ->
            android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
        }
        val tmp = File(cached.path + ".tmp")
        tmp.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        listOf(out, scaled, bmp).distinct().forEach { it.recycle() }
        if (!tmp.renameTo(cached)) { tmp.delete(); return path }
        cached.path
    }.getOrDefault(path)
}
