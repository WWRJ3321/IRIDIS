package com.iris.localai.v241

import com.iris.localai.engine.geniex.GenieX
import com.iris.localai.engine.geniex.GenieXLibs
import com.iris.localai.engine.geniex.QairtCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.4.1 G: GenieX 0.10.0 (QAIRT 2.50) + a clear reason before an AI Hub bundle that cannot load. */
class V241GenieXTest {
    private fun file(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }

    @Test fun runtimeIsPinnedToTheNewAar() {
        assertEquals("0.10.0", GenieX.VERSION)
        assertEquals("2.50", GenieX.QAIRT_VERSION)
        assertEquals(89_045_966L, GenieXLibs.AAR_BYTES)
        assertEquals("3a40def4890fe4991d3547acf8bcfa9f1be491ad7790a1cb5d26bbecb2dde69b", GenieXLibs.AAR_SHA256)
        assertTrue(GenieXLibs.AAR_URLS.all { "/geniex-android/0.10.0/geniex-android-0.10.0.aar" in it })
        assertEquals(27, GenieXLibs.PINS.size)
        assertTrue(GenieXLibs.PINS.values.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size > 10_000 })
        assertEquals(3979248L, GenieXLibs.PINS.getValue("libQnnHtp.so").size)
        for (a in listOf(73, 75, 79, 81)) GenieXLibs.forArch(a) // every arch resolves all its pins
        val mb = GenieXLibs.installedBytes(81) / (1 shl 20)
        assertTrue("installed $mb MB", mb in 40..60)
        assertTrue(file("../gradle/libs.versions.toml").readText().contains("geniex = \"0.10.0\""))
    }

    @Test fun olderRuntimeAsksForADownload() {
        assertEquals("0.8.0", GenieXLibs.oldRuntime(listOf("rt-0.8.0-v79", "rt-0.10.0-v79"), "rt-0.10.0-v79"))
        assertNull(GenieXLibs.oldRuntime(listOf("rt-0.10.0-v79"), "rt-0.10.0-v79"))
        assertNull(GenieXLibs.oldRuntime(listOf("rt-0.10.0-v79.tmp", "other"), "rt-0.10.0-v79"))
        val r = GenieXLibs.oldRuntimeReason("0.8.0")
        assertTrue(r, r.contains("0.8.0") && r.contains("0.10.0") && r.contains("重新下载"))
        // marker of the old version → not "installed" for the new one
        val libs = GenieXLibs.forArch(79)
        val old = GenieXLibs.markerText(79, libs).replace("geniex=0.10.0", "geniex=0.8.0")
        assertTrue(GenieXLibs.check(79, old) { p -> libs.first { it.path == p }.pin.size } is GenieXLibs.Check.Broken)
    }

    @Test fun bundleCompatibility() {
        val htp = """{"devices":[{"soc_model":69,"dsp_arch":"v79","cores":[{"core_id":0,"perf_profile":"burst"}]}],"memory":{"mem_type":"shared_buffer"}}"""
        val meta = """{"model_id":"hy_mt2","tool_versions":{"qairt":"2.50.0.250930104512_000000"},"chipset":"qualcomm-snapdragon-8-elite"}"""
        assertEquals(79, QairtCompat.dspArch(htp))
        assertEquals("2.50", QairtCompat.qairtVersion(meta))
        assertEquals("2.45", QairtCompat.qairtVersion("""{"qnn_sdk_version": "v2.45.0.260326"}"""))
        val info = QairtCompat.info(mapOf(QairtCompat.HTP_EXT_CONFIG to htp, "metadata.json" to meta))
        assertEquals(QairtCompat.Info(79, "2.50"), info)
        assertNull(QairtCompat.problem(info, 79))
        val arch = QairtCompat.problem(info, 81)
        assertNotNull(arch); assertTrue(arch!!, arch.contains("V79") && arch.contains("V81") && arch.contains("骁龙 8 Elite"))
        val newer = QairtCompat.problem(QairtCompat.Info(81, "2.55"), 81)
        assertTrue(newer!!, newer.contains("2.55") && newer.contains("2.50"))
        assertNull(QairtCompat.problem(QairtCompat.Info(null, null), 81))     // nothing to judge → load as before
        assertNull(QairtCompat.problem(QairtCompat.Info(79, null), null))     // unknown phone chip
        assertTrue(QairtCompat.compare("2.45", "2.50") < 0 && QairtCompat.compare("2.50", "2.50") == 0 && QairtCompat.compare("3.0", "2.50") > 0)
    }

    @Test fun loadFailureHint() {
        val q = GenieX.codeHint("Llm create failed: Model loading failed", qairt = true)!!
        assertTrue(q, q.contains("QAIRT") && q.contains("2.50") && q.contains("芯片"))
        val g = GenieX.codeHint("Llm create failed: Model loading failed")!!
        assertTrue(g, !g.contains("2.50"))
        assertNull(GenieX.codeHint("something else", qairt = true))
    }
}
