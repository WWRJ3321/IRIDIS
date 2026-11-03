package com.iris.localai.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.iris.localai.ui.components.Chevron
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.PageDescription
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One bundled third-party license: `assets/licenses/index.json` entry. */
data class LicenseEntry(val name: String, val license: String, val file: String, val url: String)

/** 1.14.7: reads `assets/licenses/index.json`; a missing or malformed file gives an empty list. */
object LicenseIndex {
    const val DIR = "licenses"
    const val INDEX = "$DIR/index.json"

    /** Accepts a top-level array, or an object holding the array under "licenses" / "entries" / "components". */
    fun parse(json: String): List<LicenseEntry> = runCatching {
        val arr = when (val root = Json.parseToJsonElement(json)) {
            is JsonArray -> root
            is JsonObject -> (root["licenses"] ?: root["entries"] ?: root["components"]) as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun f(k: String) = ((o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim()
            val name = f("name")
            if (name.isEmpty()) null else LicenseEntry(name, f("license"), f("file"), f("url"))
        }
    }.getOrDefault(emptyList())

    fun load(context: Context): List<LicenseEntry> =
        runCatching { context.assets.open(INDEX).bufferedReader().use { it.readText() } }
            .map(::parse).getOrDefault(emptyList())

    /** License text for [file]; null when it is missing. Only plain file names under licenses/ are read. */
    fun text(context: Context, file: String): String? {
        if (file.isBlank() || file.contains("..") || file.startsWith("/")) return null
        return runCatching { context.assets.open("$DIR/$file").bufferedReader().use { it.readText() } }.getOrNull()
    }
}

/** 设置 › 关于 › 许可证全文: bundled third-party licenses; tapping one shows its full text. */
@Composable
fun LicensesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }
    val entries by produceState<List<LicenseEntry>?>(null) {
        value = withContext(Dispatchers.IO) { LicenseIndex.load(context) }
    }
    val open = openFile?.let { f -> entries?.firstOrNull { it.file == f } }
    if (open != null) {
        BackHandler { openFile = null }
        LicenseTextPage(open, onBack = { openFile = null })
        return
    }
    BackHandler(onBack = onBack)
    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4236d8), onBack) {
        ScrollBody {
            val list = entries
            when {
                list == null -> {}
                list.isEmpty() -> PageDescription(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ee089e))
                else -> {
                    PageDescription(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a638b9))
                    SettingsGroup {
                        list.forEachIndexed { i, e ->
                            if (i > 0) Divider()
                            SettingsRow(
                                e.name,
                                subtitle = e.license.ifBlank { null },
                                enabled = e.file.isNotBlank(),
                                onClick = { openFile = e.file },
                                trailing = { Chevron() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseTextPage(entry: LicenseEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = Iris.colors
    val text by produceState<String?>(null, entry.file) {
        value = withContext(Dispatchers.IO) { LicenseIndex.text(context, entry.file) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2cd607, entry.file)
    }
    SubpageScaffold(entry.name, onBack) {
        ScrollBody {
            if (entry.license.isNotBlank()) PageDescription(entry.license)
            if (entry.url.isNotBlank()) {
                IrisButton(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9e910f), {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    style = IrisButtonStyle.Text, compact = true,
                    modifier = Modifier.padding(horizontal = IrisDimens.pageGutter),
                )
            }
            text?.let { t ->
                SelectionContainer {
                    Text(
                        t, style = IrisType.monoSmall, color = c.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space12),
                    )
                }
            }
        }
    }
}
