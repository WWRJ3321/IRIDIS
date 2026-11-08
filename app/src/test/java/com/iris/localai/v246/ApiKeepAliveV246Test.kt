package com.iris.localai.v246

import com.iris.localai.server.ApiKeepAlive
import com.iris.localai.server.HttpResponder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ApiKeepAliveV246Test {
    @Test fun fastReplyIsUntouched() = runBlocking {
        val out = ByteArrayOutputStream(); val res = HttpResponder(out)
        val v = ApiKeepAlive.run(res) { 42 }
        assertEquals(42, v); assertFalse(res.committed)
        res.finishJson(200, "{\"a\":1}")
        val s = out.toString("UTF-8")
        assertTrue(s.contains("Content-Length: 7")); assertTrue(s.endsWith("{\"a\":1}"))
    }

    @Test fun slowReplyGetsHeadThenWhitespaceThenBody() = runBlocking {
        val out = ByteArrayOutputStream(); val res = HttpResponder(out)
        val v = ApiKeepAlive.run(res) { delay(ApiKeepAlive.FIRST_MS + 500); "x" }
        assertEquals("x", v); assertTrue(res.committed)
        res.finishJson(200, "{\"a\":1}")
        val s = out.toString("UTF-8")
        assertTrue(s.startsWith("HTTP/1.1 200")); assertTrue(s.contains("application/json"))
        assertFalse(s.contains("Content-Length"))
        val body = s.substringAfter("\r\n\r\n")
        assertEquals("{\"a\":1}", body.trim()); assertTrue(body.startsWith(" "))
    }

    @Test fun failureAfterHeadWritesErrorJson() = runBlocking {
        val out = ByteArrayOutputStream(); val res = HttpResponder(out)
        val v: String? = ApiKeepAlive.run(res) { delay(ApiKeepAlive.FIRST_MS + 500); error("boom") }
        assertNull(v)
        assertTrue(out.toString("UTF-8").substringAfter("\r\n\r\n").trim().startsWith("{\"error\":{\"message\":\"boom\""))
    }

    @Test(expected = IllegalStateException::class) fun failureBeforeHeadRethrows() = runBlocking<Unit> {
        ApiKeepAlive.run(ByteArrayOutputStream().let { HttpResponder(it) }) { error("early") }
    }
}
