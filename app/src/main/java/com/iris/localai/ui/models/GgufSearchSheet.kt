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
    const val VISION = "同时下载视觉组件（mmproj，NPU 看图用）"

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

@Composable
internal fun GgufSearchSheet(vm: ModelsViewModel, hubActive: Boolean, onDismiss: () -> Unit) {
    val c = Iris.colors
    val st by vm.search.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(st.query) }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.openBrowse() }
    IrisSheet(onDismiss = onDismiss) {
        val repo = st.repo
        SheetHeader(if (repo == null) SearchText.OPEN else repo.id.substringAfter('/')) {
            if (repo != null) IrisButton("返回", vm::closeRepo, style = IrisButtonStyle.Text, compact = true)
        }
        Column(Modifier.padding(horizontal = IrisDimens.groupGutter), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            if (repo == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                    androidx.compose.material3.OutlinedTextField(
                        text, { text = it }, singleLine = true,
                        textStyle = IrisType.body.copy(color = c.text),
                        placeholder = { Text(SearchText.HINT, style = IrisType.body, color = c.textTertiary, maxLines = 1) },
                        shape = IrisRadius.mediumShape,
                        colors = com.iris.localai.ui.components.irisTextFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchModels(text) }),
                        modifier = Modifier.weight(1f),
                    )
                    IrisButton("搜索", { vm.searchModels(text) }, compact = true, icon = Icons.Outlined.Search, enabled = text.isNotBlank() && !st.loading)
                }
                // 2.2.16: browse tabs, shown as soon as the page opens (search keeps the 热门 order)
                Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                    GgufSearch.Browse.entries.forEach { m ->
                        androidx.compose.material3.FilterChip(
                            selected = st.mode == m, onClick = { if (st.mode != m) vm.browse(m) },
                            label = { Text(m.label, style = IrisType.labelSmall) },
                        )
                    }
                }
                Text(SearchText.modeHint(st.mode, st.query), style = IrisType.caption, color = c.textTertiary)
                st.errors.forEach { Text(it, style = IrisType.caption, color = c.danger) }
                if (st.searched && !st.loading && st.hits.isEmpty()) Text(SearchText.EMPTY, style = IrisType.caption, color = c.textTertiary)
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                // infinite scroll: near the end → next page
                val nearEnd by remember { androidx.compose.runtime.derivedStateOf {
                    val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= list.layoutInfo.totalItemsCount - 3
                } }
                androidx.compose.runtime.LaunchedEffect(nearEnd, st.hits.size) { if (nearEnd && st.hits.isNotEmpty()) vm.loadMore() }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = maxH), state = list) {
                    items(st.hits.size, key = { i -> st.hits[i].site.name + st.hits[i].id }) { i ->
                        val h = st.hits[i]
                        Column(
                            Modifier.fillMaxWidth().clickable { vm.openRepo(h) }.padding(vertical = IrisDimens.space8),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(h.id, style = IrisType.rowStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(h.site.tag, style = IrisType.labelSmall, color = if (h.site == GgufSearch.Site.MS) c.accent else c.textSecondary,
                                    modifier = Modifier.padding(start = IrisDimens.space8))
                            }
                            SearchText.sizeLine(h)?.let { Text(it, style = IrisType.caption, color = c.textSecondary, maxLines = 1) }
                            Text(SearchText.stats(h) + (h.license?.let { " · $it" } ?: "") + (if (h.gated) " · 需申请访问" else ""),
                                style = IrisType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item(key = "more") {
                        Text(
                            when { st.loading -> "正在加载 HuggingFace 和 魔搭…"; st.hasMore -> "上滑加载更多"; st.hits.isNotEmpty() -> "没有更多了"; else -> "" },
                            style = IrisType.caption, color = c.textTertiary, modifier = Modifier.padding(vertical = IrisDimens.space8),
                        )
                    }
                }
            } else {
                Text(SearchText.stats(repo) + " · " + (if (repo.site == GgufSearch.Site.MS) "来自 魔搭" else "来自 HuggingFace"),
                    style = IrisType.caption, color = c.textTertiary)
                val files = st.files
                files?.license?.let { Text("许可证：$it（使用前请确认许可证允许你的用途）", style = IrisType.caption, color = c.textSecondary) }
                if (st.filesLoading) Text("正在读取文件列表…", style = IrisType.caption, color = c.textSecondary)
                st.filesError?.let { Text(it, style = IrisType.caption, color = c.danger) }
                if (files != null) {
                    if (files.gated) {
                        Text(com.iris.localai.data.MnnHub.GATED, style = IrisType.caption, color = c.danger)
                    } else if (files.choices.isEmpty()) {
                        Text(SearchText.NO_GGUF, style = IrisType.caption, color = c.textTertiary)
                    } else {
                        var withVision by remember(repo.id) { mutableStateOf(false) }
                        val projector = files.projectors.firstOrNull { "f16" in it.name.lowercase() } ?: files.projectors.firstOrNull()
                        val ram = remember { vm.availRam() }
                        Text(SearchText.MAIN, style = IrisType.caption, color = c.textSecondary)
                        if (hubActive) Text("已有下载在进行，完成后再下载下一个", style = IrisType.caption, color = c.danger)
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
                                        onDismiss()
                                    }, style = IrisButtonStyle.Outline, compact = true, enabled = !hubActive && ch.complete)
                                }
                            }
                            if (projector != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = IrisDimens.space8).clickable { withVision = !withVision }) {
                                    Checkbox(withVision, { withVision = it })
                                    Column {
                                        Text(SearchText.VISION, style = IrisType.secondary, color = c.text)
                                        Text("${projector.name} · ${Format.bytes(projector.size)}", style = IrisType.caption, color = c.textTertiary)
                                    }
                                }
                            }
                        }
                        Text("下载进度在「模型与加载」页上方显示，完成后出现在本机模型里。", style = IrisType.caption, color = c.textTertiary)
                    }
                }
            }
        }
    }
}
