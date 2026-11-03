package com.iris.localai.ui.markdown

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.FileProvider
import com.iris.localai.IrisApp
import com.iris.localai.agent.SafeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2.2: a minimal image loader for chat (the app has no Coil): bytes from https (through
 * [SafeHttpClient]: public hosts only, no cookies, size-capped), the agent workspace, content:// or
 * data:, decoded with inSampleSize so a photo never becomes a full-size bitmap. Decoded bitmaps sit
 * in a small LRU shared by the whole chat; raw bytes are fetched again only to save / share.
 */
object ChatImageLoader {
    const val MAX_BYTES = 12 * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val bitmaps = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun key(src: ImageSource, maxSide: Int) = "$maxSide|" + when (src) {
        is ImageSource.Data -> "data:" + src.base64.hashCode() + ":" + src.base64.length
        else -> src.toString()
    }

    /** Decoded bitmap with its longer side ≤ about [maxSide] px; null = could not be read or decoded. */
    suspend fun load(context: Context, src: ImageSource, maxSide: Int): Bitmap? {
        val k = key(src, maxSide)
        bitmaps.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = bytes(context, src) ?: return@withContext null
            decode(bytes, maxSide)?.also { bitmaps.put(k, it) }
        }
    }

    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = ImageRefs.sampleSize(o.outWidth, o.outHeight, maxSide) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    /** The original bytes (blocking; call off the main thread). */
    fun bytes(context: Context, src: ImageSource): ByteArray? = runCatching {
        when (src) {
            is ImageSource.Remote -> fetchRemote(src.url)?.first
            is ImageSource.Data -> android.util.Base64.decode(src.base64.filterNot { it.isWhitespace() }, android.util.Base64.DEFAULT)
            is ImageSource.Content -> context.contentResolver.openInputStream(android.net.Uri.parse(src.uri))?.use { readCapped(it) }
            is ImageSource.LocalFile -> localBytes(context, src.path)
            is ImageSource.Blocked -> null
        }
    }.getOrNull()

    /** https image through the guarded client: (bytes, mime) or null. Also used by the HTML preview. */
    fun fetchRemote(url: String): Pair<ByteArray, String>? = runCatching {
        val r = SafeHttpClient().get(url, mapOf("User-Agent" to UA, "Accept" to "image/avif,image/webp,image/*;q=0.8"), MAX_BYTES)
        if (r.code !in 200..299 || r.body.isEmpty()) return null
        val type = r.header("content-type")?.substringBefore(';')?.trim()?.lowercase()
        val mime = ImageRefs.mimeOf(r.body) ?: type?.takeIf { it.startsWith("image/") } ?: return null
        r.body to mime
    }.getOrNull()

    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Workspace (current location or the private one) and the app's attachments only; SAF grant as fallback. */
    private fun localBytes(context: Context, path: String): ByteArray? {
        val app = context.applicationContext as? IrisApp ?: return null
        val agent = app.container.agent
        val ws = agent.workspace.value.root
        val extra = listOf(agent.privateRoot, File(context.filesDir, "attachments"))
        ImageRefs.resolveLocal(path, ws, extra)?.let { f ->
            if (f.length() > MAX_BYTES) return null
            return f.readBytes()
        }
        val rel = ImageRefs.workspaceRelative(path)?.takeIf { it.isNotEmpty() && !it.split('/').contains("..") } ?: return null
        return agent.externalFiles()?.read(rel, MAX_BYTES.toLong())
    }

    /** MediaStore Pictures/IRIDIS (no storage permission on Android 10+). True = saved. */
    fun saveToGallery(context: Context, bytes: ByteArray): Boolean = runCatching {
        val mime = ImageRefs.mimeOf(bytes) ?: "image/jpeg"
        val name = "IRIDIS_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + "." + ImageRefs.extensionOf(mime)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IRIDIS")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = runCatching { resolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
        if (!ok) { resolver.delete(uri, null, null); return false }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    }.getOrDefault(false)

    /** Share sheet with a copy in cache/shared (served by the app's FileProvider). */
    fun share(context: Context, bytes: ByteArray): Boolean = runCatching {
        val mime = ImageRefs.mimeOf(bytes) ?: "image/jpeg"
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "image." + ImageRefs.extensionOf(mime)).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, com.iris.localai.I18n.s(com.iris.localai.R.string.markdown_106d52)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
