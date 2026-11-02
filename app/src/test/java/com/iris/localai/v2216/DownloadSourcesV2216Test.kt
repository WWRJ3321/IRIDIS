package com.iris.localai.v2216

import com.iris.localai.data.AppSettings
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.DownloadSource.AUTO
import com.iris.localai.data.DownloadSource.HF_MIRROR
import com.iris.localai.data.DownloadSource.HUGGING_FACE
import com.iris.localai.data.DownloadSource.MODELSCOPE
import com.iris.localai.data.DownloadSources
import com.iris.localai.data.MnnHub
import com.iris.localai.data.SourceUrl
import com.iris.localai.data.migrated
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageDownloads
import com.iris.localai.ui.models.SourceText
import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadSourcesV2216Test {
    private val qwen = "https://huggingface.co/taobao-mnn/Qwen3-1.7B-MNN/resolve/main/llm.mnn.weight"
    private val spark = "https://huggingface.co/XHToken/Spark-X2.6-4B-GGUF/resolve/main/Spark-X2.6-4B-Q4_K_M.gguf"
    private val ld = "https://huggingface.co/xororz/sd-qnn/resolve/64d16039/AnythingV5_qnn2.28_8gen2.zip#bytes=10-20"

    @After fun reset() { DownloadSources.lastGood = null; DownloadSources.onLastGood = {} }

    @Test fun parseAndRewrite() {
        val r = DownloadSources.parseHf(qwen)!!
        assertEquals("taobao-mnn/Qwen3-1.7B-MNN", r.repo); assertEquals("main", r.revision); assertEquals("llm.mnn.weight", r.path)
        assertEquals("https://hf-mirror.com/taobao-mnn/Qwen3-1.7B-MNN/resolve/main/llm.mnn.weight", DownloadSources.urlOn(HF_MIRROR, qwen))
        assertEquals(qwen, DownloadSources.urlOn(HUGGING_FACE, qwen))
        assertEquals("https://www.modelscope.cn/models/MNN/Qwen3-1.7B-MNN/resolve/master/llm.mnn.weight", DownloadSources.urlOn(MODELSCOPE, qwen))
        assertEquals("https://www.modelscope.cn/models/XHToken/Spark-X2.6-4B-GGUF/resolve/master/Spark-X2.6-4B-Q4_K_M.gguf", DownloadSources.urlOn(MODELSCOPE, spark))
        assertNull(DownloadSources.urlOn(AUTO, qwen))
        assertNull(DownloadSources.parseHf("https://qaihub-public-assets.s3.us-west-2.amazonaws.com/x.zip"))
    }

    @Test fun rangeFragmentKeptAndUnmappedRepoHasNoModelScope() {
        assertEquals("https://hf-mirror.com/xororz/sd-qnn/resolve/64d16039/AnythingV5_qnn2.28_8gen2.zip#bytes=10-20", DownloadSources.urlOn(HF_MIRROR, ld))
        assertNull(DownloadSources.urlOn(MODELSCOPE, ld))
        // pinned HF revision → ModelScope master, sub-folder prefix applied
        val sd = "https://huggingface.co/taobao-mnn/stable-diffusion-v1-5-mnn-general/resolve/56c93a62/vocab.json"
        assertEquals("https://www.modelscope.cn/models/MNN/stable-diffusion-v1-5-mnn/resolve/master/general/vocab.json", DownloadSources.urlOn(MODELSCOPE, sd))
    }

    @Test fun orderManualAutoLastGoodDemote() {
        assertEquals(listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE), DownloadSources.order(AUTO, true))
        assertEquals(listOf(HF_MIRROR, HUGGING_FACE), DownloadSources.order(AUTO, false))
        assertEquals(listOf(HUGGING_FACE, MODELSCOPE, HF_MIRROR), DownloadSources.order(HUGGING_FACE, true))
        // 魔搭 chosen but no copy → hf-mirror first
        assertEquals(listOf(HF_MIRROR, HUGGING_FACE), DownloadSources.order(MODELSCOPE, false))
        assertEquals(listOf(HF_MIRROR, MODELSCOPE, HUGGING_FACE), DownloadSources.order(AUTO, true, lastGood = HF_MIRROR))
        assertEquals(listOf(HF_MIRROR, HUGGING_FACE, MODELSCOPE), DownloadSources.order(AUTO, true, demote = MODELSCOPE))
        assertEquals(listOf(HF_MIRROR, HUGGING_FACE), DownloadSources.order(AUTO, false, demote = HUGGING_FACE).let { it })
    }

    @Test fun candidatesForNonHfUrlIsItself() {
        val s3 = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/a.zip"
        assertEquals(listOf(SourceUrl(null, s3)), DownloadSources.candidates(s3, listOf(MODELSCOPE, HF_MIRROR)))
        assertEquals(listOf(HF_MIRROR, HUGGING_FACE), DownloadSources.candidates(ld, DownloadSources.order(AUTO, false)).map { it.source })
    }

    @Test fun rankPrefersModelScopeWithinSlackElseFastest() {
        val o = listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE)
        assertEquals(listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE), DownloadSources.rank(o, mapOf(MODELSCOPE to 900L, HF_MIRROR to 400L, HUGGING_FACE to null)))
        assertEquals(listOf(HF_MIRROR, MODELSCOPE, HUGGING_FACE), DownloadSources.rank(o, mapOf(MODELSCOPE to 2500L, HF_MIRROR to 300L, HUGGING_FACE to null)))
        assertEquals(listOf(HUGGING_FACE, MODELSCOPE, HF_MIRROR), DownloadSources.rank(o, mapOf(MODELSCOPE to null, HF_MIRROR to null, HUGGING_FACE to 200L)))
        assertEquals(o, DownloadSources.rank(o, emptyMap())) // nothing reachable: keep order, still try all
    }

    @Test fun autoResolveProbes() = runBlocking<Unit> {
        val r = DownloadSources.resolve(qwen, AUTO, probe = { u -> if ("modelscope" in u) null else if ("hf-mirror" in u) 1500L else 100L })
        assertEquals(listOf(HUGGING_FACE, HF_MIRROR, MODELSCOPE), r.map { it.source })
        // manual: no probing, chosen first
        val m = DownloadSources.resolve(spark, HF_MIRROR, probe = { fail("no probe for manual"); null })
        assertEquals(listOf(HF_MIRROR, MODELSCOPE, HUGGING_FACE), m.map { it.source })
    }

    @Test fun fetchAnySwitchesOnStallAndResumesPart() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("v2216").toFile(); val target = File(dir, "m.bin")
        val calls = mutableListOf<Pair<String, Long>>(); val notes = mutableListOf<String>()
        var saved: DownloadSource? = null
        DownloadSources.onLastGood = { saved = it }
        val cands = DownloadSources.candidates(qwen, listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE))
        DownloadSources.fetchAny(cands, target, 10, {}, fetch = { u, t, _, _ ->
            val part = HttpDownload.partFile(t)
            calls += u to (if (part.exists()) part.length() else 0L)
            when {
                "modelscope" in u -> { part.writeBytes(ByteArray(4)); throw SocketTimeoutException("Read timed out") }
                "hf-mirror" in u -> { part.appendBytes(ByteArray(6)); part.renameTo(t) }
                else -> fail("HF should not be needed")
            }
        }, onSwitch = { notes += it }, retryDelayMs = 0, attemptsPerSource = 1)
        assertEquals(10L, target.length())
        assertEquals(4L, calls[1].second) // hf-mirror resumed the 4 bytes 魔搭 wrote
        assertEquals(HF_MIRROR, saved); assertEquals(HF_MIRROR, DownloadSources.lastGood)
        assertTrue(notes.single().startsWith("魔搭 ModelScope 下载失败（20 秒没有收到数据），已换到 hf-mirror.com"))
        dir.deleteRecursively()
    }

    @Test fun fetchAnyReportsEverySourceInChinese() = runBlocking<Unit> {
        val cands = DownloadSources.candidates(qwen, listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE))
        var n = 0
        try {
            DownloadSources.fetchAny(cands, File("/tmp/none-v2216"), 1, {}, fetch = { u, _, _, _ ->
                n++
                when {
                    "modelscope" in u -> throw DownloadSources.NotFound("HTTP 404（该源没有这个文件）")
                    "hf-mirror" in u -> throw DownloadSources.SizeMismatch("文件大小与预期不同（2 / 1）")
                    else -> throw UnknownHostException("huggingface.co")
                }
            }, retryDelayMs = 0)
            fail()
        } catch (e: IOException) {
            val m = e.message!!
            assertTrue(m, m.startsWith("所有下载源都失败了"))
            assertTrue(m, "魔搭 ModelScope：HTTP 404" in m && "hf-mirror.com 镜像：文件大小与预期不同" in m && "HuggingFace 官方：无法连接（域名解析失败）" in m)
        }
        assertEquals(4, n) // 404 and size mismatch are not retried; HF gets 2 attempts
    }

    @Test fun remoteTotalFromHeaders() {
        assertEquals(1000L, HttpDownload.remoteTotal("bytes 400-999/1000", 600, true, 400))
        assertEquals(1000L, HttpDownload.remoteTotal(null, 600, true, 400))
        assertEquals(1000L, HttpDownload.remoteTotal(null, 1000, false, 0))
        assertNull(HttpDownload.remoteTotal(null, -1, false, 0))
    }

    @Test fun everyRecommendedChatModelHasModelScope() {
        val chat = MnnHub.RECOMMENDED_GROUPS.flatMap { it.items }.filter { it.imageModel == null }
        assertEquals(16, chat.size)
        chat.forEach { assertTrue(it.id, DownloadSources.MODELSCOPE.containsKey(it.id)) }
        // generic taobao-mnn → official MNN org; others none
        assertEquals("MNN/Foo-MNN", DownloadSources.modelScopeFor("taobao-mnn/Foo-MNN")!!.id)
        assertNull(DownloadSources.modelScopeFor("xororz/sd-qnn"))
        assertNull(DownloadSources.modelScopeFor("csukuangfj/kokoro-int8-multi-lang-v1_1"))
    }

    @Test fun modelScopeTreeStripsPrefix() {
        val f = listOf(Triple("general", 0L, "tree"), Triple("general/vocab.json", 5L, "blob"), Triple("opencl/vocab.json", 5L, "blob"), Triple("README.md", 1L, "blob"))
        assertEquals(listOf("vocab.json" to 5L), MnnHub.modelScopeTree(f, "general/"))
        assertEquals(3, MnnHub.modelScopeTree(f, "").size)
    }

    @Test fun imageDownloadUsesModelScopeForSdTokenizerAndFallsBack() = runBlocking<Unit> {
        val root = kotlin.io.path.createTempDirectory("v2216img").toFile()
        val seen = mutableListOf<String>()
        ImageDownloads.download(root, ImageCatalog.SD15_MNN, AUTO, fetch = { u, t, e, _ ->
            seen += u
            if ("modelscope" in u && u.endsWith("unet.mnn.weight")) throw SocketTimeoutException("Read timed out")
            t.parentFile?.mkdirs(); t.writeBytes(ByteArray(0)); java.io.RandomAccessFile(t, "rw").use { it.setLength(e) }
        }, publish = {}, retryDelayMs = 0, probe = { u -> if ("modelscope" in u) 300L else if ("hf-mirror" in u) 200L else null })
        assertTrue(seen.first().startsWith("https://www.modelscope.cn/models/MNN/stable-diffusion-v1-5-mnn/resolve/master/general/"))
        assertTrue(seen.any { it.startsWith("https://hf-mirror.com/taobao-mnn/stable-diffusion-v1-5-mnn-general/resolve/") && it.endsWith("unet.mnn.weight") })
        root.deleteRecursively()
    }

    @Test fun urlsForKeepsOldBehaviourShape() {
        val u = "https://huggingface.co/a/b/resolve/main/x.bin"
        assertEquals(listOf("https://hf-mirror.com/a/b/resolve/main/x.bin", u), ImageDownloads.urlsFor(u, HF_MIRROR))
        assertEquals(listOf(u, "https://hf-mirror.com/a/b/resolve/main/x.bin"), ImageDownloads.urlsFor(u, HUGGING_FACE))
    }

    @Test fun settingsDefaultAndMigration() {
        assertEquals(AUTO, AppSettings().downloadSource)
        assertEquals(AUTO, AppSettings(downloadSource = HF_MIRROR).migrated().downloadSource)
        assertEquals(HUGGING_FACE, AppSettings(downloadSource = HUGGING_FACE).migrated().downloadSource)
        // after migration a deliberate hf-mirror choice stays
        assertEquals(HF_MIRROR, AppSettings(downloadSource = HF_MIRROR, downloadSourceMigrated = true).migrated().downloadSource)
        assertEquals(listOf(AUTO, MODELSCOPE, HF_MIRROR, HUGGING_FACE), DownloadSource.entries.toList())
    }

    @Test fun texts() {
        assertEquals("换源重试", SourceText.RETRY_OTHER)
        assertEquals("从文件导入", SourceText.IMPORT)
        assertTrue(DownloadSource.entries.all { SourceText.hint(it).isNotBlank() })
        assertEquals("自动", AUTO.short)
    }
}
