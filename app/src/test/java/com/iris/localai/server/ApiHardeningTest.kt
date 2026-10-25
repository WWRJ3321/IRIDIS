package com.iris.localai.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket

/** API hardening: DNS rebinding / `Origin: null`, CORS only with a key, head deadline outside the permit, wake lock. */
class ApiHardeningTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    @Test fun emptyKeyOnlyServesLoopbackHosts() {
        assertNull(ApiAuth.check("", mapOf("host" to "127.0.0.1:11434")))
        assertNull(ApiAuth.check("", mapOf("host" to "localhost:8080")))
        assertNull(ApiAuth.check("", mapOf("host" to "[::1]:8080")))
        assertNull(ApiAuth.check("", mapOf("host" to "LOCALHOST")))
        assertNull(ApiAuth.check("", emptyMap())) // non-browser client without Host
        assertEquals(403, ApiAuth.check("", mapOf("host" to "evil.example:11434"))?.status)
        assertEquals(403, ApiAuth.check("", mapOf("host" to "127.0.0.1.evil.example"))?.status)
        assertEquals(403, ApiAuth.check("", mapOf("origin" to "null", "host" to "127.0.0.1:11434"))?.status)
    }

    @Test fun keySetIgnoresHost() {
        assertNull(ApiAuth.check("k", mapOf("host" to "192.168.1.5:11434", "authorization" to "Bearer k")))
        assertEquals(401, ApiAuth.check("k", mapOf("host" to "192.168.1.5:11434"))?.status)
    }

    private fun rawResponse(port: Int, request: String): String = Socket(InetAddress.getLoopbackAddress(), port).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().apply { write(request.toByteArray()); flush() }
        BufferedReader(InputStreamReader(s.getInputStream())).readText()
    }

    @Test fun corsHeaderFollowsSwitch() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = HttpServer(InetAddress.getLoopbackAddress(), port) { _, res -> res.json(200, "{}") }
        server.start()
        try {
            val req = "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            assertTrue(rawResponse(port, req).contains("Access-Control-Allow-Origin: *"))
            server.cors = { false }
            val r = rawResponse(port, req)
            assertTrue(r.startsWith("HTTP/1.1 200"))
            assertFalse(r.contains("Access-Control-Allow-Origin"))
        } finally {
            server.stop()
        }
    }

    @Test fun idleClientsDoNotBlockOthers() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = HttpServer(InetAddress.getLoopbackAddress(), port) { _, res -> res.json(200, "{}") }
        server.start()
        // More idle connections (no bytes sent) than MAX_CONNECTIONS permits.
        val idle = List(12) { Socket(InetAddress.getLoopbackAddress(), port) }
        try {
            Thread.sleep(200)
            val r = rawResponse(port, "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(r, r.startsWith("HTTP/1.1 200"))
        } finally {
            idle.forEach { runCatching { it.close() } }
            server.stop()
        }
    }

    @Test fun wakeLockNotReferenceCounted() {
        val svc = src("server/ApiService.kt")
        assertTrue(svc.contains("setReferenceCounted(false)"))
        assertTrue(svc.contains("inFlight.getAndIncrement() == 0"))
        assertTrue(svc.contains("inFlight.decrementAndGet() == 0"))
        assertTrue(svc.contains("srv.cors = { c.settings.current.apiKey.isNotBlank() }"))
    }
}
