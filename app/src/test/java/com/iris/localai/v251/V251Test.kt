package com.iris.localai.v251

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.5.1 A（MoE 专家按需加载 / 激活专家数 / Gemma 4 19B REAP）与 C（隐藏 sd15-mnn）、D（版本、CHANGELOG、7 语言）。
 * 只用源码断言（测试编译不依赖本版新增的 main 类）。
 */
class V251Test {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()
    private val langs = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")

    // ---- D ----
    @Test fun version() {
        val g = src("app/build.gradle.kts")
        // 2.5.2: 版本号由 V252Test 断言；这里只要求不低于 2.5.1
        assertTrue(Regex("versionCode = (\\d+)").find(g)!!.groupValues[1].toInt() >= 250)
    }

    @Test fun changelogOnlyAc() {
        val c = src("CHANGELOG.md")
        assertTrue(c.contains("## 2.5.1"))
        val sec = c.substring(c.indexOf("## 2.5.1"), c.indexOf("## 2.5.0（"))
        assertTrue(sec.contains("专家按需加载") && sec.contains("激活专家数") && sec.contains("Gemma 4 19B REAP") && sec.contains("Stable Diffusion 1.5 (CPU)"))
        assertTrue("2.5.1 节应含 B（知识库已合并）", sec.contains("知识库") && sec.contains("嵌入"))
    }

    @Test fun stringsInSevenLanguages() {
        val keys = listOf("v251_moe_cpu", "v251_moe_cpu_auto", "v251_moe_cpu_sub", "v251_experts", "v251_experts_default",
            "v251_experts_sub", "v251_reap_purpose", "v251_sd15_mnn_hidden", "v251_sd15_mnn_delete_title", "v251_sd15_mnn_delete")
        for (d in langs) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (k in keys) assertTrue("$d/$k", x.contains("name=\"$k\""))
        }
    }

    @Test fun greyTextOneLineShort() {
        // 灰字最多一行：新的说明文案保持短句（简中 ≤ 30 字）
        val x = src("app/src/main/res/values/strings.xml")
        for (k in listOf("v251_moe_cpu_auto", "v251_moe_cpu_sub", "v251_experts_sub", "v251_sd15_mnn_hidden")) {
            val v = Regex("name=\"$k\">([^<]*)<").find(x)!!.groupValues[1]
            assertTrue("$k: $v", v.length <= 30 && !v.contains("\\n"))
        }
    }

    // ---- A: 原生 ----
    @Test fun loadParamsAndEngine() {
        val h = src("app/src/main/cpp/iris_engine.h")
        assertTrue(h.contains("bool moe_cpu = false;") && h.contains("int32_t n_expert_used = 0;") && h.contains("bool no_prefetch = false;"))
        val e = src("app/src/main/cpp/iris_engine.cpp")
        assertTrue(e.contains("\"\\\\.ffn_(up|down|gate|gate_up)_exps\""))
        assertTrue(e.contains("ggml_backend_cpu_buffer_type()"))
        assertTrue(e.contains("if (moe_cpu) mp.use_extra_bufts = false;"))
        assertTrue(e.contains("if (moe_cpu && devices[0]) mp.tensor_buft_overrides = moe_cpu_overrides();"))
        assertTrue(e.contains("gguf_init_from_file(") && e.contains("\".expert_count\"") && e.contains("%s.expert_used_count"))
        assertTrue(e.contains("LLAMA_KV_OVERRIDE_TYPE_INT") && e.contains("kvo[1].key[0] = 0;") && e.contains("mp.kv_overrides = kvo;"))
        assertTrue(e.contains("std::clamp<int64_t>(params.n_expert_used, 1, n_exp)"))
        assertTrue(e.contains("const bool no_prefetch = moe_cpu || params.no_prefetch"))
        assertTrue(e.contains("llama_set_mmap_prefetch(!no_prefetch);") && e.contains("llama_set_mmap_prefetch(true);"))
        assertTrue(e.contains("resource_log(\"load: moe_cpu=%d"))
        val j = src("app/src/main/cpp/jni_bridge.cpp")
        assertTrue(j.contains("IRIS_JNI(nativeSetMoe)") && j.contains("lp.moe_cpu = e->moe_cpu_cfg();") && j.contains("lp.n_expert_used = e->expert_used_cfg();"))
    }

    @Test fun llamaPatchStored() {
        val p = src("native-patches/llama-mmap-prefetch.patch")
        assertTrue(p.contains("LLAMA_API void llama_set_mmap_prefetch(bool on);"))
        assertTrue(p.contains("POSIX_FADV_RANDOM"))
        assertTrue(p.contains("ml.init_mappings(llama_mmap_get_prefetch()"))
        assertTrue(p.contains("posix_madvise(addr, file->size(), POSIX_MADV_RANDOM)") || p.contains("!llama_mmap_get_prefetch()"))
        assertTrue(File(root(), "native-patches/README.md").isFile)
    }

    // ---- A: Kotlin ----
    @Test fun kotlinWiring() {
        val n = src("app/src/main/java/com/iris/localai/engine/llama/LlamaNative.kt")
        assertTrue(n.contains("external fun nativeSetMoe(handle: Long, moeCpu: Boolean, nExpertUsed: Int)"))
        val le = src("app/src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt")
        assertTrue(le.contains("jni.setMoe(h, config.moeCpu, config.expertUsed.coerceIn(0, 32))"))
        val htp = src("app/src/main/java/com/iris/localai/engine/llama/LlamaHtp.kt")
        assertTrue(htp.contains("runCatching { LlamaNative.nativeSetMoe(h, moeCpu, expertUsed) }"))
        val mc = src("app/src/main/java/com/iris/localai/data/ModelConfig.kt")
        assertTrue(mc.contains("val moeCpu: Boolean? = null,") && mc.contains("val expertUsed: Int? = null,"))
        assertTrue(mc.contains("mtp, moeCpu, expertUsed"))
        val em = src("app/src/main/java/com/iris/localai/engine/EngineManager.kt")
        assertTrue(em.contains("MoeLoad.active(cfg.moeCpu, info, plain.level)"))
        assertTrue(em.contains("MemoryPreflight.needMoe(MoeLoad.nonExpertBytes(info, model.sizeBytes), info.expsBytes, proj, cfg.contextLength, gpu)"))
        assertTrue(em.contains("moeCpu = moeCpu && model.format == ModelFormat.GGUF,"))
        val ml = src("app/src/main/java/com/iris/localai/engine/MoeLoad.kt")
        assertTrue(ml.contains("info?.moe == true && (setting ?: (plainLevel != MemoryPreflight.Level.OK))"))
        val mp = src("app/src/main/java/com/iris/localai/engine/load/MemoryPreflight.kt")
        assertTrue(mp.contains("val resident = nonExpertBytes + expertBytes / 4 + extraBytes"))
        val q = src("app/src/main/java/com/iris/localai/engine/GgufQuant.kt")
        assertTrue(q.contains("contains(\"_exps\")") && q.contains("key.endsWith(\".expert_count\")"))
    }

    @Test fun settingsRowsNoNewSegmented() {
        val s = src("app/src/main/java/com/iris/localai/ui/models/ModelConfigSheet.kt")
        assertTrue(s.contains("R.string.v251_moe_cpu)") && s.contains("R.string.v251_experts)"))
        val block = s.substring(s.indexOf("moeInfo?.let { mi ->"), s.indexOf("SectionFold(\"model-adv-\"")) // 2.5.5: MoE 组移到高级折叠之前
        assertFalse("禁止新分段控件", block.contains("SegmentedRow") || block.contains("Segmented"))
        assertTrue(block.contains("OvSwitch(") && block.contains("OvSlider("))
        // 2.5.5: 用户要求 MoE 单独成组（v255_moe_section），只允许这一个分组
        assertTrue(block.contains("R.string.v255_moe_section"))
    }

    @Test fun reapCatalogAndRecommend() {
        val c = src("app/src/main/java/com/iris/localai/data/RecommendedCatalog.kt")
        assertTrue(c.contains("mradermacher/gemma-4-19b-a4b-it-REAP-i1-GGUF") && c.contains("mradermacher/gemma-4-19b-a4b-it-REAP-GGUF"))
        assertTrue(c.contains("reap(REAP_IQ3, \"IQ3_XXS\", 8_311_270_560L)") && c.contains("reap(REAP_Q4, \"Q4_0\", 10_647_317_152L)"))
        assertTrue(c.contains("gemma-4-19b-a4b-it-REAP.mmproj-Q8_0.gguf\", 806_408_480L, mmprojRepo = REAP_MMPROJ_REPO"))
        assertTrue(src("app/src/main/java/com/iris/localai/data/GgufSearch.kt").contains("fun crossRepoPath(repoId: String, file: String)"))
        assertTrue(src("app/src/main/java/com/iris/localai/ui/models/ModelsScreen.kt").contains("Triple(k.mmprojPath ?: it, k.mmprojSize"))
        val d = src("app/src/main/java/com/iris/localai/data/DeviceRecommend.kt")
        assertTrue(d.contains("12 -> listOf(RecommendedCatalog.REAP_IQ3)") && d.contains("in 16..Int.MAX_VALUE -> listOf(RecommendedCatalog.REAP_Q4)"))
        // 原推荐不变
        assertTrue(d.contains("gib >= 14.5 && strong -> \"Qwen3.5-9B-MNN\""))
    }

    // ---- C ----
    @Test fun sd15MnnHidden() {
        val ic = src("app/src/main/java/com/iris/localai/imagegen/ImageCatalog.kt")
        assertTrue(ic.contains("known.filter { ImageRules.listed(it) && (!it.sdq || ImageRules.sdqListed(arch)) }"))
        assertTrue(ic.contains("fun listed(m: ImageModel): Boolean = m.backend != Backend.MNN_CPU"))
        assertTrue(src("app/src/main/java/com/iris/localai/imagegen/ImageGenRepository.kt").contains("it.id in dl && ImageRules.listed(it)"))
        val rc = src("app/src/main/java/com/iris/localai/data/RecommendedCatalog.kt")
        assertFalse(rc.contains("Kind.Image(\"sd15-mnn\")"))
        val su = src("app/src/main/java/com/iris/localai/util/io/StorageUsage.kt")
        assertTrue(su.contains("const val IMAGEGEN_HIDDEN = \"model:imagegen-hidden\"") && su.contains("fun hiddenImageDirs("))
        val sp = src("app/src/main/java/com/iris/localai/ui/settings/StoragePage.kt")
        assertTrue(sp.contains("e.id == Ids.IMAGEGEN_HIDDEN") && sp.contains("fun deleteHiddenImageModels()"))
    }
}
