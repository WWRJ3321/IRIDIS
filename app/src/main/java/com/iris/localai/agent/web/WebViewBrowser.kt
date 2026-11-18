package com.iris.localai.agent.web

import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.workspace.*
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * the agent's browser — one WebView kept for the app's lifetime (so cookies and logins
 * stay), created on the main thread on first use. Off screen it is laid out at a phone size; the
 * 浏览器 page shows the same WebView so the user can watch it or sign in by hand.
 * Every call blocks the calling (tool) thread, never the main thread.
 */
class WebViewBrowser(context: Context) : BrowserDriver, SearchBrowser {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: WebView? = null
    @Volatile private var loading = false
    @Volatile private var navigated = 0L

    /**
     * the `browser` tool and 浏览器搜索 share this one WebView, so every call holds this lock
     * (reentrant; a search holds it for its whole load → consent → read sequence).
     */
    private val gate = java.util.concurrent.locks.ReentrantLock(true)

    private fun <T> exclusive(block: () -> T): T {
        if (!gate.tryLock(BUSY_WAIT_S, TimeUnit.SECONDS)) throw IllegalStateException("浏览器正忙，请稍后再试")
        try { return block() } finally { gate.unlock() }
    }

    data class Status(val url: String = "", val title: String = "", val loading: Boolean = false)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The WebView, created on first use. Main thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        view?.let { return it }
        val w = WebView(ctx)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.loadWithOverviewMode = true
        w.settings.useWideViewPort = true
        w.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                loading = true; navigated = System.currentTimeMillis()
                _status.value = Status(url, v.title.orEmpty(), true)
            }
            override fun onPageFinished(v: WebView, url: String) {
                loading = false
                _status.value = Status(url, v.title.orEmpty(), false)
            }
            // navigations (links, redirects, form posts) to a local name / private IP literal
            // are dropped. Main thread: pure check only, no DNS.
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                BrowserNet.obviouslyLocal(request.url?.toString())
            // every http(s) request the page makes is checked without DNS (private literals,
            // local names); main-frame loads are also resolved and refused when the name points
            // inside. Runs on a WebView worker thread, so the lookup does not block the UI.
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url ?: return null
                val scheme = u.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null
                val refuse = BrowserNet.obviouslyLocal(u.toString()) || (request.isForMainFrame && !BrowserNet.resolvesPublic(u.host))
                return if (refuse) BrowserNet.refusal() else null
            }
        }
        layoutOffscreen(w)
        view = w
        return w
    }

    /** Gives a detached WebView a phone-sized layout so pages render and JS sees a viewport. */
    fun layoutOffscreen(w: WebView) {
        if (w.parent != null) return
        val dm = ctx.resources.displayMetrics
        val wSpec = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        w.measure(wSpec, hSpec)
        w.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun <T> onMain(timeoutMs: Long = 10_000, block: (WebView, (T) -> Unit) -> Unit): T? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "browser called on the main thread" }
        val latch = CountDownLatch(1)
        val ref = AtomicReference<T?>(null)
        main.post {
            runCatching { block(webView()) { v -> ref.set(v); latch.countDown() } }.onFailure { latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ref.get()
    }

    private fun js(script: String, timeoutMs: Long = 8_000): String? = onMain<String>(timeoutMs) { w, done ->
        w.evaluateJavascript(script) { r -> done(r ?: "null") }
    }

    /** Waits for a navigation started after [since] to finish (or a short settle when none starts). */
    private fun settle(since: Long, maxMs: Long = 20_000) {
        val end = System.currentTimeMillis() + maxMs
        pause(400)
        while (System.currentTimeMillis() < end) {
            if (!loading && (navigated < since || System.currentTimeMillis() - since > 900)) break
            pause(150)
        }
        pause(500) // let scripts render
    }

    /** Loads [url] (public hosts only) and waits for it to settle. Caller holds [gate]. */
    private fun navigate(url: String) {
        // resolved here (tool thread) so the agent gets a clear refusal (SecurityException).
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            NetGuard.resolvePublic(uri?.host ?: runCatching { java.net.URL(url).host }.getOrNull())
        }
        val t = System.currentTimeMillis()
        loading = true
        onMain<Unit> { w, done -> w.loadUrl(url); done(Unit) }
        settle(t - 1)
    }

    override fun open(url: String): PageSnapshot = exclusive {
        navigate(url)
        snapshot()
    }

    override fun snapshot(): PageSnapshot = exclusive {
        val raw = js(BrowserScripts.SNAPSHOT) ?: return@exclusive PageSnapshot("", status.value.url, listOf("(page did not answer)"))
        BrowserScripts.parseSnapshot(raw) ?: PageSnapshot("", status.value.url, listOf("(page could not be read)"))
    }

    override fun click(id: Int): PageSnapshot? = exclusive {
        val t = System.currentTimeMillis()
        val r = js(BrowserScripts.click(id)) ?: return@exclusive null
        if (!r.contains("ok")) return@exclusive null
        settle(t)
        snapshot()
    }

    override fun type(id: Int, text: String, submit: Boolean): PageSnapshot? = exclusive {
        val t = System.currentTimeMillis()
        val r = js(BrowserScripts.type(id, text, submit)) ?: return@exclusive null
        if (!r.contains("ok")) return@exclusive null
        if (submit) settle(t) else pause(300)
        snapshot()
    }

    override fun back(): PageSnapshot = exclusive {
        val t = System.currentTimeMillis()
        onMain<Unit> { w, done -> if (w.canGoBack()) w.goBack(); done(Unit) }
        settle(t)
        snapshot()
    }

    // ---- 2.0.0 浏览器搜索: same WebView, cookies and network checks as the browser tool ----

    private val searchOps = object : SearchPageOps {
        override fun load(url: String) = navigate(url)
        override fun eval(script: String): String? = js(script)
        override fun evalAndSettle(script: String): String? {
            val t = System.currentTimeMillis()
            val r = js(script)
            settle(t)
            return r
        }
        override fun pause(ms: Long) = this@WebViewBrowser.pause(ms)
    }

    /** The page is left on the results, so the 浏览器 page shows what was searched. */
    override fun <T> withSearchPage(block: (SearchPageOps) -> T): T = exclusive { block(searchOps) }

    /** 可中断的等待：工具经 runInterruptible 运行，取消时线程被中断，这里立即抛出 InterruptedException。 */
    private fun pause(ms: Long) {
        if (Thread.interrupted()) throw InterruptedException()
        Thread.sleep(ms)
    }

    private companion object { const val BUSY_WAIT_S = 90L }
}

/**
 * the in-app browser's address checks (see [WebViewBrowser]). WebView resolves names
 * itself, so unlike web_fetch this cannot pin the checked address: it keeps the agent's browser off
 * local names, private literals and names that resolve inside, not a determined DNS rebinding.
 */
object BrowserNet {
    fun obviouslyLocal(url: String?): Boolean = url != null && NetGuard.obviouslyLocal(url)

    /** True when [host] resolves to public addresses only; any failure refuses. */
    fun resolvesPublic(host: String?): Boolean = runCatching { NetGuard.resolvePublic(host); true }.getOrDefault(false)

    fun refusal(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"),
        java.io.ByteArrayInputStream(NetGuard.REFUSED.toByteArray(Charsets.UTF_8)),
    )
}

/** The page scripts and their result parsing (parsing unit-tested). */
object BrowserScripts {
    private val json = Json { ignoreUnknownKeys = true }

    val SNAPSHOT = """
(function(){
var SEL='a[href],button,input,textarea,select,[role=button],[role=link],[role=tab],[role=menuitem],[onclick],summary';
var BLOCK=/^(P|DIV|LI|TR|H[1-6]|SECTION|ARTICLE|HEADER|FOOTER|NAV|UL|OL|TABLE|FORM|MAIN|ASIDE|BR|DT|DD|BLOCKQUOTE|PRE)$/;
var out=[],cur='',n=0;
document.querySelectorAll('[data-iris]').forEach(function(e){e.removeAttribute('data-iris');});
function flush(){var t=cur.replace(/\s+/g,' ').trim();if(t)out.push(t);cur='';}
function vis(e){return !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);}
function label(e){var t=(e.innerText||'').replace(/\s+/g,' ').trim();if(!t)t=e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||e.name||'';if(!t){var i=e.querySelector&&e.querySelector('img[alt]');if(i)t=i.alt;}return t.slice(0,80);}
function desc(e){var tag=e.tagName.toLowerCase();
 if(tag=='input'){var ty=(e.type||'text').toLowerCase();if(ty=='hidden')return null;var v=(ty=='password')?(e.value?'***':''):(e.value||'');if(ty=='checkbox'||ty=='radio')v=e.checked?'checked':'unchecked';return 'input:'+ty+' "'+label(e)+'"'+(v?' = '+v.slice(0,60):'');}
 if(tag=='textarea')return 'textarea "'+label(e)+'"'+(e.value?' = '+e.value.slice(0,60):'');
 if(tag=='select'){var o=e.options[e.selectedIndex];return 'select "'+label(e).slice(0,30)+'" = '+(o?o.text:'');}
 if(tag=='a')return 'link "'+label(e)+'"';
 return 'button "'+label(e)+'"';}
function walk(node){for(var c=node.firstChild;c;c=c.nextSibling){
 if(c.nodeType==3){cur+=' '+c.textContent;continue;}
 if(c.nodeType!=1)continue;
 var tag=c.tagName;if(/^(SCRIPT|STYLE|NOSCRIPT|SVG|TEMPLATE|IFRAME|CANVAS)$/.test(tag))continue;
 if(!vis(c))continue;
 if(c.matches(SEL)){var d=desc(c);if(d){flush();n++;c.setAttribute('data-iris',n);out.push('['+n+'] '+d);}continue;}
 var b=BLOCK.test(tag);if(b)flush();walk(c);if(b)flush();}}
if(document.body)walk(document.body);flush();
return JSON.stringify({title:document.title||'',url:location.href,lines:out.slice(0,1500)});
})()
""".trimIndent()

    fun click(id: Int) = """
(function(){var e=document.querySelector('[data-iris="$id"]');if(!e)return 'missing';
e.scrollIntoView({block:'center'});if(e.tagName=='A'&&e.target)e.removeAttribute('target');e.click();return 'ok';})()
""".trimIndent()

    fun type(id: Int, text: String, submit: Boolean): String {
        val lit = kotlinx.serialization.json.JsonPrimitive(text).toString()
        return """
(function(){var e=document.querySelector('[data-iris="$id"]');if(!e)return 'missing';
e.focus();var t=$lit;var p=Object.getPrototypeOf(e);var d=Object.getOwnPropertyDescriptor(p,'value');
if(d&&d.set)d.set.call(e,t);else e.value=t;
e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
if($submit){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keyup',o));
if(e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}}
return 'ok';})()
""".trimIndent()
    }

    /** evaluateJavascript hands back a JSON string literal holding our JSON object. */
    fun parseSnapshot(raw: String): PageSnapshot? = runCatching {
        val inner = json.parseToJsonElement(raw).jsonPrimitive.content
        val o = json.parseToJsonElement(inner).jsonObject
        PageSnapshot(
            o["title"]?.jsonPrimitive?.content.orEmpty(),
            o["url"]?.jsonPrimitive?.content.orEmpty(),
            o["lines"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }.getOrNull()
}
