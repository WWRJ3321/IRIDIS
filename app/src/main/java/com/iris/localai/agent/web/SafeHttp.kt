package com.iris.localai.agent.web

import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.workspace.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * which addresses the agent's web tools may reach. [isPublic] is pure (no DNS).
 * Refused: loopback, private (RFC 1918), CGNAT 100.64/10, link-local, "this network" 0/8,
 * unspecified, multicast, reserved / broadcast 240/4, benchmark / documentation ranges, IPv6 ULA
 * fc00::/7, link-/site-local, multicast, and IPv4-mapped / -compatible / NAT64 / 6to4 forms of a
 * refused IPv4 address (Teredo outright). So a prompt-injected page cannot point the agent at the
 * local API server, the LAN or the phone itself.
 */
object NetGuard {
    const val REFUSED = "不允许访问本机或局域网地址"

    /**
     * The DNS seam: every lookup of the web tools goes through this unless a caller passes its own
     * resolver. Tests swap it (or pass a resolver) so nothing touches the network.
     */
    @Volatile
    var systemResolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }

    fun isPublic(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
        return when (a) {
            is Inet4Address -> isPublicV4(a.address)
            is Inet6Address -> isPublicV6(a.address)
            else -> false
        }
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    fun isPublicV4(b: ByteArray): Boolean {
        if (b.size != 4) return false
        val a0 = u(b[0]); val a1 = u(b[1]); val a2 = u(b[2])
        return when {
            a0 == 0 || a0 == 10 || a0 == 127 -> false                 // this network, private, loopback
            a0 == 100 && a1 in 64..127 -> false                        // CGNAT 100.64/10
            a0 == 169 && a1 == 254 -> false                            // link-local
            a0 == 172 && a1 in 16..31 -> false                         // private
            a0 == 192 && a1 == 168 -> false                            // private
            a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2) -> false      // IETF protocol, TEST-NET-1
            a0 == 198 && (a1 == 18 || a1 == 19) -> false               // benchmarking
            a0 == 198 && a1 == 51 && a2 == 100 -> false                // TEST-NET-2
            a0 == 203 && a1 == 0 && a2 == 113 -> false                 // TEST-NET-3
            a0 >= 224 -> false                                         // multicast, reserved, broadcast
            else -> true
        }
    }

    fun isPublicV6(b: ByteArray): Boolean {
        if (b.size != 16) return false
        val b0 = u(b[0]); val b1 = u(b[1])
        if (b0 and 0xFE == 0xFC) return false                          // ULA fc00::/7
        if (b0 == 0xFE && b1 and 0xC0 == 0x80) return false             // link-local fe80::/10
        if (b0 == 0xFE && b1 and 0xC0 == 0xC0) return false             // site-local fec0::/10 (deprecated)
        if (b0 == 0xFF) return false                                   // multicast
        fun zero(from: Int, to: Int) = (from until to).all { b[it].toInt() == 0 }
        fun v4() = b.copyOfRange(12, 16)
        // ::/96 (unspecified, loopback, IPv4-compatible) and ::ffff:0:0/96 (IPv4-mapped): judge the IPv4 part.
        if (zero(0, 10) && ((u(b[10]) == 0 && u(b[11]) == 0) || (u(b[10]) == 0xFF && u(b[11]) == 0xFF))) {
            return isPublicV4(v4())
        }
        // NAT64 64:ff9b::/96 and local-use 64:ff9b:1::/48 (RFC 8215) carry an IPv4 address in the last 32 bits.
        if (b0 == 0x00 && b1 == 0x64 && u(b[2]) == 0xFF && u(b[3]) == 0x9B) {
            if (zero(4, 12) || (u(b[4]) == 0x00 && u(b[5]) == 0x01)) return isPublicV4(v4())
        }
        if (b0 == 0x20 && b1 == 0x02) return isPublicV4(b.copyOfRange(2, 6)) // 6to4 2002::/16
        if (b0 == 0x20 && b1 == 0x01 && u(b[2]) == 0x00 && u(b[3]) == 0x00) return false // Teredo 2001::/32
        if (b0 == 0x20 && b1 == 0x01 && u(b[2]) == 0x0D && u(b[3]) == 0xB8) return false // documentation 2001:db8::/32
        if (b0 == 0x01 && zero(1, 8)) return false                      // discard-only 100::/64
        return true
    }

    /** Host names that never leave the phone, refused without asking DNS. */
    fun localName(host: String): Boolean {
        val h = host.lowercase().trim().trim('[', ']').trimEnd('.')
        return h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
            h.endsWith(".internal") || h == "ip6-localhost" || h == "ip6-loopback"
    }

    /**
     * [host] as an IP literal (dotted-quad IPv4, or IPv6 with or without brackets), or null when it
     * is not one. Never touches DNS (a bracketed text is only ever parsed as a literal).
     */
    fun literal(host: String): InetAddress? {
        val h = host.trim().trim('[', ']')
        if (h.isEmpty()) return null
        if (h.contains(':')) {
            if (h.contains('%')) return null // zone ids: refused by resolvePublic
            return runCatching { InetAddress.getByName("[$h]") }.getOrNull()
        }
        val parts = h.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } }) return null
        val n = parts.map { it.toInt() }
        if (n.any { it > 255 }) return null
        return InetAddress.getByAddress(ByteArray(4) { n[it].toByte() })
    }

    /**
     * Resolves [host] once and returns its addresses, all public; throws [SecurityException] when
     * any of them is not (a name that also points inside cannot be used at all). The caller must
     * connect to one of the returned addresses, never resolve the name again.
     */
    fun resolvePublic(host: String?, resolve: (String) -> Array<InetAddress> = systemResolver): List<InetAddress> {
        if (host.isNullOrBlank()) throw IllegalArgumentException("无效网址")
        val h = asciiHost(host)
        if (localName(h)) throw SecurityException(REFUSED)
        literal(h)?.let { ip -> if (isPublic(ip)) return listOf(ip) else throw SecurityException(REFUSED) }
        // Anything IPv6-looking that is not a clean literal (zone id, garbage) never reaches DNS.
        if (h.contains(':') || h.contains('%')) throw SecurityException(REFUSED)
        val addrs = resolve(h)
        if (addrs.isEmpty() || addrs.any { !isPublic(it) }) throw SecurityException(REFUSED)
        return addrs.toList()
    }

    /** Brackets / trailing dot removed, internationalised names in their ASCII (punycode) form. */
    fun asciiHost(host: String): String {
        val h = host.trim().trim('[', ']').trimEnd('.')
        if (h.all { it.code < 0x80 }) return h
        return runCatching { java.net.IDN.toASCII(h) }.getOrElse { throw IllegalArgumentException("无效网址") }
    }

    /**
     * Pure, DNS-free pre-check of a URL: true when its host is obviously inside (a local name or a
     * non-public IP literal). Names are judged later, after resolving.
     */
    fun obviouslyLocal(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?: runCatching { URL(url).host }.getOrNull() ?: return false
        if (localName(host)) return true
        val ip = literal(host) ?: return false
        return !isPublic(ip)
    }
}

/**
 * a minimal HTTP/1.1 GET client for web_fetch / web_search that connects to the very
 * address it checked. HttpURLConnection resolves the host again when it connects, so a DNS answer
 * that changed between the check and the connection (DNS rebinding: public at first, 127.0.0.1 a
 * moment later) slipped through. Here the host is resolved once ([NetGuard.resolvePublic]), the
 * socket is opened to one of those addresses, and HTTPS runs over that socket with the real host
 * name for SNI and certificate / hostname checks. Every redirect goes through the same steps.
 * No proxies, no keep-alive, no compression (identity only). Parsing ([HttpWire]) is pure.
 */
class SafeHttpClient(
    private val resolve: (String) -> Array<InetAddress> = NetGuard.systemResolver,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
    /** The whole exchange of one hop (connect, headers, body) must finish within this. */
    private val totalTimeoutMs: Long = 45_000,
) {
    class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray, val url: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    fun get(url: String, headers: Map<String, String>, maxBytes: Int, maxRedirects: Int = 3): Response {
        var current = url
        repeat(maxRedirects + 1) {
            val u = runCatching { URL(current) }.getOrNull() ?: throw IllegalArgumentException("无效网址：$current")
            val r = once(u, headers, maxBytes)
            if (r.code in 300..399 && r.code != 304) {
                val loc = r.header("location") ?: return r
                current = runCatching { URL(u, loc).toString() }.getOrNull() ?: throw IllegalArgumentException("无效跳转：$loc")
                return@repeat
            }
            return r
        }
        throw IllegalStateException("重定向次数过多")
    }

    private fun once(u: URL, headers: Map<String, String>, maxBytes: Int): Response {
        val https = when (u.protocol.lowercase()) {
            "https" -> true
            "http" -> false
            else -> throw IllegalArgumentException("只支持 http / https")
        }
        if (u.host.isNullOrBlank()) throw IllegalArgumentException("无效网址")
        val host = NetGuard.asciiHost(u.host)
        val port = if (u.port > 0) u.port else if (https) 443 else 80
        val addrs = NetGuard.resolvePublic(host, resolve)
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        var last: IOException? = null
        for (addr in addrs.take(3)) {
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(addr, port), connectTimeoutMs)
                // The peer is exactly the checked address; re-checked anyway (defence in depth).
                if (!NetGuard.isPublic(raw.inetAddress)) throw SecurityException(NetGuard.REFUSED)
                raw.soTimeout = readTimeoutMs
                val sock: Socket = if (https) tls(raw, host, port) else raw
                sock.use { s ->
                    val out = s.getOutputStream()
                    out.write(HttpWire.request(u, host, port, https, headers).toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    val input = DeadlineInput(BufferedInputStream(s.getInputStream()), deadline)
                    val head = HttpWire.readHead(input)
                    val body = if (head.code in 300..399 || head.code == 204) ByteArray(0)
                        else HttpWire.readBody(input, head, maxBytes)
                    return Response(head.code, head.headers, body, u.toString())
                }
            } catch (e: IOException) {
                runCatching { raw.close() }
                if (e is SSLException) throw e // certificate / name problems: another address will not help
                last = e
            } catch (t: Throwable) {
                runCatching { raw.close() }
                throw t
            }
        }
        throw last ?: IOException("无法连接 $host")
    }

    /** TLS over the already connected socket: SNI and certificate checked against [host], not the IP. */
    private fun tls(raw: Socket, host: String, port: Int): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val s = factory.createSocket(raw, host, port, true) as SSLSocket
        try {
            // Set one by one: an odd name SNIHostName rejects must not cost the endpoint check.
            runCatching { s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" } }
            if (NetGuard.literal(host) == null) {
                runCatching { s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(host)) } }
            }
            s.startHandshake()
            // Android: the platform verifier (as HttpsURLConnection uses). A desktop JVM's default
            // verifier rejects everything; there the HTTPS endpoint identification above checks the name.
            if (ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
                throw SSLPeerUnverifiedException("证书与网址不符：$host")
            }
            return s
        } catch (t: Throwable) {
            runCatching { s.close() }
            throw t
        }
    }

    private companion object {
        val ANDROID: Boolean = System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true ||
            System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true
    }

    /** Ends a read that runs past the overall deadline (a server dripping one byte at a time). */
    private class DeadlineInput(private val inner: InputStream, private val deadline: Long) : InputStream() {
        private fun check() { if (System.currentTimeMillis() > deadline) throw java.net.SocketTimeoutException("读取超时") }
        override fun read(): Int { check(); return inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return inner.read(b, off, len) }
        override fun close() = inner.close()
    }
}

/** HTTP/1.1 request writing and response reading for [SafeHttpClient]. Pure (streams only). */
object HttpWire {
    data class Head(val code: Int, val headers: Map<String, String>)

    private const val MAX_LINE = 16 * 1024
    private const val MAX_HEADERS = 200

    fun request(u: URL, host: String, port: Int, https: Boolean, headers: Map<String, String>): String = buildString {
        val target = encodeTarget(u.path.ifEmpty { "/" } + (u.query?.let { "?$it" } ?: ""))
        append("GET ").append(target).append(" HTTP/1.1\r\n")
        val defaultPort = if (https) 443 else 80
        val hostHeader = (if (host.contains(':')) "[$host]" else host) + if (port != defaultPort) ":$port" else ""
        append("Host: ").append(hostHeader).append("\r\n")
        for ((k, v) in headers) {
            if (k.equals("host", true) || k.equals("connection", true) || k.equals("accept-encoding", true)) continue
            if (k.isEmpty() || k.any { it <= ' ' || it == ':' || it.code >= 0x7F } || v.any { it == '\r' || it == '\n' }) continue
            append(k).append(": ").append(v).append("\r\n")
        }
        append("Accept-Encoding: identity\r\n")
        append("Connection: close\r\n\r\n")
    }

    /** Request target with spaces, controls and non-ASCII percent-encoded (UTF-8); existing escapes kept. */
    fun encodeTarget(raw: String): String = buildString {
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c <= 0x20 || c >= 0x7F || c == '"'.code || c == '<'.code || c == '>'.code || c == '`'.code ||
                c == '{'.code || c == '}'.code || c == '|'.code || c == '\\'.code || c == '^'.code) {
                append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            } else append(c.toChar())
        }
    }

    /** One CRLF (or LF) terminated line, ISO-8859-1; null at end of stream before any byte. */
    fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw IOException("响应头过长")
        }
    }

    fun readHead(input: InputStream): Head {
        var status = readLine(input) ?: throw IOException("服务器没有响应")
        while (true) {
            val code = parseStatus(status)
            val headers = LinkedHashMap<String, String>()
            var count = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (++count > MAX_HEADERS) throw IOException("响应头过多")
                val i = line.indexOf(':')
                if (i <= 0) continue
                val k = line.substring(0, i).trim().lowercase()
                val v = line.substring(i + 1).trim()
                headers[k] = headers[k]?.let { "$it, $v" } ?: v
            }
            // 1xx interim responses (100 Continue, 103 Early Hints …): skip to the real one.
            if (code in 100..199) { status = readLine(input) ?: throw IOException("服务器没有响应"); continue }
            return Head(code, headers)
        }
    }

    fun parseStatus(line: String): Int {
        val parts = line.trim().split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("不是 HTTP 响应")
        return parts[1].toIntOrNull()?.takeIf { it in 100..999 } ?: throw IOException("不是 HTTP 响应")
    }

    /** The body: chunked, Content-Length or until close; at most [maxBytes] (the rest is not read). */
    fun readBody(input: InputStream, head: Head, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val te = head.headers["transfer-encoding"]?.lowercase().orEmpty()
        if ("chunked" in te) {
            while (out.size() < maxBytes) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                if (size == null || size < 0) throw IOException("分块长度无效")
                if (size == 0L) break // last chunk (trailers are not needed: the connection closes)
                if (copy(input, out, size, maxBytes) < size) break // cap reached or stream ended
                readLine(input) // the CRLF after the chunk
            }
            return out.toByteArray()
        }
        val len = head.headers["content-length"]?.substringBefore(',')?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        copy(input, out, len ?: Long.MAX_VALUE, maxBytes)
        return out.toByteArray()
    }

    /** Copies up to [count] bytes (never past [maxBytes] in total); returns how many were copied. */
    private fun copy(input: InputStream, out: ByteArrayOutputStream, count: Long, maxBytes: Int): Long {
        val buf = ByteArray(8192)
        var left = count
        while (left > 0 && out.size() < maxBytes) {
            val want = minOf(buf.size.toLong(), left, (maxBytes - out.size()).toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return count - left
    }
}
