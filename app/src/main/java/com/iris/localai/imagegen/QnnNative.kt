package com.iris.localai.imagegen

import androidx.annotation.Keep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * One graph input/output of a QNN context binary, as libiris_imagegen reads it from the binary info.
 * [offset] is QNN's: real = (q + offset) * scale, so the zero point is −offset ([zp]).
 */
data class QnnTensorSpec(
    val name: String,
    val dims: List<Int>,
    val dtype: String,
    val bytes: Long,
    val scale: Float? = null,
    val offset: Int? = null,
) {
    val elements: Int get() = dims.fold(1) { a, b -> a * b }
    val zp: Int? get() = offset?.let { -it }
}

/** A loaded graph: what it takes and returns, and a synchronous run on direct, native-order buffers. */
interface QnnGraph : Closeable {
    val inputs: List<QnnTensorSpec>
    val outputs: List<QnnTensorSpec>
    /** Buffers in [inputs] / [outputs] order; throws on failure. */
    fun execute(inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>)
}

/** Opens context binaries (native: [NativeQnnGraphLoader]; tests: a fake). */
fun interface QnnGraphLoader {
    fun open(bin: File): QnnGraph
    /** 多图文件按名字挑一个（空名 = 第一个）。 */
    fun open(bin: File, graph: String): QnnGraph = open(bin)
    /** 文件里所有图的名字。 */
    fun graphNames(bin: File): List<String> = emptyList()
}

/** Parsed `nativeProbe` result. */
data class QnnProbe(
    val libLoaded: Boolean,
    val backendOk: Boolean = false,
    val deviceOk: Boolean = false,
    val error: String? = null,
    val cdspPresent: Boolean = false,
    val unsignedPd: Boolean = false,
    val htpArch: Int? = null,
) {
    companion object {
        fun parse(json: String): QnnProbe {
            val o = Json.parseToJsonElement(json).jsonObject
            val c = o["cdsp"] as? JsonObject
            return QnnProbe(
                libLoaded = true,
                backendOk = o["backendOk"]?.jsonPrimitive?.booleanOrNull == true,
                deviceOk = o["deviceOk"]?.jsonPrimitive?.booleanOrNull == true,
                error = o["error"]?.jsonPrimitive?.contentOrNull ?: c?.get("error")?.jsonPrimitive?.contentOrNull,
                cdspPresent = c?.get("present")?.jsonPrimitive?.booleanOrNull == true,
                unsignedPd = c?.get("unsignedPd")?.jsonPrimitive?.booleanOrNull == true,
                htpArch = c?.get("arch")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            )
        }
    }
}

/** Parses `nativeInfo` JSON: {graph, inputs:[{name,dims,dtype,bytes,scale?,offset?}], outputs:[…]}. */
object QnnInfoJson {
    fun parse(json: String): Pair<List<QnnTensorSpec>, List<QnnTensorSpec>> {
        val o = Json.parseToJsonElement(json).jsonObject
        fun list(key: String) = o[key]?.jsonArray.orEmpty().map { e ->
            val t = e.jsonObject
            QnnTensorSpec(
                name = t["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                dims = t["dims"]?.jsonArray.orEmpty().map { it.jsonPrimitive.intOrNull ?: 0 },
                dtype = t["dtype"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                bytes = t["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                scale = t["scale"]?.jsonPrimitive?.floatOrNull,
                offset = t["offset"]?.jsonPrimitive?.intOrNull,
            )
        }
        return list("inputs") to list("outputs")
    }
}

/** JNI surface of libiris_imagegen.so (QNN HTP runner). Every call is safe to make once [loaded]. */
@Keep
object QnnNative {
    /** null when the library loaded, else why not. */
    val loadError: String? by lazy {
        try {
            System.loadLibrary("iris_imagegen"); null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }
    val loaded: Boolean get() = loadError == null

    /** Backend/device init + CDSP facts; [QnnProbe.libLoaded] false when the library is missing. */
    fun probe(runtimeDir: File?): QnnProbe {
        if (!loaded) return QnnProbe(libLoaded = false, error = loadError)
        return runCatching { QnnProbe.parse(nativeProbe(runtimeDir?.path ?: "/nonexistent")) }
            .getOrElse { QnnProbe(libLoaded = true, error = it.message) }
    }

    @JvmStatic external fun nativeProbe(runtimeDir: String): String
    @JvmStatic external fun nativeOpen(runtimeDir: String, binPath: String): Long
    @JvmStatic external fun nativeOpenGraph(runtimeDir: String, binPath: String, graph: String): Long
    @JvmStatic external fun nativeGraphNames(runtimeDir: String, binPath: String): String?
    @JvmStatic external fun nativeLastError(): String?
    @JvmStatic external fun nativeInfo(handle: Long): String?
    @JvmStatic external fun nativeExecute(handle: Long, inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>): String?
    @JvmStatic external fun nativeClose(handle: Long)
    /** HTP DCVS v3 vote on the loaded runtime: burst (true) / relax (false); null or why it was not applied. */
    @JvmStatic external fun nativeSetPerf(burst: Boolean): String?
}

/**
 * HTP clock votes around a generation: [burst] before the first graph runs, [relax] after the last.
 * Best effort: implementations never throw (a failed vote only means default clocks).
 */
interface HtpPerf {
    fun burst()
    fun relax()

    companion object {
        val NONE: HtpPerf = object : HtpPerf {
            override fun burst() = Unit
            override fun relax() = Unit
        }
    }
}

/** [HtpPerf] through libiris_imagegen (the vote applies to the process-wide QNN runtime). */
object NativeHtpPerf : HtpPerf {
    /** Why the last vote was not applied (null when it was); for diagnostics. */
    @Volatile var lastError: String? = null; private set

    override fun burst() = vote(true)
    override fun relax() = vote(false)

    private fun vote(burst: Boolean) {
        lastError = if (!QnnNative.loaded) QnnNative.loadError
                    else runCatching { QnnNative.nativeSetPerf(burst) }.getOrElse { it.message ?: it.javaClass.simpleName }
    }
}

/** [QnnGraph] backed by libiris_imagegen. */
class NativeQnnGraph private constructor(private var handle: Long) : QnnGraph {
    override val inputs: List<QnnTensorSpec>
    override val outputs: List<QnnTensorSpec>

    init {
        val (i, o) = QnnInfoJson.parse(QnnNative.nativeInfo(handle) ?: "{}")
        inputs = i; outputs = o
    }

    override fun execute(inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>) {
        check(handle != 0L) { "graph closed" }
        QnnNative.nativeExecute(handle, inputs, outputs)?.let { throw IllegalStateException(it) }
    }

    @Synchronized override fun close() {
        if (handle != 0L) { QnnNative.nativeClose(handle); handle = 0L }
    }

    companion object {
        fun open(runtimeDir: File, bin: File, graph: String = ""): NativeQnnGraph {
            QnnNative.loadError?.let { throw IllegalStateException("无法加载 libiris_imagegen：$it") }
            val h = if (graph.isEmpty()) QnnNative.nativeOpen(runtimeDir.path, bin.path) else QnnNative.nativeOpenGraph(runtimeDir.path, bin.path, graph)
            if (h == 0L) throw IllegalStateException("${bin.name}：${QnnNative.nativeLastError() ?: "QNN 打开失败"}")
            return NativeQnnGraph(h)
        }
    }
}

class NativeQnnGraphLoader(private val runtimeDir: File) : QnnGraphLoader {
    override fun open(bin: File): QnnGraph = NativeQnnGraph.open(runtimeDir, bin)
    override fun open(bin: File, graph: String): QnnGraph = NativeQnnGraph.open(runtimeDir, bin, graph)
    override fun graphNames(bin: File): List<String> {
        QnnNative.loadError?.let { throw IllegalStateException("无法加载 libiris_imagegen：$it") }
        val s = QnnNative.nativeGraphNames(runtimeDir.path, bin.path)
            ?: throw IllegalStateException("${bin.name}：${QnnNative.nativeLastError() ?: "读取失败"}")
        return s.split('\n').filter { it.isNotEmpty() }
    }
}
