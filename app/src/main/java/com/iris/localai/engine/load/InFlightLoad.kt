package com.iris.localai.engine.load

import com.iris.localai.engine.*
import com.iris.localai.engine.resources.*
import kotlinx.coroutines.CompletableDeferred

/**
 * the model load that is running (or queued) right now, shared by everyone who needs a model.
 *
 * Before, a message sent while model B was loading went through `ensureLoaded()`, which saw
 * "not Ready", fell back to the *last used* model A, queued a load of A behind B and then threw B
 * away — the reply came from the wrong model after two loads. Now every load registers here and
 * `ensureLoaded()` (no explicit model) awaits the in-flight one, whatever model it is, and answers
 * with it. Pure (no Android) so the coordination is unit-tested (v217ux/LoadCoordinationTest).
 */
class InFlightLoad {
    class Entry internal constructor(val key: String) {
        internal val result = CompletableDeferred<Boolean>()
        val isDone: Boolean get() = result.isCompleted
        suspend fun await(): Boolean = result.await()
    }

    private val lock = Any()
    private var current: Entry? = null

    /** The newest load that has not finished yet (null = none). */
    fun current(): Entry? = synchronized(lock) { current?.takeIf { !it.isDone } }

    /** Registers a load of [key]; the newest registration is the one waiters follow. */
    fun begin(key: String): Entry = Entry(key).also { synchronized(lock) { current = it } }

    /** Finishes [e] (true = that model is now loaded); waiters on it resume with [ok]. */
    fun end(e: Entry, ok: Boolean) {
        e.result.complete(ok)
        synchronized(lock) { if (current === e) current = null }
    }

    /**
     * Runs [block] as a registered load of [key]. A throw (including cancellation) counts as
     * "not loaded" for the waiters and is rethrown to the caller.
     */
    suspend fun run(key: String, block: suspend () -> Boolean): Boolean {
        val e = begin(key)
        var ok = false
        try {
            ok = block()
            return ok
        } finally {
            end(e, ok)
        }
    }

    /**
     * Waits for the load in flight (any model), following a newer one started meanwhile.
     * null = nothing was loading; else whether the last awaited load succeeded.
     */
    suspend fun awaitInFlight(): Boolean? {
        var awaited: Boolean? = null
        while (true) {
            val e = current() ?: break
            awaited = e.await()
        }
        return awaited
    }

    /**
     * What `ensureLoaded(null)` should do: [Plan.AwaitInFlight] whenever a load is running (any
     * model — never start another one), else [Plan.Ready] when a model is loaded, else
     * [Plan.LoadFallback] (default / last used model).
     */
    enum class Plan { AwaitInFlight, Ready, LoadFallback }

    fun plan(engineReady: Boolean): Plan = when {
        current() != null -> Plan.AwaitInFlight
        engineReady -> Plan.Ready
        else -> Plan.LoadFallback
    }
}
