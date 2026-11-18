package com.iris.localai.agent

import com.iris.localai.agent.tools.FetchResult
import com.iris.localai.agent.tools.HtmlText
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.tools.WebFetchTool
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.data.AppSettings
import com.iris.localai.data.ChatImage
import com.iris.localai.ui.markdown.HtmlBlocks
import com.iris.localai.ui.markdown.HtmlSandbox
import com.iris.localai.ui.markdown.ImageRefs
import com.iris.localai.ui.markdown.ImageSource
import com.iris.localai.ui.markdown.IncrementalMarkdown
import com.iris.localai.ui.markdown.MarkdownParser
import com.iris.localai.ui.markdown.MdBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2.2 step 2: rich rendering in chat — HTML preview cards, markdown images, web_fetch images. */
class V220RenderTest {

    private fun code(md: String) = MarkdownParser.parse(md).filterIsInstance<MdBlock.Code>().single()

    // ------------------------------------------------------------------ html block detection

    @Test fun closedHtmlAndSvgFencesArePreviewed() {
        val html = code("看这里：\n\n```html\n<h1>你好</h1>\n<script>document.title='x'</script>\n```\n\n完")
        assertTrue(html.closed)
        assertEquals(HtmlBlocks.Kind.HTML, HtmlBlocks.kindOf(html))
        assertEquals(HtmlBlocks.Kind.HTML, HtmlBlocks.kindOf(code("```HTML\n<p>a</p>\n```")))
        assertEquals(HtmlBlocks.Kind.SVG, HtmlBlocks.kindOf(code("```svg\n<svg viewBox=\"0 0 10 10\"><circle r=\"4\"/></svg>\n```")))
        assertEquals(HtmlBlocks.Kind.SVG, HtmlBlocks.kindOf(code("```xml\n<svg></svg>\n```")))
        // other languages and empty blocks stay code
        assertNull(HtmlBlocks.kindOf(code("```kotlin\nval a = \"<html>\"\n```")))
        assertNull(HtmlBlocks.kindOf(code("```xml\n<root/>\n```")))
        assertNull(HtmlBlocks.kindOf(code("```html\n\n```")))
    }

    @Test fun streamingIncompleteFenceIsNotPreviewed() {
        val partial = "做好了：\n\n```html\n<div>\n  <p>还在写"
        val open = code(partial)
        assertFalse(open.closed)
        assertNull(HtmlBlocks.kindOf(open))
        // the incremental (streaming) parser agrees, and flips once the closing fence arrives
        val inc = IncrementalMarkdown()
        assertNull(inc.parse(partial).filterIsInstance<MdBlock.Code>().single().let(HtmlBlocks::kindOf))
        val done = inc.parse("$partial</p>\n</div>\n```\n\n说明")
        assertEquals(HtmlBlocks.Kind.HTML, HtmlBlocks.kindOf(done.filterIsInstance<MdBlock.Code>().single()))
        assertEquals(MdBlock.Paragraph("说明"), done.last())
    }

    @Test fun nestedBackticksNeedALongerOuterFence() {
        // a ```` fence keeps ``` lines inside (CommonMark: the closing fence is at least as long)
        val md = "````html\n<pre>\n```\ninner\n```\n</pre>\n````\nafter"
        val b = MarkdownParser.parse(md)
        val c = b[0] as MdBlock.Code
        assertTrue(c.closed)
        assertEquals("<pre>\n```\ninner\n```\n</pre>", c.code)
        assertEquals(HtmlBlocks.Kind.HTML, HtmlBlocks.kindOf(c))
        assertEquals(MdBlock.Paragraph("after"), b[1])
        // inline backticks and a closing fence with text after it do not close the block
        val c2 = code("```html\n<code>`x`</code>\n``` not a close\n<p>y</p>\n```")
        assertTrue(c2.closed); assertTrue(c2.code.contains("<p>y</p>"))
        // streaming: the inner ``` does not close the outer fence early
        val partial = IncrementalMarkdown().parse("````html\n<pre>\n```\n\nstill inside")
        assertFalse((partial.single() as MdBlock.Code).closed)
        // plain ``` blocks behave as before
        assertEquals(MdBlock.Code("py", "print(1)", closed = true), code("```py\nprint(1)\n```"))
        assertEquals(MdBlock.Code("py", "print(1)", closed = true), code("```py\nprint(1)\n`````"))
    }

    @Test fun wholeReplyHtmlDocument() {
        assertTrue(HtmlBlocks.wholeDocument("<!DOCTYPE html>\n<html><body><h1>Hi</h1></body></html>"))
        assertTrue(HtmlBlocks.wholeDocument("  <html lang=\"zh\"><head></head><body>x</body></html>\n"))
        assertFalse(HtmlBlocks.wholeDocument("这是网页：<html><body>x</body></html>"))
        assertFalse(HtmlBlocks.wholeDocument("<!DOCTYPE html><html><body>写到一半"))
        assertFalse(HtmlBlocks.wholeDocument("```html\n<html></html>\n```"))
    }

    // ------------------------------------------------------------------ sandbox

    @Test fun previewDocumentCarriesTheCsp() {
        val full = HtmlSandbox.document("<!DOCTYPE html><html><head><title>t</title></head><body>b</body></html>", HtmlBlocks.Kind.HTML, allowImages = true)
        val head = full.indexOf("<head>")
        assertTrue(full.indexOf("Content-Security-Policy") in head..full.indexOf("<title>"))
        assertTrue(full.contains("connect-src 'none'")); assertTrue(full.contains("img-src data: blob: https:"))
        val noImg = HtmlSandbox.document("<p>x</p>", HtmlBlocks.Kind.HTML, allowImages = false)
        assertTrue(noImg.startsWith("<!DOCTYPE html><html><head>")); assertTrue(noImg.contains("<body><p>x</p></body>"))
        assertTrue(noImg.contains("img-src data: blob:;")); assertFalse(noImg.contains("https:"))
        val htmlOnly = HtmlSandbox.document("<html><body>x</body></html>", HtmlBlocks.Kind.HTML, true)
        assertTrue(htmlOnly.startsWith("<html><head><meta charset"))
        val svg = HtmlSandbox.document("<svg></svg>", HtmlBlocks.Kind.SVG, true)
        assertTrue(svg.contains("<body><svg></svg></body>")); assertTrue(svg.contains("Content-Security-Policy"))
        listOf("script-src 'unsafe-inline'", "form-action 'none'", "frame-src 'none'", "default-src 'none'").forEach { assertTrue(it, HtmlSandbox.csp(true).contains(it)) }
    }

    @Test fun previewRequestsAreBlockedExceptImages() {
        assertTrue(HtmlSandbox.allowRequest("data:image/png;base64,AAAA", false, true))
        assertTrue(HtmlSandbox.allowRequest("blob:null/1234", false, false))
        assertTrue(HtmlSandbox.allowRequest("https://example.com/a.png", allowImages = true, isImage = true))
        assertFalse(HtmlSandbox.allowRequest("https://example.com/a.png", allowImages = false, isImage = true))
        assertFalse(HtmlSandbox.allowRequest("https://cdn.example.com/lib.js", allowImages = true, isImage = false))
        assertFalse(HtmlSandbox.allowRequest("http://example.com/a.png", true, true))
        assertFalse(HtmlSandbox.allowRequest("https://127.0.0.1/a.png", true, true))
        assertFalse(HtmlSandbox.allowRequest("https://192.168.1.2/a.png", true, true))
        assertFalse(HtmlSandbox.allowRequest("file:///sdcard/a.png", true, true))
        assertFalse(HtmlSandbox.allowRequest("content://x/y", true, true))
        assertTrue(HtmlSandbox.looksLikeImage("https://x/y", "image/avif,image/webp,*/*"))
        assertTrue(HtmlSandbox.looksLikeImage("https://x/pic.JPG?w=2", "*/*"))
        assertFalse(HtmlSandbox.looksLikeImage("https://x/app.js", "*/*"))
        assertFalse(HtmlSandbox.looksLikeImage("https://x/page", "text/html,application/xhtml+xml,image/webp"))
        assertTrue(HtmlSandbox.externalLink("https://a.b")); assertTrue(HtmlSandbox.externalLink("mailto:a@b.c"))
        assertFalse(HtmlSandbox.externalLink("javascript:alert(1)")); assertFalse(HtmlSandbox.externalLink("intent://x"))
        assertFalse(HtmlSandbox.externalLink("file:///data/data/x"))
    }

    // ------------------------------------------------------------------ images

    @Test fun markdownImagesBecomeBlocks() {
        assertEquals(listOf(MdBlock.Image("猫", "https://a.com/cat.png")), MarkdownParser.parse("![猫](https://a.com/cat.png)"))
        val b = MarkdownParser.parse("看图 ![图一](images/a.png \"标题\") 和 ![](<data:image/png;base64,iVBORw0K>) 结束")
        assertEquals(
            listOf(MdBlock.Paragraph("看图"), MdBlock.Image("图一", "images/a.png"), MdBlock.Paragraph("和"), MdBlock.Image("", "data:image/png;base64,iVBORw0K"), MdBlock.Paragraph("结束")),
            b,
        )
        // a link is still a link; an unfinished image (streaming) stays text
        assertEquals(listOf(MdBlock.Paragraph("[a](https://x)")), MarkdownParser.parse("[a](https://x)"))
        assertEquals(listOf(MdBlock.Paragraph("![猫](https://a.com/ca")), MarkdownParser.parse("![猫](https://a.com/ca"))
        // the generate_image hook writes markdown the parser reads back
        val img = ChatImage.fromWorkspace("/out/my cat (1).png", "生成的[猫]")
        assertEquals(MdBlock.Image("生成的 猫", "out/my%20cat%20(1%29.png"), MarkdownParser.parse(img.markdown()).single())
        assertEquals(ImageSource.LocalFile("out/my cat (1).png"), ImageRefs.classify("out/my%20cat%20(1%29.png", true))
    }

    @Test fun imageUrlClassificationAndAllowRules() {
        assertEquals(ImageSource.Remote("https://a.com/x.png"), ImageRefs.classify(" https://a.com/x.png ", allowNetwork = true))
        assertEquals(ImageSource.Blocked(ImageRefs.NETWORK_OFF), ImageRefs.classify("https://a.com/x.png", allowNetwork = false))
        assertEquals(ImageSource.Blocked(ImageRefs.HTTP_ONLY), ImageRefs.classify("http://a.com/x.png", true))
        assertEquals(ImageSource.Blocked(ImageRefs.LOCAL_ADDRESS), ImageRefs.classify("https://localhost/x.png", true))
        assertEquals(ImageSource.Blocked(ImageRefs.LOCAL_ADDRESS), ImageRefs.classify("https://10.0.0.1/x.png", true))
        assertEquals(ImageSource.Data("image/png", "iVBORw0K"), ImageRefs.classify("data:image/png;base64,iVBORw0K", false))
        assertTrue(ImageRefs.classify("data:image/svg+xml;base64,PHN2Zz4=", true) is ImageSource.Blocked)
        assertTrue(ImageRefs.classify("data:text/html;base64,PGgxPg==", true) is ImageSource.Blocked)
        assertEquals(ImageSource.Content("content://media/external/images/1"), ImageRefs.classify("content://media/external/images/1", false))
        assertEquals(ImageSource.LocalFile("/storage/emulated/0/Download/IRIDIS/workspace/a b.png"), ImageRefs.classify("file:///storage/emulated/0/Download/IRIDIS/workspace/a%20b.png", false))
        assertEquals(ImageSource.LocalFile("Download/IRIDIS/workspace/img/a.png"), ImageRefs.classify("Download/IRIDIS/workspace/img/a.png", false))
        for (bad in listOf("javascript:alert(1)", "intent://x#Intent;end", "ftp://a/b.png", "")) assertTrue(bad, ImageRefs.classify(bad, true) is ImageSource.Blocked)
    }

    @Test fun localImagesOnlyFromTheWorkspace() {
        val tmp = Files.createTempDirectory("render").toFile()
        val ws = File(tmp, "Download/IRIDIS/workspace").apply { mkdirs() }
        val att = File(tmp, "attachments").apply { mkdirs() }
        File(ws, "img").mkdirs(); File(ws, "img/a.png").writeBytes(byteArrayOf(1, 2))
        File(att, "b.jpg").writeBytes(byteArrayOf(3))
        File(tmp, "secret.png").writeBytes(byteArrayOf(4))
        val a = File(ws, "img/a.png").canonicalFile
        assertEquals(a, ImageRefs.resolveLocal("img/a.png", ws))
        assertEquals(a, ImageRefs.resolveLocal("./img/a.png", ws))
        assertEquals(a, ImageRefs.resolveLocal("Download/IRIDIS/workspace/img/a.png", ws))
        assertEquals(a, ImageRefs.resolveLocal("/storage/emulated/0/Download/IRIDIS/workspace/img/a.png", ws))
        assertEquals(a, ImageRefs.resolveLocal(a.path, ws))
        assertEquals(File(att, "b.jpg").canonicalFile, ImageRefs.resolveLocal(File(att, "b.jpg").path, ws, listOf(att)))
        assertNull(ImageRefs.resolveLocal(File(att, "b.jpg").path, ws))
        assertNull(ImageRefs.resolveLocal("../../../secret.png", ws))
        assertNull(ImageRefs.resolveLocal(File(tmp, "secret.png").path, ws))
        assertNull(ImageRefs.resolveLocal("img/missing.png", ws))
        assertNull(ImageRefs.resolveLocal("img", ws)) // a folder
        assertEquals("img/a.png", ImageRefs.workspaceRelative("/sdcard/Download/IRIDIS/workspace/img/a.png"))
        assertNull(ImageRefs.workspaceRelative("/data/data/x.png"))
        tmp.deleteRecursively()
    }

    @Test fun downsamplingAndMime() {
        assertEquals(1, ImageRefs.sampleSize(1000, 800, 1440))
        assertEquals(2, ImageRefs.sampleSize(3000, 2000, 1440))
        assertEquals(4, ImageRefs.sampleSize(4000, 6000, 1440))
        assertEquals(8, ImageRefs.sampleSize(12000, 9000, 1440))
        assertEquals(1, ImageRefs.sampleSize(0, 0, 1440))
        assertEquals("image/png", ImageRefs.mimeOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals("image/jpeg", ImageRefs.mimeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("image/webp", ImageRefs.mimeOf("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(ImageRefs.mimeOf("<svg".toByteArray()))
        assertEquals("png", ImageRefs.extensionOf("image/png")); assertEquals("jpg", ImageRefs.extensionOf(null))
    }

    // ------------------------------------------------------------------ web_fetch images

    private val page = """
        <html><head><title>猫的百科</title>
        <meta property="og:image" content="/img/hero.jpg">
        <meta name="twitter:image" content="https://cdn.example.com/hero.jpg?x=1&amp;y=2">
        </head><body>
        <img src="/static/logo.png" alt="站点 Logo">
        <img src="https://cdn.example.com/cat1.webp" alt="一只橘猫" width="800">
        <img src="pixel.gif" alt="" width="1">
        <img src="http://insecure.example.com/cat.jpg" alt="不安全">
        <img src="https://cdn.example.com/diagram.svg" alt="示意图">
        <img data-src="cat2.jpg" alt="第二只猫">
        <img src="https://cdn.example.com/cat3.jpg" alt="第三只猫">
        </body></html>
    """.trimIndent()

    @Test fun webFetchListsAFewPageImages() {
        val imgs = HtmlText.images(page, "https://example.com/wiki/cat")
        assertEquals(
            listOf(
                ChatImage("https://example.com/img/hero.jpg", "猫的百科"),
                ChatImage("https://cdn.example.com/hero.jpg?x=1&y=2", "猫的百科"),
                ChatImage("https://cdn.example.com/cat1.webp", "一只橘猫"),
            ),
            imgs,
        )
        val more = HtmlText.images(page, "https://example.com/wiki/cat", max = 10).map { it.source }
        assertTrue(more.contains("https://example.com/wiki/cat2.jpg"))
        assertFalse(more.any { "logo" in it || "pixel" in it || it.startsWith("http:") || it.endsWith(".svg") })
        assertTrue(HtmlText.images("<p>no images</p>", "https://e.com").isEmpty())

        val tmp = Files.createTempDirectory("fetch").toFile()
        val env = ToolEnv(WorkspaceSandbox(tmp), UndoStore(File(tmp, ".u")), fetcher = { FetchResult(200, "text/html", page, it) })
        val r = WebFetchTool.run(buildJsonObject { put("url", "https://example.com/wiki/cat") }, env)
        assertTrue(r.ok)
        assertTrue(r.output, r.output.contains("\nImages (show with ![alt](url)):\n![猫的百科](https://example.com/img/hero.jpg)\n"))
        assertTrue(r.output.trimEnd().endsWith("![一只橘猫](https://cdn.example.com/cat1.webp)"))
        assertTrue(r.output.contains("一只橘猫")) // body text kept
        // non-HTML responses list nothing
        val txt = WebFetchTool.run(buildJsonObject { put("url", "https://e.com/a.txt") }, ToolEnv(WorkspaceSandbox(tmp), UndoStore(File(tmp, ".u")), fetcher = { FetchResult(200, "text/plain", "hello", it) }))
        assertFalse(txt.output.contains("Images"))
        assertTrue(WebFetchTool.promptLine.contains("images"))
        val prompt = com.iris.localai.agent.core.AgentPrompt.build(com.iris.localai.agent.tools.AgentTools.all, "简短", com.iris.localai.agent.core.AgentPrompt.CallStyle.QWEN)
        assertTrue(com.iris.localai.util.TokenEstimator.estimate(prompt) <= 800)
        tmp.deleteRecursively()
    }

    // ------------------------------------------------------------------ settings

    @Test fun richRenderSettingsDefaultOn() {
        val d = AppSettings()
        assertTrue(d.renderHtml); assertTrue(d.loadNetworkImages)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString(AppSettings.serializer(), """{"maxNewTokens":1024}""")
        assertTrue(old.renderHtml); assertTrue(old.loadNetworkImages)
        val off = json.decodeFromString(AppSettings.serializer(), """{"renderHtml":false,"loadNetworkImages":false}""")
        assertFalse(off.renderHtml); assertFalse(off.loadNetworkImages)
    }

    // ------------------------------------------------------------------ house rules

    private fun source(rel: String): String {
        val roots = listOf(File("src/main/java/com/iris/localai"), File("app/src/main/java/com/iris/localai"))
        return File(roots.first { it.exists() }, rel).readText()
    }

}
