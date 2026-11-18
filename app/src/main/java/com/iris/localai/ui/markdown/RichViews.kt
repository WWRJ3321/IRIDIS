package com.iris.localai.ui.markdown

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.chat.ImageEditor
import com.iris.localai.ui.chat.ToolButton
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisFontSize
import com.iris.localai.ui.theme.IrisSpace
import com.iris.localai.ui.motion.enterOnce
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
import androidx.compose.ui.layout.onSizeChanged
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

/** the two switches the rich views follow (外观 › 聊天内容). */
internal data class RichSettings(val renderHtml: Boolean = true, val loadNetworkImages: Boolean = true)

/** Read only by messages that actually contain an image or an HTML block, so plain replies pay nothing. */
@Composable
internal fun rememberRichSettings(): RichSettings {
    val app = LocalContext.current.applicationContext as? IrisApp ?: return RichSettings()
    val s by app.container.settings.settings.collectAsStateWithLifecycle()
    return RichSettings(s.renderHtml, s.loadNetworkImages)
}

internal object RichText {
    val PREVIEW: String get() = I18n.s(R.string.markdown_7056bd)
    val OPEN_IMAGE: String get() = I18n.s(R.string.markdown_3bc999)
    val SVG: String get() = I18n.s(R.string.markdown_e077ad)
    val SOURCE: String get() = I18n.s(R.string.markdown_5b804b)
    val SHOW_PREVIEW: String get() = I18n.s(R.string.files_645dbc)
    val FULLSCREEN: String get() = I18n.s(R.string.markdown_185926)
    val COPY: String get() = I18n.s(R.string.ui_79d3ab)
    val COPIED: String get() = I18n.s(R.string.chat_52e6ab)
    val CLOSE: String get() = I18n.s(R.string.ui_b15d91)
    val SAVE: String get() = I18n.s(R.string.markdown_a3c86e)
    val EDIT: String get() = I18n.s(R.string.img262_edit)
    val SHARE: String get() = I18n.s(R.string.files_c31f48)
    val SAVED: String get() = I18n.s(R.string.markdown_c1a317)
    val SAVE_FAILED: String get() = I18n.s(R.string.imagegen_6de920)
    val SHARE_FAILED: String get() = I18n.s(R.string.markdown_c0fda5)
    val LOAD_FAILED: String get() = I18n.s(R.string.markdown_b3b83e)
    val CRASHED: String get() = I18n.s(R.string.markdown_bbf012)
}

private val PREVIEW_MAX = 360.dp
/** a page that fills its viewport (3D / canvas / height:100%) gets this fixed card height instead of a measured one. */
private val PREVIEW_FILL = 300.dp

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
            Text(if (kind == HtmlBlocks.Kind.SVG) RichText.SVG else RichText.PREVIEW, color = c.textTertiary, fontSize = IrisFontSize.XS, modifier = Modifier.weight(1f))
            CardAction(IrisIcons.Code, if (showSource) RichText.SHOW_PREVIEW else RichText.SOURCE) { showSource = !showSource }
            CardAction(IrisIcons.Expand, RichText.FULLSCREEN) { fullscreen = true }
            CardAction(IrisIcons.Copy, if (copied) RichText.COPIED else RichText.COPY) {
                clipboard.setText(AnnotatedString(source)); copied = true
            }
        }
        if (showSource) SourceText(source, Modifier.heightIn(max = PREVIEW_MAX))
        else WebPreview(doc, allowImages, fills = remember(source, kind) { kind == HtmlBlocks.Kind.HTML && HtmlSandbox.fillsViewport(source) })
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
        Text(label, color = c.textSecondary, fontSize = IrisFontSize.XS)
    }
}

@Composable
private fun SourceText(source: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
            .padding(start = IrisSpace.M, end = IrisSpace.M, bottom = IrisDimens.space12),
    ) {
        Text(source, fontFamily = FontFamily.Monospace, fontSize = IrisFontSize.XS, lineHeight = 20.sp, color = Iris.colors.text, softWrap = false)
    }
}

/**
 * The in-chat preview. The WebView exists only while the card is on screen (its clipped bounds
 * are non-empty): scrolled away it is destroyed, and its last height keeps the layout steady.
 */
@Composable
private fun WebPreview(doc: String, allowImages: Boolean, fills: Boolean = false) {
    val c = Iris.colors
    var visible by remember { mutableStateOf(false) }
    var crashed by remember(doc) { mutableStateOf(false) }
    var contentHeight by remember(doc) { mutableIntStateOf(0) }
    // a full-viewport page (Three.js canvas sized to innerHeight, html/body height:100%) has no
    // content height to measure; measuring it made the card track its own viewport. Fixed height instead.
    val h: Dp = when {
        fills -> PREVIEW_FILL
        contentHeight > 0 -> contentHeight.dp.coerceIn(48.dp, PREVIEW_MAX)
        else -> 160.dp
    }
    Box(
        Modifier.fillMaxWidth().height(h).background(c.surfaceVariant)
            .onGloballyPositioned { visible = it.boundsInWindow().height > 0f },
        contentAlignment = Alignment.Center,
    ) {
        when {
            crashed -> Text(RichText.CRASHED, color = c.textTertiary, fontSize = IrisFontSize.XS)
            visible -> key(doc) {
                AndroidView(
                    factory = { ctx ->
                        sandboxWebView(ctx, allowImages, onHeight = if (fills) null else ({ contentHeight = it }), onGone = { crashed = true })
                            .also { it.loadWhenSized(doc) }
                    },
                    onRelease = { destroyWebView(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> Icon(IrisIcons.Globe, null, tint = c.textTertiary, modifier = Modifier.size(IrisDimens.space20))
        }
    }
}

/**
 * 网页预览 Three.js 空白: the document used to be loaded in the AndroidView factory, before the
 * WebView had a size, so scripts read innerWidth / innerHeight = 0 and sized their canvas 0×0 (inline
 * style beats the page's CSS). Load only once the view has a real size; on later size changes (card height,
 * rotation) also fire `resize` so pages that listen re-fit.
 */
private fun WebView.loadWhenSized(doc: String) {
    var loaded = false
    addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or0, ob ->
        val w = r - l; val h = b - t
        if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
        val web = v as WebView
        if (!loaded) {
            loaded = true
            web.post { web.loadDataWithBaseURL(ThreeAssets.BASE, doc, "text/html", "utf-8", null) }
        } else if (w != or0 - ol || h != ob - ot) {
            web.postDelayed({ web.evaluateJavascript(HtmlSandbox.RESIZE_JS, null) }, 120)
        }
    }
}

@Composable
private fun HtmlFullscreen(doc: String, source: String, kind: HtmlBlocks.Kind, allowImages: Boolean, onDismiss: () -> Unit) {
    val c = Iris.colors
    var showSource by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // the activity handles rotation itself, so the dialog window kept its first size (wider /
        // taller than the screen after a turn). Match the window to the screen on every configuration.
        val cfg = LocalConfiguration.current
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        LaunchedEffect(cfg.screenWidthDp, cfg.screenHeightDp, cfg.orientation) {
            dialogWindow?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Column(Modifier.fillMaxSize().enterOnce().background(c.background).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(IrisIcons.Close, RichText.CLOSE, tint = c.text, onClick = onDismiss)
                Text(if (kind == HtmlBlocks.Kind.SVG) RichText.SVG else RichText.PREVIEW, color = c.text, fontSize = IrisFontSize.M, modifier = Modifier.weight(1f))
                CardAction(IrisIcons.Code, if (showSource) RichText.SHOW_PREVIEW else RichText.SOURCE) { showSource = !showSource }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(c.surfaceVariant)) {
                if (showSource) SourceText(source, Modifier.fillMaxSize().padding(top = IrisDimens.space8))
                else key(doc) {
                    AndroidView(
                        factory = { ctx -> sandboxWebView(ctx, allowImages, onHeight = null, onGone = onDismiss).also { it.loadWhenSized(doc) } },
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
            // bundled Three.js for any CDN `three` URL (any version), with CORS for module scripts.
            ThreeAssets.assetFor(url)?.let { asset ->
                return runCatching {
                    WebResourceResponse(ThreeAssets.MIME, "utf-8", 200, "OK", ThreeAssets.HEADERS, ThreeAssets.open(asset) { view.context.assets.open(it) })
                }.getOrElse { blocked() }
            }
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
                if (block.alt.isNotBlank()) Text(block.alt, color = c.textTertiary, fontSize = IrisFontSize.XS)
            }
        }
    }
    if (viewer) ImageViewer(src, block.alt) { viewer = false }
}

/** a small square thumbnail of a workspace picture (agent step cards); tap opens the full-screen viewer. */
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
        Modifier.clip(IrisRadius.smallShape).background(c.surfaceVariant).padding(horizontal = IrisSpace.S, vertical = IrisDimens.space6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IrisIcons.Image, null, tint = c.textTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(IrisDimens.space6))
        Text(if (alt.isBlank()) reason else "$alt · $reason", color = c.textTertiary, fontSize = IrisFontSize.XS, maxLines = 2)
    }
}

/** Full screen: pinch / double-tap zoom, pan; 保存到相册 (MediaStore) and 分享. */
@Composable
private fun ImageViewer(src: ImageSource, alt: String, onDismiss: () -> Unit) = ChatImageViewer(listOf(src to alt), 0, onDismiss)

/**
 * Issue #13: the full-screen viewer for pictures in a conversation (sent photos, markdown
 * images, step pictures). Pinch or double-tap to zoom, drag to pan (kept inside the picture), left /
 * right through the pictures of one message like the 生成历史 viewer; 保存到相册 and 分享.
 */
@Composable
internal fun ChatImageViewer(
    items: List<Pair<ImageSource, String>>,
    start: Int,
    onDismiss: () -> Unit,
    /** given (composer attachments) = 编辑 in the toolbar; the edited picture replaces item [index]. */
    onEdit: ((index: Int, edited: Bitmap) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    val (src, alt) = items.getOrNull(index) ?: run { onDismiss(); return }
    val bitmap by produceState<Bitmap?>(null, src) { value = ChatImageLoader.load(ctx, src, 2048) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // immersive — black, bars float over the picture; one tap hides / shows them.
    var chrome by remember { mutableStateOf(true) }
    val chromeA by androidx.compose.animation.core.animateFloatAsState(if (chrome) 1f else 0f, IrisMotion.FadeIn.float(), label = "viewerChrome")
    var editing by remember { mutableStateOf<Bitmap?>(null) }
    val white = androidx.compose.ui.graphics.Color.White
    val black = androidx.compose.ui.graphics.Color.Black
    fun withBytes(block: (ByteArray) -> Boolean, failed: String, done: String?) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { ChatImageLoader.bytes(ctx, src) }
            val ok = bytes != null && withContext(Dispatchers.IO) { runCatching { block(bytes) }.getOrDefault(false) }
            val msg = if (ok) done else failed
            if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        editing?.let { full ->
            ImageEditor(full, onCancel = { editing = null }) { out ->
                editing = null
                onEdit?.invoke(index, out)
            }
            return@Dialog
        }
        Box(Modifier.fillMaxSize().enterOnce().background(black).onSizeChanged { box = it }) {
            bitmap?.let { b ->
                val img = remember(b) { b.asImageBitmap() }
                Image(
                    img, alt.ifBlank { null }, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(index) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = ZoomMath.scale(scale, zoom)
                                offset = ZoomMath.clamp(offset + pan, scale, box.width.toFloat(), box.height.toFloat())
                            }
                        }
                        .pointerInput(index) {
                            detectTapGestures(
                                onTap = { chrome = !chrome },
                                onDoubleTap = { at ->
                                    if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                                    else {
                                        scale = ZoomMath.DOUBLE_TAP
                                        // zoom towards the tapped point
                                        val center = Offset(box.width / 2f, box.height / 2f)
                                        offset = ZoomMath.clamp((center - at) * (ZoomMath.DOUBLE_TAP - 1f), scale, box.width.toFloat(), box.height.toFloat())
                                    }
                                },
                            )
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                )
            } ?: Icon(IrisIcons.Image, null, tint = white.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center).size(IrisDimens.space32))
            if (items.size > 1 && scale <= 1f && chromeA > 0f) {
                if (index > 0) Box(Modifier.align(Alignment.CenterStart).padding(IrisDimens.space8).graphicsLayer { alpha = chromeA }) {
                    PressableIcon(IrisIcons.ChevronLeft, RichText.OPEN_IMAGE, tint = white, background = black.copy(alpha = 0.35f), enabled = chrome) { index-- }
                }
                if (index < items.size - 1) Box(Modifier.align(Alignment.CenterEnd).padding(IrisDimens.space8).graphicsLayer { alpha = chromeA }) {
                    PressableIcon(IrisIcons.ChevronRight, RichText.OPEN_IMAGE, tint = white, background = black.copy(alpha = 0.35f), enabled = chrome) { index++ }
                }
            }
            if (chromeA > 0f) {
                // Top: close + counter over a soft fade.
                Row(
                    Modifier.fillMaxWidth().graphicsLayer { alpha = chromeA }
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(black.copy(alpha = 0.6f), androidx.compose.ui.graphics.Color.Transparent)))
                        .systemBarsPadding().padding(horizontal = IrisDimens.space4, vertical = IrisDimens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PressableIcon(IrisIcons.Close, RichText.CLOSE, tint = white, enabled = chrome, onClick = onDismiss)
                    Spacer(Modifier.weight(1f))
                    if (items.size > 1) Text("${index + 1} / ${items.size}", color = white.copy(alpha = 0.85f), fontSize = IrisFontSize.S, modifier = Modifier.padding(end = IrisDimens.space16))
                }
                // Bottom toolbar: 编辑 · 保存 · 分享.
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { alpha = chromeA }
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, black.copy(alpha = 0.6f))))
                        .systemBarsPadding().padding(vertical = IrisDimens.space8),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    if (onEdit != null) ToolButton(IrisIcons.Edit, RichText.EDIT, false, enabled = chrome && bitmap != null) {
                        scope.launch {
                            val full = withContext(Dispatchers.IO) { ChatImageLoader.load(ctx, src, 4096) }
                            if (full != null) editing = full else Toast.makeText(ctx, RichText.LOAD_FAILED, Toast.LENGTH_SHORT).show()
                        }
                    }
                    ToolButton(IrisIcons.Download, RichText.SAVE, false, enabled = chrome) { withBytes({ ChatImageLoader.saveToGallery(ctx, it) }, RichText.SAVE_FAILED, RichText.SAVED) }
                    ToolButton(IrisIcons.Share, RichText.SHARE, false, enabled = chrome) {
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) { ChatImageLoader.bytes(ctx, src) }
                            if (bytes == null || !ChatImageLoader.share(ctx, bytes)) Toast.makeText(ctx, RichText.SHARE_FAILED, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

/** zoom / pan limits of [ChatImageViewer]. Pure. */
object ZoomMath {
    const val MIN = 1f
    const val MAX = 6f
    const val DOUBLE_TAP = 2.5f
    fun scale(current: Float, zoom: Float): Float = (current * zoom).coerceIn(MIN, MAX)

    /** Keeps the scaled picture covering the box: at most (scale − 1) · size / 2 each way; zero at 1×. */
    fun clamp(offset: Offset, scale: Float, width: Float, height: Float): Offset =
        if (scale <= 1f) Offset.Zero else Offset(axis(offset.x, scale, width), axis(offset.y, scale, height))

    /** One axis of [clamp]. */
    fun axis(v: Float, scale: Float, size: Float): Float {
        if (scale <= 1f) return 0f
        val m = (scale - 1f) * size / 2f
        return v.coerceIn(-m, m)
    }
}
