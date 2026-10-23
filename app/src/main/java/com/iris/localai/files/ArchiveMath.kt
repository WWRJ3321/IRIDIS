package com.iris.localai.files

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * 2.2 「档案模式」: the pure geometry of the archive deck (no Android / Compose types, unit-tested).
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
    fun spacing(a: Float): Float = a * 0.62f - 0.06f * a * a

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
}
