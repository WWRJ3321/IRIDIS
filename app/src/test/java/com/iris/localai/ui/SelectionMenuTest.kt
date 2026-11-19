package com.iris.localai.ui

import com.iris.localai.ui.chat.SelectionMenuText
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionMenuTest {
    @Test fun quoteMarksEveryLine() {
        assertEquals("> a\n> b\n\n", SelectionMenuText.quote(" a\nb "))
        assertEquals("> a\n>\n> b\n\n", SelectionMenuText.quote("a\n\nb"))
    }

    @Test fun quoteIntoInput() {
        assertEquals("> x\n\n", SelectionMenuText.quoteInto("", "x"))
        assertEquals("hi\n\n> x\n\n", SelectionMenuText.quoteInto("hi", "x"))
        assertEquals("hi\n\n> x\n\n", SelectionMenuText.quoteInto("hi\n", "x"))
        assertEquals("hi", SelectionMenuText.quoteInto("hi", "  "))
    }
}
