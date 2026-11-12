package com.iris.localai.ui.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.I18n
import com.iris.localai.IrisApp
import com.iris.localai.R
import com.iris.localai.character.CardCodec
import com.iris.localai.character.CharacterCard
import com.iris.localai.character.CharacterStore
import com.iris.localai.character.Lorebook
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.HairlineDivider
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2.3.2 角色卡 page (sidebar › 角色): list (avatar, name, one line), tap = start a chat with it;
 * ⋮ = edit / export PNG / export JSON / delete; 新建 and 导入 (PNG / JSON / CHARX) at the top.
 */
@Composable
fun CharactersPage(onBack: () -> Unit, onStartChat: (String) -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IrisApp).container }
    val store = container.characters
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<CharacterStore.Entry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CharacterStore.Entry?>(null) }
    var exporting by remember { mutableStateOf<Pair<CharacterStore.Entry, Boolean>?>(null) } // (card, png?)
    val c = Iris.colors
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    val cards by produceState<List<CharacterStore.Entry>?>(null, tick) {
        value = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment.orEmpty()
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { inp ->
                        val b = inp.readBytes(); if (b.size > CardCodec.MAX_BYTES) throw CardCodec.CardFormatException(CardCodec.CardFormatException.Kind.TOO_BIG); b
                    }
                    val imp = CardCodec.decode(bytes, name)
                    val ext = imp.avatar?.let { CharacterStore.imageExt(it) }
                    store.save(imp.card, avatar = imp.avatar?.takeIf { ext != null }, avatarExt = ext ?: "png", assets = imp.assets)
                    I18n.s(R.string.ch232_imported, imp.card.name.ifBlank { "?" }, imp.format)
                }.getOrElse { e ->
                    when ((e as? CardCodec.CardFormatException)?.kind) {
                        CardCodec.CardFormatException.Kind.PNG_NO_CARD -> I18n.s(R.string.ch232_err_png)
                        CardCodec.CardFormatException.Kind.TOO_BIG -> I18n.s(R.string.ch232_err_big)
                        else -> I18n.s(R.string.ch232_err_format)
                    }
                }
            }
            toast(msg); tick++
        }
    }

    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        val e = exporting?.first; exporting = null
        if (uri == null || e == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val png = e.avatar?.let { pngOf(it.readBytes()) } ?: CardCodec.solidPng()
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(CardCodec.encodePng(e.card, png)) }
                }.isSuccess
            }
            toast(I18n.s(if (ok) R.string.ch232_exported else R.string.kb232_failed))
        }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val e = exporting?.first; exporting = null
        if (uri == null || e == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(e.card.toJson().toByteArray()) } }.isSuccess
            }
            toast(I18n.s(if (ok) R.string.ch232_exported else R.string.kb232_failed))
        }
    }

    // 2.5.3: the editor pushes in / pops out with the app's page transition (it used to swap in place).
    val editorKey: Any? = if (creating) EDITOR_NEW else editing
    val reduceMotion = com.iris.localai.ui.theme.LocalReduceMotion.current
    androidx.compose.animation.AnimatedContent(
        targetState = editorKey,
        contentKey = { k -> (k as? CharacterStore.Entry)?.id ?: k },
        transitionSpec = {
            if (reduceMotion) com.iris.localai.ui.motion.IrisMotion.none
            else com.iris.localai.ui.motion.IrisMotion.push(forward = targetState != null)
        },
        label = "characterEditor",
    ) { target ->
    if (target != null) {
        CharacterEditor(
            entry = target as? CharacterStore.Entry,
            onClose = { editing = null; creating = false },
            onSaved = { editing = null; creating = false; tick++; toast(I18n.s(R.string.kb232_saved)) },
        )
    } else
    SubpageScaffold(I18n.s(R.string.ch232_title), onBack) {
        ScrollBody {
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                I18n.s(R.string.ch232_new), { creating = true }, icon = Icons.Outlined.Add,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16),
            )
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                I18n.s(R.string.ch232_import), { pick.launch(arrayOf("image/png", "application/json", "application/zip", "application/octet-stream", "*/*")) },
                style = IrisButtonStyle.Tonal, icon = IrisIcons.Folder,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16),
            )
            Spacer(Modifier.height(IrisDimens.space16))
            val list = cards
            when {
                list == null -> Text(I18n.s(R.string.chat_62a33a), style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.padding(IrisDimens.space16))
                list.isEmpty() -> Text(I18n.s(R.string.ch232_empty), style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.padding(IrisDimens.space16))
                else -> SettingsGroup {
                    list.forEachIndexed { i, e ->
                        if (i > 0) HairlineDivider(start = IrisDimens.space16 + 44.dp + IrisDimens.space12)
                        CardRow(
                            e, onOpen = { onStartChat(e.id) }, onEdit = { editing = e },
                            onExportPng = { exporting = e to true; savePng.launch(fileBase(e) + ".png") },
                            onExportJson = { exporting = e to false; saveJson.launch(fileBase(e) + ".json") },
                            onDelete = { deleting = e },
                        )
                    }
                }
            }
            GroupFootnote(I18n.s(R.string.ch232_note, store.dir().path))
        }
    }
    }

    deleting?.let { e ->
        ConfirmDialog(
            title = I18n.s(R.string.ch232_delete_q, e.card.name),
            message = I18n.s(R.string.ch232_delete_msg),
            confirmText = I18n.s(R.string.chat_2f4aad),
            onConfirm = { scope.launch { withContext(Dispatchers.IO) { store.delete(e.id) }; tick++ } },
            onDismiss = { deleting = null },
        )
    }
}

/** 2.5.3: AnimatedContent key of 「新建角色」 (an existing card is keyed by its entry). */
private const val EDITOR_NEW = "new"

private fun fileBase(e: CharacterStore.Entry) = CharacterStore.slug(e.card.name).ifEmpty { e.id }

/** Any image bytes → PNG (as is when already PNG). */
internal fun pngOf(bytes: ByteArray): ByteArray? {
    if (CardCodec.isPng(bytes)) return bytes
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

/** A picked image scaled to at most 512 px, as PNG. */
private fun avatarPng(bytes: ByteArray): ByteArray? {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val scale = minOf(1f, 512f / maxOf(bmp.width, bmp.height))
    val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
    return java.io.ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

@Composable
private fun CardRow(e: CharacterStore.Entry, onOpen: () -> Unit, onEdit: () -> Unit, onExportPng: () -> Unit, onExportJson: () -> Unit, onDelete: () -> Unit) {
    val c = Iris.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = I18n.s(R.string.ch232_start), onClick = onOpen)
            .padding(start = IrisDimens.space16, top = IrisDimens.space12, bottom = IrisDimens.space12, end = IrisDimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(e.avatar, e.card.name, 44.dp)
        Spacer(Modifier.width(IrisDimens.space12))
        Column(Modifier.weight(1f)) {
            Text(e.card.name.ifBlank { e.id }, style = IrisType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = e.card.blurb()
            if (line.isNotEmpty()) Text(line, style = IrisType.secondary, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box {
            PressableIcon(IrisIcons.More, I18n.s(R.string.agent_0ec9ea), size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { menu = true }
            IrisMenu(menu, { menu = false }) {
                IrisMenuItem(I18n.s(R.string.ch232_start), IrisIcons.Chat) { menu = false; onOpen() }
                IrisMenuItem(I18n.s(R.string.kb232_edit), IrisIcons.Edit) { menu = false; onEdit() }
                IrisMenuItem(I18n.s(R.string.ch232_export_png), IrisIcons.Image) { menu = false; onExportPng() }
                IrisMenuItem(I18n.s(R.string.ch232_export_json), null) { menu = false; onExportJson() }
                IrisMenuItem(I18n.s(R.string.chat_2f4aad), null, destructive = true) { menu = false; onDelete() }
            }
        }
    }
}

/** Round avatar from [file]; the first letter of [name] on an accent circle when there is none. */
@Composable
fun CharacterAvatar(file: java.io.File?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file?.path, file?.lastModified()) {
        value = file?.let { f -> withContext(Dispatchers.IO) { runCatching { decodeSmall(f, 256)?.asImageBitmap() }.getOrNull() } }
    }
    val b = bmp
    if (b != null) Image(b, name, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    else Box(modifier.size(size).clip(CircleShape).background(c.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).ifEmpty { "?" }, color = c.accent, fontSize = (size.value * 0.42f).sp, style = IrisType.body)
    }
}

private fun decodeSmall(f: java.io.File, max: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}

/** Create / edit one card. Simple fields first; the advanced ones are folded under 「高级」. */
@Composable
private fun CharacterEditor(entry: CharacterStore.Entry?, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IrisApp).container }
    val scope = rememberCoroutineScope()
    val c = Iris.colors
    val base = remember { entry?.card ?: CharacterCard.blank() }
    var name by remember { mutableStateOf(base.name) }
    var description by remember { mutableStateOf(base.description) }
    var personality by remember { mutableStateOf(base.personality) }
    var scenario by remember { mutableStateOf(base.scenario) }
    var firstMes by remember { mutableStateOf(base.firstMes) }
    val alternates = remember { mutableStateListOf<String>().apply { addAll(base.alternateGreetings) } }
    var examples by remember { mutableStateOf(base.mesExample) }
    var system by remember { mutableStateOf(base.systemPrompt) }
    var post by remember { mutableStateOf(base.postHistory) }
    var tags by remember { mutableStateOf(base.tags.joinToString(", ")) }
    var notes by remember { mutableStateOf(base.creatorNotes) }
    var model by remember { mutableStateOf(base.bindModel) }
    var ttsPack by remember { mutableStateOf(base.bindTtsPack) }
    var ttsSpeaker by remember { mutableIntStateOf(base.bindTtsSpeaker) }
    var cloneVoice by remember { mutableStateOf(base.bindCloneVoice) }
    var imageStyle by remember { mutableStateOf(base.bindImageStyle) }
    var avatar by remember { mutableStateOf<ByteArray?>(null) }
    // 2.3.5: imported cards that use the split fields open 高级 so nothing is hidden
    var advanced by remember { mutableStateOf(base.personality.isNotBlank() || base.scenario.isNotBlank() || base.alternateGreetings.isNotEmpty()) }
    var modelMenu by remember { mutableStateOf(false) }
    val models by container.models.models.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val png = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)!!.use { avatarPng(it.readBytes()) } }.getOrNull() }
            if (png == null) Toast.makeText(context, I18n.s(R.string.ch232_err_image), Toast.LENGTH_SHORT).show() else avatar = png
        }
    }

    fun build(): CharacterCard = base.withData(
        "name" to CharacterCard.text(name.trim()),
        "description" to CharacterCard.text(description),
        "personality" to CharacterCard.text(personality),
        "scenario" to CharacterCard.text(scenario),
        "first_mes" to CharacterCard.text(firstMes),
        "alternate_greetings" to CharacterCard.list(alternates.filter { it.isNotBlank() }),
        "mes_example" to CharacterCard.text(examples),
        "system_prompt" to CharacterCard.text(system),
        "post_history_instructions" to CharacterCard.text(post),
        "tags" to CharacterCard.list(tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }),
        "creator_notes" to CharacterCard.text(notes),
        "modification_date" to kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis() / 1000),
    ).let { if (entry == null) it.withData("creation_date" to kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis() / 1000)) else it }
        .withBindings(model = model, ttsPack = ttsPack, ttsSpeaker = ttsSpeaker, cloneVoice = cloneVoice, imageStyle = imageStyle)

    SubpageScaffold(
        I18n.s(if (entry == null) R.string.ch232_new else R.string.ch232_edit), onClose,
        actions = {
            IrisButton(I18n.s(R.string.settings_be5fbb), {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { container.characters.save(build(), entry?.id, avatar) }.isSuccess }
                    if (ok) onSaved() else Toast.makeText(context, I18n.s(R.string.kb232_failed), Toast.LENGTH_SHORT).show()
                }
            }, enabled = name.isNotBlank())
        },
    ) {
        ScrollBody {
            Spacer(Modifier.height(IrisDimens.space8))
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16), verticalAlignment = Alignment.CenterVertically) {
                val preview = remember(avatar) { avatar?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() } }
                Box(Modifier.clip(CircleShape).clickable { pickImage.launch(arrayOf("image/*")) }) {
                    if (preview != null) Image(preview, null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else CharacterAvatar(entry?.avatar, name, 72.dp)
                }
                Spacer(Modifier.width(IrisDimens.space16))
                Text(I18n.s(R.string.ch232_avatar_hint), style = IrisType.secondary, color = c.textSecondary)
            }
            Field(I18n.s(R.string.ch232_f_name), name, { name = it }, single = true)
            // 2.3.5: one prompt box is enough — 设定 is written straight as a prompt (stored as the card's
            // description). The tavern-card split fields (性格/场景/备选开场白/标签) live under 高级.
            Field(I18n.s(R.string.ch235_f_prompt), description, { description = it }, hint = I18n.s(R.string.ch235_h_prompt), minLines = 6)
            Field(I18n.s(R.string.ch232_f_first), firstMes, { firstMes = it }, hint = I18n.s(R.string.ch235_h_first))
            SectionTitle(I18n.s(R.string.ch232_bind_title))
            SettingsGroup {
                Box {
                    SettingsRow(
                        I18n.s(R.string.ch232_bind_model),
                        subtitle = model.ifBlank { I18n.s(R.string.ch232_follow_current) } + if (model.isNotBlank() && models.none { it.fileName == model }) " · " + I18n.s(R.string.ch232_missing) else "",
                        onClick = { modelMenu = true },
                    )
                    IrisMenu(modelMenu, { modelMenu = false }) {
                        IrisMenuItem(I18n.s(R.string.ch232_follow_current), null) { model = ""; modelMenu = false }
                        models.forEach { m -> IrisMenuItem(m.displayName, null) { model = m.fileName; modelMenu = false } }
                    }
                }
                HairlineDivider()
                SettingsRow(
                    I18n.s(R.string.ch232_bind_voice),
                    subtitle = if (ttsPack.isBlank()) I18n.s(R.string.ch232_follow_current) else I18n.s(R.string.ch232_voice_set, ttsPack + (if (cloneVoice.isNotBlank()) " · ${container.cloneVoices.get(cloneVoice)?.name ?: cloneVoice}" else if (ttsSpeaker >= 0) " #$ttsSpeaker" else "")),
                    onClick = {
                        if (ttsPack.isBlank()) { val s = container.settings.current; ttsPack = s.ttsPack; ttsSpeaker = s.ttsSpeakerId; cloneVoice = s.cloneVoiceId }
                        else { ttsPack = ""; ttsSpeaker = -1; cloneVoice = "" }
                    },
                )
                HairlineDivider()
                Field(I18n.s(R.string.ch232_bind_style), imageStyle, { imageStyle = it }, single = true, hint = I18n.s(R.string.ch232_h_style))
            }
            GroupFootnote(I18n.s(R.string.ch232_bind_note))

            SettingsGroup {
                SettingsRow(I18n.s(R.string.ch232_advanced), subtitle = I18n.s(R.string.ch235_advanced_sub), onClick = { advanced = !advanced },
                    trailing = { Text(if (advanced) "−" else "+", style = IrisType.body, color = c.textSecondary, modifier = Modifier.padding(end = IrisDimens.space8)) })
            }
            if (advanced) {
                Field(I18n.s(R.string.ch232_f_personality), personality, { personality = it })
                Field(I18n.s(R.string.ch232_f_scenario), scenario, { scenario = it })
                alternates.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Field(I18n.s(R.string.ch232_f_alt, i + 1), g, { alternates[i] = it }) }
                        PressableIcon(IrisIcons.Close, I18n.s(R.string.chat_2f4aad), size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { alternates.removeAt(i) }
                    }
                }
                IrisButton(I18n.s(R.string.ch232_add_alt), { alternates.add("") }, style = IrisButtonStyle.Tonal, compact = true, icon = Icons.Outlined.Add,
                    modifier = Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space4))
                Field(I18n.s(R.string.ch232_f_tags), tags, { tags = it }, single = true, hint = I18n.s(R.string.ch232_h_tags))
                Field(I18n.s(R.string.ch232_f_examples), examples, { examples = it }, hint = I18n.s(R.string.ch232_h_examples))
                Field(I18n.s(R.string.ch232_f_system), system, { system = it }, hint = I18n.s(R.string.ch232_h_system))
                Field(I18n.s(R.string.ch232_f_post), post, { post = it })
                Field(I18n.s(R.string.ch232_f_notes), notes, { notes = it })
                val book = remember { Lorebook.of(base) }
                if (book != null) GroupFootnote(I18n.s(R.string.ch232_lore, book.entries.count { it.enabled }))
            }
            Spacer(Modifier.height(IrisDimens.space16))
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, single: Boolean = false, hint: String? = null, minLines: Int = 2) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space4)) {
        Text(label, style = IrisType.caption, color = c.textSecondary, modifier = Modifier.padding(start = IrisDimens.space4, bottom = IrisDimens.space2))
        androidx.compose.material3.OutlinedTextField(
            value, onChange, singleLine = single, minLines = if (single) 1 else minLines, maxLines = if (single) 1 else maxOf(10, minLines),
            textStyle = IrisType.body.copy(color = c.text),
            placeholder = hint?.let { h -> { Text(h, style = IrisType.secondary, color = c.textTertiary) } },
            shape = IrisRadius.mediumShape,
            colors = com.iris.localai.ui.components.irisTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
