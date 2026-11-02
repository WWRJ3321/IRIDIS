package com.iris.localai.v2216

import com.iris.localai.data.GgufSearch
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.companionOf
import com.iris.localai.data.isSplitTail
import com.iris.localai.ui.models.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufSearchV2216Test {
    @Test fun parsesHfSearch() {
        val body = """[{"_id":"x","id":"unsloth/Qwen3-4B-GGUF","cardData":{"license":"apache-2.0"},"gated":false,"lastModified":"2025-06-08T08:08:00.000Z","likes":252,"downloads":531614},
            {"id":"meta-llama/Llama-3.2-1B-GGUF","gated":"manual","tags":["gguf","license:llama3.2"],"downloads":5},
            {"id":"a/b","cardData":{"license":["mit"]}}]"""
        val h = GgufSearch.parseHfSearch(body)
        assertEquals(3, h.size)
        assertEquals(GgufSearch.RepoHit("unsloth/Qwen3-4B-GGUF", GgufSearch.Site.HF, 531614, 252, "2025-06-08", "apache-2.0", false, 4_000_000_000L), h[0])
        assertTrue(h[1].gated); assertEquals("llama3.2", h[1].license)
        assertEquals("mit", h[2].license); assertFalse(h[2].gated)
    }

    @Test fun parsesModelScopeSearch() {
        val body = """{"Code":200,"Data":{"Model":{"TotalCount":2,"Models":[
            {"Path":"unsloth","Name":"Qwen3-30B-A3B-GGUF","Downloads":1121597,"Stars":17,"License":"apache-2.0","LastUpdatedTime":1749401232,"ApprovalMode":0,"IsAccessible":1},
            {"Path":"x","Name":"y-GGUF","Downloads":3,"Stars":0,"License":"","LastUpdatedTime":0,"ApprovalMode":1,"IsAccessible":1}]}}}"""
        val h = GgufSearch.parseMsSearch(body)
        assertEquals("unsloth/Qwen3-30B-A3B-GGUF", h[0].id); assertEquals(GgufSearch.Site.MS, h[0].site)
        assertEquals(1121597L, h[0].downloads); assertEquals(17L, h[0].likes); assertEquals("2025-06-08", h[0].updated)
        assertFalse(h[0].gated); assertTrue(h[1].gated); assertNull(h[1].license)
        assertEquals(emptyList<GgufSearch.RepoHit>(), GgufSearch.parseMsSearch("""{"Code":200,"Data":{}}"""))
    }

    @Test fun parsesTreesAndDetail() {
        val hf = """[{"type":"directory","path":"BF16"},{"type":"file","path":"Qwen3-4B-Q4_K_M.gguf","size":134,"lfs":{"size":2497281632}},{"type":"file","path":"README.md","size":10}]"""
        assertEquals(listOf("Qwen3-4B-Q4_K_M.gguf" to 2497281632L, "README.md" to 10L), GgufSearch.parseHfTree(hf))
        val ms = """{"Data":{"Files":[{"Path":"BF16","Type":"tree","Size":0},{"Path":"a-Q8_0.gguf","Type":"blob","Size":42}]}}"""
        assertEquals(listOf("a-Q8_0.gguf" to 42L), GgufSearch.parseMsTree(ms))
        assertEquals("apache-2.0" to true, GgufSearch.parseHfDetail("""{"id":"a/b","gated":"auto","cardData":{"license":"apache-2.0"}}"""))
    }

    @Test fun groupsQuantsSplitsAndProjectors() {
        val f = GgufSearch.group(listOf(
            "Model-Q8_0.gguf" to 800L,
            "Model-Q4_K_M.gguf" to 400L,
            "mmproj-F16.gguf" to 60L,
            "Q4_0/Model-Q4_0.gguf" to 380L,
            "BF16/Model-BF16-00002-of-00002.gguf" to 900L,
            "BF16/Model-BF16-00001-of-00002.gguf" to 1000L,
            "Big-Q2_K-00001-of-00003.gguf" to 10L,
            "README.md" to 1L,
        ))
        assertEquals(listOf("Q2_K", "Q4_0", "Q4_K_M", "Q8_0", "BF16"), f.choices.map { it.quant })
        val bf = f.choices.first { it.quant == "BF16" }
        assertEquals(listOf("BF16/Model-BF16-00001-of-00002.gguf", "BF16/Model-BF16-00002-of-00002.gguf"), bf.parts.map { it.path })
        assertEquals(1900L, bf.size); assertTrue(bf.complete); assertEquals("Model-BF16.gguf（2 个分卷）", bf.title)
        assertFalse(f.choices.first { it.quant == "Q2_K" }.complete)
        assertEquals(listOf("mmproj-F16.gguf"), f.projectors.map { it.path })
        assertTrue(f.choices.none { it.main.projector })
    }

    @Test fun localNamesAndCompanions() {
        val p = GgufSearch.projectorLocalName("Qwen3-VL-4B-Q4_K_M.gguf", "mmproj-F16.gguf")
        assertEquals("Qwen3-VL-4B-Q4_K_M-mmproj-F16.gguf", p)
        assertEquals("M-BF16-mmproj-F16.gguf", GgufSearch.projectorLocalName("M-BF16-00001-of-00002.gguf", "mmproj-F16.gguf"))
        // found by the existing vision lookup
        assertEquals(p, com.iris.localai.engine.geniex.GenieXVision.findMmproj("Qwen3-VL-4B-Q4_K_M.gguf", listOf(p, "Other.gguf")))
        assertTrue(isSplitTail("M-00002-of-00003.gguf")); assertFalse(isSplitTail("M-00001-of-00003.gguf")); assertFalse(isSplitTail("M.gguf"))
        assertTrue(companionOf("M-00001-of-00003.gguf", "M-00003-of-00003.gguf"))
        assertFalse(companionOf("M-00001-of-00003.gguf", "N-00002-of-00003.gguf"))
        assertTrue(companionOf("M-BF16-00001-of-00002.gguf", "M-BF16-mmproj-F16.gguf"))
        assertTrue(companionOf("Qwen3-VL-4B-Q4_K_M.gguf", p))
        assertFalse(companionOf("Qwen3-VL-4B-Q4_K_M.gguf", "Qwen3-VL-4B-Q8_0.gguf"))
    }

    @Test fun requestsAndHelpers() {
        val path = GgufSearch.hfSearchPath("qwen 3")
        assertTrue(path, path.startsWith("/api/models?search=qwen+3&filter=gguf&sort=downloads"))
        val body = GgufSearch.msSearchBody("a\"b")
        assertTrue(body, "\"Name\":\"a\\\"b\"" in body && "\"values\":[\"gguf\"]" in body)
        assertEquals(listOf("https://hf-mirror.com", "https://huggingface.co"), GgufSearch.hfHosts(DownloadSource.MODELSCOPE))
        assertEquals(listOf("https://huggingface.co", "https://hf-mirror.com"), GgufSearch.hfHosts(DownloadSource.HUGGING_FACE))
        assertEquals(listOf(1, "a", 2, "b", 3), GgufSearch.interleave(listOf(1, 2, 3), listOf("a", "b")))
        assertEquals("内存够用", GgufSearch.fitHint(1L shl 30, 8L shl 30))
        assertEquals("内存可能不够", GgufSearch.fitHint(8L shl 30, 2L shl 30))
        assertEquals("53.2万", SearchText.count(531614)); assertEquals("999", SearchText.count(999))
        assertEquals("2025-06-08", GgufSearch.date("2025-06-08T08:08:00.000Z"))
    }

    @Test fun urlsForHits() = kotlinx.coroutines.runBlocking<Unit> {
        val ms = GgufSearch.RepoHit("unsloth/Qwen3-4B-GGUF", GgufSearch.Site.MS)
        assertEquals(listOf("https://www.modelscope.cn/models/unsloth/Qwen3-4B-GGUF/resolve/master/sub/a%20b.gguf"),
            GgufSearch.urls(ms, "sub/a b.gguf", DownloadSource.AUTO).map { it.url })
        val hf = GgufSearch.RepoHit("unsloth/Qwen3-4B-GGUF", GgufSearch.Site.HF)
        assertEquals(listOf("https://hf-mirror.com/unsloth/Qwen3-4B-GGUF/resolve/main/x.gguf", "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/x.gguf"),
            GgufSearch.urls(hf, "x.gguf", DownloadSource.HF_MIRROR).map { it.url })
    }

    @Test fun strictGgufAndBrowseHelpers() {
        val hf = """[{"id":"a/Qwen3-1.7B-GGUF","tags":["gguf"],"gguf":{"total":2031739904,"architecture":"qwen3"}},
            {"id":"b/Some-safetensors-only","tags":["safetensors"]},
            {"id":"taobao-mnn/Qwen3-4B-MNN","tags":["gguf"]},
            {"id":"c/gemma-3-270m-it-GGUF","tags":["gguf"]}]"""
        val h = GgufSearch.parseHfSearch(hf)
        assertEquals(listOf("a/Qwen3-1.7B-GGUF", "c/gemma-3-270m-it-GGUF"), h.map { it.id })
        assertEquals(2031739904L, h[0].params); assertEquals("qwen3", h[0].arch)
        assertEquals(270_000_000L, h[1].params)
        val ms = """{"Data":{"Model":{"Models":[{"Path":"MNN","Name":"Qwen3-4B-MNN","Libraries":["mnn"]},{"Path":"x","Name":"Llama-3.2-3B-GGUF","Libraries":["gguf"]}]}}}"""
        assertEquals(listOf("x/Llama-3.2-3B-GGUF"), GgufSearch.parseMsSearch(ms).map { it.id })
        assertEquals(2, GgufSearch.msRawCount(ms))
        assertEquals(30_000_000_000L, GgufSearch.paramsFromName("unsloth/Qwen3-30B-A3B-GGUF"))
        assertEquals(1_500_000_000L, GgufSearch.paramsFromName("x/DeepSeek-R1-Distill-Qwen-1.5B-GGUF"))
        assertEquals(0L, GgufSearch.paramsFromName("x/Phi-mini-GGUF"))
        assertFalse(GgufSearch.looksGguf("litert-community/gemma")); assertTrue(GgufSearch.looksGguf("bartowski/Qwen3-8B-GGUF"))
        val ram = 8L shl 30
        assertTrue(GgufSearch.fits(GgufSearch.RepoHit("a", GgufSearch.Site.HF, params = 4_000_000_000L), ram))
        assertFalse(GgufSearch.fits(GgufSearch.RepoHit("a", GgufSearch.Site.HF, params = 14_000_000_000L), ram))
        assertFalse(GgufSearch.fits(GgufSearch.RepoHit("a", GgufSearch.Site.HF), ram))
        val link = "<https://huggingface.co/api/models?filter=gguf&sort=downloads&cursor=eyJ9%3D>; rel=\"next\""
        assertEquals("/api/models?filter=gguf&sort=downloads&cursor=eyJ9%3D", GgufSearch.nextFromLink(link))
        assertNull(GgufSearch.nextFromLink(null))
        assertEquals("lastModified", GgufSearch.hfSort(GgufSearch.Browse.NEW)); assertEquals("GmtModified", GgufSearch.msSort(GgufSearch.Browse.NEW))
        assertEquals("DownloadsCount", GgufSearch.msSort(GgufSearch.Browse.FIT))
        assertTrue(GgufSearch.hfSearchPath("").startsWith("/api/models?filter=gguf&sort=downloads"))
        assertEquals(listOf("热门", "最新", "适合本机"), GgufSearch.Browse.entries.map { it.label })
        assertEquals("约 4.0B 参数 · Q4–Q8 约 2.1–4.0 GB · qwen3",
            SearchText.sizeLine(GgufSearch.RepoHit("a", GgufSearch.Site.HF, params = 4_000_000_000L, arch = "qwen3")))
    }
}
