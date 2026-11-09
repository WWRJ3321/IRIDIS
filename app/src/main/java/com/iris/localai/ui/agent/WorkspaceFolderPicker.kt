package com.iris.localai.ui.agent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2.3.3: in-app folder browser over the mounted storage volumes (real paths, needs 全部文件访问) for
 * the 自定义位置 agent workspace. [validate] returns a problem text or null; 「选择此文件夹」 picks the
 * folder shown. Hidden folders are not listed.
 */
@Composable
fun WorkspaceFolderPicker(
    volumes: List<File>,
    start: String?,
    validate: (String) -> String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Iris.colors
    val roots = remember(volumes) { volumes.map { it.absoluteFile } }
    var dir by remember {
        mutableStateOf(start?.let { File(it) }?.takeIf { f -> f.isDirectory && roots.any { r -> f.path.startsWith(r.path) } } ?: roots.firstOrNull() ?: File("/storage/emulated/0"))
    }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val subs by produceState(emptyList<File>(), dir, tick) {
        value = withContext(Dispatchers.IO) {
            (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray()).sortedBy { it.name.lowercase() }
        }
    }
    val root = roots.firstOrNull { dir.path == it.path }
    IrisDialog(
        onDismiss = onDismiss, title = I18n.s(R.string.ws233_pick_title),
        confirmText = I18n.s(R.string.ws233_pick_this),
        onConfirm = { val e = validate(dir.path); if (e == null) onPick(dir.path) else error = e },
        onDismissClick = onDismiss,
    ) {
        Column {
            if (roots.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                roots.forEachIndexed { i, r ->
                    com.iris.localai.ui.components.IrisButton(
                        if (i == 0) I18n.s(R.string.ws233_internal) else r.name,
                        { dir = r; error = null },
                        style = com.iris.localai.ui.components.IrisButtonStyle.Tonal, compact = true,
                    )
                    Spacer(Modifier.width(com.iris.localai.ui.theme.IrisSpace.S))
                }
            }
            Text(dir.path, style = IrisType.secondary, color = c.textSecondary, modifier = Modifier.padding(vertical = com.iris.localai.ui.theme.IrisSpace.XS))
            error?.let { Text(it, style = IrisType.secondary, color = c.danger, modifier = Modifier.padding(bottom = com.iris.localai.ui.theme.IrisSpace.XS)) }
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                if (root == null) SettingsRow("..", icon = IrisIcons.Folder, subtitle = I18n.s(R.string.ws233_up), onClick = { dir.parentFile?.let { dir = it }; error = null })
                subs.forEach { f -> SettingsRow(f.name, icon = IrisIcons.Folder, onClick = { dir = f; error = null }) }
                SettingsRow(I18n.s(R.string.kb232_new_folder), icon = IrisIcons.FolderPlus, onClick = { creating = true })
            }
            Spacer(Modifier.height(com.iris.localai.ui.theme.IrisSpace.XS))
        }
    }
    if (creating) com.iris.localai.ui.components.TextInputDialog(
        title = I18n.s(R.string.kb232_new_folder), initial = "", placeholder = I18n.s(R.string.kb232_folder_name),
        onConfirm = { name ->
            val n = name.trim().replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_")
            if (n.isNotEmpty() && !n.startsWith(".")) { val f = File(dir, n); if (runCatching { f.mkdirs() }.getOrDefault(false) || f.isDirectory) { dir = f; error = null } else error = I18n.s(R.string.ws233_p_write) }
            tick++
        },
        onDismiss = { creating = false },
    )
}
