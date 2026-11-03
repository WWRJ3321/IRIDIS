package com.iris.localai.ui.settings

import androidx.compose.runtime.Composable
import com.iris.localai.data.AppSettings
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.icons.IrisIcons

/** 2.2 外观 › 聊天内容: rich rendering switches (kept in their own file so 外观 only gains one call). */
object RenderSettingsText {
    val SECTION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_204d38)
    val HTML: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_322e4c)
    val HTML_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d28b3a)
    val IMAGES: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c0a0b2)
    val IMAGES_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be4e07)
}

@Composable
fun RenderSettingsSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    SectionTitle(RenderSettingsText.SECTION)
    SettingsGroup {
        SwitchRow(RenderSettingsText.HTML, s.renderHtml, subtitle = RenderSettingsText.HTML_SUB, icon = IrisIcons.Code) { on -> update { it.copy(renderHtml = on) } }
        Divider()
        SwitchRow(RenderSettingsText.IMAGES, s.loadNetworkImages, subtitle = RenderSettingsText.IMAGES_SUB, icon = IrisIcons.Image) { on -> update { it.copy(loadNetworkImages = on) } }
    }
}
