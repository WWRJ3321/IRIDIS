package com.iris.localai.knowledge

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

/** 2.5.1: the subtitle of the 嵌入模型 row on the 知识库 page. Pure. */
object KbEmbedText {
    fun line(model: KbEmbedModels.Model?, chosen: String?, anyInstalled: Boolean, p: KbSemantic.Progress): String = when {
        chosen == KbEmbedModels.OFF -> I18n.s(R.string.kb251_embed_off)
        model == null || !anyInstalled -> I18n.s(R.string.kb251_embed_none)
        p.modelId == model.id && p.running -> I18n.s(R.string.kb251_embed_progress, model.name, p.done, p.total)
        p.modelId == model.id && p.failed -> I18n.s(R.string.kb251_embed_failed, model.name)
        else -> I18n.s(R.string.kb251_embed_ready, model.name)
    }
}

/**
 * 2.5.1 知识库页 one row: which embedding model the 语义检索 uses (自动 / a downloaded one / 关闭), the vector fill
 * progress, and — when none is downloaded — a one-line pointer to 下载模型 › 知识库嵌入模型.
 */
@Composable
fun KbEmbedRow(kb: KnowledgeBase) {
    val sem = kb.semantic ?: return
    val progress by sem.progress.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val installed by produceState(emptyList<KbEmbedModels.Model>(), tick, progress.running) {
        value = withContext(Dispatchers.IO) { runCatching { KbEmbedModels.installed() }.getOrDefault(emptyList()) }
    }
    val chosen = remember(tick) { sem.chosen }
    val model = KbEmbedModels.pick(chosen, installed.map { it.id })
    SettingsGroup {
        SettingsRow(
            I18n.s(R.string.kb251_embed_row), icon = IrisIcons.Search,
            subtitle = KbEmbedText.line(model, chosen, installed.isNotEmpty(), progress), subtitleMaxLines = 1,
            onClick = { tick++; picking = true },
            trailing = { com.iris.localai.ui.components.Chevron() },
        )
    }
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
                    CheckRow(m.name, chosen == m.id, subtitle = if (have) null else I18n.s(R.string.kb251_embed_not_downloaded)) { if (have) choose(m.id) }
                }
                CheckRow(I18n.s(R.string.kb251_embed_off_choice), chosen == KbEmbedModels.OFF) { choose(KbEmbedModels.OFF) }
                if (progress.running) CheckRow(I18n.s(R.string.kb251_embed_stop), false) { sem.cancel(); picking = false }
            }
        }
    }
}
