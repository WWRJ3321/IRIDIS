package com.iris.localai.engine.edge0

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.GenerationStats
import com.iris.localai.data.LocalModel
import com.iris.localai.engine.Backend
import com.iris.localai.engine.EngineException
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.InferenceEngine
import com.iris.localai.engine.LoadConfig
import com.iris.localai.engine.ModelDetails
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.engine.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 实验: Edge0-35B through edge0-infer (libiris_edge0.so, CPU only). One runtime per process
 * (the engine's rule), so the native handle is shared and only models/sessions come and go.
 * Not tested on a real phone yet.
 */
class Edge0Engine : InferenceEngine {
    private val lock = Mutex()
    @Volatile private var loaded = false

    override suspend fun load(model: LocalModel, config: LoadConfig, onProgress: (Float) -> Unit): ModelDetails = withContext(Dispatchers.IO) {
        if (!Edge0Support.deviceOk()) throw EngineException(I18n.s(R.string.e265_unsupported))
        if (!Edge0Native.ensureLoaded()) throw EngineException(I18n.s(R.string.e265_lib_missing))
        lock.withLock {
            onProgress(0.05f)
            val h = handle()
            val info = runCatching { Json.parseToJsonElement(Edge0Native.nativeInspect(model.path)).jsonObject }.getOrNull()
            info?.get("error")?.jsonPrimitive?.content?.let { throw EngineException(I18n.s(R.string.e265_bad_assets, it)) }
            val desc = try {
                Edge0Native.nativeLoad(h, model.path, config.contextLength.toLong(), config.threads)
            } catch (e: Throwable) {
                throw EngineException(I18n.s(R.string.e265_load_failed, e.message ?: e.javaClass.simpleName), e)
            }
            val d = Json.parseToJsonElement(desc).jsonObject
            loaded = true
            onProgress(1f)
            val ctx = d.long("context").toInt().takeIf { it > 0 } ?: config.contextLength
            ModelDetails(
                description = "Edge0-35B (${I18n.s(R.string.e265_experimental)})",
                parameterCount = 35_000_000_000L,
                contextLength = ctx,
                trainContextLength = d.long("context_limit").toInt().takeIf { it > 0 } ?: ctx,
                supportsThinking = d.bool("thinking"),
                hasChatTemplate = true,
                backend = "CPU · edge0-infer",
                activeBackend = Backend.CPU,
            )
        }
    }

    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = callbackFlow {
        if (!loaded) { send(GenEvent.Done(StopReason.ERROR, null, I18n.s(R.string.e265_not_loaded))); close(); return@callbackFlow }
        val job = launch(Dispatchers.IO) {
            lock.withLock {
                val stream = Edge0Prompt.Stream()
                val t0 = System.currentTimeMillis()
                var first = 0L
                val result = runCatching {
                    Edge0Native.nativeGenerate(
                        handle(), Edge0Prompt.inputJson(history), !params.disableThinking, params.maxNewTokens,
                        params.temperature.toDouble(), params.topP.toDouble(), params.topK,
                        // a real class (not a lambda) so R8 keeps onEvent, which native code calls by name
                        object : Edge0Native.Events {
                            override fun onEvent(kind: Int, index: Int, text: ByteArray) {
                                val piece = stream.map(kind, String(text, Charsets.UTF_8))
                                if (piece.isNotEmpty()) {
                                    if (first == 0L) first = System.currentTimeMillis()
                                    trySend(GenEvent.Piece(piece))
                                }
                            }
                        },
                    )
                }
                stream.finish().takeIf { it.isNotEmpty() }?.let { trySend(GenEvent.Piece(it)) }
                val done = result.fold(
                    onSuccess = { json ->
                        val o = Json.parseToJsonElement(json).jsonObject
                        val end = System.currentTimeMillis()
                        val prefill = if (first > 0) first - t0 else end - t0
                        val stats = GenerationStats(
                            promptTokens = o.long("input").toInt(), reusedTokens = o.long("reused").toInt(),
                            generatedTokens = o.long("generated").toInt(), prefillMs = prefill,
                            generateMs = (end - t0 - prefill).coerceAtLeast(0),
                        )
                        GenEvent.Done(Edge0Prompt.stop(o["stop"]?.jsonPrimitive?.content), stats, o["error"]?.jsonPrimitive?.content)
                    },
                    onFailure = { GenEvent.Done(StopReason.ERROR, null, it.message) },
                )
                trySend(done)
            }
            close()
        }
        awaitClose { if (job.isActive) this@Edge0Engine.cancel() }
    }

    override fun cancel() { native?.let { Edge0Native.nativeCancel(it) } }

    override suspend fun resetContext() = withContext(Dispatchers.IO) {
        lock.withLock { if (loaded) runCatching { Edge0Native.nativeReset(handle()) } }
        Unit
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        cancel()
        lock.withLock { if (loaded) { runCatching { Edge0Native.nativeUnload(handle()) }; loaded = false } }
        Unit
    }

    private fun JsonObject.long(k: String): Long = this[k]?.jsonPrimitive?.longOrNull ?: this[k]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
    private fun JsonObject.bool(k: String): Boolean = this[k]?.jsonPrimitive?.booleanOrNull ?: false

    companion object {
        @Volatile private var native: Long? = null
        @Synchronized private fun handle(): Long = native ?: Edge0Native.nativeCreate().also { native = it }
    }
}
