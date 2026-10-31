package com.iris.localai.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** 2.2: the two switches the rich views follow (外观 › 聊天内容). */
internal data class RichSettings(val renderHtml: Boolean = true, val loadNetworkImages: Boolean = true)

/** Read only by messages that actually contain an image or an HTML block, so plain replies pay nothing. */
@Composable
internal fun rememberRichSettings(): RichSettings {
    val app = LocalContext.current.applicationContext as? IrisApp ?: return RichSettings()
    val s by app.container.settings.settings.collectAsStateWithLifecycle()
    return RichSettings(s.renderHtml, s.loadNetworkImages)
}

private object RichText {
    const val PREVIEW = "网页预览"
    const val OPEN_IMAGE = "查看大图"
    const val SVG = "SVG 预览"
    const val SOURCE = "源码"
    const val SHOW_PREVIEW = "预览"
    const val FULLSCREEN = "全屏"
    const val COPY = "复制"
    const val COPIED = "已复制"
    const val CLOSE = "关闭"
    const val SAVE = "保存到相册"
    const val SHARE = "分享"
    const val SAVED = "已保存到相册"
    const val SAVE_FAILED = "保存失败"
    const val SHARE_FAILED = "无法分享"
    const val LOAD_FAILED = "图片加载失败"
    const val CRASHED = "预览已停止，点「全屏」重新打开"
}

private val PREVIEW_MAX = 360.dp

// ------------------------------------------------------------------------------------------ HTML

/** A closed ```html / ```svg block: preview card when 渲染网页代码 is on, the plain code block otherwise. */
@Composable
internal fun HtmlCodeBlock(source: String, kind: HtmlBlocks.Kind, fallback: @Composable () -> Unit) {
    val rich = rememberRichSettings()
    if (!rich.renderHtml) { fallback(); return }
    HtmlCard(source, kind, rich.loadNetworkImages)
}

@Composable
private fun HtmlCard(source: String, kind: HtmlBlocks.Kind, allowImages: Boolean) {
    val c = Iris.colors
    val clipboard = LocalClipboardManager.current
    var showSource by remember(source) { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val doc = remember(source, kind, allowImages) { HtmlSandbox.document(source, kind, allowImages) }
    val shape = IrisRadius.mediumShape
    Column(Modifier.fillMaxWidth().clip(shape).background(c.codeBackground).border(IrisDimens.hairline, c.outline, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = IrisDimens.space12, end = IrisDimens.space4, top = IrisDimens.space2, bottom = IrisDimens.space2), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (kind == HtmlBlocks.Kind.SVG) IrisIcons.Image else IrisIcons.Globe, null, tint = c.textTertiary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(IrisDimens.space6))
            Text(if (kind == HtmlBlocks.Kind.SVG) RichText.SVG else RichText.PREVIEW, color = c.textTertiary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            CardAction(IrisIcons.Code, if (showSource) RichText.SHOW_PREVIEW else RichText.SOURCE) { showSource = !showSource }
            CardAction(IrisIcons.Expand, RichText.FULLSCREEN) { fullscreen = true }
            CardAction(IrisIcons.Copy, if (copied) RichText.COPIED else RichText.COPY) {
                clipboard.setText(AnnotatedString(source)); copied = true
            }
        }
        if (showSource) SourceText(source, Modifier.heightIn(max = PREVIEW_MAX))
        else WebPreview(doc, allowImages)
    }
    if (fullscreen) HtmlFullscreen(doc, source, kind, allowImages) { fullscreen = false }
}

@Composable
private fun CardAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Iris.colors
    Row(
        Modifier.clip(IrisRadius.smallShape).clickable(onClick = onClick).padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(IrisDimens.space4))
        Text(label, color = c.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun SourceText(source: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = IrisDimens.space12),
    ) {
        Text(source, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = Iris.colors.text, softWrap = false)
    }
}

/**
 * The in-chat preview. The WebView exists only while the card is on screen (its clipped bounds
 * are non-empty): scrolled away it is destroyed, and its last height keeps the layout steady.
 */
@Composable
private fun WebPreview(doc: String, allowImages: Boolean) {
    val c = Iris.colors
    var visible by remember { mutableStateOf(false) }
    var crashed by remember(doc) { mutableStateOf(false) }
    var contentHeight by remember(doc) { mutableIntStateOf(0) }
    val h: Dp = if (contentHeight > 0) contentHeight.dp.coerceIn(48.dp, PREVIEW_MAX) else 160.dp
    Box(
        Modifier.fillMaxWidth().height(h).background(c.surfaceVariant)
            .onGloballyPositioned { visible = it.boundsInWindow().height > 0f },
        contentAlignment = Alignment.Center,
    ) {
        when {
            crashed -> Text(RichText.CRASHED, color = c.textTertiary, fontSize = 12.sp)
            visible -> key(doc) {
                AndroidView(
                    factory = { ctx ->
                        sandboxWebView(ctx, allowImages, onHeight = { contentHeight = it }, onGone = { crashed = true })
                            .also { it.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null) }
                    },
                    onRelease = { destroyWebView(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> Icon(IrisIcons.Globe, null, tint = c.textTertiary, modifier = Modifier.size(IrisDimens.space20))
        }
    }
}

@Composable
private fun HtmlFullscreen(doc: String, source: String, kind: HtmlBlocks.Kind, allowImages: Boolean, onDismiss: () -> Unit) {
    val c = Iris.colors
    var showSource by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(IrisIcons.Close, RichText.CLOSE, tint = c.text, onClick = onDismiss)
                Text(if (kind == HtmlBlocks.Kind.SVG) RichText.SVG else RichText.PREVIEW, color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                CardAction(IrisIcons.Code, if (showSource) RichText.SHOW_PREVIEW else RichText.SOURCE) { showSource = !showSource }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(c.surfaceVariant)) {
                if (showSource) SourceText(source, Modifier.fillMaxSize().padding(top = IrisDimens.space8))
                else key(doc) {
                    AndroidView(
                        factory = { ctx -> sandboxWebView(ctx, allowImages, onHeight = null, onGone = onDismiss).also { it.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null) } },
                        onRelease = { destroyWebView(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/**
 * A WebView with JavaScript but nothing else: no file / content access, no JS bridge, no storage,
 * no popups, no geolocation. Every request is checked ([HtmlSandbox.allowRequest]); allowed https
 * images are fetched by the app ([ChatImageLoader.fetchRemote]: public hosts, no cookies). A
 * navigation never happens inside: a tapped http(s) link opens in the external browser.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun sandboxWebView(ctx: Context, allowImages: Boolean, onHeight: ((Int) -> Unit)?, onGone: () -> Unit): WebView = WebView(ctx).apply {
    settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setGeolocationEnabled(false)
        blockNetworkImage = !allowImages
        mediaPlaybackRequiresUserGesture = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        builtInZoomControls = false
    }
    runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, false) }
    isVerticalScrollBarEnabled = false
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val image = HtmlSandbox.looksLikeImage(url, request.requestHeaders["Accept"])
            if (!HtmlSandbox.allowRequest(url, allowImages, image)) return blocked()
            if (url.startsWith("https://", ignoreCase = true)) {
                val (bytes, mime) = ChatImageLoader.fetchRemote(url) ?: return blocked()
                return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
            }
            return null // data: / blob:
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (request.hasGesture() && HtmlSandbox.externalLink(url)) {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val report = onHeight ?: return
            val measure = Runnable {
                view.evaluateJavascript("Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0)") { v ->
                    v?.trim('"')?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }?.let(report)
                }
            }
            measure.run()
            view.postDelayed(measure, 600) // after images / fonts settled
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            onGone()
            return true // never take the app down with the preview
        }
    }
}

private fun destroyWebView(web: WebView) {
    runCatching {
        web.stopLoading()
        web.handler?.removeCallbacksAndMessages(null)
        web.destroy()
    }
}

// ---------------------------------------------------------------------------------------- images

private sealed interface ImageState {
    data object Loading : ImageState
    data object Failed : ImageState
    data class Loaded(val bitmap: Bitmap) : ImageState
}

/** `![alt](url)`: placeholder → image (tap: full screen) or a small error line. */
@Composable
internal fun MarkdownImage(block: MdBlock.Image) {
    val c = Iris.colors
    val rich = rememberRichSettings()
    val src = remember(block.url, rich.loadNetworkImages) { ImageRefs.classify(block.url, rich.loadNetworkImages) }
    if (src is ImageSource.Blocked) { ImageNote(block.alt, src.reason); return }
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val maxSide = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }.coerceIn(480, 1440)
    val state by produceState<ImageState>(ImageState.Loading, src, maxSide) {
        value = ChatImageLoader.load(ctx, src, maxSide)?.let { ImageState.Loaded(it) } ?: ImageState.Failed
    }
    var viewer by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space4)) {
        when (val s = state) {
            ImageState.Loading -> Box(
                Modifier.fillMaxWidth().height(180.dp).clip(IrisRadius.mediumShape).background(c.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(IrisIcons.Image, null, tint = c.textTertiary, modifier = Modifier.size(IrisDimens.space24)) }
            ImageState.Failed -> ImageNote(block.alt, RichText.LOAD_FAILED)
            is ImageState.Loaded -> {
                val img = remember(s.bitmap) { s.bitmap.asImageBitmap() }
                val w = with(density) { s.bitmap.width.toDp() }
                Image(
                    img, block.alt.ifBlank { null },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = w).heightIn(max = 420.dp).clip(IrisRadius.mediumShape).clickable { viewer = true },
                )
                if (block.alt.isNotBlank()) Text(block.alt, color = c.textTertiary, fontSize = 12.sp)
            }
        }
    }
    if (viewer) ImageViewer(src, block.alt) { viewer = false }
}

/** 2.1.7: a small square thumbnail of a workspace picture (agent step cards); tap opens the full-screen viewer. */
@Composable
internal fun ImageThumbnail(path: String, size: Dp = 72.dp) {
    val c = Iris.colors
    val ctx = LocalContext.current
    val src = remember(path) { ImageRefs.classify(path, allowNetwork = false) }
    if (src is ImageSource.Blocked) { ImageNote("", src.reason); return }
    val px = with(LocalDensity.current) { size.roundToPx() * 2 }
    val bitmap by produceState<Bitmap?>(null, src, px) { value = ChatImageLoader.load(ctx, src, px) }
    var viewer by remember { mutableStateOf(false) }
    Box(
        Modifier.size(size).clip(IrisRadius.smallShape).background(c.surface).clickable(onClickLabel = RichText.OPEN_IMAGE) { viewer = true },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(img, path.substringAfterLast('/'), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } ?: Icon(IrisIcons.Image, null, tint = c.textTertiary, modifier = Modifier.size(IrisDimens.space20))
    }
    if (viewer) ImageViewer(src, path.substringAfterLast('/')) { viewer = false }
}

@Composable
private fun ImageNote(alt: String, reason: String) {
    val c = Iris.colors
    Row(
        Modifier.clip(IrisRadius.smallShape).background(c.surfaceVariant).padding(horizontal = 10.dp, vertical = IrisDimens.space6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IrisIcons.Image, null, tint = c.textTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(IrisDimens.space6))
        Text(if (alt.isBlank()) reason else "$alt · $reason", color = c.textTertiary, fontSize = 12.sp, maxLines = 2)
    }
}

/** Full screen: pinch / double-tap zoom, pan; 保存到相册 (MediaStore) and 分享. */
@Composable
private fun ImageViewer(src: ImageSource, alt: String, onDismiss: () -> Unit) {
    val c = Iris.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<Bitmap?>(null, src) { value = ChatImageLoader.load(ctx, src, 2048) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun withBytes(block: (ByteArray) -> Boolean, failed: String, done: String?) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { ChatImageLoader.bytes(ctx, src) }
            val ok = bytes != null && withContext(Dispatchers.IO) { runCatching { block(bytes) }.getOrDefault(false) }
            val msg = if (ok) done else failed
            if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            bitmap?.let { b ->
                val img = remember(b) { b.asImageBitmap() }
                Image(
                    img, alt.ifBlank { null }, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale <= 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                )
            } ?: Icon(IrisIcons.Image, null, tint = c.textTertiary, modifier = Modifier.align(Alignment.Center).size(IrisDimens.space32))
            Row(
                Modifier.fillMaxWidth().systemBarsPadding().background(c.background.copy(alpha = 0.7f)).padding(horizontal = IrisDimens.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PressableIcon(IrisIcons.Close, RichText.CLOSE, tint = c.text, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                CardAction(IrisIcons.Download, RichText.SAVE) { withBytes({ ChatImageLoader.saveToGallery(ctx, it) }, RichText.SAVE_FAILED, RichText.SAVED) }
                CardAction(IrisIcons.Share, RichText.SHARE) {
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { ChatImageLoader.bytes(ctx, src) }
                        if (bytes == null || !ChatImageLoader.share(ctx, bytes)) Toast.makeText(ctx, RichText.SHARE_FAILED, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
