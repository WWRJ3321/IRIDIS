package com.iris.localai.imagegen

import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.ui.imagegen.ImageGenUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageGenUiTest {
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }
    private fun src(p: String) = file("src/main/java/com/iris/localai/$p").readText()

    @Test fun requestRules() {
        val qnn = ImageGenUi(prompt = "cat", initImage = "/x.png", width = 1024, height = 768, seed = "7")
        assertFalse(qnn.img2imgSupported)
        val r = qnn.request()
        assertNull("QNN model has no img2img", r.initImagePath)
        assertEquals(512, r.width); assertEquals(512, r.height); assertEquals(7L, r.seed)

        val hex = ImageGenUi(modelId = ImageCatalog.FLUX2_KLEIN.id, prompt = "cat")
        assertFalse(hex.canGenerate)
        assertFalse(ImageGenUi().canGenerate)
        assertTrue(ImageGenUi(prompt = "x").canGenerate)
        assertTrue(ImageGenUi(prompt = "x").request().seed >= 0)
    }


}
