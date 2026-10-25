package com.iris.localai.server

import java.security.MessageDigest

/**
 * 2.1.7 API 可自定 Key: the local API's access check, pure so it can be unit-tested.
 *
 * - key empty → no key needed (old behaviour), but a browser page (Origin header, also `null`
 *   from sandboxed iframes / file: pages) is refused (1.13.0), and so is a Host other than
 *   loopback (DNS rebinding: a same-origin page sends no Origin).
 * - key set → `Authorization: Bearer <key>` or `x-api-key: <key>` must match (constant-time compare).
 *
 * [headers] use lower-case names (as [HttpServer] parses them). Returns null when allowed, else the HTTP error.
 */
object ApiAuth {
    /** Allowed characters / length of a user-typed key (keeps it header-safe). */
    const val MAX_KEY_LENGTH = 256

    fun check(key: String, headers: Map<String, String>): HttpError? {
        val k = key.trim()
        if (k.isEmpty()) {
            val origin = headers["origin"]?.trim().orEmpty()
            if (origin.isNotEmpty()) return HttpError(403, "browser requests need an API key (设置 › API 服务)")
            return if (isLoopbackHost(headers["host"])) null else HttpError(403, "requests to this host need an API key (设置 › API 服务)")
        }
        val candidates = buildList {
            headers["authorization"]?.trim()?.takeIf { it.isNotEmpty() }?.let { h ->
                add(if (h.startsWith("Bearer ", ignoreCase = true)) h.substring(7).trim() else h)
            }
            headers["x-api-key"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        val want = k.toByteArray(Charsets.UTF_8)
        return if (candidates.any { MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), want) }) null
        else HttpError(401, "invalid or missing API key")
    }

    /** Host header names a loopback address (port ignored); a missing Host (non-browser client) passes. */
    fun isLoopbackHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase().orEmpty()
        if (h.isEmpty()) return true
        val name = if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':')
        return name in LOOPBACK_HOSTS
    }

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

    /** Cleans a key typed in 设置: printable ASCII without spaces, capped (anything else could not travel in a header). */
    fun sanitizeKey(raw: String): String = raw.filter { it in '!'..'~' }.take(MAX_KEY_LENGTH)
}
