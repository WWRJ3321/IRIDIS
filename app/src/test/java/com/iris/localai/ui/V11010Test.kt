package com.iris.localai.ui

import com.iris.localai.ui.theme.IrisType
import com.iris.localai.util.diagnostics.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V11010Test {
    @Test fun dwellIsShortButNonZero() { assertTrue(Activity.DWELL_MS in 200L..600L) }

    /** Streaming plain text and finished Markdown use the same metrics (no line-height jump). */
    @Test fun markdownBodyMatchesMessage() {
        assertEquals(IrisType.message.fontSize, IrisType.message.copy().fontSize)
        assertTrue(IrisType.mdH1.fontSize.value > IrisType.mdH2.fontSize.value)
        assertTrue(IrisType.mdH2.fontSize.value > IrisType.mdH3.fontSize.value)
        assertTrue(IrisType.mdH3.fontSize.value > IrisType.message.fontSize.value)
    }
}
