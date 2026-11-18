package com.iris.localai.agent

import com.iris.localai.agent.TerminalLog
import com.iris.localai.ui.agent.FilesOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V1122Test {
    @Test fun listsFoldersFirstAndCrumbs() {
        val root = Files.createTempDirectory("ws").toFile()
        File(root, "b.txt").writeText("x"); File(root, "A").mkdirs(); File(root, "A/in.md").writeText("y"); File(root, ".hidden").writeText("")
        val items = FilesOps.list(root, "")
        assertEquals(listOf("A", "b.txt"), items.map { it.name })
        assertTrue(items[0].dir); assertEquals(1, items[0].children)
        assertEquals("A/in.md", FilesOps.list(root, "A").single().path)
        assertEquals(listOf("工作区" to "", "a" to "a", "b" to "a/b"), FilesOps.crumbs("a/b"))
        assertEquals("a", FilesOps.parent("a/b")); assertEquals("", FilesOps.parent("a"))
        assertEquals("b (2).txt", FilesOps.unique(root, "b.txt"))
        assertTrue(FilesOps.validName("新建 文件夹")); assertFalse(FilesOps.validName("../x")); assertFalse(FilesOps.validName(".."))
    }

    @Test fun terminalLogSharesAgentCommands() {
        TerminalLog.clear()
        val id = TerminalLog.start("ls", byAgent = true)
        TerminalLog.finish(id, "a.txt", 0)
        val e = TerminalLog.entries.value.single()
        assertTrue(e.byAgent); assertEquals("a.txt", e.output); assertEquals(0, e.exit)
        repeat(TerminalLog.MAX + 5) { TerminalLog.start("echo $it", false) }
        assertEquals(TerminalLog.MAX, TerminalLog.entries.value.size)
    }
}
