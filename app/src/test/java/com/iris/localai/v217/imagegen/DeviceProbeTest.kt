package com.iris.localai.v217.imagegen

import com.iris.localai.imagegen.DeviceProbe
import com.iris.localai.imagegen.ProbeFacts
import com.iris.localai.imagegen.StubEngine
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeviceProbeTest {
    @Test fun socToHtpArch() {
        assertEquals(81, DeviceProbe.htpArch("SM8850")) // 2.5.0-beta8: 8 Elite Gen 5
        assertEquals(79, DeviceProbe.htpArch("SM8750"))
        assertEquals(75, DeviceProbe.htpArch("SM8650"))
        assertEquals(73, DeviceProbe.htpArch("SM8550"))
        assertEquals(79, DeviceProbe.htpArch(" sm8750-ab "))
        assertEquals(75, DeviceProbe.htpArch("SM8650P"))
        assertNull(DeviceProbe.htpArch("SM8450"))
        assertNull(DeviceProbe.htpArch("MT6989"))
        assertNull(DeviceProbe.htpArch(null))
    }

    @Test fun qnnRuntimeFolder() {
        val files = Files.createTempDirectory("probe").toFile()
        assertFalse(DeviceProbe.qnnRuntimePresent(files))
        val dir = File(files, "npu/qnn-2.37-v79").apply { mkdirs() }
        assertFalse("empty folder", DeviceProbe.qnnRuntimePresent(files))
        File(dir, "libQnnHtp.so").writeText("x")
        assertTrue(DeviceProbe.qnnRuntimePresent(files))
    }

    @Test fun reportNotesPendingNativeProbe() {
        val f = ProbeFacts("SM8750", 79, true, false, true, false, 6000, emptySet())
        val r = DeviceProbe.report(f)
        assertTrue(r.startsWith("【IRIDIS 图像生成自检】"))
        assertTrue(r.endsWith(DeviceProbe.NATIVE_PENDING))
    }

    @Test fun stubEngineFails() = runBlocking {
        val r = StubEngine().generate(ImageRequest("p"), ImageCatalog.SD15_QNN) { _, _ -> }
        assertTrue(r.isFailure)
        assertEquals("运行库未就绪（下个版本接入）", r.exceptionOrNull()!!.message)
    }
}
