package com.iris.localai.data

import com.iris.localai.data.ModelFormat
import com.iris.localai.ui.chat.WarmUp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmUpTest {
    @Test fun onlyPrefixCachingEngines() {
        assertTrue(WarmUp.supported(ModelFormat.MNN))
        assertTrue(WarmUp.supported(ModelFormat.GGUF))
        assertFalse(WarmUp.supported(ModelFormat.LITERT))
        assertFalse(WarmUp.supported(null))
    }
    @Test fun onlyFinishedConversations() {
        assertFalse(WarmUp.worthIt(0, false))
        assertFalse(WarmUp.worthIt(1, false))
        assertFalse(WarmUp.worthIt(3, false))
        assertTrue(WarmUp.worthIt(2, true))
    }
}
