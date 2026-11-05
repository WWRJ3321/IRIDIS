package com.iris.localai.v241

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

    @Test fun deadCodeIsGone() {
        assertEquals(listOf(Backend.QNN, Backend.MNN_CPU, Backend.SDCPP), Backend.entries.toList())
        val src = File(main, "java/com/iris/localai")
        assertFalse(File(src, "ui/hints/RecentHints.kt").exists())
        val all = src.walk().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (gone in listOf("needsHexagonRuntime", "HEXAGON_PENDING", "class SlabField", "fun ModelListItem(", "fun pickerMeta(")) assertFalse(gone, gone in all)
        // still used, so kept
        assertTrue(File(src, "util/diagnostics/PromptLog.kt").isFile && File(src, "voice/AgentVoiceStream.kt").isFile)
        val strings = File(main, "res/values/strings.xml").readText()
        for (id in listOf("imagegen_1b8a07", "hints_f3439f", "lang_title", "files_331dd7")) assertFalse(id, "name=\"$id\"" in strings)
        // every language file keeps the same keys
        val keys = { f: File -> Regex("<string name=\"([^\"]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toSet() }
        val base = keys(File(main, "res/values/strings.xml"))
        for (d in listOf("values-en", "values-zh-rTW", "values-ja")) {
            val k = keys(File(main, "res/$d/strings.xml"))
            assertTrue(d + ": " + (k - base).take(5), (k - base).isEmpty())
        }
    }

    @Test fun chatFilesSplitWithoutChangingTheMorphPanel() {
        val chat = File(main, "java/com/iris/localai/ui/chat")
        assertTrue(File(chat, "ChatViewModel.kt").readLines().size < 1500)
        assertTrue(File(chat, "ChatScreen.kt").readLines().size < 900)
        for (f in listOf("ChatViewModelReply.kt", "ChatViewModelAgent.kt", "ChatViewModelMemory.kt", "ChatTopBar.kt", "ChatBanners.kt", "ChatScreenParts.kt"))
            assertTrue(f, File(chat, f).isFile)
        val all = chat.listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertEquals(1, Regex("fun MorphPanel\\(").findAll(all).count())
        assertEquals(1, Regex("fun ModelMorphPanel\\(").findAll(all).count())
        assertEquals(1, Regex("fun ChatViewModel\\.runReply\\(").findAll(all).count())
    }
}
