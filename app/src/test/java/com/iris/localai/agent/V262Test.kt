package com.iris.localai.agent

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

}
