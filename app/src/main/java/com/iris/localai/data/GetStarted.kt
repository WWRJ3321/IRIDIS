package com.iris.localai.data

/**
 * 3.0.0 Beta4：空对话页的「开始」按钮该做什么。纯逻辑，便于单测。
 * 没装模型 → 下载本机推荐；下载中 → 显示进度；装了没加载 → 不显示（r2 用户要求去掉，发消息时自动加载）；加载失败 → 重试；其余不显示。
 */
object GetStarted {
    enum class Step { DOWNLOAD, DOWNLOADING, LOAD, RETRY, NONE }

    fun step(installed: Int, ready: Boolean, loading: Boolean, failed: Boolean, downloading: Boolean, hasRecommend: Boolean): Step = when {
        ready || loading -> Step.NONE
        downloading -> Step.DOWNLOADING
        installed == 0 -> if (hasRecommend) Step.DOWNLOAD else Step.NONE
        failed -> Step.RETRY
        else -> Step.NONE
    }

    /** 没有默认/上次模型时，按用户排序取第一个可对话的模型（跳过知识库向量模型）。 */
    fun <T> fallback(list: List<T>, name: (T) -> String, isEmbed: (String) -> Boolean): T? =
        list.firstOrNull { !isEmbed(name(it)) }
}
