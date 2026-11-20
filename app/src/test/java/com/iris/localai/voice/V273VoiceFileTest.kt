package com.iris.localai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.7.3：语音模型文件读不了时不进原生构造。 */
class V273VoiceFileTest {
    @Test fun listsUnreadable() {
        val bad = setOf("/d/model.int8.onnx")
        val r = VoiceFileCheck.unreadable(listOf("/d/model.int8.onnx", "/d/tokens.txt", ""), { it !in bad })
        assertEquals(listOf("/d/model.int8.onnx"), r)
        assertTrue(VoiceFileCheck.unreadable(listOf("/d/tokens.txt"), { true }).isEmpty())
        assertTrue(VoiceFileCheck.message(r).contains("无权限读取"))
        assertTrue(VoiceFileCheck.message(r).contains("model.int8.onnx"))
    }

    @Test fun realCheck() {
        val f = java.io.File.createTempFile("v273", ".onnx"); f.writeText("x")
        assertTrue(VoiceFileCheck.canReadReal(f.absolutePath))
        assertFalse(VoiceFileCheck.canReadReal(f.absolutePath + ".none"))
        f.delete()
    }
}
