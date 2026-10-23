package com.iris.localai.files

import com.iris.localai.ui.motion.IrisMotion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** 2.1.9 「档案」 重做: the slab field, columns, labels and detail reveal of [ArchiveMath]. */
class ArchiveFieldTest {
    private val eps = 1e-4f
    private val light = ArchiveMath.FieldColors.LIGHT

    private fun field(w: Float = 1080f, h: Float = 2200f, d: Float = 2.75f) = ArchiveMath.SlabField().apply { layout(w, h, d) }

    private fun entry(name: String, dir: Boolean = false, system: SystemFolder? = null) =
        FileEntry(name, name, dir, 10, 0, 0, system)

    // ---------------------------------------------------------------- projection

    @Test fun projectionAnchorsTheSelectionAndZGoesUp() {
        val f = field(400f, 800f, 1f)
        val p = FloatArray(2)
        f.screenOf(0f, 0f, 0f, p)
        assertEquals(400f * ArchiveMath.Field.ANCHOR_X, p[0], eps)
        assertEquals(800f * ArchiveMath.Field.ANCHOR_Y, p[1], eps)
        val q = FloatArray(2)
        f.screenOf(0f, 0f, 10f, q)
        assertEquals(p[0], q[0], eps)
        assertEquals(p[1] - 10f, q[1], eps)          // +Z = straight up on screen
        f.screenOf(0f, 10f, 0f, q)
        assertTrue(q[0] > p[0] && q[1] < p[1])        // slabs run up-right
        f.screenOf(10f, 0f, 0f, q)
        assertTrue(q[0] > p[0] && q[1] > p[1])        // columns step down-right
    }

    @Test fun densityScalesTheProjection() {
        val a = FloatArray(2); val b = FloatArray(2)
        field(400f, 800f, 1f).screenOf(10f, 20f, 5f, a)
        field(1200f, 2400f, 3f).screenOf(10f, 20f, 5f, b)
        assertEquals(a[0] * 3f, b[0], 1e-3f)
        assertEquals(a[1] * 3f, b[1], 1e-3f)
    }

    @Test fun viewerSeesTopSideAndNearEnd() {
        val f = field()
        assertTrue(f.kx > 0f)  // +X side face
        assertTrue(f.ky < 0f)  // −Y (near) end
        assertTrue(f.kz > 0f)  // top
    }

    // ---------------------------------------------------------------- stand / lift / wave

    @Test fun selectedStandsAndOnlyIt() {
        assertEquals(1f, ArchiveMath.standOf(0f), eps)
        assertEquals(0f, ArchiveMath.standOf(1f), eps)
        assertEquals(0f, ArchiveMath.standOf(-3f), eps)
        assertEquals(0.5f, ArchiveMath.standOf(0.5f), eps)
        assertEquals(ArchiveMath.standOf(0.3f), ArchiveMath.standOf(-0.3f), eps)
        var last = 1.1f
        for (i in 0..10) { val v = ArchiveMath.standOf(i / 10f); assertTrue(v <= last); last = v }
    }

    @Test fun neighboursLiftInADecayingWave() {
        var last = Float.MAX_VALUE
        for (d in 0..5) { val v = ArchiveMath.liftOf(d.toFloat()); assertTrue("d=$d", v < last || v == 0f); last = v }
        assertEquals(0f, ArchiveMath.liftOf(ArchiveMath.Field.LIFT_REACH), eps)
        assertTrue(ArchiveMath.liftOf(1f) > ArchiveMath.liftOf(2f))
        assertEquals(ArchiveMath.liftOf(2f), ArchiveMath.liftOf(-2f), eps)
    }

    @Test fun selectionWaveStartsAtTheSelectionAndRipplesOut() {
        assertEquals(0f, ArchiveMath.fieldWave(0f, 0f), eps)
        assertEquals(0f, ArchiveMath.fieldWave(1f, 2f), eps)
        assertTrue(ArchiveMath.fieldWave(0.1f, 0f) > 0f)
        assertEquals(0f, ArchiveMath.fieldWave(0.1f, 3f), eps)            // not started yet out there
        assertTrue(ArchiveMath.fieldWave(0.6f, 3f) > 0f)
        assertTrue(ArchiveMath.fieldWave(0.3f, 0f) <= ArchiveMath.Field.WAVE + eps)
    }

    @Test fun riseIsStaggeredOutwards() {
        assertEquals(1f, ArchiveMath.riseFor(1f, 4f), eps)
        assertEquals(0f, ArchiveMath.riseFor(0f, 0f), eps)
        assertTrue(ArchiveMath.riseFor(0.3f, 0f) > ArchiveMath.riseFor(0.3f, 2f))
    }

    @Test fun fieldSnapLoopsAndCapsAFling() {
        assertEquals(-1f, ArchiveMath.fieldSnap(-0.6f, 0f), eps)        // never clamped at 0
        assertEquals(3f, ArchiveMath.fieldSnap(2.6f, 0f), eps)
        assertEquals(2.4f + ArchiveMath.MAX_FLING_CARDS, ArchiveMath.fieldSnap(2.4f, 1000f) + 0.4f, eps)
    }

    // ---------------------------------------------------------------- the field buffers

    private fun build(f: ArchiveMath.SlabField, pos: Float = 0f, active: Boolean = true, rise: Float = 1f, shift: Float = 0f, wave: Float = 1f, drop: Float = 0f) =
        f.build(pos, active, rise, shift, wave, drop, light)

    @Test fun buildFillsTrianglesWithinCapacityWithoutAllocating() {
        val f = field()
        val verts = f.verts; val colors = f.colors
        val n = build(f)
        assertTrue("slabs drawn", n > 60 * 6 * 4)
        assertEquals(0, n % 6)                                           // whole quads (2 triangles)
        assertTrue(n <= f.colors.size)
        assertTrue(f.capacitySlabs * ArchiveMath.Field.VERTS_PER_SLAB == f.colors.size)
        build(f, pos = 0.37f, wave = 0.5f)
        assertSame(verts, f.verts)                                       // reused per frame
        assertSame(colors, f.colors)
        f.layout(1080f, 2200f, 2.75f)                                    // same size: no reallocation
        assertSame(verts, f.verts)
        for (i in 0 until n) assertEquals("opaque vertex colour", 0xFF, f.colors[i] ushr 24)
    }

    @Test fun buildIsDeterministic() {
        val a = field(); val b = field()
        val na = build(a, pos = 1.25f, wave = 0.4f)
        val nb = build(b, pos = 1.25f, wave = 0.4f)
        assertEquals(na, nb)
        assertArrayEquals(a.verts.copyOf(na * 2), b.verts.copyOf(nb * 2), 0f)
        assertArrayEquals(a.colors.copyOf(na), b.colors.copyOf(nb))
    }

    @Test fun fieldLoopsByWholeColumns() {
        // moving the selection by whole columns redraws the identical field (the entries loop over it)
        val a = field(); val b = field()
        val na = build(a, pos = 0f)
        val nb = build(b, pos = 7f)
        assertEquals(na, nb)
        assertArrayEquals(a.verts.copyOf(na * 2), b.verts.copyOf(nb * 2), 1e-2f)
        assertEquals(7, b.selectedColumn)
    }

    @Test fun selectedSlabStandsUprightAndIsHit() {
        val f = field()
        val box = FloatArray(4)
        build(f, rise = 0f)
        assertTrue(f.selectedBounds(box))
        val lyingH = box[3] - box[1]
        val lyingW = box[2] - box[0]
        assertTrue(lyingW > lyingH)                                      // a lying slab is wider than tall
        build(f, rise = 1f)
        assertTrue(f.selectedBounds(box))
        assertTrue(box[3] - box[1] > box[2] - box[0])                    // standing: taller than wide
        assertTrue(box[3] - box[1] > lyingH * 1.5f)
        assertEquals(0, f.hit((box[0] + box[2]) / 2f, box[1] + (box[3] - box[1]) * 0.3f))
        assertNull(f.hit(-500f, -500f))
        // the standing slab is near the anchor, its top above it
        assertTrue(box[3] >= 2200f * ArchiveMath.Field.ANCHOR_Y - 5f)
        assertTrue(box[1] < 2200f * ArchiveMath.Field.ANCHOR_Y - ArchiveMath.Field.L * 2.75f * 0.8f)
    }

    @Test fun neighbourTapSelectsIt() {
        val f = field()
        build(f, pos = 0f)
        assertTrue(f.activeCount >= 5)
        val i = (0 until f.activeCount).first { f.activeColumn[it] == 2 }
        val x = (f.activeBounds[i * 4] + f.activeBounds[i * 4 + 2]) / 2f
        val y = (f.activeBounds[i * 4 + 1] + f.activeBounds[i * 4 + 3]) / 2f
        assertEquals(2, f.hit(x, y))
    }

    @Test fun noEntriesMeansNothingStands() {
        val f = field()
        val box = FloatArray(4)
        build(f, active = false)
        assertFalse(f.selectedBounds(box))
        assertEquals(0, f.activeCount)
        assertNull(f.hit(1080f * ArchiveMath.Field.ANCHOR_X, 2200f * ArchiveMath.Field.ANCHOR_Y))
    }

    @Test fun lyingActiveRowLooksLikeAnyOtherRow() {
        // at rise 0 the active row has no tag, no stand, no lift: the same buffers as an empty field,
        // so the folder-change row shift can snap back invisibly
        val a = field(); val b = field()
        val na = build(a, active = true, rise = 0f)
        val nb = build(b, active = false, rise = 0f)
        assertEquals(na, nb)
        assertArrayEquals(a.verts.copyOf(na * 2), b.verts.copyOf(nb * 2), 0f)
        assertArrayEquals(a.colors.copyOf(na), b.colors.copyOf(nb))
    }

    @Test fun rowShiftKeepsTheFieldFilled() {
        val f = field()
        val n0 = build(f, rise = 0f, shift = 0f)
        val n1 = build(f, rise = 0f, shift = 1f)
        val n5 = build(f, rise = 0f, shift = 0.5f)
        assertTrue(abs(n0 - n1) < n0 / 8)
        assertTrue(abs(n0 - n5) < n0 / 8)
    }

    @Test fun hazeLightensTowardTheTop() {
        val f = field()
        val n = build(f)
        var topSum = 0.0; var topN = 0; var botSum = 0.0; var botN = 0
        for (i in 0 until n) {
            val y = f.verts[i * 2 + 1]
            val lum = (f.colors[i] shr 16 and 0xFF) + (f.colors[i] shr 8 and 0xFF) + (f.colors[i] and 0xFF)
            if (y < 200f) { topSum += lum; topN++ } else if (y > 1600f) { botSum += lum; botN++ }
        }
        assertTrue(topN > 0 && botN > 0)
        assertTrue(topSum / topN > botSum / botN)
    }

    // ---------------------------------------------------------------- colours

    @Test fun colourMath() {
        assertEquals(0xFF000000.toInt(), ArchiveMath.lerpColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), ArchiveMath.lerpColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), ArchiveMath.lerpColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f + 0.5f / 255f))
        assertEquals(0xFFEFEBE5.toInt(), light.background)                // warm off-white reference
        assertTrue(ArchiveMath.FieldColors.DARK.background and 0xFF < 0x30) // graphite
        assertSame(light, ArchiveMath.FieldColors.tinted(light, 0xFF0000FF.toInt(), 0f))
        val tinted = ArchiveMath.FieldColors.tinted(light, 0xFF0000FF.toInt(), 0.1f)
        assertTrue((tinted.shade and 0xFF) > (light.shade and 0xFF))     // bluer
    }

    // ---------------------------------------------------------------- columns and labels

    @Test fun columnsGroupAndSkipEmpty() {
        val es = listOf(
            entry("models", dir = true, system = SystemFolder.MODELS), entry("a", dir = true),
            entry("notes.md"), entry("paper.pdf"), entry("q.gguf"), entry("x.bin"), entry("pic.png"), entry("blob"), entry("pack.zip"),
        )
        assertEquals(
            listOf(ArchiveMath.Column.FOLDERS, ArchiveMath.Column.DOCS, ArchiveMath.Column.MEDIA, ArchiveMath.Column.MODELS, ArchiveMath.Column.OTHER),
            ArchiveMath.columns(es),
        )
        assertEquals(listOf("models", "a"), ArchiveMath.inColumn(es, ArchiveMath.Column.FOLDERS).map { it.name })
        assertEquals(listOf("notes.md", "paper.pdf"), ArchiveMath.inColumn(es, ArchiveMath.Column.DOCS).map { it.name })
        assertEquals(listOf("blob", "pack.zip"), ArchiveMath.inColumn(es, ArchiveMath.Column.OTHER).map { it.name })
        assertEquals(emptyList<FileEntry>(), ArchiveMath.inColumn(es, null))
        val onlyDocs = listOf(entry("a.txt"), entry("b.md"))
        assertEquals(listOf(ArchiveMath.Column.DOCS), ArchiveMath.columns(onlyDocs))
        assertEquals(emptyList<ArchiveMath.Column>(), ArchiveMath.columns(emptyList()))
        // keep the chosen column while it exists, else fall back to the first
        val cols = ArchiveMath.columns(es)
        assertEquals(ArchiveMath.Column.MEDIA, ArchiveMath.keepColumn(cols, ArchiveMath.Column.MEDIA))
        assertEquals(ArchiveMath.Column.DOCS, ArchiveMath.keepColumn(listOf(ArchiveMath.Column.DOCS), ArchiveMath.Column.MEDIA))
        assertNull(ArchiveMath.keepColumn(emptyList(), ArchiveMath.Column.MEDIA))
    }

    @Test fun serialAndCounterFormatting() {
        assertEquals("F-001", ArchiveMath.fileNumber(ArchiveMath.Column.FOLDERS, 0))
        assertEquals("X-042", ArchiveMath.fileNumber(ArchiveMath.Column.OTHER, 41))
        assertEquals("D-1000", ArchiveMath.fileNumber(ArchiveMath.Column.DOCS, 999))
        assertEquals("01", ArchiveMath.counter(1))
        assertEquals("06", ArchiveMath.counter(6))
        assertEquals("128", ArchiveMath.counter(128))
        assertEquals("COLUMN 01 / 03", ArchiveMath.columnLabel(0, 3))
        assertEquals("DOWNLOAD / IRIDIS / 模型", ArchiveMath.pathLine(listOf("IRIDIS", "模型")))
        assertEquals(6, ArchiveMath.rulerTicks(6))
        assertEquals(12, ArchiveMath.rulerTicks(40))
        assertEquals(3, ArchiveMath.rulerSelected(3, 6))
        assertEquals(11, ArchiveMath.rulerSelected(39, 40))
        assertEquals(0, ArchiveMath.rulerSelected(0, 40))
    }

    // ---------------------------------------------------------------- detail reveal

    @Test fun decryptLineGrowsThenCollapses() {
        val out = FloatArray(2)
        assertFalse(ArchiveMath.decryptLine(0f, out))
        assertTrue(ArchiveMath.decryptLine(0.2f, out))
        assertEquals(0f, out[0], eps)
        assertTrue(out[1] in 0.4f..0.6f)
        assertTrue(ArchiveMath.decryptLine(0.5f, out))
        assertEquals(1f, out[1], eps)
        assertTrue(out[0] > 0f && out[0] < 1f)
        assertFalse(ArchiveMath.decryptLine(0.9f, out))
    }

    @Test fun barsRetractOneAfterAnother() {
        assertEquals(0f, ArchiveMath.redactProgress(0f, 0), eps)
        for (i in 0..7) assertEquals("bar $i done by the end", 1f, ArchiveMath.redactProgress(1f, i), eps)
        assertTrue(ArchiveMath.redactProgress(0.6f, 0) > ArchiveMath.redactProgress(0.6f, 3))
        assertEquals(0f, ArchiveMath.frostFront(0.1f), eps)
        assertEquals(1f, ArchiveMath.frostFront(0.8f), eps)
    }

    @Test fun thumbnailsOnlyForImagesAndDownscaled() {
        assertTrue(ArchiveMath.isImage("PNG"))
        assertTrue(ArchiveMath.isImage("webp"))
        assertFalse(ArchiveMath.isImage("gif"))
        assertEquals(1, ArchiveMath.sampleSize(300, 200, 360))
        assertEquals(8, ArchiveMath.sampleSize(4000, 3000, 360))
        assertTrue(4000 / ArchiveMath.sampleSize(4000, 3000, 360) >= 360)
    }

    // ---------------------------------------------------------------- 「卡片」 fixes

    @Test fun cardDeckCentresItsStack() {
        assertTrue(ArchiveMath.deckBias(0f, 3) < 0f)           // only cards below → lift the stack
        assertTrue(ArchiveMath.deckBias(2f, 3) > 0f)           // only cards above → lower it
        assertEquals(0f, ArchiveMath.deckBias(2f, 5), eps)     // symmetric
        assertEquals(0f, ArchiveMath.deckBias(0f, 9), eps)     // looping deck is symmetric
        assertEquals(0f, ArchiveMath.deckBias(0f, 1), eps)
        assertEquals(1f, ArchiveMath.contentAlpha(0f), eps)
        assertEquals(0f, ArchiveMath.contentAlpha(0.5f), eps)
        assertEquals(0f, ArchiveMath.contentAlpha(-2f), eps)
    }

    @Test fun motionTokens() {
        assertTrue(IrisMotion.ARCHIVE_INTRO_MS in 500..900)
        assertTrue(IrisMotion.ARCHIVE_REVEAL_MS > IrisMotion.ARCHIVE_DECRYPT_MS)
        assertTrue(IrisMotion.ARCHIVE_SETTLE_MS > 0)
    }

    /**
     * Optional sanity dump (IRIS_ARCHIVE_PREVIEW=dir): the built vertex / colour buffers as little-endian
     * binaries (count, then x y pairs, then ARGB ints) for an offline flat-shaded render. Eyeballing only.
     */
    @Test fun previewDump() {
        val dir = System.getenv("IRIS_ARCHIVE_PREVIEW") ?: return
        File(dir).mkdirs()
        for ((name, pal, pos) in listOf(Triple("light", light, 0f), Triple("light-mid", light, 0.5f), Triple("dark", ArchiveMath.FieldColors.DARK, 0f))) {
            val f = ArchiveMath.SlabField().apply { layout(1080f, 2200f, 2.75f) }
            val n = f.build(pos, true, 1f, 0f, 1f, 0f, pal)
            val buf = java.nio.ByteBuffer.allocate(4 + n * 12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.putInt(n)
            for (i in 0 until n * 2) buf.putFloat(f.verts[i])
            for (i in 0 until n) buf.putInt(f.colors[i])
            File(dir, "field-$name.bin").writeBytes(buf.array())
        }
    }
}
