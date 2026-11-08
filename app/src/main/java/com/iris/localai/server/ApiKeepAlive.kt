package com.iris.localai.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 2.4.6: non-streaming chat requests (OpenAI `stream:false`, Ollama `stream:false`) used to send nothing
 * until the whole reply was generated. Agent clients with long system prompts + tool lists need tens of
 * seconds of prefill on a phone, and their HTTP client (often a 60 s timeout) gave up with
 * "Request timed out", retried, and timed out again.
 *
 * Now, when the reply is not ready after [FIRST_MS], the 200 head goes out (application/json, no
 * Content-Length, Connection: close) followed by one space every [EVERY_MS]. Leading whitespace is valid
 * JSON, so every JSON parser reads the final body unchanged; short requests are untouched.
 */
object ApiKeepAlive {
    const val FIRST_MS = 8_000L
    const val EVERY_MS = 8_000L

    /** Runs [block]; null when it failed after the head was committed (the error JSON was already written). */
    suspend fun <T> run(res: HttpResponder, block: suspend () -> T): T? = coroutineScope {
        val beat = launch(Dispatchers.IO) {
            delay(FIRST_MS)
            res.startStream("application/json; charset=utf-8")
            while (true) { res.chunk(" "); delay(EVERY_MS) }
        }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            beat.cancelAndJoin()
            if (!res.committed) throw e
            runCatching { res.chunk(errorBody(e.message ?: e.javaClass.simpleName)) }
            null
        } finally {
            beat.cancelAndJoin()
        }
    }

    fun errorBody(message: String): String =
        """{"error":{"message":${HttpServer.jsonString(message)},"type":"server_error"}}"""
}
