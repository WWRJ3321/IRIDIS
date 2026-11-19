package com.iris.localai.agent

import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.tools.MemoryGate
import com.iris.localai.agent.tools.MemoryTool
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.memory.MemoryDocs
import com.iris.localai.memory.MemoryTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 长期记忆开关与智能体 memory 工具一致（2.7.0-beta3）。 */
class MemoryGateTest {
    private lateinit var tmp: File

    @Before fun setUp() { tmp = Files.createTempDirectory("memgate").toFile() }
    @After fun tearDown() { tmp.deleteRecursively() }

    private fun add(c: String) = JsonObject(mapOf("action" to JsonPrimitive("add"), "target" to JsonPrimitive("user"), "content" to JsonPrimitive(c)))

    @Test fun toolListedExactlyWhenSwitchOn() {
        val legacyOff = AgentSettings().let { it.copy(enabled = it.enabled + (MemoryTool.name to false)) }
        assertTrue(AgentTools.enabled(legacyOff, true, memoryOn = true).any { it.name == MemoryTool.name })
        assertFalse(AgentTools.enabled(AgentSettings(), true, memoryOn = false).any { it.name == MemoryTool.name })
        assertFalse(MemoryGate.listed(MemoryTool, AgentSettings(), memoryOn = false))
    }

    @Test fun callAfterSwitchOffIsRefusedAndWritesNothing() {
        val docs = MemoryDocs(File(tmp, "memory"))
        var on = true
        val env = ToolEnv(WorkspaceSandbox(File(tmp, "ws")), UndoStore(File(tmp, "u")), memory = docs, memoryOn = { on })
        assertTrue(MemoryTool.run(add("喜欢简短回答"), env).ok)
        on = false
        val r = MemoryTool.run(add("住在香港"), env)
        assertFalse(r.ok)
        assertEquals(MemoryGate.OFF_ERROR, r.output)
        assertEquals("- 喜欢简短回答", docs.read(MemoryTarget.USER))
    }

    @Test fun noDocsMeansOff() {
        val env = ToolEnv(WorkspaceSandbox(File(tmp, "ws")), UndoStore(File(tmp, "u")))
        assertFalse(MemoryTool.run(add("x y z"), env).ok)
    }
}
