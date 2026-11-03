package com.iris.localai.files

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * 2.2 「档案模式」 / 「卡片」: the pure geometry of the archive views (no Android / Compose types, unit-tested).
 * The first half is the card deck of [com.iris.localai.ui.files.CardStackFileView] (「卡片」); the
 * slab field of the shelved 「档案」 view (branch shelf/archive-terminal; no UI uses it now) follows at the end.
 *
 * The deck is a vertical column of cards around a continuous scroll position `pos` measured in
 * cards. A card's *offset* `d = logicalIndex - pos` (0 = focused, front-facing) drives its depth:
 * translation, scale, alpha, rotation and shadow. With more than [LOOP_MIN] + 1 entries the deck
 * loops (logical indices wrap); otherwise it is a bounded column with a rubber band at the ends.
 *
 * Design inspiration: the Rhine Lab terminal UI of LBEILC/RhineLabUI (MIT). No code is copied.
 */
object ArchiveMath {
    /** The deck loops when it has more than this many entries. */
    const val LOOP_MIN = 5
    /** At most this many cards on each side of the focused one are composed (7 in total). */
    const val MAX_HALF_WINDOW = 3
    /** Offsets beyond this are fully faded (and not composed). */
    const val MAX_DEPTH = 3.5f

    fun loops(count: Int): Boolean = count > LOOP_MIN

    /** [i] wrapped into 0 until [n] (negative-safe); 0 for an empty deck. */
    fun wrap(i: Int, n: Int): Int = if (n <= 0) 0 else ((i % n) + n) % n

    /** Cards composed on each side of the focus: never two slots showing the same entry when looping. */
    fun halfWindow(count: Int): Int = when {
        count <= 1 -> 0
        loops(count) -> minOf(MAX_HALF_WINDOW, (count - 1) / 2)
        else -> MAX_HALF_WINDOW
    }

    /** Entry index shown at [logical], or -1 when the bounded deck has nothing there. */
    fun entryAt(logical: Int, count: Int): Int = when {
        count <= 0 -> -1
        loops(count) -> wrap(logical, count)
        logical in 0 until count -> logical
        else -> -1
    }

    /** Index of the focused entry for scroll position [pos]. */
    fun focusIndex(pos: Float, count: Int): Int = if (count <= 0) 0 else entryAt(pos.roundToInt(), count).let { if (it < 0) pos.roundToInt().coerceIn(0, count - 1) else it }

    /** New position after a drag of [deltaCards]; a bounded deck resists past its ends (rubber band). */
    fun dragStep(pos: Float, deltaCards: Float, count: Int): Float {
        if (count <= 0) return 0f
        if (loops(count)) return pos + deltaCards
        val max = (count - 1).toFloat()
        val outside = (pos <= 0f && deltaCards < 0f) || (pos >= max && deltaCards > 0f)
        return pos + if (outside) deltaCards * RUBBER else deltaCards
    }
    private const val RUBBER = 0.35f

    /**
     * Snap target after a release with [velocity] (cards / second, positive = toward later entries).
     * A fling moves at most [MAX_FLING_CARDS]; a bounded deck clamps to its ends.
     */
    fun snapTarget(pos: Float, velocity: Float, count: Int): Float {
        if (count <= 0) return 0f
        val projected = (pos + velocity * FLING_PROJECTION).coerceIn(pos - MAX_FLING_CARDS, pos + MAX_FLING_CARDS)
        val target = projected.roundToInt().toFloat()
        return if (loops(count)) target else target.coerceIn(0f, (count - 1).toFloat())
    }
    const val FLING_PROJECTION = 0.16f
    const val MAX_FLING_CARDS = 6f

    /** Keeps a bounded deck's position valid after the entry list shrank. */
    fun clampPos(pos: Float, count: Int): Float = when {
        count <= 0 -> 0f
        loops(count) -> pos
        else -> pos.coerceIn(0f, (count - 1).toFloat())
    }

    /**
     * 「卡片」 fix: vertical bias (card heights) that centres the visible stack of a bounded deck
     * rather than only the focused card, so a short folder does not leave the top half empty.
     * 0 for looping decks (symmetric) and single cards.
     */
    fun deckBias(pos: Float, count: Int): Float {
        if (count <= 1 || loops(count)) return 0f
        val half = MAX_HALF_WINDOW.toFloat()
        val above = pos.coerceIn(0f, half)
        val below = ((count - 1) - pos).coerceIn(0f, half)
        return -(spacing(below) - spacing(above)) / 2f
    }

    /** 「卡片」 fix: only the focused card shows its text; it fades out within half a card of scroll. */
    /** 2.2.14: neighbours show their text too (dimmer with distance), not only the focused card. */
    fun contentAlpha(d: Float): Float = (1f - abs(d) * 0.35f).coerceIn(0f, 1f)

    /** 2.2.14: continuous draw order (nearest to the focus on top), so overlapping cards never swap with a flash. */
    fun drawOrder(d: Float): Float = MAX_DEPTH - abs(d).coerceAtMost(MAX_DEPTH)

    // ---------------------------------------------------------------- per-card depth

    /** One card's transform. Mutable and reused, so a frame allocates nothing. Units: card heights / degrees / 0..1. */
    class Layer {
        var y = 0f
        var scale = 1f
        var alpha = 1f
        var rotationX = 0f
        var rotationY = 0f
        /** 0..1 of the maximum shadow. */
        var elevation = 0f
        var z = 0f
    }

    const val FOCUS_SCALE = 1.06f
    private const val SCALE_STEP = 0.12f
    private const val MIN_SCALE = 0.62f
    private const val ALPHA_STEP = 0.24f
    private const val TILT_X = 11f
    private const val MAX_TILT_X = 34f
    private const val TILT_Y = 4f
    private const val MAX_TILT_Y = 12f

    /** Vertical spacing curve: neighbours overlap more the further they are (monotonic up to [MAX_DEPTH]). */
    // 2.2.14: wider (cards above / below stay readable instead of hiding under the focus).
    fun spacing(a: Float): Float = a * 0.9f - 0.06f * a * a

    /**
     * Depth of a card at offset [d] from the focus. With [tilt] false (reduced motion) the stack
     * keeps its scale / fade but every rotation is 0.
     */
    fun layer(d: Float, tilt: Boolean, out: Layer): Layer {
        val a = abs(d).coerceAtMost(MAX_DEPTH)
        val s = sign(d)
        out.y = s * spacing(a)
        out.scale = (FOCUS_SCALE - SCALE_STEP * a).coerceAtLeast(MIN_SCALE)
        out.alpha = (1f - ALPHA_STEP * a).coerceIn(0f, 1f)
        out.rotationX = if (tilt) -s * (TILT_X * a).coerceAtMost(MAX_TILT_X) else 0f
        out.rotationY = if (tilt) -(TILT_Y * a).coerceAtMost(MAX_TILT_Y) else 0f
        out.elevation = (1f - a / MAX_DEPTH).coerceIn(0f, 1f)
        out.z = MAX_DEPTH - a
        return out
    }

    /**
     * 「卡片」: opacity of a card [a] cards from the focus in a window of [half] slots. Cards stay
     * opaque inside the window (depth is a background-coloured veil, so nothing shows through) and
     * fade to 0 over the last half card, so a card entering or leaving the window never pops.
     */
    fun edgeFade(a: Float, half: Int): Float = ((half + 0.5f - abs(a)) / 0.5f).coerceIn(0f, 1f)

    /** 「卡片」: the background-coloured veil over a card [a] cards deep (replaces see-through alpha). */
    fun depthVeil(a: Float): Float = (ALPHA_STEP * abs(a).coerceAtMost(MAX_DEPTH)).coerceIn(0f, 0.85f)

    // ---------------------------------------------------------------- staggered waves

    /** Delay of each further slot, as a fraction of the whole wave. */
    const val WAVE_STAGGER = 0.14f
    private const val WAVE_SPAN = 1f - WAVE_STAGGER * MAX_HALF_WINDOW

    /** Local 0..1 progress of the card [slot] cards from the focus when the whole wave is at [t]. */
    fun staggered(t: Float, slot: Int): Float = ((t - slot * WAVE_STAGGER) / WAVE_SPAN).coerceIn(0f, 1f)

    /**
     * Lift (card heights, negative = up) of the card [slot] away while the selection wave is at [t]:
     * a single bump that starts at the focused card and ripples outwards, smaller with distance.
     */
    fun waveLift(t: Float, slot: Int): Float {
        if (t <= 0f || t >= 1f) return 0f
        val p = staggered(t, slot)
        return -sin(PI.toFloat() * p) * LIFT / (1f + slot * 0.6f)
    }
    const val LIFT = 0.09f

    /**
     * Folder change. [phase] > 0: the current cards fall back (0 → 1); [phase] < 0: the new cards
     * rise into place (-1 → 0). Applies the change on top of [out] for the card [slot] away.
     */
    fun transit(phase: Float, slot: Int, out: Layer): Layer {
        if (phase == 0f) return out
        if (phase > 0f) {
            val p = staggered(phase, slot)
            out.y += p * 0.6f
            out.scale *= 1f - 0.22f * p
            out.alpha *= 1f - p
            out.rotationX += 24f * p
        } else {
            val p = staggered(1f + phase, slot) // 0 at phase -1 → 1 at 0
            val q = 1f - p
            out.y += q * 1.1f
            out.alpha *= p
            out.rotationX -= 18f * q
        }
        return out
    }

    // ---------------------------------------------------------------- decrypt sweep

    /** Reveal front of the diagonal sweep at progress [t]: points with x + y < front are clear. */
    fun sweepFront(t: Float, w: Float, h: Float): Float = t.coerceIn(0f, 1f) * (w + h)

    fun revealed(t: Float, x: Float, y: Float, w: Float, h: Float): Boolean = x + y < sweepFront(t, w, h)

    /**
     * The still-frosted part of a [w]×[h] rect at progress [t] (rect ∩ {x + y ≥ front}) as a
     * polygon written into [out] (x0, y0, x1, y1 …, at most 5 vertices: needs 10 floats). Returns the vertex count
     * (0 when fully revealed).
     */
    fun veilPolygon(t: Float, w: Float, h: Float, out: FloatArray): Int {
        val f = sweepFront(t, w, h)
        if (f >= w + h) return 0
        if (f <= 0f) {
            for (i in 0 until 4) { out[i * 2] = cornerX(i, w); out[i * 2 + 1] = cornerY(i, h) }
            return 4
        }
        var n = 0
        // walk the rect corners clockwise; keep inside points and add edge crossings
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val ax = cornerX(i, w); val ay = cornerY(i, h)
            val bx = cornerX(j, w); val by = cornerY(j, h)
            val aIn = ax + ay > f
            val bIn = bx + by > f
            if (aIn) { out[n * 2] = ax; out[n * 2 + 1] = ay; n++ }
            if (aIn != bIn) {
                val da = ax + ay - f
                val db = bx + by - f
                val k = da / (da - db)
                out[n * 2] = ax + (bx - ax) * k; out[n * 2 + 1] = ay + (by - ay) * k; n++
            }
        }
        return if (n < 3) 0 else n
    }

    /** Corners clockwise from the top-left (no arrays, so the per-frame call allocates nothing). */
    private fun cornerX(i: Int, w: Float) = if (i == 1 || i == 2) w else 0f
    private fun cornerY(i: Int, h: Float) = if (i >= 2) h else 0f

    /** Blur radius step (0 = sharp … steps − 1 = most frosted) at progress [t]; quantised so effects can be cached. */
    fun blurStep(t: Float, steps: Int): Int = if (steps <= 1) 0 else ((1f - t.coerceIn(0f, 1f)) * (steps - 1)).roundToInt()

    // ---------------------------------------------------------------- labels

    /** "ARC-0042" for the 42nd entry ([index] 41); wider past 9999. */
    fun serial(index: Int): String = "ARC-" + (index.coerceAtLeast(0) + 1).toString().padStart(4, '0')

    /**
     * Digits of [value] most significant first, zero-padded to [minWidth]. Each position rolls on
     * its own; [slotFromEnd] names a position so 9 → 10 keeps the ones digit in its slot.
     */
    fun digits(value: Int, minWidth: Int = 1): IntArray {
        val s = value.coerceAtLeast(0).toString().padStart(minWidth, '0')
        return IntArray(s.length) { s[it] - '0' }
    }

    fun slotFromEnd(position: Int, length: Int): Int = length - 1 - position

    /** Roll direction for one digit: up when it grew (9 → 0 counts as growing, 0 → 9 as shrinking). */
    fun rollsUp(old: Int, new: Int): Boolean = when {
        old == 9 && new == 0 -> true
        old == 0 && new == 9 -> false
        else -> new >= old
    }

    enum class Kind { SYSTEM, FOLDER, TEXT, MEDIA, MODEL, PACKAGE, OTHER }

    private val TEXT = setOf("txt", "md", "markdown", "log", "json", "jsonl", "yaml", "yml", "toml", "ini", "csv", "tsv", "xml", "html", "htm", "kt", "java", "py", "sh", "js", "ts", "srt", "jinja")
    private val MEDIA = setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "bmp", "mp3", "wav", "ogg", "m4a", "flac", "mp4", "mkv", "webm", "mov")
    private val MODEL = setOf("gguf", "mnn", "litertlm", "task", "tflite", "onnx", "bin", "safetensors", "pte", "qnn")
    private val PACKAGE = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "apk", "rar")

    fun kind(isDir: Boolean, system: Boolean, extension: String): Kind {
        val ext = extension.lowercase(Locale.ROOT)
        return when {
            system -> Kind.SYSTEM
            isDir -> Kind.FOLDER
            ext in TEXT -> Kind.TEXT
            ext in MEDIA -> Kind.MEDIA
            ext in MODEL -> Kind.MODEL
            ext in PACKAGE -> Kind.PACKAGE
            else -> Kind.OTHER
        }
    }

    fun kind(e: FileEntry): Kind = kind(e.isDir, e.system != null, e.extension)

    /** Short type tag on a card: SYS / DIR / the extension (≤ 5 chars) / FILE. */
    fun typeTag(isDir: Boolean, system: Boolean, extension: String): String = when {
        system -> "SYS"
        isDir -> "DIR"
        extension.isBlank() -> "FILE"
        else -> extension.uppercase(Locale.ROOT).take(5)
    }

    fun typeTag(e: FileEntry): String = typeTag(e.isDir, e.system != null, e.extension)

    /** Path tag of the HUD: "/IRIDIS" or "/IRIDIS/models/qwen". */
    fun pathTag(rel: String): String = "/" + FilesPaths.ROOT_LABEL + (if (rel.isEmpty()) "" else "/" + rel.trim('/'))

    // ================================================================ 2.1.9 「档案模式」 slab field
    //
    // The archive view is a full-screen field of thin rectangular slabs ("archive cassettes")
    // lying in parallel rows, drawn as 2.5D prisms by an oblique parallel projection. World axes:
    // X = across the rows (columns side by side), Y = along a slab's length, Z = up. Each entry of
    // the current column is one slab of the *active row* (row 0); every other slab is decoration.
    // The selected slab stands upright (rotates about its near bottom edge), neighbours lift in a
    // decaying wave. Everything below is allocation-free per frame and unit-tested.

    /** Slab and grid metrics in dp (world units = dp before the projection). */
    object Field {
        /** Slab thickness (X), height when lying (Z) and length (Y). */
        const val W = 6f
        const val H = 25f
        const val L = 142f
        /** Gap between the ends of two slabs in a column (≥ [H], so a rising slab never cuts into its neighbour). */
        const val GAP = 30f
        /** Column pitch along X and the per-column stagger along Y (the staircase of slab ends). */
        const val PITCH = 31f
        const val STAGGER = 25f
        /** Screen directions of the X and Y axes (degrees, screen y down); Z is straight up. */
        const val EX_DEG = 52f
        const val EY_DEG = -24f
        /** The selected slab widens to H × this when it stands (a plate rather than a pole). */
        const val STAND_WIDEN = 1.4f
        /** Peak lift (dp) of the first neighbour, and of the selection wave. */
        const val LIFT = 16f
        const val WAVE = 10f
        /** Neighbours further than this (columns) do not lift. */
        const val LIFT_REACH = 5f
        /** Anchor of the selected slab's base, as fractions of the canvas. */
        const val ANCHOR_X = 0.30f
        const val ANCHOR_Y = 0.60f
        /** Margin (dp) around the canvas inside which slabs are generated. */
        const val MARGIN = 220f
        /** Height of the intro "settle" drop (dp). */
        const val DROP = 36f
        const val ROW = L + GAP
        /** Vertices per slab at most: shadow + 3 faces + highlight + dot + label (2 quads), 6 each. */
        const val VERTS_PER_SLAB = 8 * 6
    }

    /** Smoothstep: how upright the slab [d] columns from the scroll position stands (1 at 0, 0 from 1 on). */
    fun standOf(d: Float): Float {
        val a = abs(d)
        if (a >= 1f) return 0f
        val t = 1f - a
        return t * t * (3f - 2f * t)
    }

    /** Lift (0..1 of [Field.LIFT]) of an active slab [d] columns away: decays with distance, 0 beyond the reach. */
    fun liftOf(d: Float): Float {
        val a = abs(d)
        if (a >= Field.LIFT_REACH) return 0f
        return kotlin.math.exp(-0.75f * a) * (1f - a / Field.LIFT_REACH)
    }

    /** Extra lift (dp) of the selection wave at progress [t] for a slab [d] columns away (staggered outwards). */
    fun fieldWave(t: Float, d: Float): Float {
        if (t <= 0f || t >= 1f) return 0f
        val slot = abs(d).roundToInt().coerceAtMost(MAX_HALF_WINDOW)
        val p = staggered(t, slot)
        return sin(PI.toFloat() * p) * Field.WAVE / (1f + slot * 0.6f)
    }

    /** Global rise 0..1 (folder change / intro / back from a file) for the slab [d] columns away (staggered). */
    fun riseFor(rise: Float, d: Float): Float =
        if (rise >= 1f) 1f else if (rise <= 0f) 0f else staggered(rise, abs(d).roundToInt().coerceAtMost(MAX_HALF_WINDOW))

    /** Snap target of the looping field after a release at [velocity] columns / s (never clamped: the field always loops). */
    fun fieldSnap(pos: Float, velocity: Float): Float =
        (pos + velocity * FLING_PROJECTION).coerceIn(pos - MAX_FLING_CARDS, pos + MAX_FLING_CARDS).roundToInt().toFloat()

    // ---------------------------------------------------------------- columns (categories)

    /** The archive's columns, switched with ← COLUMN →; empty ones are skipped. [code] prefixes the file number. */
    enum class Column(val label: String, val code: Char) {
        FOLDERS(com.iris.localai.I18n.s(com.iris.localai.R.string.files_1f4c10), 'F'), DOCS(com.iris.localai.I18n.s(com.iris.localai.R.string.files_325369), 'D'), MEDIA(com.iris.localai.I18n.s(com.iris.localai.R.string.files_104ec0), 'M'), MODELS(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8000f1), 'N'), OTHER(com.iris.localai.I18n.s(com.iris.localai.R.string.util_0d98c7), 'X')
    }

    private val DOCS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "rtf", "odt")

    fun columnOf(isDir: Boolean, system: Boolean, extension: String): Column = when (kind(isDir, system, extension)) {
        Kind.SYSTEM, Kind.FOLDER -> Column.FOLDERS
        Kind.TEXT -> Column.DOCS
        Kind.MEDIA -> Column.MEDIA
        Kind.MODEL -> Column.MODELS
        Kind.PACKAGE, Kind.OTHER -> if (extension.lowercase(Locale.ROOT) in DOCS) Column.DOCS else Column.OTHER
    }

    fun columnOf(e: FileEntry): Column = columnOf(e.isDir, e.system != null, e.extension)

    /** Non-empty columns of [entries], in [Column] order. */
    fun columns(entries: List<FileEntry>): List<Column> {
        val present = entries.mapTo(HashSet()) { columnOf(it) }
        return Column.entries.filter { it in present }
    }

    /** The entries of [column], keeping the page's sort order. */
    fun inColumn(entries: List<FileEntry>, column: Column?): List<FileEntry> =
        if (column == null) emptyList() else entries.filter { columnOf(it) == column }

    /** The column to show after the entries changed: the same one when still present, else the first. */
    fun keepColumn(columns: List<Column>, current: Column?): Column? = if (current != null && current in columns) current else columns.firstOrNull()

    /** "F-001" for the first folder ([index] 0); wider past 999. */
    fun fileNumber(column: Column, index: Int): String = column.code + "-" + (index.coerceAtLeast(0) + 1).toString().padStart(3, '0')

    /** Two-digit counter text: "01", "12", "128". */
    fun counter(value: Int): String = value.coerceAtLeast(0).toString().padStart(2, '0')

    /** "COLUMN 01 / 03". */
    fun columnLabel(index: Int, count: Int): String = "COLUMN " + counter(index + 1) + " / " + counter(count)

    /** Path line of the HUD: "DOWNLOAD / IRIDIS / 模型 / qwen". */
    fun pathLine(crumbLabels: List<String>): String = (listOf("DOWNLOAD") + crumbLabels).joinToString(" / ")

    /** Ticks of the bottom ruler: at most [max], the selected one at [selected] (wraps for long folders). */
    fun rulerTicks(count: Int, max: Int = 12): Int = count.coerceIn(0, max)
    fun rulerSelected(index: Int, count: Int, max: Int = 12): Int = if (count <= max) index else ((index.toLong() * max) / count.coerceAtLeast(1)).toInt().coerceIn(0, max - 1)

    // ---------------------------------------------------------------- detail reveal

    /** Local 0..1 progress of the redaction bar [i] (bars retract left → right, one after another) at overall [t]. */
    fun redactProgress(t: Float, i: Int, start: Float = 0.42f, stagger: Float = 0.04f, span: Float = 0.25f): Float =
        ((t - start - i * stagger) / span).coerceIn(0f, 1f)

    /**
     * The decrypt line across the glass panel at progress [t]: it grows from the bottom-left corner
     * to the top-right (0 → [LINE_GROW]), then collapses into the top-right ([LINE_GROW] → [LINE_END]).
     * Writes the start / end fractions (0 = bottom-left, 1 = top-right) into [out] and returns false when hidden.
     */
    fun decryptLine(t: Float, out: FloatArray): Boolean {
        val grow = (t / LINE_GROW).coerceIn(0f, 1f)
        val collapse = ((t - LINE_GROW) / (LINE_END - LINE_GROW)).coerceIn(0f, 1f)
        out[0] = collapse
        out[1] = grow
        return t > 0f && collapse < 1f
    }
    const val LINE_GROW = 0.38f
    const val LINE_END = 0.62f

    /** Frost → clear front of the glass panel, top-down (0 = all frosted, 1 = clear), at overall [t]. */
    fun frostFront(t: Float): Float = ((t - 0.18f) / 0.55f).coerceIn(0f, 1f)

    private val IMAGES = setOf("png", "jpg", "jpeg", "webp")
    /** Image files get a thumbnail inside the glass slab. */
    fun isImage(extension: String): Boolean = extension.lowercase(Locale.ROOT) in IMAGES

    /** inSampleSize (power of two) so the larger side of a [w]×[h] image is ≤ [maxSide]. */
    fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    // ---------------------------------------------------------------- open transition (2.1.9 档案 打开转场与详情页重做)
    //
    // One linear 0 → 1 timeline drives the whole open (IrisMotion.ARCHIVE_OPEN_MS): the selected
    // slab's face is carried to the detail's glass panel (shared-element push, the field zooming and
    // blurring behind it), a diagonal scan line reveals the glass contents, then the info rows
    // "decrypt" one after another. Each phase gets its own local 0..1 and eases itself (the view
    // passes IrisMotion.ProgressEasing). Closing runs its own timeline (ARCHIVE_CLOSE_MS) in reverse
    // order: info fades, the panel shrinks back into its slot, the field clears.

    /** Local 0..1 progress of the phase [start, end] at overall [t]. */
    fun phase(t: Float, start: Float, end: Float): Float =
        if (end <= start) (if (t >= end) 1f else 0f) else ((t - start) / (end - start)).coerceIn(0f, 1f)

    object Open {
        /** Push (slab face → glass panel, field zoom / blur) over [0, PUSH_END]. */
        const val PUSH_END = 0.40f
        /** Info area (labels + redaction bars) fades in while the panel travels. */
        const val INFO_START = 0.10f
        const val INFO_END = 0.34f
        /** Diagonal scan across the glass. */
        const val SCAN_START = 0.38f
        const val SCAN_END = 0.68f
        /** Info rows decrypt one after another from ROWS_START, ROW_STAGGER apart, ROW_SPAN each. */
        const val ROWS_START = 0.50f
        const val ROW_STAGGER = 0.04f
        const val ROW_SPAN = 0.22f
        /** The title row takes longer: a few characters first, a pause, then the rest. */
        const val TITLE_SPAN = 0.36f
        /** Rows that decrypt (title, subtitle, four fields, abstract). */
        const val ROWS = 7
        // close timeline
        const val CLOSE_INFO_END = 0.35f
        const val CLOSE_CONTENT_START = 0.10f
        const val CLOSE_CONTENT_END = 0.40f
        const val CLOSE_PUSH_START = 0.25f
    }

    /** Push progress (0 = the slab in the field, 1 = the glass panel in place) at open time [t], eased by [ease]. */
    fun pushOf(t: Float, ease: (Float) -> Float = { it }): Float = ease(phase(t, 0f, Open.PUSH_END))
    fun infoOf(t: Float): Float = phase(t, Open.INFO_START, Open.INFO_END)
    fun scanOf(t: Float, ease: (Float) -> Float = { it }): Float = ease(phase(t, Open.SCAN_START, Open.SCAN_END))

    /** Decrypt progress of info row [row] (0 = all bar, 1 = all text) at open time [t]; row 0 is the title. */
    fun rowOf(t: Float, row: Int, ease: (Float) -> Float = { it }): Float {
        if (row == 0) return phase(t, Open.ROWS_START, Open.ROWS_START + Open.TITLE_SPAN)
        val start = Open.ROWS_START + row * Open.ROW_STAGGER
        return ease(phase(t, start, start + Open.ROW_SPAN))
    }

    /** Close: the info area's opacity, the glass contents' opacity, and the push (from its value [from] at close start). */
    fun closeInfo(c: Float): Float = 1f - phase(c, 0f, Open.CLOSE_INFO_END)
    fun closeContent(c: Float): Float = 1f - phase(c, Open.CLOSE_CONTENT_START, Open.CLOSE_CONTENT_END)
    fun closePush(c: Float, from: Float, ease: (Float) -> Float = { it }): Float =
        from.coerceIn(0f, 1f) * (1f - ease(phase(c, Open.CLOSE_PUSH_START, 1f)))

    /**
     * Title reveal with a pause: the bar first retracts to [prefix] (the width fraction of the first
     * few characters), holds, then clears the rest. [p] is the title row's progress. With prefix 0
     * it is a plain retract.
     */
    fun titleReveal(p: Float, prefix: Float): Float {
        val k = p.coerceIn(0f, 1f)
        val pre = prefix.coerceIn(0f, 1f)
        if (pre <= 0f) return k
        return when {
            k < TITLE_FIRST -> pre * (k / TITLE_FIRST)
            k < TITLE_HOLD -> pre
            else -> pre + (1f - pre) * ((k - TITLE_HOLD) / (1f - TITLE_HOLD))
        }.coerceIn(0f, 1f)
    }
    const val TITLE_FIRST = 0.38f
    const val TITLE_HOLD = 0.62f

    /** Characters shown before the title's pause: none for short names (only longer titles stutter). */
    fun titlePrefixChars(length: Int): Int = if (length < 6) 0 else minOf(3, length / 3)

    /** Within one row of [lines] text lines, line [line]'s own 0..1 (lines start a little after each other). */
    fun lineReveal(p: Float, line: Int, lines: Int): Float {
        if (lines <= 1) return p.coerceIn(0f, 1f)
        val stagger = minOf(0.18f, 0.5f / (lines - 1))
        val start = line.coerceIn(0, lines - 1) * stagger
        val span = 1f - (lines - 1) * stagger
        return phase(p, start, start + span)
    }

    /** Quad (TL, TR, BR, BL as x, y pairs) of the rectangle [l, t, r, b] into [out]. */
    fun rectQuad(l: Float, t: Float, r: Float, b: Float, out: FloatArray): FloatArray {
        out[0] = l; out[1] = t; out[2] = r; out[3] = t; out[4] = r; out[5] = b; out[6] = l; out[7] = b
        return out
    }

    /** Corner-wise interpolation of two quads (shared-element geometry: 0 = [from], 1 = [to]). */
    fun lerpQuad(from: FloatArray, to: FloatArray, t: Float, out: FloatArray): FloatArray {
        for (i in 0 until 8) out[i] = from[i] + (to[i] - from[i]) * t
        return out
    }

    /** Quad [q] scaled by [s] about its own centre (fallback start when the slab was not drawn). */
    fun scaleQuad(q: FloatArray, s: Float, out: FloatArray): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        for (i in 0 until 4) {
            out[i * 2] = cx + (q[i * 2] - cx) * s
            out[i * 2 + 1] = cy + (q[i * 2 + 1] - cy) * s
        }
        return out
    }

    /** Centre of quad [q] into [out] (x, y). */
    fun quadCenter(q: FloatArray, out: FloatArray): FloatArray {
        out[0] = (q[0] + q[2] + q[4] + q[6]) / 4f
        out[1] = (q[1] + q[3] + q[5] + q[7]) / 4f
        return out
    }

    /** Depth of field behind the push [p]: the field zooms toward the slab, fades toward the background, blurs. */
    fun backdropScale(p: Float): Float = 1f + BACKDROP_ZOOM * p.coerceIn(0f, 1f)
    fun backdropAlpha(p: Float): Float = 1f - BACKDROP_FADE * p.coerceIn(0f, 1f)
    /** Background-coloured veil over the field (never black). */
    fun backdropVeil(p: Float): Float = BACKDROP_VEIL * p.coerceIn(0f, 1f)
    const val BACKDROP_ZOOM = 0.30f
    const val BACKDROP_FADE = 0.40f
    const val BACKDROP_VEIL = 0.55f

    /**
     * The scan over a [w]×[h] panel at [s] (0..1): the line x/w + y/h = 2s, oriented bottom-left →
     * top-right, travels from the top-left corner to the bottom-right one. Writes the revealed region
     * (the part already swept, x/w + y/h ≤ 2s) as a polygon of up to 5 points into [out] (10 floats);
     * returns the point count (0 = nothing revealed yet).
     */
    fun scanPolygon(s: Float, w: Float, h: Float, out: FloatArray): Int {
        if (w <= 0f || h <= 0f || s <= 0f) return 0
        val k = 2f * s.coerceAtMost(1f)
        val xs = floatArrayOf(0f, w, w, 0f)
        val ys = floatArrayOf(0f, 0f, h, h)
        fun f(i: Int) = xs[i] / w + ys[i] / h - k
        var n = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val fi = f(i); val fj = f(j)
            if (fi <= 0f) { out[n * 2] = xs[i]; out[n * 2 + 1] = ys[i]; n++ }
            if ((fi < 0f && fj > 0f) || (fi > 0f && fj < 0f)) {
                val a = fi / (fi - fj)
                out[n * 2] = xs[i] + (xs[j] - xs[i]) * a
                out[n * 2 + 1] = ys[i] + (ys[j] - ys[i]) * a
                n++
            }
        }
        return n
    }

    /** End points (x0, y0 = lower-left end, x1, y1 = upper-right end) of the scan line clipped to the panel; false when not on it. */
    fun scanSegment(s: Float, w: Float, h: Float, out: FloatArray): Boolean {
        if (s <= 0f || s >= 1f) return false
        val k = 2f * s
        if (k <= 1f) { out[0] = 0f; out[1] = k * h; out[2] = k * w; out[3] = 0f }
        else { out[0] = (k - 1f) * w; out[1] = h; out[2] = w; out[3] = (k - 1f) * h }
        return true
    }

    // ---------------------------------------------------------------- detail facts

    /** The app folder an entry lives in (models/…, voice/…), or null at the root / elsewhere. */
    fun systemTop(rel: String): SystemFolder? = FilesPaths.segments(rel)?.firstOrNull()?.let { SystemFolder.of(it) }

    private val CODE = setOf("kt", "java", "py", "sh", "js", "ts", "c", "h", "cpp", "rs", "go", "sql", "css")
    private val CONFIG = setOf("yaml", "yml", "toml", "ini", "cfg", "conf", "properties")
    private val IMAGE_ALL = setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "bmp")
    private val AUDIO = setOf("mp3", "wav", "ogg", "m4a", "flac")
    private val VIDEO = setOf("mp4", "mkv", "webm", "mov")

    /** Chinese type name of the detail's subtitle (模型权重 / 语音模型 / Markdown 文档 / 图片 / 文件夹 …). */
    fun typeName(isDir: Boolean, system: Boolean, extension: String, top: SystemFolder?): String {
        val ext = extension.lowercase(Locale.ROOT)
        return when {
            system -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_83f35b)
            isDir -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_1f4c10)
            ext in MODEL -> if (top == SystemFolder.VOICE) com.iris.localai.I18n.s(com.iris.localai.R.string.util_589dad) else com.iris.localai.I18n.s(com.iris.localai.R.string.files_a10468)
            ext == "md" || ext == "markdown" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_f26f71)
            ext == "txt" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_779203)
            ext == "log" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_456d29)
            ext == "json" || ext == "jsonl" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_e77523)
            ext in CONFIG -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_15c64a)
            ext == "xml" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_878046)
            ext == "csv" || ext == "tsv" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_642e53)
            ext == "html" || ext == "htm" -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_18a481)
            ext == "jinja" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_59cf15)
            ext == "srt" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_a8c3cc)
            ext in CODE -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_81cb1f)
            ext in IMAGE_ALL -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_20def7)
            ext in AUDIO -> if (top == SystemFolder.VOICE) com.iris.localai.I18n.s(com.iris.localai.R.string.files_58deff) else com.iris.localai.I18n.s(com.iris.localai.R.string.files_726dd5)
            ext in VIDEO -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_7fcf42)
            ext == "pdf" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_11f65b)
            ext == "doc" || ext == "docx" || ext == "odt" || ext == "rtf" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_43e115)
            ext == "xls" || ext == "xlsx" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_833a88)
            ext == "ppt" || ext == "pptx" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_930904)
            ext == "epub" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_709f7c)
            ext == "apk" -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_03070d)
            ext in PACKAGE -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_c806d0)
            else -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2a0c47)
        }
    }

    fun typeName(e: FileEntry): String = typeName(e.isDir, e.system != null, e.extension, systemTop(e.relPath))

    /** Big glyph of the glass for types without a picture / text: ".GGUF", "DIR", "FILE". */
    fun glyphOf(isDir: Boolean, extension: String): String = when {
        isDir -> "DIR"
        extension.isBlank() -> "FILE"
        else -> "." + extension.uppercase(Locale.ROOT).take(6)
    }

    /** What the glass shows: a thumbnail, the first lines as a printed sheet, a folder drawer, or a glyph. */
    enum class Visual { IMAGE, TEXT, FOLDER, GLYPH }

    fun visualOf(isDir: Boolean, extension: String): Visual = when {
        isDir -> Visual.FOLDER
        isImage(extension) -> Visual.IMAGE
        FilePreview.isText(extension) -> Visual.TEXT
        else -> Visual.GLYPH
    }

    /** "001 / 040": position of the entry in its column. */
    fun serialLabel(index: Int, total: Int): String =
        (index.coerceAtLeast(0) + 1).toString().padStart(3, '0') + " / " + total.coerceAtLeast(0).toString().padStart(3, '0')

    /** Lines of a text head (a trailing newline does not start a line; empty = 0). */
    fun lineCount(bytes: ByteArray, length: Int = bytes.size): Int {
        if (length <= 0) return 0
        var n = 0
        for (i in 0 until length) if (bytes[i] == '\n'.code.toByte()) n++
        return if (bytes[length - 1] == '\n'.code.toByte()) n else n + 1
    }

    /** The first [max] non-blank-trimmed lines of [text], each cut to [width] characters (the glass's printed sheet). */
    fun headLines(text: String, max: Int, width: Int): List<String> =
        text.replace("\r\n", "\n").split('\n').take(max).map { if (it.length > width) it.take(width - 1) + "…" else it }

    /** What the 概述 tab knows about one entry; [summary] turns it into the abstract. */
    class Facts(
        val typeName: String,
        val isDir: Boolean,
        val sizeText: String,
        val modifiedText: String,
        val previewable: Boolean = false,
        /** Text files: line count (null = not counted, e.g. too large). */
        val lines: Int? = null,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        /** Folders: number of visible items (null = unreadable) and the first few names. */
        val children: Int? = null,
        val childNames: List<String> = emptyList(),
        /** Label of the app folder it lives in (模型 / 语音 …), or null. */
        val systemLabel: String? = null,
        /** The entry is one of the app's fixed folders. */
        val fixed: Boolean = false,
        /** FileProtection.warningText for files inside models / voice. */
        val warning: String? = null,
    )

    fun summary(f: Facts): String = buildString {
        if (f.isDir) {
            append(f.typeName).append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_d3b98f))
            if (f.children == null) append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_628444))
            else if (f.children == 0) append(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e96910))
            else {
                append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_03d4b1)).append(f.children).append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_c4c4af))
                if (f.childNames.isNotEmpty()) {
                    append(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dec892)).append(f.childNames.joinToString(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b50566)))
                    if (f.children > f.childNames.size) append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_2a3524))
                }
            }
            append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_72e90f)).append(f.modifiedText).append(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_81197c))
        } else {
            append(f.typeName).append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_d3b98f)).append(f.sizeText)
            if (f.lines != null) append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_899285)).append(f.lines).append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_d17020))
            if (f.imageWidth > 0 && f.imageHeight > 0) append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_8833ed)).append(f.imageWidth).append(" × ").append(f.imageHeight)
            append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_18e6b7)).append(f.modifiedText).append(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_81197c))
        }
        if (f.fixed) append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_3b0bfd))
        else if (f.systemLabel != null) append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_e19652)).append(f.systemLabel).append(com.iris.localai.I18n.s(com.iris.localai.R.string.files_2cc416))
        if (f.warning != null) append(f.warning)
        else if (!f.isDir) append(if (f.previewable) com.iris.localai.I18n.s(com.iris.localai.R.string.files_e172b5) else com.iris.localai.I18n.s(com.iris.localai.R.string.files_31a6b4))
    }

    // ---------------------------------------------------------------- colours (ARGB ints, no Android types)

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + ((y - x) * k)).roundToInt() and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Palette of the field. Light default = the warm off-white of the reference; dark = deep graphite. */
    class FieldColors(
        val background: Int,
        /** Face colour lit head-on and facing away from the light. */
        val lit: Int,
        val shade: Int,
        /** Haze toward the top of the field. */
        val haze: Int,
        val shadow: Int,
        val highlight: Int,
        val dot: Int,
        val label: Int,
    ) {
        companion object {
            val LIGHT = FieldColors(
                background = 0xFFEFEBE5.toInt(), lit = 0xFFFBF9F5.toInt(), shade = 0xFFC2BAAE.toInt(), haze = 0xFFF5F2EE.toInt(),
                shadow = 0xFFD3CCC1.toInt(), highlight = 0xFFFFFFFF.toInt(), dot = 0xFF85807A.toInt(), label = 0xFF2A2826.toInt(),
            )
            val DARK = FieldColors(
                background = 0xFF141416.toInt(), lit = 0xFF4C4C52.toInt(), shade = 0xFF19191C.toInt(), haze = 0xFF1B1B1E.toInt(),
                shadow = 0xFF0C0C0D.toInt(), highlight = 0xFF66666D.toInt(), dot = 0xFF9A9AA2.toInt(), label = 0xFFD8D8DE.toInt(),
            )

            /** [base] tinted toward [accent] by [amount] (non-default palettes get a hint of their colour). */
            fun tinted(base: FieldColors, accent: Int, amount: Float): FieldColors = if (amount <= 0f) base else FieldColors(
                lerpColor(base.background, accent, amount * 0.5f), lerpColor(base.lit, accent, amount * 0.4f),
                lerpColor(base.shade, accent, amount), lerpColor(base.haze, accent, amount * 0.5f),
                lerpColor(base.shadow, accent, amount), base.highlight, base.dot, base.label,
            )
        }
    }

    // ---------------------------------------------------------------- the field itself

    /**
     * Geometry of the whole slab field for one canvas size. [layout] once per size, then [build]
     * per frame: it writes triangles (x, y pairs) into [verts] and per-vertex colours into [colors]
     * in painter's order (one `drawVertices` call draws everything) and returns the vertex count.
     * Nothing is allocated in [build].
     */
    class SlabField {
        // screen basis (dp per world unit) of X and Y; Z maps to straight up
        private val exx = kotlin.math.cos(Field.EX_DEG * PI.toFloat() / 180f)
        private val exy = sin(Field.EX_DEG * PI.toFloat() / 180f)
        private val eyx = kotlin.math.cos(Field.EY_DEG * PI.toFloat() / 180f)
        private val eyy = sin(Field.EY_DEG * PI.toFloat() / 180f)
        /** Direction toward the viewer (kernel of the projection, oriented up): a face is visible when n·k > 0. */
        val kx: Float; val ky: Float; val kz: Float
        // light direction (normalised): from the upper left, in front
        private val lx: Float; private val ly: Float; private val lz: Float

        init {
            // rows of the 2×3 projection: (exx, eyx, 0) and (exy, eyy, -1); k = r1 × r2
            var ax = eyx * -1f - 0f * eyy
            var ay = 0f * exy - exx * -1f
            var az = exx * eyy - eyx * exy
            if (az < 0f) { ax = -ax; ay = -ay; az = -az }
            kx = ax; ky = ay; kz = az
            val n = kotlin.math.sqrt(0.35f * 0.35f + 0.55f * 0.55f + 0.76f * 0.76f)
            lx = -0.35f / n; ly = -0.55f / n; lz = 0.76f / n
        }

        var width = 0f; private set
        var height = 0f; private set
        var density = 1f; private set
        private var anchorX = 0f
        private var anchorY = 0f
        private var uMin = 0f; private var uMax = 0f
        private var jMin = 0; private var jMax = 0

        var verts = FloatArray(0); private set
        var colors = IntArray(0); private set
        var capacitySlabs = 0; private set

        /** Screen bounds (l, t, r, b) and logical column of every active slab drawn by the last [build]. */
        val activeBounds = FloatArray(MAX_ACTIVE * 4)
        val activeColumn = IntArray(MAX_ACTIVE)
        var activeCount = 0; private set
        /** Logical column of the selected slab in the last [build] (round(pos)). */
        var selectedColumn = 0; private set
        /** Screen quad (TL, TR, BR, BL) of the selected slab's broad +X face in the last [build]; see [selectedFace]. */
        private val selFace = FloatArray(8)
        private var selFaceValid = false

        /** Sizes the field for a [w]×[h] px canvas at [density] px / dp; reallocates only when needed. */
        fun layout(w: Float, h: Float, density: Float) {
            if (w == width && h == height && density == this.density && verts.isNotEmpty()) return
            width = w; height = h; this.density = density
            anchorX = w * Field.ANCHOR_X
            anchorY = h * Field.ANCHOR_Y
            // invert the column / row basis at the four (margin-expanded) corners
            val m = Field.MARGIN * density
            val ax = (Field.PITCH * exx + Field.STAGGER * eyx) * density
            val ay = (Field.PITCH * exy + Field.STAGGER * eyy) * density
            val bx = Field.ROW * eyx * density
            val by = Field.ROW * eyy * density
            val det = ax * by - ay * bx
            var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var j0 = Float.MAX_VALUE; var j1 = -Float.MAX_VALUE
            for (i in 0 until 4) {
                val cx = (if (i and 1 == 0) -m else w + m) - anchorX
                val cy = (if (i and 2 == 0) -m else h + m) - anchorY
                val u = (cx * by - cy * bx) / det
                val j = (ax * cy - ay * cx) / det
                u0 = minOf(u0, u); u1 = maxOf(u1, u); j0 = minOf(j0, j); j1 = maxOf(j1, j)
            }
            uMin = u0; uMax = u1
            jMin = kotlin.math.floor(j0).toInt() - 1
            jMax = kotlin.math.ceil(j1).toInt() + 1
            val cols = kotlin.math.ceil(u1 - u0).toInt() + 3
            val rows = jMax - jMin + 1
            capacitySlabs = cols * rows
            verts = FloatArray(capacitySlabs * Field.VERTS_PER_SLAB * 2)
            colors = IntArray(capacitySlabs * Field.VERTS_PER_SLAB)
        }

        /** Column range drawn for scroll position [pos] (inclusive). */
        fun firstColumn(pos: Float): Int = kotlin.math.floor(pos + uMin).toInt() - 1
        fun lastColumn(pos: Float): Int = kotlin.math.ceil(pos + uMax).toInt() + 1

        // ---- per-slab scratch (no allocation)
        private var n = 0
        private var sx0 = 0f; private var sy0 = 0f; private var sz0 = 0f // slab origin (world dp)
        private var cosT = 1f; private var sinT = 0f; private var hh = Field.H; private var yOff = 0f
        private var px = 0f; private var py = 0f
        private val cx = FloatArray(8); private val cy = FloatArray(8)
        private lateinit var pal: FieldColors

        /** World → screen px (into px / py). */
        private fun project(x: Float, y: Float, z: Float) {
            px = anchorX + (x * exx + y * eyx) * density
            py = anchorY + (x * exy + y * eyy - z) * density
        }

        /** Slab-local (x across, u along, v up) → screen, through the slab's rotation about its near bottom edge. */
        private fun local(x: Float, u: Float, v: Float) =
            project(sx0 + x, sy0 + yOff + u * cosT - v * sinT, sz0 + u * sinT + v * cosT)

        /** Haze toward the top: 0 at 55 % of the height and below, up to 0.65 at the top edge. */
        private fun hazeAt(y: Float): Float {
            val t = (1f - y / (height * 0.55f)).coerceIn(0f, 1f)
            return t * kotlin.math.sqrt(t) * 0.65f
        }

        private fun shadeOf(nx: Float, ny: Float, nz: Float): Int =
            lerpColor(pal.shade, pal.lit, 0.5f + 0.5f * (nx * lx + ny * ly + nz * lz))

        private fun vertex(x: Float, y: Float, color: Int) {
            verts[n * 2] = x; verts[n * 2 + 1] = y
            colors[n] = lerpColor(color, pal.haze, hazeAt(y))
            n++
        }

        /** Quad from the corner points a, b, c, d (indices into [cx] / [cy]) with one colour and a darker bottom pair. */
        private fun face(a: Int, b: Int, c: Int, d: Int, color: Int, aoA: Float, aoB: Float, aoC: Float, aoD: Float) {
            val ca = lerpColor(color, pal.shade, aoA); val cb = lerpColor(color, pal.shade, aoB)
            val cc = lerpColor(color, pal.shade, aoC); val cd = lerpColor(color, pal.shade, aoD)
            vertex(cx[a], cy[a], ca); vertex(cx[b], cy[b], cb); vertex(cx[c], cy[c], cc)
            vertex(cx[a], cy[a], ca); vertex(cx[c], cy[c], cc); vertex(cx[d], cy[d], cd)
        }

        private var qx0 = 0f; private var qy0 = 0f; private var qx1 = 0f; private var qy1 = 0f
        private var qx2 = 0f; private var qy2 = 0f

        /** Quad of slab-local points on one face, flat [color] (or [c2] for the far pair). */
        private fun localQuad(
            x0: Float, u0: Float, v0: Float, x1: Float, u1: Float, v1: Float,
            x2: Float, u2: Float, v2: Float, x3: Float, u3: Float, v3: Float, color: Int, c2: Int = color,
        ) {
            local(x0, u0, v0); qx0 = px; qy0 = py
            local(x1, u1, v1); qx1 = px; qy1 = py
            local(x2, u2, v2); qx2 = px; qy2 = py
            local(x3, u3, v3)
            vertex(qx0, qy0, color); vertex(qx1, qy1, color); vertex(qx2, qy2, c2)
            vertex(qx0, qy0, color); vertex(qx2, qy2, c2); vertex(px, py, c2)
        }

        /**
         * Rebuilds the vertex buffers.
         * @param pos scroll position in columns (the selected slab is the column round(pos))
         * @param hasActive false = no entries: every slab lies flat
         * @param rise global stand / lift 0..1 (staggered outwards from the selection)
         * @param shiftRows 0..1: the whole field slides one row along the slabs (folder change)
         * @param wave selection wave progress 0..1 (no effect at 0 or 1)
         * @param drop 0..1 intro drop (1 = slabs float [Field.DROP] above their rest)
         * @return vertex count (each vertex is two floats in [verts])
         */
        fun build(
            pos: Float, hasActive: Boolean, rise: Float, shiftRows: Float, wave: Float, drop: Float, palette: FieldColors,
            hideSelected: Boolean = false,
        ): Int {
            pal = palette
            n = 0
            activeCount = 0
            selFaceValid = false
            selectedColumn = pos.roundToInt()
            val c0 = firstColumn(pos); val c1 = lastColumn(pos)
            val maxVerts = colors.size
            val sideX = shadeOf(1f, 0f, 0f)
            for (c in c0..c1) {
                val u = c - pos
                for (j in jMax downTo jMin) {
                    if (n + Field.VERTS_PER_SLAB > maxVerts) return n
                    val active = hasActive && j == 0
                    val stand = if (active) standOf(u) * riseFor(rise, u) else 0f
                    val lift = if (active) (liftOf(u) * Field.LIFT * riseFor(rise, u) + fieldWave(wave, u)) * (1f - stand) else 0f
                    val theta = stand * (PI.toFloat() / 2f)
                    cosT = kotlin.math.cos(theta); sinT = sin(theta)
                    hh = Field.H * (1f + Field.STAND_WIDEN * stand)
                    yOff = maxOf(0f, hh * sinT - (Field.GAP - 2f))
                    sx0 = u * Field.PITCH
                    sy0 = (j + shiftRows) * Field.ROW + u * Field.STAGGER
                    sz0 = lift + drop * Field.DROP * (1f + 0.15f * (j and 3))
                    // corners: bit 0 = x (0 / W), bit 1 = u (0 / L), bit 2 = v (0 / hh)
                    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                    for (k in 0 until 8) {
                        local(if (k and 1 != 0) Field.W else 0f, if (k and 2 != 0) Field.L else 0f, if (k and 4 != 0) hh else 0f)
                        cx[k] = px; cy[k] = py
                        minX = minOf(minX, px); maxX = maxOf(maxX, px); minY = minOf(minY, py); maxY = maxOf(maxY, py)
                    }
                    val sh = 14f * density
                    if (maxX + sh < 0f || minX > width || maxY + sh < 0f || minY > height) continue
                    if (active && activeCount < MAX_ACTIVE && abs(u) <= MAX_ACTIVE / 2) {
                        activeBounds[activeCount * 4] = minX; activeBounds[activeCount * 4 + 1] = minY
                        activeBounds[activeCount * 4 + 2] = maxX; activeBounds[activeCount * 4 + 3] = maxY
                        activeColumn[activeCount] = c
                        activeCount++
                    }
                    if (active && c == selectedColumn) {
                        // the broad +X face: corners 7 (u = L, v = hh), 3, 1, 5 — top-left, top-right,
                        // bottom-right, bottom-left on screen when the slab stands
                        selFace[0] = cx[7]; selFace[1] = cy[7]; selFace[2] = cx[3]; selFace[3] = cy[3]
                        selFace[4] = cx[1]; selFace[5] = cy[1]; selFace[6] = cx[5]; selFace[7] = cy[5]
                        selFaceValid = true
                        // the open transition carries this face to the detail's glass panel
                        if (hideSelected) continue
                    }
                    // contact shadow on the ground along the +X side of the footprint
                    val yNear = sy0 + yOff - hh * sinT
                    val yFar = sy0 + yOff + Field.L * cosT
                    val sAlpha = 1f / (1f + sz0 * 0.08f)
                    val sc = lerpColor(pal.background, pal.shadow, sAlpha)
                    project(sx0 + Field.W, yNear, 0f); qx0 = px; qy0 = py
                    project(sx0 + Field.W, yFar, 0f); qx1 = px; qy1 = py
                    project(sx0 + Field.W + 13f, yFar + 5f, 0f); qx2 = px; qy2 = py
                    project(sx0 + Field.W + 13f, yNear + 5f, 0f)
                    vertex(qx0, qy0, sc); vertex(qx1, qy1, sc); vertex(qx2, qy2, pal.background)
                    vertex(qx0, qy0, sc); vertex(qx2, qy2, pal.background); vertex(px, py, pal.background)
                    // +X side (always visible): darker toward the ground
                    face(1, 3, 7, 5, sideX, 0.22f, 0.22f, 0f, 0f)
                    // original top (+Z) or bottom face
                    val topNy = -sinT; val topNz = cosT
                    val topVisible = topNy * ky + topNz * kz > 0f
                    if (topVisible) face(4, 5, 7, 6, shadeOf(0f, topNy, topNz), 0f, 0f, 0f, 0f)
                    else face(0, 1, 3, 2, shadeOf(0f, sinT, -cosT), 0f, 0f, 0f, 0f)
                    // near (−Y) or far (+Y) end
                    val nearNy = -cosT; val nearNz = -sinT
                    if (nearNy * ky + nearNz * kz > 0f) face(0, 1, 5, 4, shadeOf(0f, nearNy, nearNz), 0.12f, 0.12f, 0f, 0f)
                    else face(2, 3, 7, 6, shadeOf(0f, cosT, sinT), 0f, 0f, 0f, 0f)
                    if (topVisible) {
                        // bevel highlight along the +X edge of the top face, and the small dark dot near the far end
                        val hw = 1.1f
                        localQuad(Field.W - hw, 0f, hh, Field.W, 0f, hh, Field.W, Field.L, hh, Field.W - hw, Field.L, hh, pal.highlight)
                        val d0 = Field.W / 2f - 1.7f; val d1 = Field.W / 2f + 1.7f
                        localQuad(d0, Field.L - 14f, hh, d1, Field.L - 14f, hh, d1, Field.L - 10f, hh, d0, Field.L - 10f, hh, pal.dot)
                    }
                    val tag = if (active) riseFor(rise, u) else 0f
                    if (tag > 0f) {
                        // the entry's tag: a light label with a dark strip on the +X face near the far end,
                        // fading in with the rise (so a lying active row looks like any other row)
                        val lu0 = Field.L - 34f; val lu1 = Field.L - 18f
                        val lv0 = hh * 0.36f; val lv1 = hh * 0.64f
                        localQuad(Field.W, lu0, lv0, Field.W, lu1, lv0, Field.W, lu1, lv1, Field.W, lu0, lv1, lerpColor(sideX, pal.highlight, 0.45f * tag))
                        localQuad(Field.W, lu1 - 2.5f, lv0, Field.W, lu1, lv0, Field.W, lu1, lv1, Field.W, lu1 - 2.5f, lv1, lerpColor(sideX, pal.label, 0.55f * tag))
                    }
                }
            }
            return n
        }

        /**
         * Logical column of the active slab under ([x], [y]) from the last [build], preferring the
         * selected one, then the nearest by bounds centre; null when none.
         */
        fun hit(x: Float, y: Float, slop: Float = 0f): Int? {
            var best: Int? = null
            var bestD = Float.MAX_VALUE
            for (i in 0 until activeCount) {
                val l = activeBounds[i * 4] - slop; val t = activeBounds[i * 4 + 1] - slop
                val r = activeBounds[i * 4 + 2] + slop; val b = activeBounds[i * 4 + 3] + slop
                if (x < l || x > r || y < t || y > b) continue
                if (activeColumn[i] == selectedColumn) return selectedColumn
                val dx = x - (l + r) / 2f; val dy = y - (t + b) / 2f
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = activeColumn[i] }
            }
            return best
        }

        /** Bounds of the selected slab from the last [build] into [out] (l, t, r, b); false when it was not drawn. */
        fun selectedBounds(out: FloatArray): Boolean {
            for (i in 0 until activeCount) if (activeColumn[i] == selectedColumn) {
                for (k in 0 until 4) out[k] = activeBounds[i * 4 + k]
                return true
            }
            return false
        }

        /** The selected slab's face quad from the last [build] into [out] (8 floats, TL TR BR BL); false when it was not drawn. */
        fun selectedFace(out: FloatArray): Boolean {
            if (!selFaceValid) return false
            selFace.copyInto(out)
            return true
        }

        /** Screen position (px) of world point ([x], [y], [z]) relative to the anchor, for tests. */
        fun screenOf(x: Float, y: Float, z: Float, out: FloatArray) { project(x, y, z); out[0] = px; out[1] = py }

        companion object {
            /** Active slabs whose bounds are kept for hit testing (centred on the selection). */
            const val MAX_ACTIVE = 12
        }
    }
}
