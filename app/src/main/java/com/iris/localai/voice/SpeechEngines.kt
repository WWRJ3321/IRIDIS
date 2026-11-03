package com.iris.localai.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.iris.localai.data.SettingsRepository
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

const val ASR_SAMPLE_RATE = 16_000
/** Silero VAD window at 16 kHz. Recording happens in multiples of this. */
const val VAD_WINDOW = 512

/** sherpa-onnx aborts the whole process on a missing file, so check every path first. */
internal fun requireFiles(vararg paths: String) {
    val missing = paths.filter { it.isNotEmpty() && !File(it).exists() }
    check(missing.isEmpty()) {
        "语音模型文件不完整（缺少 ${missing.joinToString { File(it).name }}），请到 设置 › 语音 删除后重新下载"
    }
}

/**
 * On-device speech recognition: Silero VAD (bundled, 640 KB) + SenseVoice-Small int8.
 * Recognition only ever runs on speech segments the VAD found, never on silence.
 */
class SpeechRecognizer(private val context: Context, private val store: VoiceModelStore, private val settings: SettingsRepository) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "iris-asr") }.asCoroutineDispatcher()
    private var recognizer: OfflineRecognizer? = null
    private var language = ""
    private var loadedPack: VoicePack? = null

    val isAvailable: Boolean get() = activePack() != null
    fun activePack(): VoicePack? = store.active(VoiceKind.ASR, settings.current.asrPack)

    /** Creates a fresh VAD (cheap; one per listening session). */
    fun newVad(minSilenceSec: Float = 0.6f): Vad {
        com.iris.localai.util.CrashReporter.breadcrumb("加载语音检测 VAD")
        return createVad(minSilenceSec).also { com.iris.localai.util.CrashReporter.breadcrumb(null) }
    }

    private fun createVad(minSilenceSec: Float): Vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = minSilenceSec,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = 20f,
            ),
            sampleRate = ASR_SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    suspend fun prepare(lang: String) = withContext(worker) {
        val wanted = if (lang == "auto") "" else lang
        val pack = activePack() ?: return@withContext
        if (recognizer != null && language == wanted && loadedPack == pack) return@withContext
        recognizer?.release()
        recognizer = null
        val dir = store.dir(pack)
        fun p(name: String) = File(dir, name).absolutePath
        val model = when (pack) {
            VoicePack.ASR_PARAFORMER -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = p("model.int8.onnx")),
                tokens = p("tokens.txt"), numThreads = 4, provider = "cpu",
            )
            VoicePack.ASR_QWEN3 -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = p("conv_frontend.onnx"),
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.int8.onnx"),
                    tokenizer = p("tokenizer"),
                    maxNewTokens = 256,
                ),
                numThreads = 4, provider = "cpu",
            )
            VoicePack.ASR_ZIPFORMER_CTC -> OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(model = p("model.int8.onnx")),
                tokens = p("tokens.txt"), numThreads = 4, provider = "cpu",
            )
            else -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = p("model.int8.onnx"),
                    language = wanted,
                    useInverseTextNormalization = true, // punctuation + numbers as digits
                ),
                tokens = p("tokens.txt"), numThreads = 4, provider = "cpu",
            )
        }
        when (pack) {
            VoicePack.ASR_QWEN3 -> requireFiles(p("conv_frontend.onnx"), p("encoder.int8.onnx"), p("decoder.int8.onnx"), p("tokenizer/vocab.json"))
            else -> requireFiles(p("model.int8.onnx"), p("tokens.txt"))
        }
        if (pack == VoicePack.ASR_QWEN3) {
            val mi = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mi)
            check(mi.availMem == 0L || mi.availMem > 1_400L * 1024 * 1024) {
                "可用内存不足（剩 ${mi.availMem / 1024 / 1024} MB），Qwen3-ASR 需要约 1.2 GB。请换用 SenseVoice，或先卸载对话模型"
            }
        }
        // The breadcrumb only has to survive a *native* crash (process gone); a Java exception
        // must clear it, or the next launch would wrongly skip the speech warm-up.
        try {
            com.iris.localai.util.CrashReporter.breadcrumb("加载语音识别 ${pack.id}")
            val r = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = ASR_SAMPLE_RATE, featureDim = 80),
                    modelConfig = model,
                ),
            )
            recognizer = r
            language = wanted
            loadedPack = pack
            com.iris.localai.util.CrashReporter.breadcrumb("预热语音识别 ${pack.id}")
            // The first decode pays ONNX Runtime's lazy allocation / kernel selection; do it now.
            runCatching {
                val st = r.createStream()
                try {
                    st.acceptWaveform(FloatArray(ASR_SAMPLE_RATE / 2), ASR_SAMPLE_RATE)
                    r.decode(st)
                } finally {
                    st.release()
                }
            }
        } finally {
            com.iris.localai.util.CrashReporter.breadcrumb(null)
        }
    }

    /** Transcribes one utterance (16 kHz mono float PCM). */
    suspend fun transcribe(samples: FloatArray): String = withContext(worker) {
        val r = recognizer ?: return@withContext ""
        if (samples.isEmpty()) return@withContext ""
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, ASR_SAMPLE_RATE)
            com.iris.localai.util.CrashReporter.breadcrumb("语音识别 ${loadedPack?.id} ${samples.size / 16} ms")
            r.decode(stream)
            cleanup(r.getResult(stream).text)
        } finally {
            com.iris.localai.util.CrashReporter.breadcrumb(null)
            stream.release()
        }
    }

    suspend fun release() = withContext(worker) {
        recognizer?.release()
        recognizer = null
        loadedPack = null
    }

    private fun cleanup(text: String): String = SpeechTags.strip(text)
}

/** SenseVoice-style `<|zh|><|NEUTRAL|>` tags that occasionally leak into results. */
internal object SpeechTags {
    private val TAG = Regex("<\\|[^|]*\\|>")
    fun strip(text: String): String = TAG.replace(text, "").trim()
}

/** On-device TTS: Kokoro v1.1 (int8 or fp32, 103 voices) or MeloTTS zh/en (VITS). */
class SpeechSynthesizer(
    private val store: VoiceModelStore,
    private val settings: SettingsRepository,
    private val clones: CloneVoiceStore,
    context: Context? = null,
) {
    /** 2.2.10: 朗读引擎「系统 TTS」 (null in host tests). */
    val system: SystemTts? = context?.let { SystemTts(it.applicationContext) }
    /** True while prepare() chose the system engine (cleared when a cloned voice is previewed). */
    @Volatile var usingSystem = false
        private set
    /** 2.3.3: the card chat on screen speaks with its bound voice; null = the global setting. */
    @Volatile var override: VoiceOverride? = null
    private fun sel(): VoiceSel = settings.current.let { s -> VoiceBinding.effective(VoiceSel(s.ttsPack, s.ttsSpeakerId, s.cloneVoiceId), override) }
    /** Speaker to use: the card's when it binds one, else [global]. */
    fun speaker(global: Int): Int = override?.speaker ?: global
    fun systemSelected(): Boolean = override == null && settings.current.ttsEngine == TTS_ENGINE_SYSTEM && system != null

    // Synthesis competes with the LLM for CPU while the reply is still being written; run it
    // (and the ONNX threads it spawns, which inherit this) at audio priority so speech keeps up.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            r.run()
        }, "iris-tts")
    }.asCoroutineDispatcher()
    private var tts: OfflineTts? = null
    private var loadedPack: VoicePack? = null

    /** Reference voice + transcript in use while ZipVoice is loaded. */
    private var clone: ClonedVoice? = null
    private var cloneAudio: FloatArray? = null

    val isAvailable: Boolean get() = (systemSelected() && system?.hasEngine() == true) || activeClone() != null || activePack() != null
    fun activePack(): VoicePack? = store.active(VoiceKind.TTS, sel().pack)
    /** The selected cloned voice, if it and the ZipVoice model are both present. */
    fun activeClone(): ClonedVoice? = sel().clone.takeIf { it.isNotEmpty() }
        ?.let { clones.get(it) }?.takeIf { store.states.value[VoicePack.CLONE_ZIPVOICE] == PackState.Installed }
    /** Kokoro has 103 selectable voices; the other packs and cloned voices have one. */
    val hasSpeakers: Boolean get() = activeClone() == null && activePack()?.singleSpeaker == false
    val sampleRate: Int get() = tts?.sampleRate() ?: 24_000
    /** What is loaded right now: a cloned voice or a fixed-voice pack. */
    val speakingWithClone: Boolean get() = loadedPack == VoicePack.CLONE_ZIPVOICE

    /**
     * Only one feature speaks at a time. The voice session, read-aloud and the settings 试听 used
     * to run side by side: two players talked over each other and their sentences interleaved on
     * the single TTS thread (a 试听 queued behind a long reply, or vice versa). Each user claims the
     * synthesizer with a stop callback; the previous owner is stopped first.
     */
    private val ownerLock = Any()
    private var owner: Any? = null
    private var ownerStop: (() -> Unit)? = null

    fun claim(token: Any, stop: () -> Unit) {
        val previous = synchronized(ownerLock) {
            val prev = if (owner != null && owner !== token) ownerStop else null
            owner = token
            ownerStop = stop
            prev
        }
        previous?.let { runCatching { it() } }
    }

    /** Gives up ownership if [token] still holds it (no-op otherwise). */
    fun unclaim(token: Any) = synchronized(ownerLock) {
        if (owner === token) { owner = null; ownerStop = null }
    }

    /**
     * Loads the voice to speak with: [override] (a cloned voice being previewed in settings), else
     * the selected cloned voice, else the selected fixed-voice pack.
     */
    suspend fun prepare(override: ClonedVoice? = null) = withContext(worker) {
        usingSystem = false
        if (override == null && systemSelected()) {
            if (system?.prepare() != true) throw IllegalStateException("没有可用的系统 TTS 引擎，请在系统设置中安装或启用")
            // Free the built-in voice model while the system engine speaks.
            tts?.release(); tts = null; loadedPack = null; clone = null; cloneAudio = null
            usingSystem = true
            return@withContext
        }
        system?.release() // no-op unless the system engine was in use
        val wantClone = override ?: activeClone()
        if (wantClone != null) { prepareClone(wantClone); return@withContext }
        val pack = activePack() ?: return@withContext
        if (tts != null && loadedPack == pack) return@withContext
        tts?.release()
        tts = null
        loadedPack = null
        val d = store.dir(pack)
        fun p(name: String) = File(d, name).absolutePath
        when (pack) {
            VoicePack.TTS_MELO -> requireFiles(p("model.onnx"), p("lexicon.txt"), p("tokens.txt"), p("date.fst"), p("number.fst"))
            VoicePack.TTS_PIPER_XIAOYA -> requireFiles(p("zh_CN-xiao_ya-medium.onnx"), p("lexicon.txt"), p("tokens.txt"), p("date.fst"), p("number.fst"), p("phone.fst"))
            VoicePack.TTS_MATCHA_BAKER -> requireFiles(p("model-steps-3.onnx"), p("vocos-22khz-univ.onnx"), p("lexicon.txt"), p("tokens.txt"), p("date.fst"), p("number.fst"), p("phone.fst"))
            else -> requireFiles(
                p(if (pack == VoicePack.TTS_KOKORO_HQ) "model.onnx" else "model.int8.onnx"), p("voices.bin"), p("tokens.txt"),
                p("espeak-ng-data"), p("lexicon-us-en.txt"), p("lexicon-zh.txt"), p("date-zh.fst"), p("phone-zh.fst"), p("number-zh.fst"),
            )
        }
        try {
        com.iris.localai.util.CrashReporter.breadcrumb("加载语音合成 ${pack.id}")
        val zhFsts = listOf("date.fst", "number.fst", "phone.fst").joinToString(",") { p(it) }
        tts = when (pack) {
            VoicePack.TTS_MELO -> OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = p("model.onnx"),
                        lexicon = p("lexicon.txt"),
                        tokens = p("tokens.txt"),
                        dictDir = "", // unused since sherpa-onnx 1.12; passing it trips the jieba path
                    ),
                    numThreads = 2,
                    provider = "cpu",
                ),
                ruleFsts = listOf("date.fst", "number.fst").joinToString(",") { p(it) }, // as in sherpa-onnx's own melo example
                maxNumSentences = 1,
            ),
            )
            // Piper zh_CN (sherpa-onnx conversion: lexicon + tokens, no espeak). fp32 on purpose: on the
            // aarch64 host test the int8 export was 2× slower (RTF 0.17 vs 0.08).
            VoicePack.TTS_PIPER_XIAOYA -> OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = p("zh_CN-xiao_ya-medium.onnx"), lexicon = p("lexicon.txt"), tokens = p("tokens.txt"),
                        ),
                        numThreads = 2, provider = "cpu",
                    ),
                    ruleFsts = zhFsts, maxNumSentences = 1,
                ),
            )
            VoicePack.TTS_MATCHA_BAKER -> OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        matcha = OfflineTtsMatchaModelConfig(
                            acousticModel = p("model-steps-3.onnx"), vocoder = p("vocos-22khz-univ.onnx"),
                            lexicon = p("lexicon.txt"), tokens = p("tokens.txt"),
                        ),
                        numThreads = 2, provider = "cpu",
                    ),
                    ruleFsts = zhFsts, maxNumSentences = 1,
                ),
            )
            else -> OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = p(if (pack == VoicePack.TTS_KOKORO_HQ) "model.onnx" else "model.int8.onnx"),
                        voices = p("voices.bin"),
                        tokens = p("tokens.txt"),
                        dataDir = p("espeak-ng-data"),
                        lexicon = "${p("lexicon-us-en.txt")},${p("lexicon-zh.txt")}",
                    ),
                    numThreads = 2,
                    provider = "cpu",
                ),
                ruleFsts = listOf("date-zh.fst", "phone-zh.fst", "number-zh.fst").joinToString(",") { p(it) },
                maxNumSentences = 1,
            ),
            )
        }
        loadedPack = pack
        clone = null
        cloneAudio = null
        com.iris.localai.util.CrashReporter.breadcrumb("预热语音合成 ${pack.id}")
        runCatching { tts!!.generateWithCallback("你好", 0, 1f, TtsChunkCallback { false }) } // warm-up, discarded
        } finally {
            com.iris.localai.util.CrashReporter.breadcrumb(null)
        }
    }

    /** Loads ZipVoice (once) and the reference clip of [v]. Runs on the TTS worker. */
    private fun prepareClone(v: ClonedVoice) {
        val pack = VoicePack.CLONE_ZIPVOICE
        check(store.isInstalled(pack)) { "音色克隆需要先在 设置 › 语音 下载 ZipVoice 模型" }
        if (loadedPack != pack || tts == null) {
            tts?.release()
            tts = null
            loadedPack = null
            val d = store.dir(pack)
            fun p(name: String) = File(d, name).absolutePath
            requireFiles(p("encoder.int8.onnx"), p("decoder.int8.onnx"), p("vocos_24khz.onnx"), p("tokens.txt"), p("lexicon.txt"), p("espeak-ng-data"))
            try {
                com.iris.localai.util.CrashReporter.breadcrumb("加载音色克隆 ZipVoice")
                tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            zipvoice = OfflineTtsZipVoiceModelConfig(
                                tokens = p("tokens.txt"), encoder = p("encoder.int8.onnx"), decoder = p("decoder.int8.onnx"),
                                vocoder = p("vocos_24khz.onnx"), dataDir = p("espeak-ng-data"), lexicon = p("lexicon.txt"),
                            ),
                            numThreads = 2, provider = "cpu",
                        ),
                        maxNumSentences = 1,
                    ),
                )
                loadedPack = pack
            } finally {
                com.iris.localai.util.CrashReporter.breadcrumb(null)
            }
        }
        if (clone?.id != v.id || cloneAudio == null) {
            val audio = clones.audio(v)
            check(audio.isNotEmpty() && v.text.isNotBlank()) { "克隆音色「${v.name}」缺少参考音频或参考文本" }
            clone = v
            cloneAudio = audio
        }
    }

    private fun cloneConfig(speed: Float): GenerationConfig {
        val v = clone!!
        val s = settings.current
        return GenerationConfig(
            speed = speed,
            referenceAudio = cloneAudio!!,
            referenceSampleRate = v.sampleRate,
            referenceText = v.text,
            numSteps = CloneAudio.steps(s.cloneFidelity, s.cloneStepsFast, s.cloneStepsFine),
        )
    }

    /**
     * Synthesises [text], handing audio to [onAudio] chunk by chunk as it is produced.
     * Return false from [onAudio] to stop early (barge-in).
     */
    suspend fun speak(text: String, speakerId: Int, speed: Float, onAudio: (FloatArray) -> Boolean) =
        withContext(worker) {
            if (usingSystem) {
                val pcm = system?.synthesize(text, speed, sampleRate) ?: return@withContext
                var i = 0
                while (i < pcm.size) {
                    val end = minOf(pcm.size, i + 4800)
                    if (!onAudio(pcm.copyOfRange(i, end))) break
                    i = end
                }
                return@withContext
            }
            val engine = tts ?: return@withContext
            val sid = if (loadedPack?.singleSpeaker != false) 0 else speakerId
            com.iris.localai.util.CrashReporter.breadcrumb("语音合成 ${loadedPack?.id} 音色$sid：${text.take(40)}")
            try {
                if (loadedPack == VoicePack.CLONE_ZIPVOICE && clone != null) {
                    // ZipVoice re-encodes the reference with every call; one sentence per call keeps the first audio early.
                    engine.generateWithConfigAndCallback(text, cloneConfig(speed), TtsChunkCallback(onAudio))
                } else {
                    engine.generateWithCallback(text, sid, speed, TtsChunkCallback(onAudio))
                }
            } finally {
                com.iris.localai.util.CrashReporter.breadcrumb(null)
            }
        }

    suspend fun release() = withContext(worker) {
        system?.release()
        usingSystem = false
        tts?.release()
        tts = null
        loadedPack = null
        clone = null
        cloneAudio = null
    }
}


/**
 * sherpa-onnx's JNI looks the callback up as `invoke([F)Ljava/lang/Integer;` on the object's own class.
 * Kotlin 2 compiles lambdas through invokedynamic, whose classes only carry the erased
 * `invoke(Object)Object`, so the lookup fails, a NoSuchMethodError stays pending inside native
 * code and ART aborts the process (SIGABRT in generateWithCallbackImpl). A real class declares
 * the exact signature; it is kept from R8 in proguard-rules.pro.
 */
class TtsChunkCallback(private val onAudio: (FloatArray) -> Boolean) : (FloatArray) -> Int {
    // An exception escaping here would stay pending inside native code and abort the process too.
    override fun invoke(samples: FloatArray): Int =
        try { if (onAudio(samples)) 1 else 0 } catch (t: Throwable) { 0 }
}

/** 2.2.10 AppSettings.ttsEngine values. */
const val TTS_ENGINE_LOCAL = "local"
const val TTS_ENGINE_SYSTEM = "system"
