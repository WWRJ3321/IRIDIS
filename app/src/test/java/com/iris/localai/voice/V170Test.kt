package com.iris.localai.voice

import com.iris.localai.service.GenerationText
import com.iris.localai.ui.chat.FollowScroll
import com.iris.localai.ui.chat.OrbMotion
import com.iris.localai.util.streaming.ParagraphSplitter
import com.iris.localai.util.diagnostics.PerfSampler
import com.iris.localai.util.diagnostics.RateWindow
import com.iris.localai.util.streaming.ThinkParseCache
import com.iris.localai.util.thinking.ThinkParser
import com.iris.localai.util.streaming.TypewriterPacer
import com.iris.localai.voice.CloneAudio
import com.iris.localai.voice.TarBz2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class TypewriterPacerTest {
    private fun run(p: TypewriterPacer, target: Int, seconds: Float, done: Boolean = false): Int {
        var shown = 0
        repeat((seconds * 60).toInt()) { shown = p.step(target, 1f / 60f, done) }
        return shown
    }

    @Test fun revealsGraduallyNotAtOnce() {
        val p = TypewriterPacer()
        val first = p.step(40, 1f / 60f, done = false)
        assertTrue("first frame shows only a few chars, got $first", first in 0..3)
        assertTrue(run(p, 40, 2f) == 40) // and catches up within a couple of seconds
    }

    @Test fun keepsAnEvenPaceWithinAFrame() {
        val p = TypewriterPacer()
        var prev = 0
        var maxJump = 0
        // tokens of 4 chars arriving every 50 ms (≈80 chars/s)
        var target = 0
        for (frame in 0 until 600) {
            if (frame % 3 == 0) target += 4
            val s = p.step(target, 1f / 60f, done = false)
            maxJump = maxOf(maxJump, s - prev); prev = s
        }
        assertTrue("never more than a few chars per frame at 80 chars/s, got $maxJump", maxJump <= 4)
        assertTrue("trails the stream by well under a second", target - prev < 80)
    }

    @Test fun speedsUpWhenTheBacklogGrows() {
        val slow = TypewriterPacer().also { run(it, 30, 0.5f) }.rate
        val fast = TypewriterPacer().also { run(it, 500, 0.5f) }.rate
        assertTrue("rate should grow with backlog: $slow vs $fast", fast > slow * 4)
    }

    @Test fun hugeBacklogIsCutDown() {
        val p = TypewriterPacer(maxBacklog = 600)
        val s = p.step(5_000, 1f / 60f, done = false)
        assertTrue(s >= 5_000 - 600)
    }

    @Test fun doneShowsEverythingImmediately() {
        val p = TypewriterPacer()
        p.step(1_000, 1f / 60f, done = false)
        assertEquals(1_000, p.step(1_000, 1f / 60f, done = true))
    }

    @Test fun droppedFrameDoesNotDumpText() {
        val p = TypewriterPacer()
        run(p, 100, 0.2f)
        val before = p.shown
        val after = p.step(100, 5f, done = false) // 5 s stall
        assertTrue(after - before < 60)
    }

    @Test fun shrinkingTargetNeverShowsPastEnd() {
        val p = TypewriterPacer()
        run(p, 200, 3f)
        assertTrue(p.step(50, 1f / 60f, done = false) <= 50)
    }
}

class ParagraphAndParseTest {
    @Test fun finishedParagraphsKeepTheirInstances() {
        val sp = ParagraphSplitter()
        val a = sp.split("第一段\n\n第二")
        val b = sp.split("第一段\n\n第二段继续\n\n第三")
        assertEquals(listOf("第一段", "第二"), a)
        assertEquals(listOf("第一段", "第二段继续", "第三"), b)
        assertSame(a[0], b[0])
        assertEquals(listOf("新"), sp.split("新")) // unrelated text starts over
    }

    @Test fun incrementalParseMatchesFullParse() {
        val samples = listOf(
            "<think>先想一想\n\n再想</think>答案是 42",
            "推理过程</think>结论",
            "<think>还没想完",
            "普通回答，提到 <think> 标签也无妨",
            "<think>a</think>b<think>c",
        )
        for (full in samples) {
            val cache = ThinkParseCache()
            // feed in uneven pieces, splitting tags across pieces
            var i = 0
            var step = 1
            while (i < full.length) {
                i = minOf(full.length, i + step); step = step % 5 + 1
                val prefix = full.substring(0, i)
                assertEquals("prefix '$prefix'", ThinkParser.parse(prefix), cache.parse(prefix))
            }
        }
    }
}

class PerfAndMiscTest {
    @Test fun cpuPercentIsShareOfAllCores() {
        assertEquals(50f, PerfSampler.cpuPercent(4_000, 1_000, 8)!!, 0.01f)
        assertNull(PerfSampler.cpuPercent(10, 0, 8))
        assertEquals(100f, PerfSampler.cpuPercent(99_000, 1_000, 8)!!, 0.01f)
    }

    @Test fun parsesProcAndKgsl() {
        assertEquals(123_456L * 1024, PerfSampler.parseVmRss("Name:\tx\nVmRSS:\t  123456 kB\nVmSwap: 0 kB"))
        assertNull(PerfSampler.parseVmRss("Name: x"))
        assertEquals(37, PerfSampler.parseGpuPercent("37 %"))
        assertNull(PerfSampler.parseGpuPercent("permission denied"))
        assertEquals(25, PerfSampler.parseGpuBusy("250 1000"))
        assertNull(PerfSampler.parseGpuBusy("0 0"))
    }

    @Test fun samplerHidesUnreadableGpu() {
        val s = PerfSampler(cores = 8, cpuTimeMs = { 0L }, readText = { p -> if (p.contains("kgsl")) null else "VmRSS: 10 kB" })
        val r = s.sample(1_000)
        assertNull(r.gpuPercent)
        assertEquals(10_240L, r.rssBytes)
    }

    @Test fun rateWindow() {
        val w = RateWindow(2_000)
        assertNull(w.perSecond())
        w.add(0, 0); w.add(1_000, 20); w.add(2_000, 40)
        assertEquals(20f, w.perSecond()!!, 0.01f)
    }

    @Test fun notificationText() {
        assertEquals("「讲个笑话」 · 已生成 120 字", GenerationText.progress("讲个笑话", 120))
        assertEquals("准备中", GenerationText.progress("", 0))
        assertTrue(GenerationText.done(null, 5).contains("5 字"))
    }

    @Test fun followScrollStep() {
        assertEquals(100f, FollowScroll.step(100f, reduceMotion = true), 0f)
        assertEquals(22f, FollowScroll.step(100f, reduceMotion = false), 0.01f)
        assertEquals(1f, FollowScroll.step(2f, reduceMotion = false), 0f)
        assertEquals(0.5f, FollowScroll.step(0.5f, reduceMotion = false), 0f)
    }

    @Test fun orbFloatsClearlyAndStopsWhenNotWaiting() {
        var maxDy = 0f; var maxScale = 0f
        for (i in 0..100) {
            val f = OrbMotion.at(i / 100f, 1f)
            maxDy = maxOf(maxDy, abs(f.dy)); maxScale = maxOf(maxScale, f.scale)
        }
        assertTrue("float amplitude visible", maxDy >= 0.04f)
        assertTrue("breathing far above the old 3.5 %", maxScale >= 0.07f)
        assertEquals(OrbMotion.Frame(0f, 0f), OrbMotion.at(0.3f, 0f))
        // rises (dy < 0) while it inhales (scale above its midpoint)
        val f = OrbMotion.at(0.25f, 1f)
        assertTrue(f.dy < 0 && f.scale > OrbMotion.BREATH_SCALE / 2)
    }
}

class CloneAudioTest {
    private fun tone(sec: Float, rate: Int, amp: Float) = FloatArray((sec * rate).toInt()) { amp * sin(it * 0.05f) }

    @Test fun resampleLength() {
        assertEquals(16_000, CloneAudio.resample(FloatArray(24_000), 24_000, 16_000).size)
        val same = FloatArray(10)
        assertSame(same, CloneAudio.resample(same, 16_000, 16_000))
    }

    @Test fun prepareTrimsSilenceCapsAndNormalises() {
        val rate = 24_000
        val clip = FloatArray(rate) + tone(20f, rate, 0.05f) + FloatArray(rate)
        val out = CloneAudio.prepare(clip, rate)
        assertTrue("capped at 15 s", out.size <= 15 * rate)
        assertTrue("leading silence mostly gone", out.size > 14 * rate)
        val peak = out.maxOf { abs(it) }
        assertEquals(0.89f, peak, 0.02f)
    }

    @Test fun shortOrSilentClipIsRejected() {
        val rate = 24_000
        assertNotNull(CloneAudio.problem(CloneAudio.prepare(FloatArray(rate * 5), rate), rate))
        assertNotNull(CloneAudio.problem(CloneAudio.prepare(tone(1.5f, rate, 0.3f), rate), rate))
        assertNull(CloneAudio.problem(CloneAudio.prepare(tone(6f, rate, 0.3f), rate), rate))
    }

    @Test fun stepsForFastAndLike() {
        assertEquals(4, CloneAudio.steps(0, 4, 12))
        assertEquals(12, CloneAudio.steps(1, 4, 12))
        assertEquals(2, CloneAudio.steps(0, 0, 12))
    }

    @Test fun tarPathStripping() {
        assertEquals("a/b.onnx", TarBz2.relativePath("dir/a/b.onnx", "dir/"))
        assertEquals("x", TarBz2.relativePath("./x", ""))
        assertNull(TarBz2.relativePath("other/x", "dir/"))
    }
}

class TarBz2ExtractTest {
    @Test fun extractsStripsSkipsAndRejectsTraversal() {
        val tmp = kotlin.io.path.createTempDirectory("tbz").toFile()
        val archive = java.io.File(tmp, "a.tar.bz2")
        fun write(entries: List<Pair<String, String>>) {
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(archive.outputStream()),
            ).use { tar ->
                for ((name, body) in entries) {
                    val bytes = body.toByteArray()
                    val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                    tar.putArchiveEntry(e); tar.write(bytes); tar.closeArchiveEntry()
                }
            }
        }
        write(listOf("pack/tokens.txt" to "abc", "pack/espeak-ng-data/phontab" to "x", "pack/test_wavs/a.wav" to "w"))
        val out = java.io.File(tmp, "out")
        kotlinx.coroutines.runBlocking { TarBz2.extract(archive, out, "pack/", listOf("test_wavs/")) }
        assertEquals("abc", java.io.File(out, "tokens.txt").readText())
        assertTrue(java.io.File(out, "espeak-ng-data/phontab").exists())
        assertTrue(!java.io.File(out, "test_wavs").exists())
        write(listOf("pack/../../evil" to "x"))
        val r = runCatching { kotlinx.coroutines.runBlocking { TarBz2.extract(archive, out, "pack/", emptyList()) } }
        assertTrue(r.isFailure)
        tmp.deleteRecursively()
    }
}

class BargeInTest {
    private val rnd = java.util.Random(7)
    /** Echo of our own reply: mic ≈ coupling × out with ±40 % jitter, and the VAD thinks it is speech. */
    private fun echoFrame(d: com.iris.localai.voice.BargeInDetector, out: Float, coupling: Float) =
        d.onFrame(coupling * out * (0.6f + 0.8f * rnd.nextFloat()), out, speechProb = 0.9f)

    @Test fun echoAloneNeverInterrupts() {
        for (sens in 0..2) {
            val d = com.iris.localai.voice.BargeInDetector(sens)
            var fired = 0
            repeat(30 * 40) { // 40 s of loud echo with a strong coupling (media stream, poor AEC)
                if (echoFrame(d, out = 0.05f + 0.1f * rnd.nextFloat(), coupling = 1.2f)) fired++
            }
            assertEquals("sensitivity $sens fired on echo", 0, fired)
        }
    }

    @Test fun normalSpeechInterruptsWithinAbout300ms() {
        val d = com.iris.localai.voice.BargeInDetector(com.iris.localai.voice.BargeInDetector.MEDIUM)
        repeat(90) { echoFrame(d, 0.08f, 0.5f) } // ~3 s of reply: learn the echo
        // user speaks at a normal level (mic RMS 0.10) over echo of 0.04
        var frames = 0
        while (frames < 40) {
            frames++
            if (d.onFrame(mic = 0.10f + 0.04f, out = 0.08f, speechProb = 0.85f)) break
        }
        assertTrue("took $frames frames", frames in 6..10) // 6–10 × 32 ms ≈ 190–320 ms
    }

    @Test fun oldRuleWouldHaveMissedIt() {
        // 1.6.0: mic ≥ max(0.02, 0.6 × outPeak) — with an output peak of 0.3 normal speech (0.14) fails.
        val outPeak = 0.3f
        assertTrue(0.14f < maxOf(0.02f, outPeak * 0.6f))
        val d = com.iris.localai.voice.BargeInDetector()
        repeat(90) { echoFrame(d, outPeak, 0.15f) }
        var fired = false
        repeat(12) { if (d.onFrame(0.14f, outPeak, 0.9f)) fired = true }
        assertTrue(fired)
    }

    @Test fun sensitivityOrdersThresholds() {
        val lo = com.iris.localai.voice.BargeInDetector.paramsFor(0)
        val mid = com.iris.localai.voice.BargeInDetector.paramsFor(1)
        val hi = com.iris.localai.voice.BargeInDetector.paramsFor(2)
        assertTrue(lo.margin > mid.margin && mid.margin > hi.margin)
        assertTrue(lo.frames > mid.frames && mid.frames > hi.frames)
        assertTrue(lo.frames * 32 in 250..330 && mid.frames * 32 in 240..300)
    }

    @Test fun quietFramesDoNotResetImmediately() {
        val d = com.iris.localai.voice.BargeInDetector()
        var fired = false
        for (i in 0 until 20) {
            val quiet = i % 4 == 3 // one dip every 4 frames
            if (d.onFrame(if (quiet) 0.005f else 0.2f, 0.0f, if (quiet) 0.2f else 0.9f)) { fired = true; break }
        }
        assertTrue(fired)
    }

    @Test fun silenceOrNonSpeechNeverFires() {
        val d = com.iris.localai.voice.BargeInDetector(2)
        repeat(200) { assertTrue(!d.onFrame(0.3f, 0.01f, speechProb = 0.1f)) } // loud but not speech (a door)
        repeat(200) { assertTrue(!d.onFrame(0.002f, 0.0f, speechProb = 0.9f)) } // below the floor
    }

    @Test fun frameRingKeepsNewest() {
        val r = com.iris.localai.voice.FrameRing(2)
        r.add(floatArrayOf(1f)); r.add(floatArrayOf(2f)); r.add(floatArrayOf(3f))
        assertTrue(r.snapshot().contentEquals(floatArrayOf(2f, 3f)))
    }
}
