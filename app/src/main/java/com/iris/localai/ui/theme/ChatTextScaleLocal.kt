package com.iris.localai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * 对话正文字号倍数（[com.iris.localai.data.ChatTextScale.factor]）。只在对话页与设置里的预览提供；
 * 其余页面读到默认 1f，所以文件预览等别处的 Markdown 不受影响。
 */
val LocalChatTextScale = compositionLocalOf { 1f }

/** sp 值按倍数缩放；未指定或倍数为 1 时原样返回。 */
fun TextUnit.chatScaled(f: Float): TextUnit = if (f == 1f || !isSpecified) this else this * f

/** 字号与行高一起按倍数缩放（行高跟着放大，避免大字挤在一起）。 */
fun TextStyle.chatScaled(f: Float): TextStyle =
    if (f == 1f) this else copy(fontSize = fontSize.chatScaled(f), lineHeight = lineHeight.chatScaled(f))

/** 当前对话正文倍数下的样式。 */
@Composable
@ReadOnlyComposable
fun TextStyle.chatScaled(): TextStyle = chatScaled(LocalChatTextScale.current)
