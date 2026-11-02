package com.iris.localai.translate

import android.util.Log
import com.iris.localai.data.SettingsRepository
import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.GenOwner
import com.iris.localai.engine.Requester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** libiris_bergamot.so (bergamot-translator, MPL-2.0). [available] = false when the library is missing or fails to load. */
object BergamotNative {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("iris_bergamot"); true }
            .onFailure { Log.w("IrisTranslate", "libiris_bergamot unavailable: ${it.message}") }
            .getOrDefault(false)
    }

    @JvmStatic external fun nativeLoad(config: String): Long
    @JvmStatic external fun nativeTranslate(handle: Long, text: ByteArray): String?
    @JvmStatic external fun nativeFree(handle: Long)
}

/** One message's translation shown under it. */
data class MessageTranslation(
    val text: String = "",
    val target: TransLang,
    val running: Boolean = true,
    val error: String? = null,
    /** 「显示原文」: the translation is folded away, only the original shows. */
    val folded: Boolean = false,
)

/**
 * 2.3.0 ③ 本地翻译. The loaded model translates with a short dedicated prompt (no tools, no
 * memory or conversation context, thinking off and stripped, streamed); without one, the offline
 * Bergamot engine translates paragraph by paragraph. See [EngineSelect].
 */
class Translator(
    private val engine: EngineManager,
    private val settings: SettingsRepository,
    val packs: TranslatePackStore,
    private val scope: CoroutineScope,
) {
    private val owner = GenOwner("translate")

    private val _messages = MutableStateFlow<Map<String, MessageTranslation>>(emptyMap())
    val messages: StateFlow<Map<String, MessageTranslation>> = _messages.asStateFlow()
    private val jobs = HashMap<String, Job>()

    fun modelReady(): Boolean = engine.state.value is EngineState.Ready

    fun choose(source: TransLang, target: TransLang): EngineChoice = EngineSelect.choose(
        settings.current.translateEngine, modelReady(),
        TranslatePack.of(source, target)?.let { packs.installed(it) } == true,
        BergamotNative.available, source, target,
    )

    /**
     * Streams the translation of [text] (each emission = the whole cleaned text so far).
     * [targetSetting] null = the setting (自动 = 中↔英). Throws [IllegalStateException] with a
     * user-facing message when no engine can translate.
     */
    fun translate(text: String, targetSetting: String? = null): Flow<String> {
        val target = LangDetect.target(text, targetSetting ?: settings.current.translateTarget)
        val source = LangDetect.source(text, target)
        return when (val c = choose(source, target)) {
            is EngineChoice.Unavailable -> flow { throw IllegalStateException(c.message) }
            is EngineChoice.Use -> if (c.kind == TranslateEngineKind.MODEL) viaModel(text, target) else viaOffline(text, source, target)
        }
    }

    fun targetFor(text: String): TransLang = LangDetect.target(text, settings.current.translateTarget)

    private fun viaModel(text: String, target: TransLang): Flow<String> = flow {
        val params = engine.samplingFor(disableThinking = true).copy(
            temperature = 0.2f, topP = 0.9f, topK = 40, minP = 0f, repeatPenalty = 1.05f,
            maxNewTokens = TranslatePrompt.maxTokens(text), contextShift = false,
        )
        val raw = StringBuilder()
        engine.generate(TranslatePrompt.messages(text, target), params, Requester.CHAT, owner).collect { ev ->
            when (ev) {
                is GenEvent.Piece -> { raw.append(ev.text); emit(TranslateOutput.clean(raw.toString(), text)) }
                is GenEvent.Done -> if (ev.error != null && raw.isEmpty()) throw IllegalStateException(ev.error)
            }
        }
    }

    // ---- offline (Bergamot) ----
    private val nativeLock = Mutex()
    private var loadedPack: TranslatePack? = null
    private var handle = 0L

    private suspend fun handleFor(pack: TranslatePack): Long = nativeLock.withLock {
        if (loadedPack == pack && handle != 0L) return handle
        if (handle != 0L) { BergamotNative.nativeFree(handle); handle = 0L; loadedPack = null }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        val h = BergamotNative.nativeLoad(BergamotConfig.yaml(pack, packs.dir(pack), threads))
        if (h == 0L) throw IllegalStateException("离线翻译模型加载失败，可在 设置 › 语音与翻译 删除后重新下载")
        handle = h; loadedPack = pack
        h
    }

    private fun viaOffline(text: String, source: TransLang, target: TransLang): Flow<String> = flow {
        val pack = TranslatePack.of(source, target) ?: throw IllegalStateException(EngineSelect.needPack(source, target))
        val h = handleFor(pack)
        val out = StringBuilder()
        Paragraphs.split(text).forEachIndexed { i, p ->
            if (i > 0) out.append('\n')
            out.append(if (Paragraphs.passThrough(p)) p else BergamotNative.nativeTranslate(h, p.toByteArray()) ?: throw IllegalStateException("离线翻译失败"))
            emit(out.toString().trimEnd())
        }
    }.flowOn(Dispatchers.IO)

    /** Frees the offline model (memory pressure / pack deleted). */
    fun release() {
        scope.launch { nativeLock.withLock { if (handle != 0L) BergamotNative.nativeFree(handle); handle = 0L; loadedPack = null } }
    }

    // ---- message translations (long-press 「翻译」) ----
    fun translateMessage(id: String, text: String) {
        jobs[id]?.cancel()
        val target = targetFor(text)
        _messages.update { it + (id to MessageTranslation(target = target)) }
        jobs[id] = scope.launch {
            try {
                translate(text).collect { t -> _messages.update { m -> m[id]?.let { m + (id to it.copy(text = t)) } ?: m } }
                _messages.update { m -> m[id]?.let { m + (id to it.copy(running = false)) } ?: m }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _messages.update { m -> m[id]?.let { m + (id to it.copy(running = false, error = e.message ?: "翻译失败")) } ?: m }
            }
        }
    }

    fun toggleOriginal(id: String) = _messages.update { m -> m[id]?.let { m + (id to it.copy(folded = !it.folded)) } ?: m }

    fun close(id: String) {
        jobs.remove(id)?.cancel()
        _messages.update { it - id }
    }
}
