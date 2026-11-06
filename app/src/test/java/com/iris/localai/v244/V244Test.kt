package com.iris.localai.v244

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.NpuIds
import com.iris.localai.data.RecommendedCatalog as R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.4.4 (Issue #6): MediaTek NeuroPilot NPU through LiteRT-LM. */
class V244Test {
    private val mt = "Gemma3-1B-IT_q4_ekv1280_mt6991.litertlm"

    @Test fun mediatekFileNamesAndChips() {
        assertEquals("mt6991", NpuIds.targetSoc(mt))
        assertEquals("mt6989", NpuIds.targetSoc("Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm"))
        assertEquals(1280, NpuIds.kvCapacity(mt))
        assertEquals("sm8750", NpuIds.targetSoc("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        assertNull(NpuIds.targetSoc("gemma3-1b-it-int4.litertlm"))
        assertEquals("mt6991", NpuIds.baseSocId("MT6991"))
        assertEquals("mt6989", NpuIds.baseSocId("MT6989W"))
        assertEquals("mt6991", NpuIds.mediatekNpu("Mediatek", "MT6991"))
        assertEquals("mt6993", NpuIds.mediatekNpu("MediaTek", "MT6993Z"))
        assertNull(NpuIds.mediatekNpu("Mediatek", "MT6878")) // Dimensity 7300: no official LLM NPU build
        assertNull(NpuIds.mediatekNpu("QTI", "SM8750"))
        assertTrue(NpuIds.socMatches("mt6991", "mt6991"))
        assertFalse(NpuIds.socMatches("mt6989", "mt6991"))
        assertFalse(NpuIds.socMatches("mt6991", "sm8750"))
        assertTrue(NpuIds.isMediatekTarget("mt6991")); assertFalse(NpuIds.isMediatekTarget("sm8750")); assertFalse(NpuIds.isMediatekTarget(null))
        assertEquals(null, NpuIds.hexagonArch("Mediatek", "MT6991"))
    }

    @Test fun backendOptionsForMediatekFile() {
        val env = BackendEnvironment(hasOpenCl = false, npuSoc = "mt6991", npuRuntimeInstalled = true, fullFlavor = true)
        val opts = BackendCapabilities.options(ModelFormat.LITERT, mt, env)
        val npu = opts.first { it.backend == Backend.NPU }
        assertTrue(npu.available); assertEquals("MediaTek · NeuroPilot", npu.detail)
        assertFalse(opts.first { it.backend == Backend.CPU }.available)
        val other = BackendCapabilities.options(ModelFormat.LITERT, "Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm", env)
        assertFalse(other.first { it.backend == Backend.NPU }.available)
        assertEquals(1280, BackendCapabilities.features(ModelFormat.LITERT, mt).fixedContext)
    }

    @Test fun catalogShowsOnlyThisChipsBuild() {
        val before = R.deviceSoc
        try {
            R.deviceSoc = null
            assertTrue(R.section(R.Section.CHAT).none { it.onlySoc != null })
            R.deviceSoc = "sm8750"
            assertTrue(R.section(R.Section.CHAT).none { it.onlySoc != null })
            R.deviceSoc = "mt6991"
            val mtk = R.section(R.Section.CHAT).filter { it.onlySoc != null }
            assertEquals(1, mtk.size)
            val k = mtk[0].kind as R.Kind.Hub
            assertEquals(R.GEMMA3_1B, k.repoId); assertEquals(mt, k.file)
            assertEquals(1_033_814_016L, mtk[0].sizeBytes)
            assertTrue(mtk[0].top)
            assertTrue(R.matchesLocal(R.GEMMA3_1B, mt))
        } finally { R.deviceSoc = before }
    }

    @Test fun downloadsFromModelScopeAndKeepsGenericPick() {
        assertTrue("litert-community/Gemma3-1B-IT" in com.iris.localai.data.DownloadSources.MODELSCOPE)
        val files = listOf(mt to 1L, "gemma3-1b-it-int4.litertlm" to 2L, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm" to 3L)
        assertEquals("gemma3-1b-it-int4.litertlm", com.iris.localai.data.MnnHub.pickLiteRtFile(files)?.first)
    }

    @Test fun npuLogKeepsNeuronLines() {
        val lines = listOf("E/NeuronAdapter( 123): compile failed", "I/Other( 1): nothing", "E/apusys: power on fail")
        val got = com.iris.localai.engine.NpuLog.relevant(lines, 10)
        assertTrue("NeuronAdapter" in got && "apusys" in got && "Other" !in got)
    }

    @Test fun runtimeBundledAndManifestOpensVendorLibs() {
        val so = java.io.File("app/src/full/assets/npu/libLiteRtDispatch_MediaTek.so")
        assertEquals(330_968L, so.length())
        val src = java.io.File("app/src/full/java/com/iris/localai/engine/litert/MediaTekNpuRuntime.kt").readText()
        assertTrue("DISPATCH_BYTES = 330_968L" in src)
        val man = java.io.File("app/src/full/AndroidManifest.xml").readText()
        for (l in listOf("libneuronusdk_adapter.mtk.so", "libneuron_adapter_mgvi.so", "libapuwareapusys_v2.mtk.so")) assertTrue(l, "\"$l\"" in man)
        val ef = java.io.File("app/src/full/java/com/iris/localai/engine/EngineFactory.kt").readText()
        assertTrue("MediaTekNpuRuntime.isMediatek()" in ef)
        for (d in listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")) {
            val xml = java.io.File("app/src/main/res/$d/strings.xml").readText()
            for (k in listOf("mtk244_title", "mtk244_init_failed", "mtk244_gemma_purpose", "mtk244_wrong_soc")) assertTrue("$d $k", "name=\"$k\"" in xml)
            assertFalse(d, Regex("name=\"engine_03e4b1\">[^<]*Qualcomm").containsMatchIn(xml))
        }
    }

    // ---- 删除消息 ----
    private fun msg(id: String, role: com.iris.localai.data.Role) = com.iris.localai.data.ChatMessage(id = id, role = role, content = id)

    @Test fun deleteAnyMessageAndMoveMarkers() {
        val u = com.iris.localai.data.Role.USER; val a = com.iris.localai.data.Role.ASSISTANT
        val list = listOf(msg("u1", u), msg("a1", a), msg("u2", u), msg("a2", a))
        val V = com.iris.localai.data.MessageVersions
        assertEquals(listOf("u1", "u2", "a2"), V.delete(list, "a1")!!.map { it.id })
        assertEquals(listOf("a1", "u2", "a2"), V.delete(list, "u1")!!.map { it.id })
        assertNull(V.delete(list, "nope"))
        assertEquals("u1", V.markerAfterDelete(list, "a1", "a1"))
        assertNull(V.markerAfterDelete(list, "u1", "u1"))
        assertEquals("u2", V.markerAfterDelete(list, "u2", "a1"))
        val vm = src("app/src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt").substringAfter("fun deleteMessage(").substringBefore("\n    }\n")
        assertTrue("busy()" in vm && "persist()" in vm && "contextStartAfterId = start" in vm)
        val screen = src("app/src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")
        assertEquals(2, Regex("onDelete = ").findAll(screen.substringAfter("UserMessage(").substringBefore("CompactionDivider")).count())
        assertTrue("vm.deleteMessage(m.id)" in screen)
        val item = src("app/src/main/java/com/iris/localai/ui/chat/MessageItem.kt")
        assertEquals(2, Regex("del244_delete").findAll(item).count())
    }

    // ---- 下载通知 ----
    @Test fun downloadNotificationText() {
        val T = com.iris.localai.service.DownloadText
        val a = com.iris.localai.service.DlItem("hub:x", "Gemma", 500, 1000)
        val b = com.iris.localai.service.DlItem("img:y", "FLUX", 0, 3000)
        assertEquals(50, T.percent(a))
        assertEquals(12, T.overall(listOf(a, b)))
        assertNull(T.overall(listOf(com.iris.localai.service.DlItem("v", "x", 10, 0))))
        assertTrue("50%" in T.line(a))
        val man = src("app/src/main/AndroidManifest.xml")
        assertTrue("android:name=\".service.DownloadService\"" in man && "FOREGROUND_SERVICE_DATA_SYNC" in man && "dataSync" in man)
        assertTrue("DownloadNotifier.start(this, container)" in src("app/src/main/java/com/iris/localai/IrisApp.kt"))
        val n = src("app/src/main/java/com/iris/localai/service/DownloadNotifier.kt")
        for (f in listOf("c.mnnHub.state", "c.imageGenDownloader.states", "c.voiceStore.states", "c.translator.packs.states")) assertTrue(f, f in n)
    }

    // ---- 文案不截断 / 下载按钮 ----
    @Test fun infoIsNotCutAndDownloadIsPrimary() {
        val models = src("app/src/main/java/com/iris/localai/ui/models/ModelsScreen.kt")
        assertTrue("Text(m.metaLine(tag), style = IrisType.caption, color = c.textTertiary)" in models)
        val block = models.substringAfter("item(key = \"import\"").substringBefore("AnimatedVisibility(")
        assertTrue(block.indexOf("DownloadText.TITLE") < block.indexOf("models_c60fdb"))
        assertTrue("FitText(t, style = IrisType.label" in src("app/src/main/java/com/iris/localai/ui/components/Buttons.kt"))
        assertTrue("FitText(" in src("app/src/main/java/com/iris/localai/ui/chat/ChatTopBar.kt"))
        val dl = src("app/src/main/java/com/iris/localai/ui/models/DownloadModelsScreen.kt")
        assertTrue("Text(item.purpose, style = IrisType.secondary, color = c.textSecondary)" in dl)
        assertFalse("maxLines = 1, overflow = TextOverflow.Ellipsis" in dl.substringAfter("private fun DownloadBlock"))
    }

    // ---- Spark 模板 ----
    @Test fun sparkTemplateRenderedNatively() {
        val cpp = src("app/src/main/cpp/iris_engine.cpp")
        assertTrue("std::string format_spark(" in cpp)
        assertTrue("if (is_spark_template(template_)) return format_spark(messages, !disable_thinking);" in cpp)
        assertTrue("!is_spark_template(template_)) {" in cpp) // not the ChatML fallback
        assertFalse(Regex("\\\\x9Cend").containsMatchIn(cpp)) // \x9Ce would be read as one hex escape
    }

    // ---- mmproj 卡死 ----
    @Test fun visionProjectorOffAdrenoGpuAndCountedInPreflight() {
        val V = com.iris.localai.engine.llama.VisionGpu
        assertTrue(V.adrenoOpenCl("QUALCOMM Adreno(TM) 830"))
        assertFalse(V.adrenoOpenCl("Mali-G925-Immortalis MC12"))
        assertFalse(V.adrenoOpenCl(""))
        val llama = src("app/src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt")
        assertTrue("jni.loadMmproj(h, mp.toByteArray(Charsets.UTF_8), projGpu)" in llama)
        val mgr = src("app/src/main/java/com/iris/localai/engine/EngineManager.kt")
        assertTrue("MemoryPreflight.check(mi.availMem, model.sizeBytes + proj," in mgr)
    }

    private fun src(p: String) = java.io.File(p).readText()
}
