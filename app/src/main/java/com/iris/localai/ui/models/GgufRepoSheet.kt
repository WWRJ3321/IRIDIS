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
    const val OPEN = "在线搜索模型"
    const val HINT = "输入模型名，例如 qwen3、gemma、deepseek"
    const val EMPTY = "没有找到 GGUF 模型，换个关键词试试"
    const val MAIN = "选择一个文件下载（越小越快，Q4_K_M 通常最均衡）"
    const val NO_GGUF = "这个仓库里没有可用的 GGUF 文件"
    const val VISION = "同时下载看图组件（mmproj）"

    fun stats(h: GgufSearch.RepoHit): String = listOfNotNull(
        "下载 ${count(h.downloads)}", if (h.likes > 0) "♥ ${count(h.likes)}" else null, h.updated.takeIf { it.isNotEmpty() }?.let { "更新 $it" },
    ).joinToString(" · ")

    fun modeHint(m: GgufSearch.Browse, q: String): String = (if (q.isNotBlank()) "「$q」的搜索结果 · " else "") + when (m) {
        GgufSearch.Browse.HOT -> "下载最多的 GGUF 模型"
        GgufSearch.Browse.NEW -> "最近更新的 GGUF 模型"
        GgufSearch.Browse.FIT -> "Q4 体积不超过本机内存 60% 的模型"
    }

    /** "约 4.2B 参数 · Q4–Q8 约 2.2–4.2 GB · qwen3" (null when the size is unknown). */
    fun sizeLine(h: GgufSearch.RepoHit): String? {
        val r = h.sizeRange ?: return null
        val p = h.params / 1e9
        val ps = if (p >= 1) String.format(java.util.Locale.US, "%.1fB", p) else String.format(java.util.Locale.US, "%.0fM", h.params / 1e6)
        val gb = { b: Long -> String.format(java.util.Locale.US, "%.1f", b / 1073741824.0) }
        return "约 $ps 参数 · Q4–Q8 约 ${gb(r.first)}–${gb(r.last)} GB" + (h.arch?.let { " · $it" } ?: "")
    }

    fun count(n: Long): String = when {
        n >= 100_000_000 -> String.format(java.util.Locale.US, "%.1f亿", n / 1e8)
        n >= 10_000 -> String.format(java.util.Locale.US, "%.1f万", n / 1e4)
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
                listOf(repo.id.substringBefore('/'), if (repo.site == GgufSearch.Site.MS) "来自 魔搭" else "来自 HuggingFace", SearchText.stats(repo)).joinToString(" · "),
                style = IrisType.caption, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val files = st.files
            files?.license?.let { Text("许可证 $it · 使用前请确认允许你的用途", style = IrisType.caption, color = c.textSecondary) }
            if (st.filesLoading) Text("正在读取文件列表…", style = IrisType.caption, color = c.textSecondary)
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
                    Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                        files.choices.forEach { ch ->
                            Row(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space6), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ch.quant ?: ch.title, style = IrisType.rowStrong, color = c.text)
                                    Text(
                                        listOfNotNull(Format.bytes(ch.size), if (ram > 0) GgufSearch.fitHint(ch.size, ram) else null, ch.title.takeIf { ch.quant != null })
                                            .joinToString(" · ") + if (!ch.complete) " · 分卷不全" else "",
                                        style = IrisType.caption, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IrisButton("下载", {
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
                SearchText.VISION, withVision, subtitle = "${projector.name} · ${Format.bytes(projector.size)}",
            ) { withVision = !withVision }
        }
    }
}
