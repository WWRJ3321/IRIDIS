package com.iris.localai.v2217

import com.iris.localai.data.GgufSearch
import com.iris.localai.data.MnnHub
import com.iris.localai.data.RecStatus
import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.data.RecommendedCatalog.Section
import com.iris.localai.ui.models.DownloadText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.2.17 C: 下载模型 page — catalog sections, card states, short reasons, tabs. */
class DownloadPageV2217Test {
    @Test fun sectionsAndFeaturedCoder() {
        assertEquals(listOf("对话", "特色模型", "生图", "语音", "翻译", "知识库嵌入模型"), Section.entries.map { it.title })
        assertEquals(27, RecommendedCatalog.section(Section.CHAT).size)
        val coder = RecommendedCatalog.section(Section.FEATURED).first()
        assertEquals("ccore/Qwen3.5-4B-3D-Coder", coder.repoId)
        assertEquals("Apache-2.0", coder.licence)
        assertEquals(RecommendedCatalog.Engine.GGUF, coder.engine)
        val k = coder.kind as RecommendedCatalog.Kind.Gguf
        assertEquals("Qwen3.5-4B-3D-Coder-Q4_K_M.gguf", k.file)
        assertEquals("mmproj-Qwen3.5-4B-BF16.gguf", k.mmproj)
        assertEquals(644L, k.mmprojSize / (1024 * 1024))
        assertTrue(coder.demoPrompt!!.contains("Three.js"))
        assertTrue(RecommendedCatalog.section(Section.SPEECH).isNotEmpty())
        // folded: 6 top chat models, expand shows all
        assertEquals(6, RecommendedCatalog.visible(Section.CHAT, false).size)
        assertEquals(27, RecommendedCatalog.visible(Section.CHAT, true).size)
        assertEquals(1, RecommendedCatalog.visible(Section.FEATURED, false).size)
        // plain purposes, no jargon walls
        RecommendedCatalog.ITEMS.forEach { assertTrue(it.name, it.purpose.isNotBlank() && it.purpose.length <= 40) }
    }

    @Test fun cardStatusFromHub() {
        val repo = "taobao-mnn/Qwen3.5-4B-MNN"
        val idle = MnnHub.State()
        assertEquals(RecStatus.Idle, RecStatus.forHub(repo, idle, installed = false, loaded = false))
        val dl = MnnHub.State(repoId = repo, active = true, doneBytes = 50, totalBytes = 200, source = "魔搭 ModelScope")
        val s = RecStatus.forHub(repo, dl, false, false) as RecStatus.Downloading
        assertEquals(0.25f, s.fraction!!, 0.001f)
        assertEquals("25%", RecStatus.primaryLabel(s, Section.CHAT))
        assertEquals(RecStatus.Blocked, RecStatus.forHub("other/x", dl, false, false))
        assertEquals("继续", RecStatus.primaryLabel(RecStatus.forHub(repo, MnnHub.State(repoId = repo, paused = true, doneBytes = 1, totalBytes = 2), false, false), Section.CHAT))
        val failed = RecStatus.forHub(repo, MnnHub.State(repoId = repo, error = "魔搭 ModelScope：连接超时\nhf-mirror：该源没有这个文件"), false, false)
        assertEquals(RecStatus.Failed("连接超时"), failed)
        assertEquals("加载", RecStatus.primaryLabel(RecStatus.forHub(repo, idle, true, false), Section.CHAT))
        assertEquals("使用中", RecStatus.primaryLabel(RecStatus.Installed(true), Section.FEATURED))
        assertEquals("已下载", RecStatus.primaryLabel(RecStatus.Installed(), Section.IMAGE))
        assertEquals("下载", RecStatus.primaryLabel(RecStatus.Idle, Section.SPEECH))
    }

    @Test fun shortReasons() {
        assertEquals("下载失败", RecommendedCatalog.shortReason(null))
        assertEquals("需要登录或申请权限，暂不支持", RecommendedCatalog.shortReason(MnnHub.GATED))
        assertEquals("存储空间不足：需要 2.00 GB，可用 1.00 GB", RecommendedCatalog.shortReason("存储空间不足：需要 2.00 GB，可用 1.00 GB"))
        assertTrue(RecommendedCatalog.shortReason("x".repeat(200)).length <= 120) // 2.4.4: 40 → 120, reasons are no longer cut short
    }

    @Test fun localMatching() {
        assertTrue(RecommendedCatalog.matchesLocal("taobao-mnn/Qwen3.5-4B-MNN", "Qwen3.5-4B-MNN"))
        assertFalse(RecommendedCatalog.matchesLocal("taobao-mnn/Qwen3.5-4B-MNN", "Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN"))
        assertTrue(RecommendedCatalog.matchesLocal("XHToken/Spark-X2.6-4B-GGUF", "Spark-X2.6-4B-Q4_K_M.gguf"))
        assertTrue(RecommendedCatalog.matchesLocal("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"))
        assertFalse(RecommendedCatalog.matchesLocal("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E4B-it.litertlm"))
    }

    @Test fun tabsAndStatusLine() {
        assertEquals(listOf("推荐", "热门", "最新", "适合本机"), DownloadText.TABS)
        assertNull(DownloadText.modeOf(0))
        GgufSearch.Browse.entries.forEach { assertEquals(it, DownloadText.modeOf(DownloadText.tabOf(it))) }
        assertEquals(GgufSearch.Browse.HOT, DownloadText.searchMode(0))
        assertEquals(GgufSearch.Browse.HOT, DownloadText.searchMode(2))
        assertEquals(GgufSearch.Browse.FIT, DownloadText.searchMode(3))
        assertNull(DownloadText.statusLine(MnnHub.State()))
        assertEquals("正在下载 Qwen3.5 4B 3D Coder · 50%",
            DownloadText.statusLine(MnnHub.State(repoId = RecommendedCatalog.CODER_REPO, active = true, doneBytes = 1, totalBytes = 2)))
        assertEquals("已暂停 foo", DownloadText.statusLine(MnnHub.State(repoId = "a/foo", paused = true)))
    }

    @Test fun oldDialogGone() {
        val src = File("app/src/main/java/com/iris/localai")
        val all = src.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("MnnDownloadDialog" in all)
        assertFalse("GgufSearchSheet" in all)
        assertFalse("RECOMMENDED_GROUPS" in all)
        assertTrue("Route.DOWNLOAD_MODELS ->" in all)
    }
}
