package com.iris.localai.ui.icons

import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 1.14.1: IRIS's own feature icons — one 24 grid, 1.75 stroke, round caps and joins, no fills except
 * where the shape is the meaning (外观's half-filled circle). Tinted by Icon() like Material icons.
 * Preview sheet: work/icons/sheet.png (generated from the same path strings).
 */
object IrisIcons {
    private fun icon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = "Iris.$name", defaultWidth = IrisDimens.space24, defaultHeight = IrisDimens.space24, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { p ->
            if (p.startsWith("F:")) addPath(addPathNodes(p.removePrefix("F:")), fill = SolidColor(Color.Black))
            else addPath(
                addPathNodes(p), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    /** 智能体 */
    val Agent: ImageVector by lazy { icon("Agent", "M8 8h8a3 3 0 0 1 3 3v5a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-5a3 3 0 0 1 3-3z M12 8V5.5 M12 4h0.01 M9.5 12.5v2 M14.5 12.5v2") }

    /** 深度思考 */
    val Think: ImageVector by lazy { icon("Think", "M9.5 18h5 M10.5 21h3 M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1 2V16h5.2v-.2c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z") }

    /** 长期记忆 */
    val Memory: ImageVector by lazy { icon("Memory", "M5 5a2 2 0 0 1 2-2h12v14H7a2 2 0 0 0-2 2V5z M5 19a2 2 0 0 0 2 2h12v-4 M10 3v6.5l2-1.5 2 1.5V3") }

    /** 语音 */
    val Voice: ImageVector by lazy { icon("Voice", "M4 10v4 M8 7v10 M12 4v16 M16 8v8 M20 10.5v3") }

    /** 启动时预加载 */
    val Power: ImageVector by lazy { icon("Power", "M12 3v8 M7.1 6.3a7 7 0 1 0 9.8 0") }

    /** 模型与后端 / 处理器 */
    val Chip: ImageVector by lazy { icon("Chip", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z M10 10h4v4h-4z M9.5 3v3 M14.5 3v3 M9.5 18v3 M14.5 18v3 M3 9.5h3 M3 14.5h3 M18 9.5h3 M18 14.5h3") }

    /** 模型管理 */
    val Cube: ImageVector by lazy { icon("Cube", "M12 3l8 4.5v9L12 21l-8-4.5v-9z M4 7.5l8 4.5 8-4.5 M12 12v9") }

    /** 生成 */
    val Spark: ImageVector by lazy { icon("Spark", "M12 4c.6 4.4 3.6 7.4 8 8-4.4.6-7.4 3.6-8 8-.6-4.4-3.6-7.4-8-8 4.4-.6 7.4-3.6 8-8z") }

    /** 上下文 */
    val Layers: ImageVector by lazy { icon("Layers", "M12 4l8 4-8 4-8-4z M4 12l8 4 8-4 M4 16l8 4 8-4") }

    /** 性能 / 速度 */
    val Gauge: ImageVector by lazy { icon("Gauge", "M4.5 17a8 8 0 1 1 15 0 M12 13l3.5-4 M12 13h0.01") }

    /** 联网 / 浏览器 */
    val Globe: ImageVector by lazy { icon("Globe", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M3 12h18 M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z") }

    /** API 服务 */
    val Plug: ImageVector by lazy { icon("Plug", "M9 3v4 M15 3v4 M7 7h10v3a5 5 0 0 1-10 0z M12 15v6") }

    /** 外观 / 主题 */
    val Contrast: ImageVector by lazy { icon("Contrast", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "F:M12 3a9 9 0 0 1 0 18z") }

    /** 诊断 */
    val Pulse: ImageVector by lazy { icon("Pulse", "M3 12h4l2.5-6 5 12 2.5-6h4") }

    /** 时间 */
    val Clock: ImageVector by lazy { icon("Clock", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7.5V12l3 2") }

    /** 对话 */
    val Chat: ImageVector by lazy { icon("Chat", "M6 5h12a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3h-6l-4.5 3.5V17H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z") }

    /** 自定义指令 */
    val Prompt: ImageVector by lazy { icon("Prompt", "M6 3h8.5L19 7.5V19a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M14 3v5h5 M8 13h8 M8 17h5") }

    /** 充电 / 资源档 */
    val Bolt: ImageVector by lazy { icon("Bolt", "M13 3L5 13.5h6L10 21l8-10.5h-6z") }

    /** 视觉效果 */
    val Eye: ImageVector by lazy { icon("Eye", "M2.5 12C4.8 7.6 8.1 5.5 12 5.5s7.2 2.1 9.5 6.5c-2.3 4.4-5.6 6.5-9.5 6.5S4.8 16.4 2.5 12z M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z") }

    /** 2.1.7: 隐藏 (API Key show/hide) — the eye, struck through. */
    val EyeOff: ImageVector by lazy { icon("EyeOff", "M2.5 12C4.8 7.6 8.1 5.5 12 5.5s7.2 2.1 9.5 6.5c-2.3 4.4-5.6 6.5-9.5 6.5S4.8 16.4 2.5 12z M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z M4 4l16 16") }

    /** 存储空间 (1.14.3): a storage cylinder, three stacked platters. */
    val Storage: ImageVector by lazy { icon("Storage", "M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3z M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6 M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3") }

    /** 2.0.x: 上一个版本 (message version switcher ‹). */
    val ChevronLeft: ImageVector by lazy { icon("ChevronLeft", "M14.5 6l-6 6 6 6") }

    /** 2.0.x: 下一个版本 (message version switcher ›). */
    val ChevronRight: ImageVector by lazy { icon("ChevronRight", "M9.5 6l6 6-6 6") }

    /** 2.0.x: 编辑回复. */
    val Edit: ImageVector by lazy { icon("Edit", "M4 20h4L18.5 9.5a2.1 2.1 0 0 0-3-3L5 17v3z M13.5 8.5l3 3") }


    /** 关于 (2.0.x) */
    val Info: ImageVector by lazy { icon("Info", "M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z M12 11v5.5 M12 7.5h0.01") }

    /** 文件夹 (2.0.x) */
    val Folder: ImageVector by lazy { icon("Folder", "M3.5 7a2 2 0 0 1 2-2h4l2 2.2h7a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V7z") }

    /** 重新加载 (2.0.x) */
    val Reload: ImageVector by lazy { icon("Reload", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3 M19.5 4.5v4.2h-4.2") }

    /** 更多 ⋯ (2.0.x, overflow menus) */
    val More: ImageVector by lazy { icon("More", "M6 12h0.01 M12 12h0.01 M18 12h0.01") }

    /** 触感 (2.0.x) */
    val Haptic: ImageVector by lazy { icon("Haptic", "M9 4h6a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 15 20H9a1.5 1.5 0 0 1-1.5-1.5v-13A1.5 1.5 0 0 1 9 4z M4 9v6 M20 9v6") }

    /** 音效 (2.0.x) */
    val Sound: ImageVector by lazy { icon("Sound", "M4 9.5h3l4.5-4v13L7 14.5H4z M15 9a4 4 0 0 1 0 6 M17.5 6.5a7.5 7.5 0 0 1 0 11") }

    /** 设置 (2.1, sliders) */
    val Settings: ImageVector by lazy { icon("Settings", "M4 7h9 M17 7h3 M15 4.5v5 M4 17h3 M11 17h9 M9 14.5v5") }

    /** 2.1.4 自定义背景 (picture) */
    val Image: ImageVector by lazy { icon("Image", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M3.5 16.5l5-5 4 4 2.5-2.5 5.5 5.5 M15.5 8.5h0.01") }

    /** 2.1.4 selected swatch */
    val Check: ImageVector by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7.5") }

    /** 2.1.4 移除背景 */
    val Trash: ImageVector by lazy { icon("Trash", "M4 7h16 M9.5 7V4.5h5V7 M6.5 7l1 12.5h9l1-12.5 M10 11v5 M14 11v5") }

    // ---- 2.2 rich rendering (网页预览 / 图片查看) ----

    /** 源码 */
    val Code: ImageVector by lazy { icon("Code", "M8.5 7L3.5 12l5 5 M15.5 7l5 5-5 5 M13.5 4.5l-3 15") }

    /** 全屏 */
    val Expand: ImageVector by lazy { icon("Expand", "M4 9V4h5 M20 9V4h-5 M4 15v5h5 M20 15v5h-5") }


    /** 保存到相册 */
    val Download: ImageVector by lazy { icon("Download", "M12 4v11 M7.5 10.5L12 15l4.5-4.5 M5 20h14") }

    /** 分享 */
    val Share: ImageVector by lazy { icon("Share", "M12 15V4 M8 7.5L12 3.5l4 4 M6 11v8a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-8") }

    /** 关闭 */
    val Close: ImageVector by lazy { icon("Close", "M6 6l12 12 M18 6L6 18") }

    /** 复制 */
    val Copy: ImageVector by lazy { icon("Copy", "M9 9h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z M4 15V6a2 2 0 0 1 2-2h9") }

    // ---- 2.2 composer ----

    /** 添加（附件） */
    val Plus: ImageVector by lazy { icon("Plus", "M12 5v14 M5 12h14") }

    /** 发送 */
    val Send: ImageVector by lazy { icon("Send", "M12 19V5.5 M6 11l6-6 6 6") }

    /** 停止生成 (filled rounded square) */
    val Stop: ImageVector by lazy { icon("Stop", "F:M8.5 7h7a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 15.5v-7A1.5 1.5 0 0 1 8.5 7z") }

    /** 技能 (puzzle-ish spark: a book with a star) */
    val Skill: ImageVector by lazy { icon("Skill", "M12 3l2.2 4.6 5 .7-3.6 3.5.9 5L12 14.4 7.5 16.8l.9-5L4.8 8.3l5-.7z M8 20.5h8") }

    /** 搜索 */
    val Search: ImageVector by lazy { icon("Search", "M10.5 4a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13z M15.5 15.5L20 20") }

    /** 工具 (wrench) */
    val Tool: ImageVector by lazy { icon("Tool", "M14.7 6.3a4 4 0 0 0 5 5L20 11.6a5 5 0 0 1-6.3 2.9L7.5 20.7a1.8 1.8 0 0 1-2.5-2.5l6.2-6.2A5 5 0 0 1 14 5.7z") }

    // ---- 2.2 文件 ----

    /** 文件 (document) */
    val File: ImageVector by lazy { icon("File", "M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M14 3v5h5 M9 13h6 M9 17h4") }

    /** 新建文件夹 */
    val FolderPlus: ImageVector by lazy { icon("FolderPlus", "M3.5 7a2 2 0 0 1 2-2h4l2 2.2h7a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V7z M12 10.5v5 M9.5 13h5") }

    /** 排序 */
    val Sort: ImageVector by lazy { icon("Sort", "M7 4v16 M4 17l3 3 3-3 M17 20V4 M14 7l3-3 3 3") }

    /** 档案模式 (stacked cards) */
    val Archive: ImageVector by lazy { icon("Archive", "M6 8h12a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 18 20H6a1.5 1.5 0 0 1-1.5-1.5v-9A1.5 1.5 0 0 1 6 8z M7 5h10 M9 2.5h6 M10 13h4") }

    /** 用其他应用打开 */
    val OpenExternal: ImageVector by lazy { icon("OpenExternal", "M14 4h6v6 M20 4l-9 9 M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4") }
}
