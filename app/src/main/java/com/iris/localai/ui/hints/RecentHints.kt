package com.iris.localai.ui.hints

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.util.HintLog

/** 2.2.17 D: 「最近提示」 sheet — every recent banner / toast, full text, selectable. */
@Composable
fun RecentHintsSheet(onDismiss: () -> Unit) {
    val c = Iris.colors
    val items by HintLog.items.collectAsState()
    IrisSheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter), verticalAlignment = Alignment.CenterVertically) {
            Text(com.iris.localai.I18n.s(com.iris.localai.R.string.hints_f3439f), style = IrisType.title, color = c.text, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_288f0c), HintLog::clear, style = IrisButtonStyle.Text, compact = true)
        }
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
        Column(Modifier.fillMaxWidth().heightIn(max = maxH).verticalScroll(rememberScrollState()).padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space8)) {
            if (items.isEmpty()) Text(com.iris.localai.I18n.s(com.iris.localai.R.string.hints_2aa024), style = IrisType.body, color = c.textTertiary, modifier = Modifier.padding(vertical = IrisDimens.space16))
            items.forEach { h ->
                Column(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space8)) {
                    Text(
                        RecentHints.meta(h, System.currentTimeMillis()),
                        style = IrisType.caption, color = c.textTertiary,
                    )
                    SelectionContainer { Text(h.text, style = IrisType.body, color = c.text) }
                }
            }
        }
    }
}

object RecentHints {
    /** 「模型加载 · 3 分钟前 · ×2」. */
    fun meta(h: HintLog.Hint, now: Long): String {
        val s = ((now - h.at) / 1000).coerceAtLeast(0)
        val ago = when { s < 60 -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_4181f7); s < 3600 -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_aea893, s / 60); s < 86400 -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_d935fb, s / 3600); else -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_b24ae9, s / 86400) }
        return listOfNotNull(h.source, ago, if (h.count > 1) "×${h.count}" else null).joinToString(" · ")
    }

    /** Row subtitle in 模型与加载: the newest hint, one line. */
    fun subtitle(items: List<HintLog.Hint>): String = items.firstOrNull()?.text?.lineSequence()?.firstOrNull()?.take(60) ?: com.iris.localai.I18n.s(com.iris.localai.R.string.hints_f61f4c)
}
