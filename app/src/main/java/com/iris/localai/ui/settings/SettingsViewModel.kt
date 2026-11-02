package com.iris.localai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.data.AppSettings
import com.iris.localai.server.ApiService
import com.iris.localai.voice.PcmPlayer
import com.iris.localai.voice.VoicePack
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.SecureRandom

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IrisApp).container
    val settings = c.settings.settings
    val voicePacks = c.voiceStore.states
    val apiStatus = ApiService.status
    val engine = c.engine.state
    val performanceCores: Int get() = c.engine.threadPolicy.performanceCores
    val autoThreads: Int get() = c.engine.threadPolicy.nominal(c.engine.tier).threads
    // ---- 1.7.8: 资源档配置 ----
    val topology: com.iris.localai.engine.CpuTopology get() = c.engine.threadPolicy.topology
    /** Profile set of the backend the loaded model really runs on (CPU when nothing is loaded). */
    val activeKind: com.iris.localai.engine.ResourceBackendKind
        get() = com.iris.localai.engine.ResourceBackendKind.of(c.engine.readyDetails?.activeBackend)
    fun profile(kind: com.iris.localai.engine.ResourceBackendKind, tier: com.iris.localai.engine.ResourceTier) =
        com.iris.localai.engine.TierProfiles.resolve(c.settings.current.tierProfiles, kind, tier, topology)
    fun defaultProfile(kind: com.iris.localai.engine.ResourceBackendKind, tier: com.iris.localai.engine.ResourceTier) =
        com.iris.localai.engine.TierProfiles.defaults(kind, tier, topology)
    fun validate(p: com.iris.localai.data.TierProfile) = com.iris.localai.engine.TierProfiles.validate(p, topology)
    /**
     * Saves [p] when it is valid (returns the problems otherwise, nothing saved). A profile equal to
     * the default is stored as "no entry", so a later default change still reaches it. Editing the
     * tier in use is picked up by EngineManager (live where possible, else the debounced reload).
     */
    fun saveProfile(kind: com.iris.localai.engine.ResourceBackendKind, tier: com.iris.localai.engine.ResourceTier, p: com.iris.localai.data.TierProfile): List<String> =
        com.iris.localai.engine.TierProfiles.store(c.settings, kind, tier, p, topology)
    fun resetProfile(kind: com.iris.localai.engine.ResourceBackendKind, tier: com.iris.localai.engine.ResourceTier) = update {
        it.copy(tierProfiles = it.tierProfiles - com.iris.localai.engine.TierProfiles.key(kind, tier))
    }
    fun resetProfiles(kind: com.iris.localai.engine.ResourceBackendKind) = update { s ->
        s.copy(tierProfiles = s.tierProfiles.filterKeys { !it.startsWith(kind.name + ".") })
    }

    /** 1.7.5: what the last tier change really did (status line under the bar). */
    val tierApply = c.engine.tierApply
    /** 1.7.6: last reply by tier segment, for 资源诊断. */
    val telemetryReport = c.engine.telemetryReport
    fun tierPlan(t: com.iris.localai.engine.ResourceTier, kind: com.iris.localai.engine.ResourceBackendKind = com.iris.localai.engine.ResourceBackendKind.CPU) =
        c.engine.threadPolicy.nominal(t, kind)
    fun tierThreads(tier: Int): Int = c.engine.threadPolicy.nominal(com.iris.localai.engine.ResourceTier.of(tier)).threads
    fun tierBatch(tier: Int): Int = c.engine.threadPolicy.nominal(com.iris.localai.engine.ResourceTier.of(tier)).batch
    fun setDefaultBackend(format: com.iris.localai.data.ModelFormat, backend: String?) = update { s ->
        s.copy(defaultBackends = if (backend == null) s.defaultBackends - format.name else s.defaultBackends + (format.name to backend))
    }

    // ---- NPU runtime (full flavour only) ----
    val npu = com.iris.localai.engine.EngineFactory.npu(app)
    private var npuJob: Job? = null
    fun installNpu() {
        val n = npu ?: return
        if (npuJob?.isActive == true) return
        npuJob = c.scope.launch { n.install() }
    }
    fun cancelNpu() { npuJob?.cancel(); npuJob = null }
    fun deleteNpu() { cancelNpu(); npu?.delete() }

    // ---- 2.2.0: GenieX runtime (full flavour only) ----
    val geniex = com.iris.localai.engine.EngineFactory.geniex(app)
    private var geniexJob: Job? = null
    fun installGeniex() {
        val g = geniex ?: return
        if (geniexJob?.isActive == true) return
        geniexJob = c.scope.launch { g.install() }
    }
    fun cancelGeniex() { geniexJob?.cancel(); geniexJob = null }
    fun deleteGeniex() { cancelGeniex(); geniex?.delete() }
    fun setGgufEngine(engine: String) = update { it.copy(ggufEngine = engine) }

    // ---- speed test ----
    sealed interface Bench {
        data object Idle : Bench
        data class Running(val done: Int, val total: Int) : Bench
        data class Done(val rows: List<com.iris.localai.engine.BenchResult>, val backend: String) : Bench
        data class Failed(val message: String) : Bench
    }
    private val _bench = kotlinx.coroutines.flow.MutableStateFlow<Bench>(Bench.Idle)
    val bench: kotlinx.coroutines.flow.StateFlow<Bench> = _bench
    private var benchJob: Job? = null

    fun runBenchmark() {
        if (benchJob?.isActive == true) return
        benchJob = c.scope.launch {
            try {
                val ready = c.engine.ensureLoaded()
                val details = c.engine.readyDetails
                if (!ready || details == null) { _bench.value = Bench.Failed("请先加载一个模型"); return@launch }
                val llamaCpu = details.backend.contains("llama.cpp") && details.backend.startsWith("CPU")
                val counts = if (llamaCpu) {
                    val pc = c.engine.threadPolicy.performanceCores
                    listOf(4, 5, 6, 7, 8).filter { it <= pc.coerceAtLeast(4) }
                } else listOf(0)
                val rows = c.engine.benchmark(counts) { d, t -> _bench.value = Bench.Running(d, t) }
                _bench.value = Bench.Done(rows, details.backend)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) { _bench.value = Bench.Idle; throw t }
                _bench.value = Bench.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }
    fun cancelBenchmark() { benchJob?.cancel(); c.engine.cancelBenchmark(); _bench.value = Bench.Idle }
    fun dismissBenchmark() { if (benchJob?.isActive != true) _bench.value = Bench.Idle }
    fun applyThreads(n: Int) { update { it.copy(threads = n) }; dismissBenchmark() }

    private val downloads = HashMap<VoicePack, Job>()
    private var previewJob: Job? = null
    @Volatile private var previewPlayer: PcmPlayer? = null

    /** 试听 state for the settings row. */
    sealed interface Preview {
        data object Idle : Preview
        data object Loading : Preview
        data object Playing : Preview
        data class Failed(val message: String) : Preview
    }
    private val _preview = kotlinx.coroutines.flow.MutableStateFlow<Preview>(Preview.Idle)
    val preview: kotlinx.coroutines.flow.StateFlow<Preview> = _preview

    fun update(block: (AppSettings) -> AppSettings) = c.settings.update(block)
    fun resetGeneration() = c.settings.resetGeneration()
    fun resetLoadOptions() = c.settings.resetLoadOptions()
    fun resetContext() = c.settings.resetContext()
    fun reloadModel() = viewModelScope.launch { c.engine.reload() }

    /** 2.1.7 「默认模型」: installed models for the picker; "" = 上次使用的模型. */
    val installedModels = c.models.models
    fun refreshModels() = viewModelScope.launch { c.models.refresh() }
    fun setDefaultModel(fileName: String) { update { it.copy(defaultModelFile = fileName) } }
    /** 2.1.7 「识图清晰度」 (px; takes effect with the next picture, no reload). */
    fun setVisionSide(side: Int) { update { it.copy(visionSide = com.iris.localai.util.VisionQuality.of(side).side) } }

    fun deleteAllConversations() = viewModelScope.launch { c.conversations.deleteAll() }

    // ---- voice packs ----
    fun packBytes(pack: VoicePack) = c.voiceStore.totalBytes(pack)

    fun download(pack: VoicePack) {
        if (downloads[pack]?.isActive == true) return
        downloads[pack] = c.scope.launch { runCatching { c.voiceStore.download(pack, c.settings.current.downloadSource) } }
    }

    fun cancelDownload(pack: VoicePack) { downloads.remove(pack)?.cancel() }

    fun usePack(pack: VoicePack) = update {
        if (pack.kind == com.iris.localai.voice.VoiceKind.ASR) it.copy(asrPack = pack.id) else it.copy(ttsPack = pack.id)
    }
    fun activePack(kind: com.iris.localai.voice.VoiceKind): VoicePack? =
        c.voiceStore.active(kind, if (kind == com.iris.localai.voice.VoiceKind.ASR) c.settings.current.asrPack else c.settings.current.ttsPack)

    // ---- 2.3.0 ③ offline translation packs ----
    val translatePacks get() = c.translator.packs.states
    private val trDownloads = HashMap<com.iris.localai.translate.TranslatePack, Job>()
    fun downloadTranslate(pack: com.iris.localai.translate.TranslatePack) {
        if (trDownloads[pack]?.isActive == true) return
        trDownloads[pack] = c.scope.launch { runCatching { c.translator.packs.download(pack, c.settings.current.downloadSource) } }
    }
    fun cancelTranslate(pack: com.iris.localai.translate.TranslatePack) { trDownloads.remove(pack)?.cancel() }
    fun deleteTranslate(pack: com.iris.localai.translate.TranslatePack) { c.translator.release(); c.translator.packs.delete(pack) }
    val offlineEngineAvailable: Boolean get() = com.iris.localai.translate.BergamotNative.available

    fun deletePack(pack: VoicePack) = viewModelScope.launch {
        if (pack.kind == com.iris.localai.voice.VoiceKind.ASR) c.recognizer.release() else c.synthesizer.release()
        c.voiceStore.delete(pack)
    }

    /**
     * Plays a sample sentence with the current voice and speed; tapping again stops it.
     * Before 1.5.0 this crashed the app when the voice pack was incomplete (the exception escaped
     * viewModelScope), gave no feedback while the model loaded, kept synthesising after a stop,
     * and cut off the end of the sentence by releasing the player while audio was still queued.
     */
    fun previewVoice(clone: com.iris.localai.voice.ClonedVoice? = null) {
        if (previewJob?.isActive == true) {
            val same = previewTarget.value == (clone?.id ?: "")
            stopPreview()
            if (same) return
        }
        previewTarget.value = clone?.id ?: ""
        if (clone == null && !c.synthesizer.isAvailable) { _preview.value = Preview.Failed(if (c.synthesizer.systemSelected()) "没有可用的系统 TTS 引擎" else "请先下载并选用一个朗读模型"); return }
        // Stops a running voice conversation or read-aloud instead of talking over it.
        c.synthesizer.claim(this) { stopPreview() }
        previewJob = viewModelScope.launch {
            var player: PcmPlayer? = null
            try {
                _preview.value = Preview.Loading
                c.synthesizer.prepare(clone)
                val out = PcmPlayer(c.synthesizer.sampleRate).also { previewPlayer = it }
                player = out
                val s = c.settings.current
                val text = if (c.synthesizer.usingSystem || c.synthesizer.speakingWithClone || c.synthesizer.activePack()?.singleSpeaker != false || s.ttsSpeakerId >= 3)
                    "你好，我是 IRIDIS，一个完全运行在你手机上的智能助手。" else "Hi, I'm IRIDIS, an assistant that runs entirely on your phone."
                val job = kotlin.coroutines.coroutineContext[Job]
                c.synthesizer.speak(text, s.ttsSpeakerId, s.ttsSpeed) { chunk ->
                    if (_preview.value != Preview.Playing) _preview.value = Preview.Playing
                    job?.isActive == true && out.write(chunk)
                }
                out.drain()
                _preview.value = Preview.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                _preview.value = Preview.Idle
                throw e
            } catch (t: Throwable) {
                _preview.value = Preview.Failed("试听失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                player?.release()
                if (previewPlayer === player) previewPlayer = null
                c.synthesizer.unclaim(this@SettingsViewModel)
            }
        }
    }

    // ---- cloned voices (ZipVoice) ----
    val cloneVoices = c.cloneVoices.voices

    sealed interface CloneDraft {
        data object Idle : CloneDraft
        data class Recording(val seconds: Float, val level: Float) : CloneDraft
        data class Processing(val message: String) : CloneDraft
        /** A usable clip; [text] is the ASR transcript (may be empty when no ASR model is installed). */
        data class Ready(val samples: FloatArray, val sampleRate: Int, val text: String, val seconds: Float, val note: String?) : CloneDraft
        data class Failed(val message: String) : CloneDraft
    }
    private val _draft = kotlinx.coroutines.flow.MutableStateFlow<CloneDraft>(CloneDraft.Idle)
    val cloneDraft: kotlinx.coroutines.flow.StateFlow<CloneDraft> = _draft
    private var recordJob: Job? = null
    @Volatile private var stopRecordingRequested = false
    private var draftPlayJob: Job? = null

    /** Records up to 15 s from the plain MIC source (no echo cancel / noise suppression, which smear the timbre). */
    fun startCloneRecording() {
        if (recordJob?.isActive == true) return
        stopDraftPlayback()
        stopRecordingRequested = false
        recordJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val rate = com.iris.localai.voice.CloneAudio.RECORD_RATE
            val max = (com.iris.localai.voice.CloneAudio.MAX_SEC * rate).toInt()
            val buf = FloatArray(max)
            var n = 0
            _draft.value = CloneDraft.Recording(0f, 0f)
            try {
                kotlinx.coroutines.withTimeoutOrNull(((com.iris.localai.voice.CloneAudio.MAX_SEC + 1) * 1000).toLong()) {
                    com.iris.localai.voice.Microphone.frames(rate, android.media.MediaRecorder.AudioSource.MIC, effects = false)
                        .collect { f ->
                            val k = minOf(f.size, max - n)
                            System.arraycopy(f, 0, buf, n, k)
                            n += k
                            _draft.value = CloneDraft.Recording(n.toFloat() / rate, com.iris.localai.voice.Microphone.rms(f))
                            if (n >= max || stopRecordingRequested) throw StopRecording()
                        }
                }
            } catch (_: StopRecording) {
            } catch (e: kotlinx.coroutines.CancellationException) {
                _draft.value = CloneDraft.Idle
                throw e
            } catch (t: Throwable) {
                _draft.value = CloneDraft.Failed("录音失败：${t.message ?: t.javaClass.simpleName}")
                return@launch
            }
            processClip(buf.copyOf(n), rate)
        }
    }
    private class StopRecording : RuntimeException() { override fun fillInStackTrace(): Throwable = this }

    fun stopCloneRecording() { stopRecordingRequested = true }

    fun importCloneAudio(uri: android.net.Uri) {
        if (recordJob?.isActive == true) return
        stopDraftPlayback()
        recordJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _draft.value = CloneDraft.Processing("正在读取音频…")
            val pcm = try {
                com.iris.localai.voice.AudioFileDecoder.decode(getApplication(), uri, maxSec = 40f)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
                _draft.value = CloneDraft.Failed("无法读取这个音频：${t.message ?: t.javaClass.simpleName}")
                return@launch
            }
            // Keep a 24 kHz reference: ZipVoice works at 24 kHz, higher rates only cost memory.
            val rate = minOf(pcm.sampleRate, com.iris.localai.voice.CloneAudio.RECORD_RATE)
            processClip(com.iris.localai.voice.CloneAudio.resample(pcm.samples, pcm.sampleRate, rate), rate)
        }
    }

    private suspend fun processClip(raw: FloatArray, rate: Int) {
        val clip = com.iris.localai.voice.CloneAudio.prepare(raw, rate)
        com.iris.localai.voice.CloneAudio.problem(clip, rate)?.let { _draft.value = CloneDraft.Failed(it); return }
        val secs = com.iris.localai.voice.CloneAudio.seconds(clip, rate)
        val longNote = if (raw.size.toFloat() / rate > com.iris.localai.voice.CloneAudio.MAX_SEC + 0.5f) "已截取前 15 秒" else null
        var text = ""
        var note = longNote
        if (c.recognizer.isAvailable) {
            _draft.value = CloneDraft.Processing("正在用语音识别转写参考文本…")
            text = try {
                c.recognizer.prepare(c.settings.current.asrLanguage)
                c.recognizer.transcribe(com.iris.localai.voice.CloneAudio.resample(clip, rate, com.iris.localai.voice.ASR_SAMPLE_RATE))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
                note = "自动转写失败，请手动输入这段话的文字"; ""
            }
        } else {
            note = "未安装语音识别模型，请手动输入这段话的文字"
        }
        if (secs < 5f) note = listOfNotNull(note, "样本偏短，5–15 秒效果更好").joinToString("；")
        _draft.value = CloneDraft.Ready(clip, rate, text, secs, note)
    }

    fun playDraft() {
        val d = _draft.value as? CloneDraft.Ready ?: return
        if (draftPlayJob?.isActive == true) { stopDraftPlayback(); return }
        draftPlayJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val p = PcmPlayer(d.sampleRate)
            try { p.write(d.samples); p.drain() } finally { p.release() }
        }
    }
    private fun stopDraftPlayback() { draftPlayJob?.cancel(); draftPlayJob = null }

    /** Saves the draft as a voice and selects it for voice chat and read-aloud. */
    fun saveClone(name: String, text: String) {
        val d = _draft.value as? CloneDraft.Ready ?: return
        if (text.isBlank()) return
        stopDraftPlayback()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val v = c.cloneVoices.save(name, text, d.samples, d.sampleRate)
            update { it.copy(cloneVoiceId = v.id) }
            _draft.value = CloneDraft.Idle
        }
    }

    fun discardCloneDraft() {
        stopRecordingRequested = true
        recordJob?.cancel()
        stopDraftPlayback()
        _draft.value = CloneDraft.Idle
    }

    fun useClone(id: String) = update { it.copy(cloneVoiceId = id) }
    fun deleteClone(id: String) {
        if (settings.value.cloneVoiceId == id) update { it.copy(cloneVoiceId = "") }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { c.cloneVoices.delete(id) }
    }
    fun renameClone(id: String, name: String, text: String) =
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { c.cloneVoices.update(id, name, text); c.synthesizer.release() }

    /** Which voice the 试听 button is playing ("" = the selected voice). */
    val previewTarget = kotlinx.coroutines.flow.MutableStateFlow("")

    /** 2.2.10: an Android TTS engine is installed (朗读引擎「系统 TTS」). */
    fun systemTtsAvailable(): Boolean = c.synthesizer.system?.hasEngine() == true
    fun openSystemTtsSettings() {
        val ctx = getApplication<Application>()
        runCatching { ctx.startActivity(com.iris.localai.voice.SystemTts.settingsIntent()) }
            .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun stopPreview() {
        previewPlayer?.stop()
        previewJob?.cancel()
        previewJob = null
        _preview.value = Preview.Idle
    }

    override fun onCleared() {
        stopPreview()
        discardCloneDraft()
        super.onCleared()
    }

    // ---- API ----
    fun setApiEnabled(on: Boolean) {
        update { it.copy(apiEnabled = on) }
        if (on) ApiService.start(getApplication()) else ApiService.stop(getApplication())
    }

    fun setLan(on: Boolean) {
        update { s -> s.copy(apiAllowLan = on, apiKey = if (on && s.apiKey.isBlank()) newKey() else s.apiKey) }
        restartApiIfRunning()
    }

    fun setPort(port: Int) {
        update { it.copy(apiPort = port) }
        restartApiIfRunning()
    }

    fun regenerateKey() {
        update { it.copy(apiKey = newKey()) }
    }

    /** 2.1.7: user-typed key (saved as typed, header-safe chars only). Blank on the LAN keeps the old key. */
    fun setApiKey(raw: String) {
        val k = com.iris.localai.server.ApiAuth.sanitizeKey(raw)
        update { if (k.isEmpty() && it.apiAllowLan) it else it.copy(apiKey = k) }
    }

    fun clearKey() {
        // A key is mandatory on the LAN.
        update { if (it.apiAllowLan) it else it.copy(apiKey = "") }
    }

    private fun restartApiIfRunning() {
        if (settings.value.apiEnabled) ApiService.restart(getApplication())
    }

    private fun newKey(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return "sk-iris-" + bytes.joinToString("") { "%02x".format(it) }
    }
}
