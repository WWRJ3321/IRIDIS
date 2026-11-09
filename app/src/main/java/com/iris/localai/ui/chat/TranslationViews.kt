package com.iris.localai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.translate.Translator
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.TypingDots
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.delay

/** 2.3.0 ③ 本地翻译: texts (unit-tested). */
object TranslateText {
    val TRANSLATE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8b3607)
    val SHOW_ORIGINAL: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7fa213)
    val SHOW_TRANSLATION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_284fd4)
    val CLOSE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_bab184)
    val TRANSLATING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d03cc1)
    val UNDO: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_107cac)
    val DRAFT_AUTO: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_a72abb)
    val DRAFT_ZH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1501c2)
    val DRAFT_EN: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_aa8715)
    fun header(target: String) = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8254a7, target)
}

@Composable
internal fun rememberTranslator(): Translator {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { (ctx as IrisApp).container.translator }
}

/** The translation of message [id] under it: header (译文 · 英文) + 显示原文 toggle + copy + close. */
@Composable
internal fun TranslationBlock(id: String, modifier: Modifier = Modifier) {
    val translator = rememberTranslator()
    val all by translator.messages.collectAsStateWithLifecycle()
    val t = all[id]
    AnimatedVisibility(t != null, enter = fadeIn(IrisMotion.enter()), exit = fadeOut(IrisMotion.standard())) {
        if (t == null) return@AnimatedVisibility
        val c = Iris.colors
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
        Column(
            modifier
                .padding(top = IrisDimens.space6)
                .background(c.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(com.iris.localai.ui.theme.IrisCorner.S))
                .padding(start = IrisDimens.space12, end = IrisDimens.space4, top = IrisDimens.space4, bottom = IrisDimens.space8),
            verticalArrangement = Arrangement.spacedBy(IrisDimens.space4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(com.iris.localai.ui.icons.IrisIcons.Translate, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(IrisDimens.space6))
                Text(TranslateText.header(t.target.label), style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                if (t.running) TypingDots()
                IrisButton(
                    if (t.folded) TranslateText.SHOW_TRANSLATION else TranslateText.SHOW_ORIGINAL,
                    { translator.toggleOriginal(id) }, style = IrisButtonStyle.Text, compact = true,
                )
                if (!t.folded && t.text.isNotEmpty()) PressableIcon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_52e6ab) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_cee9c3),
                    size = 32.dp, iconSize = 16.dp, tint = c.textSecondary,
                ) { clipboard.setText(AnnotatedString(t.text)); copied = true }
                PressableIcon(Icons.Outlined.Close, TranslateText.CLOSE, size = 32.dp, iconSize = 16.dp, tint = c.textSecondary) { translator.close(id) }
            }
            if (!t.folded) when {
                t.error != null -> Text(t.error, style = IrisType.secondary, color = c.danger, modifier = Modifier.padding(end = IrisDimens.space8))
                t.text.isEmpty() -> Text(TranslateText.TRANSLATING, style = IrisType.secondary, color = c.textTertiary)
                else -> Text(t.text, style = IrisType.message, color = c.text, modifier = Modifier.padding(end = IrisDimens.space8))
            }
        }
    }
}
