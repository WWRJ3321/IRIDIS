package com.iris.localai.server

import com.iris.localai.imagegen.ImageGenRepository
import com.iris.localai.imagegen.RepositoryImageGenBackend
import com.iris.localai.AppContainer
import com.iris.localai.agent.tools.GenerateImageTool

/**
 * [ApiMedia] on the app: 生图 through the same backend as the agent's generate_image (model from
 * 设置 › 智能体 › 生图, chat model unloaded for a large image model when 内存不够先卸 is on), 朗读 through
 * the app's [com.iris.localai.voice.SpeechSynthesizer] with the voice set in 设置 › 语音.
 */
class AppApiMedia(private val context: android.content.Context, private val c: AppContainer) : ApiMedia {
    private val backend by lazy {
        RepositoryImageGenBackend(ImageGenRepository(context), yielder = { c.agent.chatYield })
    }
    private val imageLock = kotlinx.coroutines.sync.Mutex()
    private val speechLock = kotlinx.coroutines.sync.Mutex()

    override fun imageModels(): List<String> = runCatching { backend.runnableModels().map { it.id } }.getOrDefault(emptyList())

    override suspend fun generateImage(p: ApiImageGen, model: String?): Result<ByteArray> = imageLock.withLockCatching {
        val a = c.agent.settings.settings.value
        val m = GenerateImageTool.pickModel(backend.runnableModels(), model ?: a.imageModel, withRef = false)
            ?: throw IllegalStateException("no image model")
        val parsed = GenerateImageTool.Parsed(p.prompt, p.negative, p.width, p.height, p.steps, p.cfg, p.seed, null, 0.75f)
        val req = GenerateImageTool.request(parsed, m, null, a.imageLowMemOn)
        val restore = if (a.imageLowMemOn) runCatching { backend.makeRoomAlways() }.getOrNull()
            else if (a.imageMakeRoom) runCatching { backend.makeRoom(m) }.getOrNull() else null
        try {
            backend.generate(req, m) { _, _ -> }.getOrThrow()
        } catch (e: kotlinx.coroutines.CancellationException) {
            backend.cancel(); throw e
        } finally {
            if (restore != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { backend.releaseNow() }
                kotlinx.coroutines.delay(500)
                runCatching { restore() }
            }
        }
    }

    override fun speechAvailable(): Boolean = runCatching { c.synthesizer.isAvailable }.getOrDefault(false)

    override suspend fun speech(text: String, speed: Float): Result<Pair<FloatArray, Int>> = speechLock.withLockCatching {
        val syn = c.synthesizer
        syn.prepare()
        val parts = ArrayList<FloatArray>()
        syn.speak(text, c.settings.current.ttsSpeakerId, speed) { parts.add(it.copyOf()); true }
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { System.arraycopy(it, 0, out, at, it.size); at += it.size }
        if (out.isEmpty()) throw IllegalStateException("the voice produced no audio")
        out to syn.sampleRate
    }

    private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockCatching(block: suspend () -> T): Result<T> =
        try {
            lock()
            try { Result.success(block()) } finally { unlock() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
