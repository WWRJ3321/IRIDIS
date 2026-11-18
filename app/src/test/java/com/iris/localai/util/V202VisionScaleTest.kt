package com.iris.localai.util

import com.iris.localai.util.io.Attachments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V202VisionScaleTest {
    @Test fun smallImagesUntouched() {
        assertNull(Attachments.visionTarget(800, 600, 1024))
        assertNull(Attachments.visionTarget(1024, 1024, 1024))
        assertNull(Attachments.visionTarget(0, 500, 1024))
    }

    @Test fun longSideCappedAt1024() {
        assertEquals(Pair(461, 1024), Attachments.visionTarget(1080, 2400, 1024))
        assertEquals(Pair(1024, 768), Attachments.visionTarget(4000, 3000, 1024))
    }

    @Test fun cachePathSitsNextToOriginal() {
        assertEquals("/a/b.png.vis.jpg", Attachments.visionPathFor("/a/b.png", 1024))
    }
}
