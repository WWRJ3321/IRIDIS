package com.iris.localai.v262

import com.iris.localai.agent.mcp.McpServer
import com.iris.localai.agent.mcp.McpToggle
import com.iris.localai.agent.mcp.McpToolInfo
import com.iris.localai.data.UpdateNotes
import com.iris.localai.ui.chat.ImageEditMath
import com.iris.localai.ui.models.DownloadNav
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V262Test {
    private fun src(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }.readText()
    private fun main(p: String) = src("src/main/java/com/iris/localai/$p")
    private fun res(locale: String) = src("src/main/res/$locale/strings.xml")

    @Test fun version() {
        assertEquals("2.6.2", UpdateNotes.RELEASES.first().version)
        val g = src("build.gradle.kts")
        assertTrue(g.contains("versionName = \"2.6.2\""))
        assertTrue(g.contains("versionCode = 261"))
        assertTrue(src("CHANGELOG.md").contains("## 2.6.2（"))
        assertFalse(UpdateNotes.RELEASES.flatMap { it.items }.any { it.text.contains("测试版") })
    }

    /** 35 tools: switching each one off, then on again, changes exactly that tool. */
    @Test fun mcpEveryToolTogglesOneByOne() {
        val tools = (1..35).map { McpToolInfo("tool_$it", "d$it") }
        var s = McpServer("x", "MT", "https://example.com/mcp", tools = tools)
        tools.forEachIndexed { i, t ->
            s = McpToggle.set(s, t.name, false)
            assertEquals(i + 1, s.disabledTools.size)
            assertTrue(t.name in s.disabledTools)
            assertEquals(35 - i - 1, s.tools.count { it.name !in s.disabledTools })
        }
        tools.reversed().forEachIndexed { i, t ->
            s = McpToggle.set(s, t.name, true)
            assertFalse(t.name in s.disabledTools)
            assertEquals(35 - i - 1, s.disabledTools.size)
        }
        // switching the 30th alone leaves every other tool on
        val one = McpToggle.set(McpServer("x", "MT", "u", tools = tools), "tool_30", false)
        assertEquals(setOf("tool_30"), one.disabledTools)
        assertEquals(one, McpToggle.set(one, "tool_30", false))
    }

    /** Root cause: the tall card's path clip hit-test; IrisCard clips drawing only now. */
    @Test fun cardClipsDrawingNotTouches() {
        val kit = main("ui/components/Kit.kt")
        val card = kit.substringAfter("fun IrisCard(").substringBefore("\n}\n")
        assertFalse(card.contains(".clip("))
        assertTrue(card.contains(".clipDraw(IrisRadius.largeShape)"))
        assertTrue(main("ui/theme/SmoothShape.kt").contains("fun androidx.compose.ui.Modifier.clipDraw(shape: Shape)"))
        val page = main("ui/agent/McpPage.kt")
        assertTrue(page.contains("McpToggle.set(it, t.name, on)"))
    }

    @Test fun aboutPage() {
        val zh = res("values")
        assertTrue(zh.contains("<string name=\"about262_privacy\">对话、推理和语音都在本机完成，内容不会上传。只有这几种情况会联网：下载模型和语音包；智能体用联网工具搜索或读网页；你开启本地 API。</string>"))
        listOf("values", "values-en", "values-ja", "values-de", "values-es", "values-hi", "values-zh-rTW").forEach { l ->
            val x = res(l)
            assertFalse(l, x.contains("785322d"))
            assertTrue(l, Regex("<string name=\"settings_bdd372\">[^<]*v1\\.0\\.0</string>").containsMatchIn(x))
            assertTrue(l, x.contains("name=\"about262_bergamot\""))
            assertTrue(l, x.contains("name=\"img262_crop\""))
            assertTrue(l, x.contains("name=\"kb262_embed_tap_dl\""))
        }
        val screens = main("ui/settings/SettingsScreens.kt")
        assertTrue(screens.contains("R.string.about262_privacy"))
        assertTrue(screens.contains("\"Bergamot Translator\""))
        val about = screens.substringAfter("fun AboutPage(").substringBefore("internal val ABOUT_PRIVACY")
        assertTrue(about.contains("settings_45020f, BuildConfig.VERSION_NAME), style = IrisType.secondary"))
    }

    @Test fun embedRowOpensDownload() {
        var opened = 0
        DownloadNav.opener = { opened++ }
        DownloadNav.open("Qwen/Qwen3-Embedding-0.6B-GGUF")
        assertEquals(1, opened)
        assertEquals("Qwen/Qwen3-Embedding-0.6B-GGUF", DownloadNav.take())
        assertNull(DownloadNav.take())
        DownloadNav.opener = null
        assertTrue(main("knowledge/KbEmbedUi.kt").contains("DownloadNav.open(m.repo)"))
        assertTrue(main("ui/MainActivity.kt").contains("DownloadNav.opener = { push(Route.DOWNLOAD_MODELS) }"))
    }

    @Test fun cropBox() {
        val box = floatArrayOf(0f, 0f, 1000f, 800f)
        // corner drags stay inside the picture and keep a minimum size
        val tl = ImageEditMath.dragBox(box, ImageEditMath.Handle.TOP_LEFT, 980f, -50f, 1000f, 800f, 100f)
        assertArrayEquals(floatArrayOf(900f, 0f, 1000f, 800f), tl, 0.01f)
        val br = ImageEditMath.dragBox(box, ImageEditMath.Handle.BOTTOM_RIGHT, -300f, -200f, 1000f, 800f, 100f)
        assertArrayEquals(floatArrayOf(0f, 0f, 700f, 600f), br, 0.01f)
        // moving can't push the box out
        val mv = ImageEditMath.dragBox(floatArrayOf(100f, 100f, 300f, 300f), ImageEditMath.Handle.MOVE, 5000f, -5000f, 1000f, 800f, 100f)
        assertArrayEquals(floatArrayOf(800f, 0f, 1000f, 200f), mv, 0.01f)
        assertNull(ImageEditMath.pixelBox(0f, 0f, 1000f, 800f, 1000, 800))
        assertArrayEquals(intArrayOf(10, 20, 500, 400), ImageEditMath.pixelBox(10.2f, 19.6f, 500f, 400f, 1000, 800))
        assertEquals(800 to 1000, ImageEditMath.rotated(1000, 800, 1))
        assertEquals(1000 to 800, ImageEditMath.rotated(1000, 800, 2))
        assertEquals("a.png" to true, ImageEditMath.outName("a.png"))
        assertEquals("IMG_1.jpg" to false, ImageEditMath.outName("IMG_1.HEIC"))
        assertEquals(3f, ImageEditMath.penWidth(200, 100))
    }

    @Test fun viewerHasToolbarAndEdit() {
        val v = main("ui/markdown/RichViews.kt").substringAfter("internal fun ChatImageViewer(").substringBefore("/** 2.4.5 (#13): zoom / pan limits")
        assertTrue(v.contains("onEdit: ((index: Int, edited: Bitmap) -> Unit)? = null"))
        assertTrue(v.contains("onTap = { chrome = !chrome }"))
        assertTrue(v.contains("ImageEditor("))
        val strip = main("ui/chat/AttachmentViews.kt")
        assertTrue(strip.contains("onReplace: ((old: Attachment, new: Attachment) -> Unit)? = null"))
        assertTrue(main("ui/chat/ChatScreen.kt").contains("onReplaceAttachment = { old, new ->"))
    }
}
