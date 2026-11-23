package com.iris.localai.v30

import com.iris.localai.data.GetStarted
import com.iris.localai.data.GetStarted.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V300B4Test {
    private fun s(installed: Int = 0, ready: Boolean = false, loading: Boolean = false, failed: Boolean = false, dl: Boolean = false, rec: Boolean = true) =
        GetStarted.step(installed, ready, loading, failed, dl, rec)

    @Test fun noModelOffersRecommendedDownload() { assertEquals(Step.DOWNLOAD, s()); assertEquals(Step.NONE, s(rec = false)) }
    @Test fun downloadingShowsProgress() = assertEquals(Step.DOWNLOADING, s(dl = true))
    @Test fun installedButIdleShowsNothing() = assertEquals(Step.NONE, s(installed = 2))
    @Test fun failedOffersRetry() = assertEquals(Step.RETRY, s(installed = 1, failed = true))
    @Test fun readyOrLoadingHidesCard() { assertEquals(Step.NONE, s(installed = 1, ready = true)); assertEquals(Step.NONE, s(installed = 1, loading = true, dl = true)) }
    @Test fun fallbackSkipsEmbeddingModels() {
        val list = listOf("bge-m3.gguf", "qwen3.gguf", "gemma.gguf")
        assertEquals("qwen3.gguf", GetStarted.fallback(list, { it }) { it.startsWith("bge") })
        assertNull(GetStarted.fallback(listOf("bge-m3.gguf"), { it }) { it.startsWith("bge") })
    }
}
