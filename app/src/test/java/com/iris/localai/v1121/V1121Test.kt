package com.iris.localai.v1121

import com.iris.localai.data.MnnHub
import com.iris.localai.data.MnnModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V1121Test {
    @Test fun picksTheGenericLiteRtFile() {
        val e2b = listOf(
            "README.md" to 1L, "gemma-4-E2B-it-gpu.litertlm" to 2008432640L, "gemma-4-E2B-it-web.litertlm" to 2008432640L,
            "gemma-4-E2B-it-web.task" to 2003697664L, "gemma-4-E2B-it.litertlm" to 2588147712L,
            "gemma-4-E2B-it_Google_Tensor_G5.litertlm" to 3113545589L, "gemma-4-E2B-it_qualcomm_sm8750.litertlm" to 3016294400L,
        )
        assertEquals("gemma-4-E2B-it.litertlm" to 2588147712L, MnnHub.pickLiteRtFile(e2b))
        assertNull(MnnHub.pickLiteRtFile(listOf("config.json" to 1L, "llm.mnn" to 2L)))
    }
}
