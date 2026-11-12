package com.iris.localai.translate

import android.content.Context
import android.os.StatFs
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.Format
import com.iris.localai.util.HttpDownload
import com.iris.localai.voice.PackState
import com.iris.localai.voice.VoiceDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 2.3.0 ③: offline translation packs — Mozilla Firefox Translations (Bergamot / Marian) models,
 * one direction per pack, pinned to a Hugging Face revision and fetched through the same download
 * sources as voice packs (自动 / 魔搭 / hf-mirror / HF). Models: MPL-2.0 (Mozilla
 * firefox-translations-models); the Hugging Face copy is published under CC-BY-4.0.
 */
enum class TranslatePack(
    val id: String, val source: TransLang, val target: TransLang, val title: String, val blurb: String,
    val dir: String, val model: String, val lex: String, val srcVocab: String, val trgVocab: String,
    val sizes: List<Long>,
) {
    ZH_EN(
        "bergamot-zhen-tiny", TransLang.ZH, TransLang.EN, com.iris.localai.I18n.s(com.iris.localai.R.string.translate_d72896), com.iris.localai.I18n.s(com.iris.localai.R.string.translate_688492),
        "tiny/zhen", "model.zhen.intgemm.alphas.bin", "lex.50.50.zhen.s2t.bin", "vocab.zhen.spm", "vocab.zhen.spm",
        listOf(25_461_051L, 9_219_524L, 1_359_697L),
    ),
    EN_ZH(
        "bergamot-enzh-base", TransLang.EN, TransLang.ZH, com.iris.localai.I18n.s(com.iris.localai.R.string.translate_931985), com.iris.localai.I18n.s(com.iris.localai.R.string.translate_5ecfb0),
        "base/enzh", "model.enzh.intgemm.alphas.bin", "lex.50.50.enzh.s2t.bin", "srcvocab.enzh.spm", "trgvocab.enzh.spm",
        listOf(42_992_955L, 6_506_248L, 806_952L, 772_004L),
    );

    /** Distinct files with their sizes (zh→en shares one vocabulary for both sides). */
    val files: List<Pair<String, Long>> get() = listOf(model, lex, srcVocab, trgVocab).distinct().zip(sizes)
    val totalBytes: Long get() = sizes.sum()

    companion object {
        const val REPO = "TiberiuCristianLeon/Bergamot"
        const val REVISION = "18008da9d2922dde09dae47f5fee71cac4253701"
        fun of(source: TransLang, target: TransLang): TranslatePack? = entries.firstOrNull { it.source == source && it.target == target }
    }
}

/** Marian YAML for one pack (absolute paths), as Firefox configures these int8 models. Pure, unit-tested. */
object BergamotConfig {
    fun yaml(pack: TranslatePack, dir: File, threads: Int): String = buildString {
        fun p(name: String) = File(dir, name).absolutePath
        appendLine("models:"); appendLine("  - ${p(pack.model)}")
        appendLine("vocabs:"); appendLine("  - ${p(pack.srcVocab)}"); appendLine("  - ${p(pack.trgVocab)}")
        appendLine("shortlist:"); appendLine("  - ${p(pack.lex)}"); appendLine("  - false")
        appendLine("beam-size: 1")
        appendLine("normalize: 1.0")
        appendLine("word-penalty: 0")
        appendLine("max-length-break: 128")
        appendLine("mini-batch-words: 1024")
        appendLine("workspace: 128")
        appendLine("max-length-factor: 2.0")
        appendLine("skip-cost: true")
        appendLine("cpu-threads: $threads")
        appendLine("quiet: true")
        appendLine("quiet-translation: true")
        appendLine("gemm-precision: int8shiftAlphaAll")
    }
}

class TranslatePackStore(private val context: Context) {
    private val privateRoot: File = File(context.getExternalFilesDir(null) ?: context.filesDir, SUB).apply { mkdirs() }

    private fun location(): com.iris.localai.data.ModelDirs.Location = com.iris.localai.data.ModelStorage.location(privateRoot, com.iris.localai.agent.StorageLayout.VOICE + "/" + SUB)

    fun dir(pack: TranslatePack): File = location().let { VoiceDirs.packDir(it.listDirs, it.dir, pack.id, MARKER) }
    fun isInstalled(pack: TranslatePack) = File(dir(pack), MARKER).exists()

    private val _states = MutableStateFlow(TranslatePack.entries.associateWith { if (isInstalled(it)) PackState.Installed else PackState.NotInstalled })
    val states: StateFlow<Map<TranslatePack, PackState>> = _states.asStateFlow()
    fun installed(pack: TranslatePack): Boolean = _states.value[pack] == PackState.Installed

    private fun publish(pack: TranslatePack, s: PackState) = _states.update { it + (pack to s) }

    suspend fun download(pack: TranslatePack, source: DownloadSource) = withContext(Dispatchers.IO) {
        val dir = dir(pack).apply { mkdirs() }
        val have = pack.files.sumOf { (name, _) -> File(dir, name).takeIf { it.exists() }?.length() ?: 0L }
        val need = pack.totalBytes - have + 32L * 1024 * 1024
        try {
            val free = StatFs(dir.absolutePath).availableBytes
            if (free < need) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_9c1de2, Format.bytes(need), Format.bytes(free)))
            var done = have
            publish(pack, PackState.Downloading(done, pack.totalBytes))
            for ((name, size) in pack.files) {
                coroutineContext.ensureActive()
                val target = File(dir, name)
                if (target.exists() && target.length() == size) continue
                val url = "${com.iris.localai.data.DownloadSources.HF}/${TranslatePack.REPO}/resolve/${TranslatePack.REVISION}/${pack.dir}/$name"
                val before = done - (HttpDownload.partFile(target).takeIf { it.exists() }?.length() ?: 0L)
                com.iris.localai.data.DownloadSources.fetchAny(
                    com.iris.localai.data.DownloadSources.resolve(url, source), target, size,
                    { got -> done = before + got; publish(pack, PackState.Downloading(done, pack.totalBytes)) },
                    fetch = { u, t, e, b -> HttpDownload.fetchOnce(u, t, e, b) }, attemptsPerSource = 3,
                )
                done = before + size
            }
            File(dir, MARKER).writeText(TranslatePack.REVISION)
            publish(pack, PackState.Installed)
        } catch (t: Throwable) {
            publish(pack, if (t is kotlinx.coroutines.CancellationException) (if (isInstalled(pack)) PackState.Installed else PackState.NotInstalled) else PackState.Failed(t.message ?: com.iris.localai.I18n.s(com.iris.localai.R.string.data_65e200)))
            throw t
        }
    }

    fun delete(pack: TranslatePack) {
        publish(pack, PackState.NotInstalled)
        location().listDirs.forEach { File(it, pack.id).deleteRecursively() }
    }

    companion object {
        const val SUB = "translate"
        const val MARKER = ".complete"
    }
}
