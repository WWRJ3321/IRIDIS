/*
 * 2.3.0: parts of this file (element compaction / unlabeled-controls check, accessibility scroll)
 * are adapted from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * Apache License 2.0. Modified for IRIDIS.
 */
package com.iris.localai.agent.gui

/** Screen rectangle in pixels. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0 || height <= 0
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    val area: Long get() = if (isEmpty) 0 else width.toLong() * height
}

/**
 * A platform-free copy of one accessibility node (AccessibilityNodeInfo on the phone), so the tree
 * can be pruned, serialised and searched on the JVM.
 */
data class UiNode(
    val cls: String = "",
    val text: String = "",
    val desc: String = "",
    val viewId: String = "",
    val bounds: PxRect = PxRect(0, 0, 0, 0),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val pkg: String = "",
    /** 2.3.0: an empty field's placeholder (AccessibilityNodeInfo.hintText). */
    val hint: String = "",
    val selected: Boolean = false,
    val children: List<UiNode> = emptyList(),
) {
    val shortClass get() = cls.substringAfterLast('.')
    val shortId get() = viewId.substringAfter(":id/", viewId)
}

/** One element the model is shown: its line in the prompt and the node behind it. */
data class UiElement(val index: Int, val node: UiNode, val label: String, val line: String)

/**
 * Prunes and serialises the node tree for the model: only visible, on-screen elements that carry
 * text or can be acted on; containers without text borrow their children's text (a clickable list
 * row reads as its title). Each line: `[i] Button "发送" (512,903) click`, the centre on the
 * profile's grid so it matches what the model answers in.
 */
object UiTreeSerializer {
    const val MAX_ELEMENTS = 60
    const val MAX_CHARS = 2400
    private const val LABEL_CHARS = 40

    /** 2.3.0: candidates collected before the cap is applied (actionable ones are kept first). */
    private const val MAX_CANDIDATES = 200

    fun elements(root: UiNode?, screenW: Int, screenH: Int, scale: Float = 1000f, maxElements: Int = MAX_ELEMENTS): List<UiElement> {
        if (root == null || screenW <= 0 || screenH <= 0) return emptyList()
        val screen = PxRect(0, 0, screenW, screenH)
        val cand = ArrayList<Pair<UiNode, String>>()
        fun visit(n: UiNode) {
            if (cand.size >= MAX_CANDIDATES) return
            if (!n.visible || n.bounds.isEmpty || !overlaps(n.bounds, screen)) return
            val actionable = isActionable(n)
            val own = ownLabel(n)
            if (actionable || own.isNotEmpty()) {
                val label = own.ifEmpty { if (actionable) childText(n) else "" }
                // Plain text that only repeats an actionable parent's borrowed label adds nothing.
                if (label.isNotEmpty() || n.editable || n.scrollable) {
                    // 2.3.0 compaction: the same label at (almost) the same place is shown once.
                    val dup = label.isNotEmpty() && cand.any { (m, l) -> l == label && near(m.bounds, n.bounds, screenW, screenH) }
                    if (!dup) cand += n to label
                }
                // An actionable row's children are already summed up in its label.
                if (actionable && own.isEmpty() && !n.scrollable && label.isNotEmpty()) return
            }
            n.children.forEach(::visit)
        }
        visit(root)
        // 2.3.0 (Mika-style compaction): over the cap, keep what can be acted on first, then plain text, in screen order.
        val keep: List<Pair<UiNode, String>> = if (cand.size <= maxElements) cand else {
            val pri = cand.indices.sortedWith(compareBy({ if (isActionable(cand[it].first)) 0 else 1 }, { it })).take(maxElements).toSet()
            cand.filterIndexed { i, _ -> i in pri }
        }
        return keep.mapIndexed { i, (n, label) -> UiElement(i, n, label, line(i, n, label, screenW, screenH, scale)) }
    }

    private fun isActionable(n: UiNode) = n.clickable || n.longClickable || n.editable || n.scrollable || n.checkable

    private fun near(a: PxRect, b: PxRect, w: Int, h: Int): Boolean =
        kotlin.math.abs((a.left + a.right) - (b.left + b.right)) / 2.0 <= w * 0.02 &&
            kotlin.math.abs((a.top + a.bottom) - (b.top + b.bottom)) / 2.0 <= h * 0.02

    /** 2.3.0: the first element whose label contains [text] (case and spaces ignored); for scroll-until-found. */
    fun find(elements: List<UiElement>, text: String): UiElement? {
        val t = text.lowercase().filterNot { it.isWhitespace() }
        if (t.isEmpty()) return null
        fun hit(e: UiElement) = t in e.label.lowercase().filterNot { c -> c.isWhitespace() } || t in e.node.hint.lowercase().filterNot { c -> c.isWhitespace() }
        // A list borrows its children's text; point at the item itself, not the whole list.
        return elements.firstOrNull { !it.node.scrollable && hit(it) } ?: elements.firstOrNull { hit(it) }
    }

    /** 2.3.0: grid centre of an element, as shown in its line. */
    fun gridCentre(e: UiElement, w: Int, h: Int, scale: Float = 1000f): Pair<Int, Int> {
        val cx = ((e.node.bounds.left + e.node.bounds.right) / 2.0).coerceIn(0.0, w.toDouble())
        val cy = ((e.node.bounds.top + e.node.bounds.bottom) / 2.0).coerceIn(0.0, h.toDouble())
        return (cx / w * scale).toInt().coerceAtMost(scale.toInt()) to (cy / h * scale).toInt().coerceAtMost(scale.toInt())
    }

    /** 2.2.3: fewer text-bearing elements than this → run the OCR fallback. */
    const val OCR_MIN_TEXT = 4
    const val MAX_OCR_LINES = 40

    /** 2.2.3: true when the tree carries too little text to be a useful reference. */
    fun needsOcr(elements: List<UiElement>, root: UiNode? = null): Boolean =
        elements.count { it.label.isNotEmpty() } < OCR_MIN_TEXT || unlabeledControls(root)

    /**
     * 2.3.0, from Mika's TreeAdequacy ("unlabeled_controls"): at least 4 visible controls and no more
     * than a third of them carry any text → the tree misses what the controls say; read the screen.
     */
    fun unlabeledControls(root: UiNode?): Boolean {
        root ?: return false
        var controls = 0; var named = 0
        fun walk(n: UiNode) {
            if (!n.visible || n.bounds.isEmpty) return
            if (n.clickable || n.editable) {
                controls++
                if (ownLabel(n).isNotEmpty() || childText(n).isNotEmpty() || n.hint.isNotBlank()) named++
            }
            n.children.forEach(::walk)
        }
        walk(root)
        return controls >= 4 && named * 3 <= controls
    }

    /**
     * 2.2.3: [elements] plus OCR text lines as `[i] OCR "text" (x,y)` on the same grid. Lines that
     * repeat a tree label or are off screen are skipped. Reference only (no coordinate snapping).
     */
    fun withOcr(elements: List<UiElement>, lines: List<OcrLine>, screenW: Int, screenH: Int, scale: Float = 1000f): List<UiElement> {
        if (lines.isEmpty() || screenW <= 0 || screenH <= 0) return elements
        val screen = PxRect(0, 0, screenW, screenH)
        val seen = elements.map { it.label }.filter { it.isNotEmpty() }.toMutableSet()
        val out = ArrayList(elements)
        for (l in lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
            if (out.size - elements.size >= MAX_OCR_LINES) break
            val label = clean(l.text)
            if (label.isEmpty() || l.bounds.isEmpty || !overlaps(l.bounds, screen) || !seen.add(label)) continue
            val n = UiNode(cls = "OCR", text = l.text, bounds = l.bounds)
            val i = out.size
            out += UiElement(i, n, label, line(i, n, label, screenW, screenH, scale))
        }
        return out
    }

    /** The prompt block, capped at [maxChars]. */
    fun render(elements: List<UiElement>, maxChars: Int = MAX_CHARS): String {
        val sb = StringBuilder()
        for (e in elements) {
            if (sb.length + e.line.length + 1 > maxChars) { sb.append("…(${elements.size - e.index} more)"); break }
            sb.append(e.line).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** The smallest visible element under a pixel (for safety checks on a tap). */
    fun hit(elements: List<UiElement>, x: Int, y: Int): UiElement? =
        elements.filter { it.node.bounds.contains(x, y) && !it.node.scrollable }.minByOrNull { it.node.bounds.area }

    /** The focused editable field, if any (where Type goes). */
    fun focusedField(root: UiNode?): UiNode? {
        root ?: return null
        if (root.editable && root.focused) return root
        return root.children.firstNotNullOfOrNull { focusedField(it) }
    }

    /** Package of the window in front (the root's). */
    fun packageOf(root: UiNode?): String? = root?.pkg?.takeIf { it.isNotEmpty() }

    /** A cheap signature of what is on screen, to notice that an action changed nothing. */
    fun signature(elements: List<UiElement>): Int = elements.joinToString("|") { "${it.label}@${it.node.bounds}" }.hashCode()

    private fun ownLabel(n: UiNode): String = clean(n.text.ifBlank { n.desc })

    private fun childText(n: UiNode): String {
        val parts = ArrayList<String>()
        fun walk(c: UiNode) {
            if (parts.sumOf { it.length } > LABEL_CHARS) return
            if (!c.visible) return
            val t = clean(c.text.ifBlank { c.desc })
            if (t.isNotEmpty()) parts += t
            c.children.forEach(::walk)
        }
        n.children.forEach(::walk)
        return clean(parts.joinToString(" "))
    }

    private fun clean(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length > LABEL_CHARS) t.take(LABEL_CHARS) + "…" else t
    }

    private fun overlaps(a: PxRect, b: PxRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun line(i: Int, n: UiNode, label: String, w: Int, h: Int, scale: Float): String {
        val cx = ((n.bounds.left + n.bounds.right) / 2.0).coerceIn(0.0, w.toDouble())
        val cy = ((n.bounds.top + n.bounds.bottom) / 2.0).coerceIn(0.0, h.toDouble())
        val gx = (cx / w * scale).toInt().coerceAtMost(scale.toInt())
        val gy = (cy / h * scale).toInt().coerceAtMost(scale.toInt())
        val flags = buildList {
            if (n.clickable) add("click")
            if (n.longClickable) add("long")
            if (n.editable) add(if (n.password) "input(password)" else "input")
            if (n.editable && n.text.isBlank() && n.hint.isNotBlank()) add("hint=\"" + clean(n.hint).replace("\"", "'") + "\"")
            if (n.selected) add("selected")
            if (n.scrollable) add("scroll")
            if (n.checkable) add(if (n.checked) "checked" else "unchecked")
            if (n.focused) add("focused")
            if (!n.enabled) add("disabled")
        }.joinToString(" ")
        val name = n.shortClass.ifEmpty { "View" }
        val q = if (label.isNotEmpty()) " \"" + label.replace("\"", "'") + "\"" else ""
        return "[$i] $name$q ($gx,$gy)" + if (flags.isNotEmpty()) " $flags" else ""
    }
}
