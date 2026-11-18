package com.iris.localai.engine.load

import com.iris.localai.engine.*
import com.iris.localai.engine.resources.*
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * the last load per model + backend (time, cache hit / first compile), kept across app
 * starts in its own SharedPreferences file `iris_load_stats` — no existing settings key is touched.
 */
class LoadStatsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _records = MutableStateFlow(readAll())
    /** Every stored record, keyed by [LoadTelemetry.key]. */
    val records: StateFlow<Map<String, LoadRecord>> = _records.asStateFlow()

    private fun readAll(): Map<String, LoadRecord> = runCatching {
        prefs.all.mapNotNull { (k, v) ->
            (v as? String)?.let { s -> runCatching { k to json.decodeFromString(LoadRecord.serializer(), s) }.getOrNull() }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun put(r: LoadRecord) {
        val k = LoadTelemetry.key(r.modelId, r.backend)
        runCatching { prefs.edit().putString(k, json.encodeToString(LoadRecord.serializer(), r)).apply() }
        _records.value = _records.value + (k to r)
    }

    fun latest(modelId: String): LoadRecord? = LoadTelemetry.latest(_records.value.values, modelId)

    private companion object {
        const val PREFS = "iris_load_stats"
    }
}
