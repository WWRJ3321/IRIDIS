package com.iris.localai.engine.load

import com.iris.localai.engine.*
import com.iris.localai.engine.resources.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * makes "a request" and "a tier reload" mutually exclusive as whole operations.
 *
 * Before 1.7.7 the tier reload only looked at EngineManager's busy flag, which a request sets
 * once it holds the generation lock. A chat request spends time before that (ensureLoaded, context
 * planning, summary) — if the debounced reload fired in that window it unloaded the engine under
 * the already-accepted request. When that reload did not end with a loaded model (cancelled by
 * another tier change → Idle, or failed → Failed) the queued request found `engine == null` and
 * ended as "模型未加载": the first message after a tier change failed, the next one reloaded and
 * worked.
 *
 * Now:
 *  - a request is registered from its very start ([request]) until it ends;
 *  - a reload may only begin when no request is registered ([tryBeginReload]); otherwise the
 *    scheduler defers it and runs it when the last request leaves ([onIdle]);
 *  - a request that arrives while a reload runs waits for it ([waiting] > 0 → UI shows
 *    「正在重载模型…」) and then starts by itself — never dropped, never failed for the race.
 *
 * Pure (no Android), unit-tested in V177Test.
 */
class ReloadGate {
    private val lock = Any()
    private var inFlight = 0
    private val _reloading = MutableStateFlow(false)
    /** True while a tier reload owns the engine. */
    val reloading: StateFlow<Boolean> = _reloading.asStateFlow()
    private val _waiting = MutableStateFlow(0)
    /** Requests currently queued behind a reload. */
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    /** Called (outside the lock) when the last registered request ends. */
    @Volatile var onIdle: () -> Unit = {}

    val requests: Int get() = synchronized(lock) { inFlight }

    /** True = the caller may reload now (it must call [endReload]); false = a request is registered. */
    fun tryBeginReload(): Boolean = synchronized(lock) {
        if (inFlight > 0 || _reloading.value) false else { _reloading.value = true; true }
    }

    fun endReload() {
        synchronized(lock) { _reloading.value = false }
    }

    /**
     * Runs [block] as one request. Waits first while a reload runs; [onWaiting] is told when the
     * wait starts (true) and ends (false). Nested calls (a request that generates) are fine.
     */
    suspend fun <T> request(onWaiting: (Boolean) -> Unit = {}, block: suspend () -> T): T {
        synchronized(lock) { inFlight++ }
        try {
            if (_reloading.value) {
                _waiting.update { it + 1 }
                onWaiting(true)
                try {
                    _reloading.first { !it }
                } finally {
                    _waiting.update { it - 1 }
                    onWaiting(false)
                }
            }
            return block()
        } finally {
            val idle = synchronized(lock) { --inFlight == 0 }
            if (idle) onIdle()
        }
    }
}
