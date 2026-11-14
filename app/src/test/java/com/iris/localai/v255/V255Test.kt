package com.iris.localai.v255

import com.iris.localai.data.ModelAliases
import com.iris.localai.data.UpdateNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V255Test {
    private fun src(rel: String) = listOf(rel, "../$rel").map { File(it) }.first { it.exists() }.readText()

    @Test fun notesShowOnceAfterUpgrade() {
        assertTrue(UpdateNotes.shouldShow(true, true, "2.5.4", "2.5.5"))
        assertTrue(UpdateNotes.shouldShow(true, true, "", "2.5.5"))
        assertFalse(UpdateNotes.shouldShow(true, true, "2.5.5", "2.5.5-full"))
        assertFalse(UpdateNotes.shouldShow(false, true, "", "2.5.5"))
        assertFalse(UpdateNotes.shouldShow(true, false, "", "2.5.5"))
        assertFalse(UpdateNotes.shouldShow(true, true, "2.5.4", "9.9.9"))
    }

    @Test fun notesCurrentVersionListed() {
        val gradle = src("app/build.gradle.kts")
        val v = Regex("versionName = \"([\\d.]+)\"").find(gradle)!!.groupValues[1]
        val r = UpdateNotes.find(v)!!
        assertEquals(v, UpdateNotes.RELEASES.first().version)
        assertTrue(r.items.isNotEmpty())
        assertEquals("2.5.5", UpdateNotes.base("2.5.5-full"))
    }

    @Test fun aliasNormalize() {
        assertNull(ModelAliases.normalize("   "))
        assertNull(ModelAliases.normalize(null))
        assertEquals("a b", ModelAliases.normalize(" a\n b "))
        assertEquals(ModelAliases.MAX_LEN, ModelAliases.normalize("x".repeat(99))!!.length)
    }

    @Test fun localModelAlias() {
        val m = com.iris.localai.data.LocalModel("Foo-Q4_K_M.gguf", "/x", 1, com.iris.localai.data.ModelFormat.GGUF)
        assertEquals(m.fileDisplayName, m.displayName)
        assertEquals("我的模型", m.copy(alias = "我的模型").displayName)
    }

    @Test fun nanbeigeRecommended() {
        val it = com.iris.localai.data.RecommendedCatalog.ITEMS.first { it.id == com.iris.localai.data.RecommendedCatalog.NANBEIGE_REPO }
        assertEquals(2_684_023_968L, it.sizeBytes)
    }

    @Test fun moeOutOfAdvancedFold() {
        val s = src("app/src/main/java/com/iris/localai/ui/models/ModelConfigSheet.kt")
        val moe = s.indexOf("R.string.v255_moe_section")
        val fold = s.indexOf("SectionFold(\"model-adv-\"")
        assertTrue(moe in 1 until fold)
        assertTrue(s.indexOf("R.string.v251_moe_cpu") in moe until fold)
    }

    @Test fun dimensity9500TabletAlias() {
        val n = com.iris.localai.engine.NpuIds
        assertEquals("mt6993", n.baseSocId("MT8910"))
        assertEquals("mt6993", n.mediatekNpu("Mediatek", "MT8910"))
        assertEquals("mt6993", n.baseSocId("MT6993"))
        assertTrue(n.socMatches("mt6993", "MT8910"))
        assertEquals(com.iris.localai.data.DeviceRecommend.Chip.FLAGSHIP, com.iris.localai.data.DeviceRecommend.chip("Mediatek", "MT8910"))
        assertNull(n.mediatekNpu("Mediatek", "MT8183"))
    }
}
