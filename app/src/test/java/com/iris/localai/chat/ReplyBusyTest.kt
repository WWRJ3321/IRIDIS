package com.iris.localai.chat

import com.iris.localai.ui.chat.ReplyBusy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1.8: busy() reads the raw sources, so the queue drain right after a reply is not skipped. */
class ReplyBusyTest {
    @Test fun idle_whenNoBubbleAndNoJob() = assertFalse(ReplyBusy.of(null, false))
    @Test fun busy_whileStreaming() = assertTrue(ReplyBusy.of("r1", true))
    @Test fun busy_whileWaitingForModel_beforeAnyBubble() = assertTrue(ReplyBusy.of(null, true))
    @Test fun busy_whenBubbleStillMarkedStreaming() = assertTrue(ReplyBusy.of("r1", false))
}
