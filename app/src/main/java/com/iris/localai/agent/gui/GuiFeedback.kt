package com.iris.localai.agent.gui

import kotlinx.coroutines.delay
import kotlin.math.abs

/** 2.2.9: a cheap fingerprint of what is on screen (tree hash) and the foreground app. */
data class ScreenState(val hash: Int, val pkg: String?)

/** 2.2.9: hash of the visible accessibility tree (text, description, bounds, checked/focused). Pure. */
object ScreenFingerprint {
    fun of(tree: UiNode?): ScreenState? {
        tree ?: return null
        var h = 17
        fun mix(v: Int) { h = h * 31 + v }
        fun walk(n: UiNode) {
            if (!n.visible) return
            mix(n.text.hashCode()); mix(n.desc.hashCode()); mix(n.bounds.hashCode())
            mix((if (n.checked) 1 else 0) or (if (n.focused) 2 else 0) or (if (n.enabled) 4 else 0))
            n.children.forEach { walk(it) }
        }
        walk(tree)
        return ScreenState(h, tree.pkg.takeIf { it.isNotEmpty() })
    }
}

/** 2.2.9: wait until the screen stops changing instead of a fixed delay. */
object StableWait {
    const val POLL_MS = 150L
    const val MIN_MS = 250L
    const val MAX_MS = 2500L

    /**
     * Samples every [pollMs] until two consecutive samples match (after at least [minMs]) or [maxMs]
     * passes; returns the last sample (null = could not read the screen).
     */
    suspend fun await(
        sample: suspend () -> ScreenState?,
        now: () -> Long = System::currentTimeMillis,
        minMs: Long = MIN_MS, maxMs: Long = MAX_MS, pollMs: Long = POLL_MS,
    ): ScreenState? {
        val t0 = now()
        var prev: ScreenState? = null
        var havePrev = false
        while (true) {
            delay(pollMs)
            val s = sample()
            val elapsed = now() - t0
            if (havePrev && s?.hash == prev?.hash && elapsed >= minMs) return s
            if (elapsed >= maxMs) return s
            prev = s; havePrev = true
        }
    }
}

/** 2.2.9: the line the model reads after each action about whether it had an effect. Pure. */
object ChangeReport {
    const val CHANGED = "画面已变化"
    const val UNCHANGED = "画面没有变化，操作可能没生效"

    /** null = unknown (no tree before or after). */
    fun changed(before: ScreenState?, after: ScreenState?): Boolean? =
        if (before == null || after == null) null else before.hash != after.hash || before.pkg != after.pkg

    fun text(before: ScreenState?, after: ScreenState?): String? = when (changed(before, after)) {
        null -> null
        true -> CHANGED + (after?.pkg?.let { "（前台：$it）" } ?: "")
        false -> UNCHANGED
    }
}

/**
 * 2.2.9 loop guard: the same action (points within [tolerance] of the screen, ~20/1000) repeated
 * [warnAt] times in a row with no screen change → a hint to try another way; [stopAt] → stop. Pure.
 */
class LoopGuard(private val warnAt: Int = 3, private val stopAt: Int = 6, private val tolerance: Float = 0.02f) {
    private var last: GuiAction? = null
    var count = 0
        private set

    /** Records one executed action; [changed] null (unknown) counts as no change. */
    fun record(action: GuiAction, changed: Boolean?): Verdict {
        if (action is GuiAction.Wait || changed == true) { last = null; count = 0; return Verdict.OK }
        count = if (last?.let { same(it, action) } == true) count + 1 else 1
        last = action
        return when {
            count >= stopAt -> Verdict.STOP
            count >= warnAt -> Verdict.WARN
            else -> Verdict.OK
        }
    }

    enum class Verdict { OK, WARN, STOP }

    fun same(a: GuiAction, b: GuiAction): Boolean = when {
        a is GuiAction.Tap && b is GuiAction.Tap -> near(a.at, b.at)
        a is GuiAction.LongPress && b is GuiAction.LongPress -> near(a.at, b.at)
        a is GuiAction.DoubleTap && b is GuiAction.DoubleTap -> near(a.at, b.at)
        a is GuiAction.Swipe && b is GuiAction.Swipe -> near(a.from, b.from) && near(a.to, b.to)
        else -> a == b
    }

    private fun near(p: NPoint, q: NPoint) = abs(p.x - q.x) <= tolerance && abs(p.y - q.y) <= tolerance

    companion object {
        fun hint(n: Int) = "注意：同一操作已连续 $n 次没有让画面变化。换个办法：滑动找找、返回、点别的元素，或用提问让用户帮忙。" +
            " (The same action had no effect $n times in a row: try scrolling, going back, a different element, or ask the user.)"
    }
}

/** 2.2.9: whether a field took the typed text (fields may reformat spaces, add a hint or cut at a max length). Pure. */
object TypeCheck {
    fun took(now: String?, typed: String): Boolean {
        val n = now.orEmpty().filterNot { it.isWhitespace() }
        val t = typed.filterNot { it.isWhitespace() }
        if (t.isEmpty()) return n.isEmpty() || now == typed
        return n == t || (n.length >= 8 && t.startsWith(n)) || n.contains(t)
    }
}
