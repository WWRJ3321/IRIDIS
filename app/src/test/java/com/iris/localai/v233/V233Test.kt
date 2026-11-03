package com.iris.localai.v233

import com.iris.localai.agent.CustomWorkspace
import com.iris.localai.agent.WorkspaceLocation
import com.iris.localai.agent.WorkspaceResolver
import com.iris.localai.character.CharacterPick
import com.iris.localai.knowledge.KbTrash
import com.iris.localai.knowledge.KnowledgeBase
import com.iris.localai.voice.VoiceBinding
import com.iris.localai.voice.VoiceOverride
import com.iris.localai.voice.VoiceSel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V233Test {
    private fun tmp(): File = Files.createTempDirectory("v233").toFile()

    // ---- 角色 pill ----
    @Test fun pickDecisions() {
        assertEquals(CharacterPick.Action.NONE, CharacterPick.decide("a", "a", true))
        assertEquals(CharacterPick.Action.NONE, CharacterPick.decide(null, null, false))
        assertEquals(CharacterPick.Action.NEW_PLAIN, CharacterPick.decide("a", null, false))
        assertEquals(CharacterPick.Action.APPLY_IN_PLACE, CharacterPick.decide(null, "a", false))
        assertEquals(CharacterPick.Action.APPLY_IN_PLACE, CharacterPick.decide("b", "a", false)) // greeting only
        assertEquals(CharacterPick.Action.NEW_WITH_CARD, CharacterPick.decide(null, "a", true))
        assertTrue(CharacterPick.compact(228, true)); assertFalse(CharacterPick.compact(228, false)); assertFalse(CharacterPick.compact(280, true))
    }

    // ---- custom workspace path validation ----
    @Test fun customPathValidation() {
        val vol = tmp(); val sd = tmp()
        val iridis = File(vol, "Download/IRIDIS").apply { mkdirs() }
        File(iridis, "workspace/sub").mkdirs(); File(iridis, "models").mkdirs()
        val ok = File(vol, "Documents/agent").apply { mkdirs() }
        val vols = listOf(vol, sd)
        fun v(p: String?, all: Boolean = true) = CustomWorkspace.validate(p, all, vols, iridis)
        assertEquals(CustomWorkspace.Problem.BLANK, v(""))
        assertEquals(CustomWorkspace.Problem.NO_ACCESS, v(ok.path, all = false))
        assertEquals(CustomWorkspace.Problem.NOT_ABSOLUTE, v("Documents/agent"))
        assertEquals(CustomWorkspace.Problem.OUTSIDE_STORAGE, v("/proc/self"))
        assertEquals(CustomWorkspace.Problem.VOLUME_ROOT, v(vol.path))
        assertEquals(CustomWorkspace.Problem.APP_DATA, v(File(vol, "Android/data/x").path))
        assertEquals(CustomWorkspace.Problem.RESERVED, v(File(iridis, "models").path))
        assertEquals(CustomWorkspace.Problem.RESERVED, v(iridis.path))
        assertEquals(CustomWorkspace.Problem.OUTSIDE_STORAGE, v(ok.path + "/../../../x"))
        assertNull(v(ok.path))
        assertNull(v(File(iridis, "workspace/sub").path))
        assertNull(v(File(sd, "new/folder").path)) // created by the probe
        assertEquals(CustomWorkspace.Problem.NOT_WRITABLE, CustomWorkspace.validate(File(vol, "ro").path, true, vols, iridis) { false })
    }

    @Test fun customResolveFallsBack() {
        val vol = tmp(); val dl = File(vol, "Download").apply { mkdirs() }; val priv = tmp()
        val ok = File(vol, "Work").apply { mkdirs() }
        val good = WorkspaceResolver.resolve(WorkspaceLocation.CUSTOM, dl, WorkspaceResolver.MOUNTED, priv, customPath = ok.path, allFiles = true, volumes = listOf(vol))
        assertEquals(WorkspaceLocation.CUSTOM, good.location); assertEquals(ok.canonicalPath, good.root.path)
        assertEquals(File(dl, "IRIDIS").path, good.appRoot.path) // models / 知识库 stay in Download/IRIDIS
        assertNull(good.fallbackReason)
        ok.deleteRecursively(); File(vol, "Work").writeText("not a dir")
        val bad = WorkspaceResolver.resolve(WorkspaceLocation.CUSTOM, dl, WorkspaceResolver.MOUNTED, priv, customPath = ok.path, allFiles = true, volumes = listOf(vol))
        assertEquals(WorkspaceLocation.DOWNLOADS, bad.location); assertEquals(WorkspaceLocation.CUSTOM, bad.requested)
        assertEquals(CustomWorkspace.Problem.NOT_WRITABLE, bad.customProblem); assertTrue(bad.fallbackReason!!.isNotBlank())
        val noAccess = WorkspaceResolver.resolve(WorkspaceLocation.CUSTOM, dl, WorkspaceResolver.MOUNTED, priv, customPath = File(vol, "x").path, allFiles = false, volumes = listOf(vol))
        assertEquals(CustomWorkspace.Problem.NO_ACCESS, noAccess.customProblem)
    }

    // ---- card voice is a per-chat override ----
    @Test fun voiceOverrideRestoresGlobal() {
        val global = VoiceSel("kokoro", 3, "")
        val o = VoiceBinding.forCard("melo", -1, "", packInstalled = true, cloneExists = false)!!
        assertEquals(VoiceSel("melo", 3, ""), VoiceBinding.effective(global, o)) // keeps global speaker
        assertEquals(VoiceSel("kokoro", 7, "c1"), VoiceBinding.effective(global, VoiceOverride("kokoro", 7, "c1")))
        assertEquals(global, VoiceBinding.effective(global, null)) // leaving the card chat
        assertNull(VoiceBinding.forCard("melo", 1, "", packInstalled = false, cloneExists = false))
        assertNull(VoiceBinding.forCard("", 1, "c", packInstalled = true, cloneExists = true))
        assertEquals("", VoiceBinding.forCard("kokoro", 1, "gone", packInstalled = true, cloneExists = false)!!.clone)
        val globalClone = VoiceSel("kokoro", 0, "mine")
        assertEquals("", VoiceBinding.effective(globalClone, o).clone) // card without clone doesn't use the user's clone
        assertEquals(globalClone, VoiceBinding.effective(globalClone, null))
    }

    // ---- 知识库回收站 ----
    @Test fun kbTrashRoundTrip() {
        val root = tmp()
        val kb = KnowledgeBase({ root }, File(root, "idx.json"))
        val d = kb.dir().apply { mkdirs() }
        File(d, "a.md").writeText("alpha 知识")
        File(d, "f/sub").mkdirs(); File(d, "f/sub/b.md").writeText("beta")
        assertTrue(kb.delete("a.md"))
        assertTrue(kb.deleteFolder("f"))
        assertFalse(File(d, "a.md").exists()); assertFalse(File(d, "f").exists())
        assertTrue(kb.scan().first.isEmpty()) // .trash never indexed
        assertTrue(kb.folders().isEmpty())
        val items = kb.trashItems()
        assertEquals(setOf("a.md" to false, "f" to true), items.map { it.path to it.folder }.toSet())
        File(d, "a.md").writeText("new")
        val back = kb.restore(items.first { it.path == "a.md" }.id)
        assertEquals("a (2).md", back)
        assertEquals("f", kb.restore(items.first { it.path == "f" }.id))
        assertTrue(File(d, "f/sub/b.md").isFile)
        assertTrue(kb.trashItems().isEmpty())
    }

    @Test fun kbTrashPurgeAndEmpty() {
        val d = tmp()
        val t = KbTrash { d }
        File(d, "x.txt").writeText("x"); File(d, "y.txt").writeText("y")
        val now = 1_000_000_000_000L
        assertTrue(t.put("x.txt", now - KbTrash.KEEP_MS - 1))
        assertTrue(t.put("y.txt", now))
        assertFalse(t.put(".trash"))
        assertEquals(1, t.purge(now))
        assertEquals(listOf("y.txt"), t.list().map { it.path })
        assertEquals(1, t.empty()); assertTrue(t.list().isEmpty())
    }
}
