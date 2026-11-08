package com.iris.localai.server

import com.iris.localai.data.ModelRepository
import com.iris.localai.data.SettingsRepository
import com.iris.localai.data.sanitized
import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.Requester
import com.iris.localai.engine.StopReason
import com.iris.localai.util.StreamingThinkSplitter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * OpenAI-compatible (and minimal Ollama-compatible) endpoints:
 *
 *   GET  /v1/models, GET /v1/models/{id}
 *   POST /v1/chat/completions      (stream + non-stream, reasoning_content for <think> models)
 *   GET  /api/tags                 (Ollama)
 *   POST /api/chat                 (Ollama, NDJSON stream)
 *   GET  /health
 */
private const val GEN_TAG = "IrisGen"

/** Paths that exist (a wrong method on them is 405, not 404). */
private val KNOWN_PATHS = setOf("/v1/models", "/api/tags", "/v1/chat/completions", "/api/chat")

class ApiRoutes(
    private val engine: EngineManager,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
) {
    suspend fun handle(req: HttpRequest, res: HttpResponder) {
        if (req.method == "OPTIONS") return res.preflight(req.headers)
        if (req.path == "/" || req.path == "/health") {
            return res.json(200, """{"status":"ok","model":${HttpServer.jsonString(engine.readyModel?.fileName ?: "")}}""")
        }
        authorize(req)
        when (req.method to req.path) {
            "GET" to "/v1/models" -> listModels(res, ollama = false)
            "GET" to "/api/tags" -> listModels(res, ollama = true)
            "POST" to "/v1/chat/completions" -> chatCompletions(req, res)
            "POST" to "/api/chat" -> ollamaChat(req, res)
            else -> when {
                req.method == "GET" && req.path.startsWith("/v1/models/") -> getModel(req.path.removePrefix("/v1/models/"), res)
                req.path in KNOWN_PATHS -> throw HttpError(405, "method ${req.method} not allowed on ${req.path}")
                else -> throw HttpError(404, "unknown endpoint ${req.method} ${req.path}")
            }
        }
    }

    private suspend fun getModel(rawId: String, res: HttpResponder) {
        val id = runCatching { java.net.URLDecoder.decode(rawId, "UTF-8") }.getOrDefault(rawId)
        val m = models.refresh().firstOrNull { it.fileName == id }
            ?: throw HttpError(404, "model '$id' not found")
        res.json(200, buildJsonObject {
            put("id", m.fileName); put("object", "model"); put("created", 0); put("owned_by", "iris-localai")
        }.toString())
    }

    private fun authorize(req: HttpRequest) {
        // 2.1.7: custom key; Bearer or x-api-key. Empty key = no auth (browser pages still refused).
        ApiAuth.check(settings.current.apiKey, req.headers)?.let { throw it }
    }

    private suspend fun listModels(res: HttpResponder, ollama: Boolean) {
        val list = models.refresh()
        val body = if (ollama) buildJsonObject {
            putJsonArray("models") {
                list.forEach { m ->
                    add(buildJsonObject {
                        put("name", m.fileName); put("model", m.fileName); put("size", m.sizeBytes)
                        putJsonObject("details") { put("format", m.format.name.lowercase()); put("quantization_level", m.quantization ?: "") }
                    })
                }
            }
        } else buildJsonObject {
            put("object", "list")
            putJsonArray("data") {
                list.forEach { m ->
                    add(buildJsonObject {
                        put("id", m.fileName); put("object", "model"); put("created", 0); put("owned_by", "iris-localai")
                    })
                }
            }
        }
        res.json(200, body.toString())
    }

    private fun parseChat(req: HttpRequest, ollama: Boolean): ChatRequest =
        ChatRequestParser.parse(req.bodyText, ollama, json)

    private suspend fun prepare(cr: ChatRequest) {
        val requested = cr.model?.takeIf { it.isNotBlank() && it != "default" }
        models.refresh()
        val match = requested?.let { name ->
            models.models.value.firstOrNull { it.fileName == name || it.displayName.equals(name, true) || it.fileName.substringBeforeLast('.') == name }
        }
        if (requested != null && match == null && engine.readyModel == null) {
            throw HttpError(404, "model '$requested' not found; GET /v1/models lists installed models")
        }
        // 2.0.0: never swap models under a running generation (load() would cancel the in-app chat).
        if (ApiPolicy.modelSwitchBlocked(engine.busy.value != null, match?.fileName, engine.readyModel?.fileName)) {
            throw HttpError(503, "model '${match?.fileName}' is not loaded and the engine is busy with another request " +
                "(in-app chat or another client); retry later or use the loaded model '${engine.readyModel?.fileName ?: "-"}'")
        }
        if (!engine.ensureLoaded(match?.fileName)) {
            throw HttpError(503, engine.state.value.let { (it as? com.iris.localai.engine.EngineState.Failed)?.message }
                ?: "no model loaded; load one in the app or pass an installed model id")
        }
    }

    /**
     * The loaded model's own settings (over the global defaults), then the client's values on top,
     * through the same clamps as the settings screen (NaN-safe) — except max_tokens, where small
     * values (e.g. 1 for a probe) are legitimate. Threads / cores follow the resource tier, like chat.
     */
    private fun sampling(cr: ChatRequest, forceNoThinking: Boolean = false): com.iris.localai.engine.SamplingConfig {
        val base = engine.effective()
        val clamped = settings.current.copy(
            temperature = cr.temperature ?: base.temperature,
            topP = cr.topP ?: base.topP,
            topK = cr.topK ?: base.topK,
            minP = cr.minP ?: base.minP,
            repeatPenalty = cr.repeatPenalty ?: base.repeatPenalty,
        ).sanitized()
        val cfg = base.copy(
            temperature = clamped.temperature, topP = clamped.topP, topK = clamped.topK,
            minP = clamped.minP, repeatPenalty = clamped.repeatPenalty,
            maxNewTokens = (cr.maxTokens ?: base.maxNewTokens).coerceIn(1, 32768),
            presencePenalty = (cr.presencePenalty ?: base.presencePenalty).coerceIn(-2f, 2f),
            frequencyPenalty = (cr.frequencyPenalty ?: base.frequencyPenalty).coerceIn(-2f, 2f),
            seed = (cr.seed ?: base.seed).coerceAtLeast(-1),
        )
        return engine.samplingFor(disableThinking = forceNoThinking || cr.thinking == false, cfg = cfg)
    }

    /** Answer / reasoning of a non-streamed generation. */
    private class Collected(val answer: String, val reasoning: String, val done: GenEvent.Done, val stopped: Boolean = false)

    /**
     * 2.0.0: a client stop sequence matched: the generation was ended by cancelling its collection,
     * so the engine's own Done (and its stats) never arrives. Pieces stand in for the token count.
     */
    private fun stoppedDone(done: GenEvent.Done?, pieces: Int): GenEvent.Done = when {
        done == null -> GenEvent.Done(StopReason.END, com.iris.localai.data.GenerationStats(generatedTokens = pieces))
        done.reason == StopReason.ERROR -> done
        else -> done.copy(reason = StopReason.END) // finish_reason "stop", not "length"
    }

    private suspend fun collectAll(
        flow: kotlinx.coroutines.flow.Flow<GenEvent>,
        splitter: StreamingThinkSplitter,
        stop: StopSequenceFilter,
    ): Collected {
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var done: GenEvent.Done? = null
        var pieces = 0
        fun add(ps: List<StreamingThinkSplitter.Piece>) = ps.forEach { if (it.thinking) reasoning.append(it.text) else answer.append(stop.push(it.text)) }
        // transformWhile: a matched stop sequence ends the collection, which stops the native generation.
        flow.transformWhile<GenEvent, Unit> { ev ->
            when (ev) {
                is GenEvent.Piece -> { pieces++; add(splitter.push(ev.text)) }
                is GenEvent.Done -> { add(splitter.flush()); answer.append(stop.flush()); done = ev }
            }
            !stop.hit
        }.collect()
        if (stop.hit) return Collected(answer.toString().trim(), reasoning.toString().trim(), stoppedDone(done, pieces), stopped = true)
        val d = done ?: throw HttpError(500, "generation ended unexpectedly")
        if (d.reason == StopReason.ERROR) throw HttpError(500, d.error ?: "generation failed")
        return Collected(answer.toString().trim(), reasoning.toString().trim(), d)
    }

    /** Both passes' token counts in one Done (usage / eval_count). */
    private fun merged(first: GenEvent.Done, second: GenEvent.Done): GenEvent.Done {
        val a = first.stats; val b = second.stats
        val stats = if (a == null) b else if (b == null) a else a.copy(
            generatedTokens = a.generatedTokens + b.generatedTokens, generateMs = a.generateMs + b.generateMs,
        )
        return second.copy(stats = stats)
    }

    /**
     * 1.7.8: non-streamed generation with the think-only rule ([com.iris.localai.util.ApiThinkOnly]):
     * reasoning but no answer → once more with thinking off; still nothing → explicit 502 error.
     */
    private suspend fun collectWithContinuation(cr: ChatRequest): Collected {
        val params = sampling(cr)
        val first = collectAll(engine.generate(history(cr), params, Requester.API), newSplitter(cr), StopSequenceFilter(cr.stop))
        if (first.stopped) return first
        val d1 = com.iris.localai.util.ApiThinkOnly.afterFirst(cr.thinking == false, first.reasoning, first.answer, first.done.reason, first.done.error, params.maxNewTokens)
        when (d1.step) {
            com.iris.localai.util.ApiThinkOnly.Step.DONE -> return first
            com.iris.localai.util.ApiThinkOnly.Step.FAIL -> throw HttpError(502, d1.message ?: com.iris.localai.util.ThinkOnlyPolicy.THINK_ONLY)
            com.iris.localai.util.ApiThinkOnly.Step.CONTINUE -> Unit
        }
        android.util.Log.i(GEN_TAG, "API think-only reply (${first.done.reason}) → continuing once with thinking off")
        val second = collectAll(engine.generate(history(cr), sampling(cr, forceNoThinking = true), Requester.API), StreamingThinkSplitter(false), StopSequenceFilter(cr.stop))
        val d2 = com.iris.localai.util.ApiThinkOnly.afterContinuation(first.reasoning, second.answer, second.done.reason, second.done.error, params.maxNewTokens)
        if (d2.step == com.iris.localai.util.ApiThinkOnly.Step.FAIL) throw HttpError(502, d2.message ?: com.iris.localai.util.ThinkOnlyPolicy.THINK_ONLY)
        return Collected(second.answer, first.reasoning, merged(first.done, second.done))
    }

    private fun newSplitter(cr: ChatRequest) =
        StreamingThinkSplitter(startInThinking = engine.readyDetails?.thinkingPrefilled == true && cr.thinking != false)

    /**
     * 1.7.8: streamed generation with the same rule. [onPiece] gets every delta (reasoning only from
     * the first pass); returns the final Done (both passes merged) and, when the reply must fail,
     * the message to send as the stream's error.
     */
    private suspend fun streamWithContinuation(
        cr: ChatRequest,
        onPiece: (StreamingThinkSplitter.Piece) -> Unit,
    ): Pair<GenEvent.Done, String?> {
        val params = sampling(cr)
        val answer = StringBuilder(); val reasoning = StringBuilder()
        suspend fun pass(
            p: com.iris.localai.engine.SamplingConfig,
            splitter: StreamingThinkSplitter,
            answerOnly: Boolean,
            stop: StopSequenceFilter,
        ): GenEvent.Done {
            var done: GenEvent.Done? = null
            var n = 0
            fun answerText(t: String) {
                if (t.isEmpty()) return
                answer.append(t)
                onPiece(StreamingThinkSplitter.Piece(false, t))
            }
            fun out(pieces: List<StreamingThinkSplitter.Piece>) = pieces.forEach { pc ->
                if (pc.thinking) {
                    if (answerOnly) return@forEach
                    reasoning.append(pc.text)
                    onPiece(pc)
                } else answerText(stop.push(pc.text))
            }
            // transformWhile: a matched stop sequence ends the collection, which stops the native generation.
            engine.generate(history(cr), p, Requester.API).transformWhile<GenEvent, Unit> { ev ->
                when (ev) {
                    is GenEvent.Piece -> { n++; out(splitter.push(ev.text)) }
                    is GenEvent.Done -> { out(splitter.flush()); answerText(stop.flush()); done = ev }
                }
                !stop.hit
            }.collect()
            if (stop.hit) return stoppedDone(done, n)
            return done ?: GenEvent.Done(StopReason.ERROR, null, "generation ended unexpectedly")
        }
        val firstStop = StopSequenceFilter(cr.stop)
        val first = pass(params, newSplitter(cr), answerOnly = false, stop = firstStop)
        if (first.reason == StopReason.ERROR) return first to null
        if (firstStop.hit) return first to null // the client's stop sequence ended the reply: no think-only continuation
        val d1 = com.iris.localai.util.ApiThinkOnly.afterFirst(cr.thinking == false, reasoning.toString(), answer.toString(), first.reason, first.error, params.maxNewTokens)
        return when (d1.step) {
            com.iris.localai.util.ApiThinkOnly.Step.DONE -> first to null
            com.iris.localai.util.ApiThinkOnly.Step.FAIL -> first to d1.message
            com.iris.localai.util.ApiThinkOnly.Step.CONTINUE -> {
                android.util.Log.i(GEN_TAG, "API think-only reply (${first.reason}) → continuing once with thinking off")
                val second = pass(sampling(cr, forceNoThinking = true), StreamingThinkSplitter(false), answerOnly = true, stop = StopSequenceFilter(cr.stop))
                if (second.reason == StopReason.ERROR) return merged(first, second) to null
                val d2 = com.iris.localai.util.ApiThinkOnly.afterContinuation(reasoning.toString(), answer.toString(), second.reason, second.error, params.maxNewTokens)
                merged(first, second) to d2.message.takeIf { d2.step == com.iris.localai.util.ApiThinkOnly.Step.FAIL }
            }
        }
    }

    private fun history(cr: ChatRequest): List<PromptMessage> {
        val sys = engine.effective().systemPrompt
        return if (sys.isNotBlank() && cr.messages.none { it.role == PromptMessage.SYSTEM }) {
            listOf(PromptMessage(PromptMessage.SYSTEM, sys)) + cr.messages
        } else cr.messages
    }

    private fun finishReason(r: StopReason) = when (r) {
        StopReason.MAX_TOKENS, StopReason.CONTEXT_FULL -> "length"
        else -> "stop"
    }

    private suspend fun chatCompletions(req: HttpRequest, res: HttpResponder) {
        val cr = parseChat(req, ollama = false)
        prepare(cr)
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000
        val modelId = engine.readyModel?.fileName ?: "local"

        if (cr.stream) {
            res.startStream("text/event-stream; charset=utf-8")
            fun chunk(delta: JsonObject, finish: String? = null) = buildJsonObject {
                put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", modelId)
                putJsonArray("choices") {
                    add(buildJsonObject {
                        put("index", 0); put("delta", delta)
                        if (finish != null) put("finish_reason", finish) else put("finish_reason", JsonNull)
                    })
                }
            }
            // Hot path (one event per token): the constant envelope is built once; output is
            // byte-identical to chunk(delta).
            val deltaPrefix = "data: {\"id\":${HttpServer.jsonString(id)},\"object\":\"chat.completion.chunk\"," +
                "\"created\":$created,\"model\":${HttpServer.jsonString(modelId)},\"choices\":[{\"index\":0,\"delta\":{"
            fun deltas(pieces: List<StreamingThinkSplitter.Piece>) = pieces.forEach { p ->
                val key = if (p.thinking) "reasoning_content" else "content"
                res.chunk(deltaPrefix + "\"" + key + "\":" + HttpServer.jsonString(p.text) + "},\"finish_reason\":null}]}\n\n")
            }
            res.chunk("data: ${chunk(buildJsonObject { put("role", "assistant"); put("content", "") })}\n\n")
            val (ev, fail) = streamWithContinuation(cr) { p -> deltas(listOf(p)) }
            if (ev.reason == StopReason.ERROR || fail != null) {
                res.chunk("data: ${HttpServer.errorJson(fail ?: ev.error ?: "generation failed", 500)}\n\n")
            } else {
                res.chunk("data: ${chunk(buildJsonObject {}, finishReason(ev.reason))}\n\n")
                if (cr.includeUsage) {
                    res.chunk("data: " + buildJsonObject {
                        put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", modelId)
                        put("choices", JsonArray(emptyList())); put("usage", usage(ev))
                    } + "\n\n")
                }
            }
            res.chunk("data: [DONE]\n\n")
        } else {
            val c = ApiKeepAlive.run(res) { collectWithContinuation(cr) } ?: return
            val d = c.done
            val body = buildJsonObject {
                put("id", id); put("object", "chat.completion"); put("created", created); put("model", modelId)
                putJsonArray("choices") {
                    add(buildJsonObject {
                        put("index", 0)
                        putJsonObject("message") {
                            put("role", "assistant"); put("content", c.answer)
                            if (c.reasoning.isNotEmpty()) put("reasoning_content", c.reasoning)
                        }
                        put("finish_reason", finishReason(d.reason))
                    })
                }
                put("usage", usage(d))
            }
            res.finishJson(200, body.toString())
        }
    }

    private fun usage(d: GenEvent.Done) = buildJsonObject {
        val p = d.stats?.promptTokens ?: 0
        val c = d.stats?.generatedTokens ?: 0
        put("prompt_tokens", p); put("completion_tokens", c); put("total_tokens", p + c)
    }

    private suspend fun ollamaChat(req: HttpRequest, res: HttpResponder) {
        val cr = parseChat(req, ollama = true)
        prepare(cr)
        val modelId = engine.readyModel?.fileName ?: "local"
        fun line(content: String, thinking: String, done: Boolean, ev: GenEvent.Done? = null) = buildJsonObject {
            put("model", modelId)
            put("created_at", java.time.Instant.now().toString())
            putJsonObject("message") {
                put("role", "assistant"); put("content", content)
                if (thinking.isNotEmpty()) put("thinking", thinking)
            }
            put("done", done)
            if (ev != null) {
                put("done_reason", finishReason(ev.reason))
                put("prompt_eval_count", ev.stats?.promptTokens ?: 0)
                put("eval_count", ev.stats?.generatedTokens ?: 0)
                put("eval_duration", (ev.stats?.generateMs ?: 0) * 1_000_000)
            }
        }.toString()

        fun pieceLine(p: StreamingThinkSplitter.Piece) =
            line(if (p.thinking) "" else p.text, if (p.thinking) p.text else "", false) + "\n"

        if (cr.stream) {
            res.startStream("application/x-ndjson")
            val (ev, fail) = streamWithContinuation(cr) { p -> res.chunk(pieceLine(p)) }
            if (ev.reason == StopReason.ERROR || fail != null) res.chunk("""{"error":${HttpServer.jsonString(fail ?: ev.error ?: "generation failed")}}""" + "\n")
            else res.chunk(line("", "", true, ev) + "\n")
        } else {
            val c = ApiKeepAlive.run(res) { collectWithContinuation(cr) } ?: return
            res.finishJson(200, line(c.answer, c.reasoning, true, c.done))
        }
    }
}
