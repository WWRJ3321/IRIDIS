package com.iris.localai.imagegen

import com.iris.localai.imagegen.Backend
import com.iris.localai.ui.markdown.ThreeAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** 2.4.1 精简: generated three.min.js, removed dead code, split chat files. */
class V241SlimTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun classicThreeIsGeneratedFromTheModule() {
        val module = File(main, "assets/three/build/three.module.js")
        assertTrue(module.isFile)
        assertFalse(File(main, "assets/three/build/three.min.js").exists())
        val classic = ThreeAssets.classicFromModule(module.readText())!!
        // byte-identical to the three.min.js shipped up to 2.4.0
        assertEquals("9ef3e4aa74aaeaf0ebd5e034df8ce4d70c4426b35ace1992adde06a38f4f3cd1", sha(classic.toByteArray()))
        assertFalse(classic.contains("export{"))
        assertTrue(classic.contains(".THREE=Object.assign(") && classic.trimEnd().endsWith("})();"))
        // both ways of asking still resolve, and the classic one is served from the module
        assertEquals("three/build/three.min.js", ThreeAssets.assetFor("https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"))
        assertEquals("three/build/three.module.js", ThreeAssets.assetFor("https://unpkg.com/three@0.160.0/build/three.module.js"))
        val read = { p: String -> File(main, "assets/$p").inputStream() }
        assertEquals(sha(classic.toByteArray()), sha(ThreeAssets.open("three/build/three.min.js", read).readBytes()))
        assertEquals(sha(module.readBytes()), sha(ThreeAssets.open("three/build/three.module.js", read).readBytes()))
        assertEquals(setOf(ThreeAssets.CLASSIC), ThreeAssets.GENERATED)
        assertEquals(null, ThreeAssets.classicFromModule("const a=1;"))
    }


}
