package com.iris.localai.v217ux

import com.iris.localai.server.ApiAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 2.1.7 API 可自定 Key. */
class ApiKeyTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    @Test fun emptyKeyKeepsOldBehaviour() {
        assertNull(ApiAuth.check("", emptyMap()))
        assertNull(ApiAuth.check("  ", mapOf("authorization" to "Bearer whatever")))
        assertEquals(403, ApiAuth.check("", mapOf("origin" to "null"))?.status) // sandboxed iframe / file: page
        assertEquals(403, ApiAuth.check("", mapOf("origin" to "https://evil.example"))?.status)
    }

    @Test fun keyRequiresBearerOrXApiKey() {
        val k = "my-Custom_key.123"
        assertEquals(401, ApiAuth.check(k, emptyMap())?.status)
        assertEquals(401, ApiAuth.check(k, mapOf("authorization" to "Bearer wrong"))?.status)
        assertEquals(401, ApiAuth.check(k, mapOf("x-api-key" to "wrong"))?.status)
        assertNull(ApiAuth.check(k, mapOf("authorization" to "Bearer $k")))
        assertNull(ApiAuth.check(k, mapOf("authorization" to "bearer  $k ")))
        assertNull(ApiAuth.check(k, mapOf("x-api-key" to k)))
        // a wrong Authorization does not hide a right x-api-key
        assertNull(ApiAuth.check(k, mapOf("authorization" to "Bearer nope", "x-api-key" to k)))
        // a key set also lets browser pages in (they prove they know it)
        assertNull(ApiAuth.check(k, mapOf("origin" to "https://app.example", "authorization" to "Bearer $k")))
        // stored key with stray whitespace still matches
        assertNull(ApiAuth.check(" $k ", mapOf("authorization" to "Bearer $k")))
    }

    @Test fun typedKeyIsHeaderSafe() {
        assertEquals("abc-123", ApiAuth.sanitizeKey(" ab c-1\n23 "))
        assertEquals("", ApiAuth.sanitizeKey("密钥"))
        assertEquals(ApiAuth.MAX_KEY_LENGTH, ApiAuth.sanitizeKey("x".repeat(1000)).length)
    }

    @Test fun routesAndPageUseIt() {
        assertEquals(true, src("server/ApiRoutes.kt").contains("ApiAuth.check("))
        val page = src("ui/settings/VoiceAndApiPages.kt").substringAfter("fun ApiPage(")
        for (s in listOf("生成随机", "\"复制\"", "PasswordVisualTransformation", "IrisIcons.EyeOff", "vm.setApiKey(")) {
            assertEquals(s, true, page.contains(s))
        }
    }
}
