package com.iris.localai.knowledge

import com.iris.localai.ui.components.Chevron
import com.iris.localai.ui.models.DownloadNav
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.components.CheckRow
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.icons.IrisIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** the subtitle of the 嵌入模型 row on the 知识库 page. Pure. */
object KbEmbedText {
    fun line(model: KbEmbedModels.Model?, chosen: String?, anyInstalled: Boolean, p: KbSemantic.Progress): String = when {
        chosen == KbEmbedModels.OFF -> I18n.s(R.string.kb251_embed_off)
        model == null || !anyInstalled -> I18n.s(R.string.kb251_embed_none)
        p.modelId == model.id && p.running && p.stale != null -> I18n.s(R.string.v272_embed_rebuild, model.name, p.done, p.total)
        p.modelId == model.id && p.running -> I18n.s(R.string.kb251_embed_progress, model.name, p.done, p.total)
        p.modelId == model.id && p.failed -> I18n.s(R.string.kb251_embed_failed, model.name)
        else -> I18n.s(R.string.kb251_embed_ready, model.name)
    }
}

/**
 * 知识库页 one row: which embedding model the 语义检索 uses (自动 / a downloaded one / 关闭), the vector fill
 * progress, and — when none is downloaded — a one-line pointer to 下载模型 › 知识库嵌入模型.
 */
@Composable
fun KbEmbedRow(kb: KnowledgeBase, inGroup: Boolean = false) {
    val sem = kb.semantic ?: return
    val progress by sem.progress.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val installed by produceState(emptyList<KbEmbedModels.Model>(), tick, progress.running) {
        value = withContext(Dispatchers.IO) { runCatching { KbEmbedModels.installed() }.getOrDefault(emptyList()) }
    }
    val chosen = remember(tick) { sem.chosen }
    val model = KbEmbedModels.pick(chosen, installed.map { it.id })
    // inGroup = drawn inside the caller's card (索引 group) so it doesn't sit flush against another card.
    val row: @Composable () -> Unit = {
        SettingsRow(
            I18n.s(R.string.kb251_embed_row), icon = IrisIcons.Search,
            subtitle = KbEmbedText.line(model, chosen, installed.isNotEmpty(), progress), subtitleMaxLines = 1,
            onClick = { tick++; picking = true },
            trailing = { Chevron() },
        )
    }
    if (inGroup) row() else SettingsGroup { row() }
    if (picking) {
        IrisDialog(
            onDismiss = { picking = false }, title = I18n.s(R.string.kb251_embed_row),
            confirmText = I18n.s(R.string.chat_625fb2), onConfirm = { picking = false }, dismissText = null,
        ) {
            Column {
                fun choose(id: String?) { sem.chosen = id; tick++; picking = false }
                CheckRow(I18n.s(R.string.kb251_embed_auto), chosen == null) { choose(null) }
                KbEmbedModels.ALL.forEach { m ->
                    val have = installed.any { it.id == m.id }
                    // a model not downloaded yet opens its card in 下载模型 (download button there).
                    val dl = DownloadNav.opener
                    CheckRow(m.name, chosen == m.id, subtitle = if (have) null else I18n.s(if (dl != null) R.string.kb262_embed_tap_dl else R.string.kb251_embed_not_downloaded)) {
                        if (have) choose(m.id)
                        else if (dl != null) { picking = false; DownloadNav.open(m.repo) }
                    }
                }
                CheckRow(I18n.s(R.string.kb251_embed_off_choice), chosen == KbEmbedModels.OFF) { choose(KbEmbedModels.OFF) }
                if (progress.running) CheckRow(I18n.s(R.string.kb251_embed_stop), false) { sem.cancel(); picking = false }
            }
        }
    }
}
