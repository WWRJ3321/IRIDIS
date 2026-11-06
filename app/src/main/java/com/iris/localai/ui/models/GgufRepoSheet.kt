package com.iris.localai.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.data.Format
import com.iris.localai.data.GgufSearch
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.SheetHeader
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/** 2.2.16 「在线搜索模型」 texts (unit-tested). */
object SearchText {
    val OPEN: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_d2c548)
    val HINT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_03e899)
    val EMPTY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_8c8980)
    val MAIN: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_22fc16)
    val NO_GGUF: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_691d85)
    val VISION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_93f6a8)

    fun stats(h: GgufSearch.RepoHit): String = listOfNotNull(
        com.iris.localai.I18n.s(com.iris.localai.R.string.models_cbd50e, count(h.downloads)), h.updated.takeIf { it.isNotEmpty() }?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.models_56ffb4, it) },
    ).joinToString(" · ")

    fun modeHint(m: GgufSearch.Browse, q: String): String = (if (q.isNotBlank()) com.iris.localai.I18n.s(com.iris.localai.R.string.models_45e86f, q) else "") + when (m) {
        GgufSearch.Browse.HOT -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_1c57f4)
        GgufSearch.Browse.NEW -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_fbdc63)
        GgufSearch.Browse.FIT -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_f07455)
    }

    /** "约 4.2B 参数 · Q4–Q8 约 2.2–4.2 GB · qwen3" (null when the size is unknown). */
    fun sizeLine(h: GgufSearch.RepoHit): String? {
        val r = h.sizeRange ?: return null
        val p = h.params / 1e9
        val ps = if (p >= 1) String.format(java.util.Locale.US, "%.1fB", p) else String.format(java.util.Locale.US, "%.0fM", h.params / 1e6)
        val gb = { b: Long -> String.format(java.util.Locale.US, "%.1f", b / 1073741824.0) }
        return com.iris.localai.I18n.s(com.iris.localai.R.string.models_4d544f, ps, gb(r.first), gb(r.last)) + (h.arch?.let { " · $it" } ?: "")
    }

    fun count(n: Long): String = when {
        n >= 100_000_000 -> String.format(java.util.Locale.US, com.iris.localai.I18n.s(com.iris.localai.R.string.models_51364d), n / 1e8)
        n >= 10_000 -> String.format(java.util.Locale.US, com.iris.localai.I18n.s(com.iris.localai.R.string.models_80b156), n / 1e4)
        else -> n.toString()
    }
}

/**
 * 2.2.17 C: one online GGUF repo (from 热门 / 最新 / 适合本机 / search on 下载模型): pick a file
 * (quant, size, 内存够用/偏紧), optionally the vision projector, licence and source. The list itself
 * is on the 下载模型 page.
 */
@Composable
internal fun GgufRepoSheet(vm: ModelsViewModel, hubActive: Boolean) {
    val c = Iris.colors
    val st by vm.search.collectAsStateWithLifecycle()
    val repo = st.repo ?: return
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    IrisSheet(onDismiss = vm::closeRepo) {
        SheetHeader(repo.id.substringAfter('/'))
        var withVision by remember(repo.id) { mutableStateOf(false) }
        val visionFile = st.files?.projectors?.let { ps -> ps.firstOrNull { "f16" in it.name.lowercase() } ?: ps.firstOrNull() }
        Column(Modifier.padding(horizontal = IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            Text(
                listOf(repo.id.substringBefore('/'), if (repo.site == GgufSearch.Site.MS) com.iris.localai.I18n.s(com.iris.localai.R.string.models_76bc06) else com.iris.localai.I18n.s(com.iris.localai.R.string.models_f4bd50), SearchText.stats(repo)).joinToString(" · "),
                style = IrisType.caption, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val files = st.files
            // 2.3.5: licence line dropped (noise); gated repos still say so below.
            if (st.filesLoading) Text(com.iris.localai.I18n.s(com.iris.localai.R.string.models_1fc70c), style = IrisType.caption, color = c.textSecondary)
            st.filesError?.let { Text(it, style = IrisType.caption, color = c.danger) }
            if (files != null) {
                if (files.gated) {
                    Text(com.iris.localai.data.MnnHub.GATED, style = IrisType.caption, color = c.danger)
                } else if (files.choices.isEmpty()) {
                    Text(SearchText.NO_GGUF, style = IrisType.caption, color = c.textTertiary)
                } else {
                    val projector = visionFile
                    val ram = remember { vm.availRam() }
                    Text(SearchText.MAIN, style = IrisType.caption, color = c.textSecondary)
                    if (hubActive) Text(DownloadText.ONE_AT_A_TIME, style = IrisType.caption, color = c.warning)
                    // 2.3.5: rows are just quant + size + fit. The file name only shows when two files share a
                    // quant, and then only the part that differs (common prefix / ".gguf" trimmed).
                    val dupQuants = remember(files) { files.choices.groupingBy { it.quant }.eachCount().filter { it.key != null && it.value > 1 }.keys }
                    val prefix = remember(files) {
                        val names = files.choices.map { it.title }
                        if (names.size < 2) "" else names.reduce { a, b -> a.commonPrefixWith(b) }
                    }
                    Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                        files.choices.forEach { ch ->
                            val variant = if (ch.quant in dupQuants) ch.title.removePrefix(prefix).removeSuffix(".gguf").trim('-', '_', '.', ' ').takeIf { it.isNotEmpty() } else null
                            val best = ch.quant.equals("Q4_K_M", ignoreCase = true)
                            Row(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space6), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ch.quant ?: ch.title, style = IrisType.rowStrong, color = if (best) c.accent else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(Format.bytes(ch.size), if (ram > 0) GgufSearch.fitHint(ch.size, ram) else null, variant)
                                            .joinToString(" · ") + if (!ch.complete) com.iris.localai.I18n.s(com.iris.localai.R.string.models_1366d0) else "",
                                        style = IrisType.caption, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f26ef9), {
                                    vm.downloadGguf(ch, if (withVision) projector else null)
                                    vm.closeRepo()
                                }, style = IrisButtonStyle.Tonal, compact = true, enabled = !hubActive && ch.complete)
                            }
                        }
                    }
                }
            }
        }
        // full-width row (its own 16 dp padding)
        visionFile?.takeIf { st.files?.gated == false && st.files?.choices?.isNotEmpty() == true }?.let { projector ->
            com.iris.localai.ui.components.CheckRow(
                SearchText.VISION, withVision, subtitle = Format.bytes(projector.size),
            ) { withVision = !withVision }
        }
    }
}
