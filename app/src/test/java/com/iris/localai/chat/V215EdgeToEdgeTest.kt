package com.iris.localai.chat

import androidx.compose.ui.graphics.Color
import com.iris.localai.ui.chat.CHAT_TOP_BAR_BACKDROP_ALPHA
import com.iris.localai.ui.chat.chatTopBarColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1.5: custom chat background runs full-bleed under the status bar and the gesture pill. */
class V215EdgeToEdgeTest {
    private fun source(path: String): String =
        listOf(path, "app/$path").map { java.io.File(it) }.first { it.exists() }.readText()

    @Test fun topBarOpaqueWithoutBackground() {
        val page = Color(0xFFFFFFFF)
        assertEquals(page, chatTopBarColor(page, backgroundImage = false))
        assertEquals(1f, chatTopBarColor(page, false).alpha)
    }

    @Test fun topBarTranslucentOverBackground() {
        val page = Color(0xFFFFF9FB)
        val bar = chatTopBarColor(page, backgroundImage = true)
        assertEquals(CHAT_TOP_BAR_BACKDROP_ALPHA, bar.alpha, 0.01f) // 8-bit alpha
        assertTrue(bar.alpha in 0.8f..0.95f) // see-through like cards, still legible
        assertEquals(page.red, bar.red, 0.01f)
    }


}
