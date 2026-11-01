package com.iris.localai.util

/**
 * 2.2.13: readable text of an ApplicationExitInfo native-crash trace. Android 12+ hands out the
 * tombstone as a protobuf (system/core/debuggerd/proto/tombstone.proto), so its printable runs lose
 * the "#00 pc …" layout and the crashing thread is buried among idle libc / libhwui threads. This
 * decodes the fields needed for a debuggerd-style report: signal + fault address, abort message,
 * causes, and every thread's backtrace with the crashing thread first. Older text tombstones are
 * returned as they are. Pure Kotlin, no protobuf dependency.
 */
object TombstoneText {

    private class Frame(var relPc: Long = 0, var fn: String = "", var fnOff: Long = 0, var file: String = "", var buildId: String = "")
    private class Thread(var id: Int = 0, var name: String = "", val frames: MutableList<Frame> = mutableListOf())

    /** Decoded report, or the bytes as UTF-8 when they are not a tombstone protobuf. */
    fun render(bytes: ByteArray): String = decodeOrNull(bytes) ?: String(bytes, Charsets.UTF_8)

    /** Decoded protobuf tombstone, a text tombstone as is, or null. */
    fun decodeOrNull(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        // Text tombstones (Android 11) start with "*** *** ***" or "Build fingerprint".
        if (bytes[0] == '*'.code.toByte() || bytes[0] == 'B'.code.toByte()) return String(bytes, Charsets.UTF_8)
        return runCatching { decode(bytes) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun decode(b: ByteArray): String? {
        var pid = 0; var tid = 0
        var fingerprint = ""; var abort = ""
        val cmd = mutableListOf<String>()
        var sigName = ""; var sigNo = 0; var codeName = ""; var fault = 0L; var hasFault = false
        val causes = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        var ok = false
        walk(b, 0, b.size) { field, wire, v, s, e ->
            when (field) {
                2 -> if (wire == 2) fingerprint = str(b, s, e)
                5 -> if (wire == 0) pid = v.toInt()
                6 -> if (wire == 0) tid = v.toInt()
                9 -> if (wire == 2) cmd += str(b, s, e)
                10 -> if (wire == 2) walk(b, s, e) { f, w, vv, ss, ee ->
                    when (f) {
                        1 -> if (w == 0) sigNo = vv.toInt()
                        2 -> if (w == 2) sigName = str(b, ss, ee)
                        4 -> if (w == 2) codeName = str(b, ss, ee)
                        8 -> if (w == 0) hasFault = vv != 0L
                        9 -> if (w == 0) fault = vv
                    }
                }
                14 -> if (wire == 2) abort = str(b, s, e)
                15 -> if (wire == 2) walk(b, s, e) { f, w, _, ss, ee -> if (f == 1 && w == 2) causes += str(b, ss, ee) }
                16 -> if (wire == 2) {
                    ok = true
                    walk(b, s, e) { f, w, _, ss, ee -> if (f == 2 && w == 2) threads += thread(b, ss, ee) }
                }
            }
        }
        if (!ok && sigNo == 0) return null
        val sb = StringBuilder()
        if (fingerprint.isNotEmpty()) sb.append("Build fingerprint: '").append(fingerprint).append("'\n")
        sb.append("pid: ").append(pid).append(", tid: ").append(tid)
        if (cmd.isNotEmpty()) sb.append("  >>> ").append(cmd.joinToString(" ")).append(" <<<")
        sb.append('\n')
        sb.append("signal ").append(sigNo).append(" (").append(sigName).append("), code ").append(codeName)
        if (hasFault) sb.append(", fault addr 0x").append(java.lang.Long.toHexString(fault))
        sb.append('\n')
        if (abort.isNotEmpty()) sb.append("Abort message: '").append(abort).append("'\n")
        causes.forEach { sb.append("Cause: ").append(it).append('\n') }
        val ordered = threads.sortedBy { if (it.id == tid) 0 else 1 }
        for (t in ordered) {
            sb.append('\n').append(if (t.id == tid) "backtrace (crashing thread" else "thread (").append(" tid ")
                .append(t.id).append(" \"").append(t.name).append("\"):\n")
            t.frames.forEachIndexed { i, f ->
                sb.append("      #").append(i.toString().padStart(2, '0')).append(" pc ")
                    .append(java.lang.Long.toHexString(f.relPc).padStart(16, '0')).append("  ").append(f.file)
                if (f.fn.isNotEmpty()) sb.append(" (").append(f.fn).append('+').append(f.fnOff).append(')')
                if (f.buildId.isNotEmpty()) sb.append(" (BuildId: ").append(f.buildId).append(')')
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun thread(b: ByteArray, s: Int, e: Int): Thread {
        val t = Thread()
        walk(b, s, e) { f, w, v, ss, ee ->
            when (f) {
                1 -> if (w == 0) t.id = v.toInt()
                2 -> if (w == 2) t.name = str(b, ss, ee)
                4 -> if (w == 2) {
                    val fr = Frame()
                    walk(b, ss, ee) { ff, ww, vv, s2, e2 ->
                        when (ff) {
                            1 -> if (ww == 0) fr.relPc = vv
                            4 -> if (ww == 2) fr.fn = str(b, s2, e2)
                            5 -> if (ww == 0) fr.fnOff = vv
                            6 -> if (ww == 2) fr.file = str(b, s2, e2)
                            8 -> if (ww == 2) fr.buildId = str(b, s2, e2)
                        }
                    }
                    t.frames += fr
                }
            }
        }
        return t
    }

    private fun str(b: ByteArray, s: Int, e: Int) = String(b, s, e - s, Charsets.UTF_8)

    /** Calls [on] for every field in b[s, e): (field, wire type, varint value, payload start, payload end). */
    private inline fun walk(b: ByteArray, s: Int, e: Int, on: (Int, Int, Long, Int, Int) -> Unit) {
        var i = s
        while (i < e) {
            var key = 0L; var shift = 0
            while (true) { val x = b[i++].toInt() and 0xFF; key = key or ((x and 0x7F).toLong() shl shift); shift += 7; if (x < 0x80) break }
            val field = (key ushr 3).toInt(); val wire = (key and 7).toInt()
            when (wire) {
                0 -> {
                    var v = 0L; shift = 0
                    while (true) { val x = b[i++].toInt() and 0xFF; v = v or ((x and 0x7F).toLong() shl shift); shift += 7; if (x < 0x80) break }
                    on(field, 0, v, i, i)
                }
                1 -> { on(field, 1, 0, i, i + 8); i += 8 }
                5 -> { on(field, 5, 0, i, i + 4); i += 4 }
                2 -> {
                    var len = 0L; shift = 0
                    while (true) { val x = b[i++].toInt() and 0xFF; len = len or ((x and 0x7F).toLong() shl shift); shift += 7; if (x < 0x80) break }
                    val end = i + len.toInt()
                    require(len >= 0 && end <= e) { "bad length" }
                    on(field, 2, 0, i, end); i = end
                }
                else -> throw IllegalArgumentException("wire $wire")
            }
        }
    }
}
