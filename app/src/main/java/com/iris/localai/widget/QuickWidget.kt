package com.iris.localai.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.MainActivity
import com.iris.localai.ui.QuickNav

/** 3.0 桌面小组件：新对话 / 语音 / 生图 / 工具箱 四个按钮，点了打开 App 对应页面（经 [QuickNav]）。 */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.w30_quick_widget)
        listOf(R.id.w30_b1 to QuickNav.NEW_CHAT, R.id.w30_b2 to QuickNav.VOICE, R.id.w30_b3 to QuickNav.IMAGEGEN, R.id.w30_b4 to QuickNav.TOOLBOX)
            .forEachIndexed { i, (id, action) -> views.setOnClickPendingIntent(id, QuickLaunch.pending(context, action, 100 + i)) }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

/** 3.0 长按图标的快捷方式 + 小组件共用的打开方式。 */
object QuickLaunch {
    fun intent(ctx: Context, action: String): Intent = Intent(ctx, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(QuickNav.EXTRA, action)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun pending(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, intent(ctx, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 长按 App 图标：新对话、语音聊天、生图、悬浮窗（工具箱）。文案跟语言走，每次启动刷新。 */
    fun publishShortcuts(ctx: Context) = runCatching {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return@runCatching
        fun sc(id: String, label: String, icon: Int, rank: Int) = ShortcutInfo.Builder(ctx, id)
            .setShortLabel(label).setLongLabel(label).setRank(rank)
            .setIcon(Icon.createWithResource(ctx, icon))
            .setIntent(intent(ctx, id)).build()
        sm.dynamicShortcuts = listOf(
            sc(QuickNav.NEW_CHAT, I18n.s(R.string.v30_w_chat), R.drawable.w30_ic_chat, 0),
            sc(QuickNav.VOICE, I18n.s(R.string.v30_w_voice), R.drawable.w30_ic_mic, 1),
            sc(QuickNav.IMAGEGEN, I18n.s(R.string.v30_w_image), R.drawable.w30_ic_image, 2),
            sc(QuickNav.TOOLBOX, I18n.s(R.string.v30_toolbox), R.drawable.w30_ic_tool, 3),
        )
    }
}
