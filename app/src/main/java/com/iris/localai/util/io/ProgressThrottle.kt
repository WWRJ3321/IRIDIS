package com.iris.localai.util.io

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载进度回调节流——最多每 [minMs] 一次。之前每 1 MB 就推一次状态，快网速下每秒几十次整页重组，
 * 下载时界面卡顿。线程安全（分块下载多线程同时回调）。[now] 可注入便于测试。
 */
class ProgressThrottle(private val minMs: Long = MIN_MS, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val last = AtomicLong(Long.MIN_VALUE)

    /** True at most once per [minMs]; the first call is always true. */
    fun ready(): Boolean {
        val t = now()
        val l = last.get()
        if (l != Long.MIN_VALUE && t - l < minMs) return false
        return last.compareAndSet(l, t)
    }

    companion object { const val MIN_MS = 200L }
}
