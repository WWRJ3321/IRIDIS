package com.iris.localai.ui.chat

import com.iris.localai.I18n
import com.iris.localai.memory.MemoryTarget
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.IrisRadio
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.IrisSpace
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.snap
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.iris.localai.R
import com.iris.localai.data.Role
import com.iris.localai.data.LocalModel
import com.iris.localai.ui.components.floatingSurface
import com.iris.localai.ui.components.glassSurface
import com.iris.localai.ui.components.glassBackdrop
import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.EngineState
import com.iris.localai.server.ApiService
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.glass.GlassStyle
import com.iris.localai.ui.glass.glass
import com.iris.localai.ui.glass.glassSource
import com.iris.localai.ui.glass.rememberGlassBackdrop
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.chatScaled
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.motion.motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// split out of ChatScreen.kt unchanged — empty state, 记住这条 dialog, queued bubble, menu texts.

@Composable
internal fun ChatEmptyState(
    modelName: String?,
    engine: EngineState,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val appear = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) appear.animateTo(1f, IrisMotion.enter()) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = topPadding, bottom = bottomPadding)
            .padding(horizontal = IrisDimens.space32)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 18.dp.toPx()
                val s = 0.97f + 0.03f * appear.value
                scaleX = s; scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // the mark is decorative again (tap-to-load removed); it still breathes while a model loads.
        Icon(
            painterResource(R.drawable.ic_iris_mark), null,
            tint = c.text,
            modifier = Modifier
                .padding(IrisSpace.XS)
                .size(44.dp)
                .pulse(engine is EngineState.Loading, min = 0.35f, periodMs = 1100),
        )
        Spacer(Modifier.height(IrisDimens.space20))
        Text(I18n.s(R.string.chat_61295b), style = IrisType.display, color = c.text, textAlign = TextAlign.Center)
        GetStartedCard(engine)
    }
}

/** 3.0.0 Beta4：空对话页一步上手——没模型就一键下载本机推荐；r2 去掉「加载 X」（发消息时自动加载）。 */
@Composable
private fun GetStartedCard(engine: EngineState) {
    val vm: com.iris.localai.ui.models.ModelsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val models by vm.models.collectAsStateWithLifecycle()
    val hub by vm.hub.collectAsStateWithLifecycle()
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val rec = remember {
        val man = if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { android.os.Build.SOC_MANUFACTURER }.getOrNull() else null
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { android.os.Build.SOC_MODEL }.getOrNull() else android.os.Build.HARDWARE
        com.iris.localai.data.DeviceRecommend.pick(vm.totalRam(), com.iris.localai.data.DeviceRecommend.chip(man, soc))
    }
    val chatModels = remember(models) { models.filterNot { com.iris.localai.knowledge.KbEmbedModels.isEmbedFile(it.fileName) } }
    val step = com.iris.localai.data.GetStarted.step(
        installed = chatModels.size,
        ready = engine is EngineState.Ready,
        loading = engine is EngineState.Loading,
        failed = engine is EngineState.Failed,
        downloading = hub.active,
        hasRecommend = rec != null,
    )
    val target = remember(chatModels, settings.defaultModelFile, settings.lastModelFile) {
        chatModels.firstOrNull { it.fileName == settings.defaultModelFile }
            ?: chatModels.firstOrNull { it.fileName == settings.lastModelFile }
            ?: chatModels.firstOrNull()
    }
    androidx.compose.animation.AnimatedVisibility(visible = step != com.iris.localai.data.GetStarted.Step.NONE, enter = IrisMotion.riseIn(), exit = IrisMotion.sinkOut()) {
        Column(Modifier.padding(top = IrisDimens.space24), horizontalAlignment = Alignment.CenterHorizontally) {
          // r2：下载 → 下载中 → 重试 之间淡入淡出，不再瞬间换按钮。
          val rm = com.iris.localai.ui.theme.LocalReduceMotion.current
          androidx.compose.animation.AnimatedContent(step, transitionSpec = { if (rm) IrisMotion.none else IrisMotion.crossfade() }, contentAlignment = Alignment.Center, label = "getStarted") { step ->
            when (step) {
                com.iris.localai.data.GetStarted.Step.DOWNLOAD -> rec?.let { r ->
                    com.iris.localai.ui.components.IrisButton(
                        I18n.s(R.string.b4_gs_download, r.name, com.iris.localai.data.Format.bytes(r.sizeBytes)),
                        onClick = { vm.downloadCatalog(r) },
                    )
                }
                com.iris.localai.data.GetStarted.Step.DOWNLOADING -> {
                    com.iris.localai.ui.components.IrisButton(
                        I18n.s(R.string.b4_gs_downloading, rec?.takeIf { it.repoId == hub.repoId }?.name ?: hub.repoId.substringAfterLast('/')) + " · " + (hub.progress * 100).toInt() + "%",
                        onClick = { com.iris.localai.ui.models.DownloadNav.opener?.invoke() },
                        style = com.iris.localai.ui.components.IrisButtonStyle.Tonal,
                    )
                }
                com.iris.localai.data.GetStarted.Step.RETRY -> target?.let { m ->
                    com.iris.localai.ui.components.IrisButton(I18n.s(R.string.b4_gs_retry), onClick = { vm.loadNow(m) }, style = com.iris.localai.ui.components.IrisButtonStyle.Tonal)
                }
                com.iris.localai.data.GetStarted.Step.NONE -> Unit
                else -> Unit
            }
          }
            if (step == com.iris.localai.data.GetStarted.Step.DOWNLOAD) {
                com.iris.localai.ui.components.IrisButton(
                    I18n.s(R.string.b4_gs_more),
                    onClick = { com.iris.localai.ui.models.DownloadNav.opener?.invoke() },
                    style = com.iris.localai.ui.components.IrisButtonStyle.Text,
                    compact = true,
                )
            }
        }
    }
}

/** Seconds since the current load started, ticking once a second while loading. */
@Composable
fun loadingSeconds(engine: EngineState): Long {
    val since = (engine as? EngineState.Loading)?.since ?: return 0
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    return ((now - since) / 1000).coerceAtLeast(0)
}

/** 2.0.x: 「记住这条」 — the line to save (editable) and where it goes: 关于你 (USER.md) or 记住的事 (MEMORY.md). */
@Composable
internal fun RememberDialog(
    initial: String,
    suggested: MemoryTarget,
    onSave: (String, MemoryTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var target by remember { mutableStateOf(suggested) }
    IrisDialog(
        onDismiss = onDismiss, title = VersionText.REMEMBER, confirmText = I18n.s(R.string.chat_f7d9d3),
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onSave(text, target); onDismiss() },
    ) {
        Column(Modifier.fillMaxWidth()) {
            androidx.compose.material3.OutlinedTextField(
                text, { text = it },
                minLines = 2, maxLines = 6,
                textStyle = IrisType.body.copy(color = Iris.colors.text),
                shape = IrisRadius.mediumShape,
                colors = com.iris.localai.ui.components.irisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(IrisSpace.S))
            MemoryTarget.entries.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(IrisRadius.smallShape)
                        .clickable { target = t }.padding(vertical = IrisSpace.S, horizontal = IrisSpace.XS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IrisRadio(selected = target == t)
                    Spacer(Modifier.width(IrisSpace.S))
                    Text(t.label, style = IrisType.body, color = Iris.colors.text)
                }
            }
        }
    }
}

/** a message waiting to be answered (or read by the 智能体); tap cancels it. */
@Composable
internal fun QueuedBubble(q: QueuedMessage, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    val c = Iris.colors
    Box(modifier.fillMaxWidth().padding(start = 52.dp), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .clip(IrisRadius.bubbleShape)
                    .background(c.surfaceVariant.copy(alpha = 0.55f))
                    .clickable(onClickLabel = I18n.s(R.string.chat_9ad32d)) { onCancel() }
                    .padding(horizontal = IrisSpace.M, vertical = IrisSpace.S),
            ) {
                Text(q.text, style = IrisType.message.chatScaled(), color = c.textSecondary)
            }
            Row(Modifier.padding(top = IrisSpace.XS, end = IrisSpace.XS), verticalAlignment = Alignment.CenterVertically) {
                Icon(IrisIcons.Clock, null, tint = c.textTertiary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(IrisSpace.XS))
                Text(
                    (if (q.forAgent) SendIntent.QUEUED_AGENT_LABEL else SendIntent.QUEUED_LABEL) + I18n.s(R.string.chat_6edc06),
                    style = IrisType.labelSmall, color = c.textTertiary,
                )
            }
        }
    }
}

/** the home model menu's single entry to the merged page (unit-tested). */
object ModelMenuText {
    val MODELS_AND_LOADING: String get() = I18n.s(R.string.chat_035516)
}
