package com.iris.localai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveDictationTest {
    @Test fun partialKeepsFinalAndReplacesTentative() {
        val p = LivePartial()
        p.tentative("今天")
        assertEquals("今天", p.text())
        p.tentative("今天天气")
        assertEquals("今天天气", p.text())
        p.finalize("今天天气很好。", 32000)
        assertEquals(32000, p.doneUpTo)
        p.tentative("我们")
        assertEquals("今天天气很好。我们", p.text())
        p.tentative("")
        assertEquals("今天天气很好。", p.text())
        p.finalize("x", 1000)
        assertEquals(32000, p.doneUpTo)
    }

    @Test fun joinAppendsToExistingInput() {
        assertEquals("你好", DictationText.join("", "你好"))
        assertEquals("abc 你好", DictationText.join("abc", "你好"))
        assertEquals("abc 你好", DictationText.join("abc ", "你好"))
        assertEquals("abc\n你好", DictationText.join("abc\n", "你好"))
        assertEquals("abc", DictationText.join("abc", "  "))
    }

    @Test fun noPackNoPartial() {
        assertFalse(AsrPartial.supports(null))
    }
}
