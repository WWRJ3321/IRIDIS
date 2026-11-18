package com.iris.localai.agent

import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.agent.tools.GenerateImageTool
import com.iris.localai.agent.tools.ImageMemory
import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageModel
import com.iris.localai.imagegen.Tier
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V257Test {
    private fun m(id: String, b: Backend) = ImageModel(id = id, tier = Tier.LOW, name = id, backend = b, files = emptyList(), fixedSize = null, defaultSteps = 20, defaultCfg = 7f)
    private val npu = m("npu", Backend.QNN)
    private val cpu = m("cpu", Backend.MNN_CPU)
    private val flux = m("flux", Backend.SDCPP)
    private fun src(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }.readText()

    @Test fun autoKeepsOldRule() {
        assertEquals("npu", GenerateImageTool.pickModel(listOf(npu, flux), "", false)!!.id)
        assertEquals("flux", GenerateImageTool.pickModel(listOf(flux), "", false)!!.id)
        assertEquals("cpu", GenerateImageTool.pickModel(listOf(npu, cpu, flux), "", true)!!.id)
        assertNull(GenerateImageTool.pickModel(listOf(npu, flux), "", true))
    }

    @Test fun pickedModelWins() {
        assertEquals("flux", GenerateImageTool.pickModel(listOf(npu, flux), "flux", false)!!.id)
        // 不支持参考图 → 自动换
        assertEquals("cpu", GenerateImageTool.pickModel(listOf(npu, cpu, flux), "flux", true)!!.id)
        // 已删除 → 自动
        assertEquals("npu", GenerateImageTool.pickModel(listOf(npu, flux), "gone", false)!!.id)
    }

    @Test fun memoryRule() {
        val gb = 1024L * 1024 * 1024
        assertTrue(ImageMemory.mustYield(3 * gb, 5 * gb))
        assertFalse(ImageMemory.mustYield(12 * gb, 1 * gb))
        assertFalse(ImageMemory.mustYield(0, 5 * gb))
        assertFalse(ImageMemory.mustYield(3 * gb, 0))
    }

    @Test fun settingsDefaults() {
        val s = AgentSettings()
        assertEquals("", s.imageModel)
        assertTrue(s.imageMakeRoom)
    }



    @Test fun perfTextShort() {
        val x = src("src/main/res/values/strings.xml")
        assertTrue(x.contains("<string name=\"settings_d52729\">一段时间不用就释放内存</string>"))
        assertFalse(x.contains("让芯片进入低功耗状态"))
    }
}
