package com.iris.localai.ui.agent

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.SegmentedControl
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1.11.0 texts of the 浏览器 pages (unit-tested). */
object ToolPageText {
    const val BROWSER_NAV = "智能体使用的内置浏览器；可在这里查看或手动登录"
    const val BROWSER_NOTE = "智能体用的就是这个浏览器（Cookie 只保存在 IRIDIS 里，不影响你的其他浏览器）。需要登录的网站可以先在这里手动登录。智能体的「浏览器」工具默认关闭，开启后每次询问。"
}

@Composable
fun BrowserPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val browser = agent.browser
    val st by browser.status.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(st.url) }
    LaunchedEffect(st.url) { url = st.url }
    val c = Iris.colors
    val web = remember { browser.webView() }
    DisposableEffect(Unit) {
        onDispose {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            browser.layoutOffscreen(web)
        }
    }
    SubpageScaffold("浏览器", onBack, actions = {
        PressableIcon(Icons.AutoMirrored.Outlined.ArrowBack, "后退", enabled = web.canGoBack()) { web.goBack() }
        PressableIcon(Icons.Outlined.Refresh, "刷新") { web.reload() }
    }) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space12, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    url, { url = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = IrisType.secondary.copy(color = c.text),
                    placeholder = { Text("输入网址或搜索", style = IrisType.secondary) },
                    shape = IrisRadius.mediumShape,
                    colors = irisTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { web.loadUrl(com.iris.localai.agent.BrowserText.normalizeUrl(url)) }),
                )
            }
            if (st.loading) com.iris.localai.ui.components.IrisProgressBar(progress = null, height = 2.dp, track = c.outline.copy(alpha = 0.4f))
            Box(Modifier.weight(1f).fillMaxWidth().clip(IrisRadius.mediumShape).background(c.surface)) {
                AndroidView(factory = {
                    (web.parent as? android.view.ViewGroup)?.removeView(web)
                    web
                }, modifier = Modifier.fillMaxSize())
            }
            GroupFootnote(ToolPageText.BROWSER_NOTE, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
