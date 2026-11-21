package com.iris.localai.ui.prompts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.prompts.PromptKey
import com.iris.localai.prompts.Prompts
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.settings.SettingsViewModel
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisType

/** 3.0 设置 › 提示词：注入模型的每段固定提示词，点开改写或恢复默认。 */
object PromptsText {
    fun title(k: PromptKey): String = when (k) {
        PromptKey.AGENT_RULES -> I18n.s(R.string.v30_pk_agent_rules)
        PromptKey.AGENT_ASK_FIRST -> I18n.s(R.string.v30_pk_ask_first)
        PromptKey.VOICE_NOTE -> I18n.s(R.string.v30_pk_voice)
        PromptKey.KB_INJECT -> I18n.s(R.string.v30_pk_kb)
        PromptKey.TRANSLATE_SYSTEM -> I18n.s(R.string.v30_pk_translate)
        PromptKey.IMAGE_EXPAND -> I18n.s(R.string.v30_pk_image)
        PromptKey.IMAGE_EXPAND_TAGS -> I18n.s(R.string.v30_pk_image_tags)
        PromptKey.FLOAT_SCREEN -> I18n.s(R.string.v30_pk_screen)
        PromptKey.FLOAT_ASK -> I18n.s(R.string.v30_pk_ask)
    }

    fun group(g: PromptKey.Group): String = when (g) {
        PromptKey.Group.CHAT -> I18n.s(R.string.v30_pg_chat)
        PromptKey.Group.AGENT -> I18n.s(R.string.v30_pg_agent)
        PromptKey.Group.TOOLS -> I18n.s(R.string.v30_pg_tools)
    }
}

@Composable
fun PromptsPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val c = Iris.colors
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    SubpageScaffold(I18n.s(R.string.v30_prompts), onBack) {
        ScrollBody {
            Text(
                I18n.s(R.string.v30_prompts_sub), style = IrisType.secondary, color = c.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = com.iris.localai.ui.theme.IrisDimens.pageGutter, vertical = com.iris.localai.ui.theme.IrisDimens.space8),
            )
            for (g in PromptKey.Group.entries) {
                val keys = PromptKey.entries.filter { it.group == g }
                if (keys.isEmpty()) continue
                SectionTitle(PromptsText.group(g))
                SettingsGroup {
                    keys.forEachIndexed { i, k ->
                        if (i > 0) Divider()
                        val custom = !s.promptOverrides[k.id].isNullOrBlank()
                        SettingsRow(
                            PromptsText.title(k),
                            subtitle = (if (custom) I18n.s(R.string.v30_prompt_custom) + " · " else "") +
                                (s.promptOverrides[k.id] ?: Prompts.default(k)).replace('\n', ' ').take(60),
                            subtitleMaxLines = 2,
                            onClick = { editing = k.id },
                        )
                    }
                }
            }
        }
    }
    val key = editing?.let { PromptKey.byId(it) }
    if (key != null) {
        var text by rememberSaveable(key.id) { mutableStateOf(s.promptOverrides[key.id] ?: Prompts.default(key)) }
        IrisDialog(
            onDismiss = { editing = null },
            title = PromptsText.title(key),
            confirmText = I18n.s(R.string.v30_prompt_save),
            onConfirm = {
                val v = Prompts.normalize(key, text)
                vm.update { st -> st.copy(promptOverrides = if (v == null) st.promptOverrides - key.id else st.promptOverrides + (key.id to v)) }
                editing = null
            },
            dismissText = I18n.s(R.string.v30_prompt_reset),
            onDismissClick = {
                vm.update { st -> st.copy(promptOverrides = st.promptOverrides - key.id) }
                editing = null
            },
        ) {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    text, { text = it }, colors = irisTextFieldColors(), textStyle = IrisType.secondary.copy(color = c.text),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                )
                if (key.vars.isNotEmpty()) Text(
                    I18n.s(R.string.v30_prompt_vars, key.vars.joinToString(" ") { "{$it}" }),
                    style = IrisType.caption, color = c.textTertiary,
                )
            }
        }
    }
}
