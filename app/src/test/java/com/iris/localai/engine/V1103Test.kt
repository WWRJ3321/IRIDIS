package com.iris.localai.engine

import com.iris.localai.data.AppSettings
import com.iris.localai.data.EffectiveModelConfig
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelOverrides
import com.iris.localai.data.migrated
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.BackendPlanner
import com.iris.localai.engine.load.CacheUse
import com.iris.localai.engine.load.CpuFasterHint
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.load.GpuCacheDiag
import com.iris.localai.engine.load.LiteRtCacheFiles
import com.iris.localai.engine.load.LoadKeepAlivePolicy
import com.iris.localai.engine.load.LoadRecord
import com.iris.localai.engine.load.SlowLoadHint
import com.iris.localai.service.LoadKeepAliveText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.10.3: GPU stays (「可我想用GPU」): 自动 unchanged, GPU option warns about the first compile,
 * GPU compile-cache diagnostics + keep-alive during GPU compiles, optional 「CPU 更快」 suggestion.
 */
class V1103Test {

    private val env = BackendEnvironment(hasOpenCl = true, npuSoc = "sm8750", npuRuntimeInstalled = true, fullFlavor = true)

    private fun source(path: String): String =
        listOf(path, "app/$path", "../$path").map { File(it) }.first { it.exists() }.readText()

    private val liteRtNames = listOf(
        "gemma-4-E2B-it.litertlm", "LFM2.5-VL-3B.litertlm", "Qwen3-0.6B.litertlm", "LFM2.5-230M.litertlm",
        "Agents-A1-4B_mixed_int4.litertlm", "Qwen3.5-2B.litertlm", "agents-b1.litertlm",
    )

    // ================================================================ 1. 自动 unchanged (GPU kept, 「可我想用GPU」)


    @Test fun explicitGpuIsRespected() {
        for (n in liteRtNames) {
            val o = BackendCapabilities.options(ModelFormat.LITERT, n, env)
            assertEquals(n, Backend.GPU, BackendCapabilities.preferred(Backend.GPU, ModelFormat.LITERT, n, o))
            assertEquals(n, Backend.GPU, BackendPlanner.chain(Backend.GPU, o).first())
        }
        val s = AppSettings()
        assertEquals(Backend.GPU, EffectiveModelConfig.resolve(s, ModelFormat.LITERT, ModelOverrides(backend = "GPU")).backend)
        assertEquals(Backend.GPU, EffectiveModelConfig.resolve(s.copy(defaultBackends = mapOf("LITERT" to "GPU")), ModelFormat.LITERT, null).backend)
        assertNull(EffectiveModelConfig.resolve(s, ModelFormat.LITERT, null).backend)
        assertTrue(AppSettings().migrated().defaultBackends.isEmpty())
    }


    // ================================================================ 2. GPU compile cache

    private val model = "gemma-4-E2B-it.litertlm"
    private val mtime = 1_783_348_971_500L
    private val size = 2_588_147_712L
    private val key = "gemma-4-E2B-it.litertlm_1783348971_2588147712"

    @Test fun gpuPartsUseTheRuntimesFileNames() {
        assertEquals(key, LiteRtCacheFiles.currentPrefix(model, mtime, size))
        val both = listOf("${key}_mldrift_program_cache.bin" to 30L, "${key}_mldrift_weight_cache.bin" to 2_000L)
        assertTrue(GpuCacheDiag.gpuParts(both, model, mtime, size).complete)
        val programOnly = GpuCacheDiag.gpuParts(both.take(1), model, mtime, size)
        assertEquals(30L, programOnly.program); assertNull(programOnly.weight); assertFalse(programOnly.complete)
        // the 0.14 order (suffix before the id, LiteRT-LM #2777) is not what 0.18 writes / reads
        assertNull(GpuCacheDiag.gpuParts(listOf("${model}_mldrift_weight_cache.bin_1783348971_2588147712" to 9L), model, mtime, size).weight)
        // another mtime = another id = not this cache
        assertFalse(GpuCacheDiag.gpuParts(both, model, mtime + 1000, size).complete)
    }

    @Test fun cacheLogLineListsEverythingOfThisModel() {
        val files = listOf(
            "${key}_mldrift_weight_cache.bin" to 2L * 1_048_576, "${key}_mldrift_program_cache.bin" to 1_048_576L,
            ".loading-GPU-$model" to 13L, "other.litertlm_1_2.xnnpack_cache" to 5L,
        )
        val line = GpuCacheDiag.logLine("before", "/data/x/litert-cache", true, files, model, mtime, size)
        assertTrue(line, line.startsWith("cache before dir=/data/x/litert-cache writable=true key=$key gpuProgram=1.0MB gpuWeight=2.0MB files=["))
        assertTrue(line.contains("${key}_mldrift_program_cache.bin=1.0MB, ${key}_mldrift_weight_cache.bin=2.0MB"))
        assertTrue(line.contains(".loading-GPU-$model="))
        assertTrue(line.endsWith("other files=1]"))
        assertTrue(GpuCacheDiag.logLine("after", "/d", false, emptyList(), model, mtime, size).contains("gpuProgram=- gpuWeight=- files=[(none); other files=0]"))
    }

    @Test fun loadingMarkerIsInvisibleToTheRuntimeAndTelemetry() {
        val m = GpuCacheDiag.loadingMarker(model, Backend.GPU)
        assertEquals(".loading-GPU-$model", m)
        // LiteRT-LM DeleteStaleCaches removes names that start with "<model><suffix>" or start with the model name and end with the suffix
        for (suffix in listOf(GpuCacheDiag.PROGRAM_SUFFIX, GpuCacheDiag.WEIGHT_SUFFIX)) {
            assertFalse(m.startsWith(model + suffix) || (m.startsWith(model) && m.endsWith(suffix)))
        }
        val snap = LiteRtCacheFiles.snapshot(listOf(m to 13L), model, mtime, size, Backend.GPU)
        assertEquals(0, snap.files)
        assertEquals("litert-cache", GpuCacheDiag.CACHE_DIR)
    }

    @Test fun lowSpaceRule() {
        val gb = 1_073_741_824L
        assertTrue(GpuCacheDiag.lowSpace(2 * gb, (2.5 * gb).toLong()))
        assertTrue(GpuCacheDiag.lowSpace((2.9 * gb).toLong(), (2.5 * gb).toLong()))
        assertFalse(GpuCacheDiag.lowSpace(4 * gb, (2.5 * gb).toLong()))
        assertFalse("unknown free space", GpuCacheDiag.lowSpace(-1, gb))
        assertTrue(GpuCacheDiag.lowSpaceNotice("A1", 2 * gb, (2.5 * gb).toLong()).contains("剩余空间约 2.0 GB"))
    }

    @Test fun keepAliveOnlyWhileLiteRtCompilesForGpuOrNpu() {
        val lit = LocalModel(model, "/m/$model", size, ModelFormat.LITERT)
        val gguf = LocalModel("a.gguf", "/m/a.gguf", 1, ModelFormat.GGUF)
        assertTrue(LoadKeepAlivePolicy.keepAlive(EngineState.Loading(lit, 0.1f, backend = Backend.GPU)))
        assertTrue(LoadKeepAlivePolicy.keepAlive(EngineState.Loading(lit, 0.1f, backend = Backend.NPU)))
        assertFalse(LoadKeepAlivePolicy.keepAlive(EngineState.Loading(lit, 0.1f, backend = Backend.CPU)))
        assertFalse(LoadKeepAlivePolicy.keepAlive(EngineState.Loading(lit, 0.1f, backend = null)))
        assertFalse(LoadKeepAlivePolicy.keepAlive(EngineState.Loading(gguf, 0.1f, backend = Backend.GPU)))
        assertFalse(LoadKeepAlivePolicy.keepAlive(EngineState.Idle))
        assertEquals("A1 · GPU · 编译完成前请让 IRIDIS 保持运行", LoadKeepAliveText.text("A1", "GPU"))
    }


    // ================================================================ 4. hint

    private fun rec(backend: String, secs: Double, ok: Boolean = true, model: String = "m.litertlm", at: Long = 1) =
        LoadRecord(modelId = model, backend = backend, ms = (secs * 1000).toLong(), cache = CacheUse.MISS, ok = ok, at = at)

    @Test fun hintShowsWhenGpuOver60sAndCpuFaster() {
        assertTrue(CpuFasterHint.shouldShow(listOf(rec("GPU", 61.0), rec("CPU", 20.0)), "m.litertlm", "GPU"))
        assertTrue(CpuFasterHint.shouldShow(listOf(rec("GPU", 300.0), rec("CPU", 120.0)), "m.litertlm", "GPU"))
        assertEquals("建议：这个模型在 CPU 上加载更快（可选）", CpuFasterHint.TEXT)
        assertEquals("改用 CPU", CpuFasterHint.ACTION)
    }

    @Test fun hintStaysHiddenOtherwise() {
        val m = "m.litertlm"
        assertFalse("exactly 60 s", CpuFasterHint.shouldShow(listOf(rec("GPU", 60.0), rec("CPU", 5.0)), m, "GPU"))
        assertFalse("GPU fast", CpuFasterHint.shouldShow(listOf(rec("GPU", 30.0), rec("CPU", 5.0)), m, "GPU"))
        assertFalse("CPU slower", CpuFasterHint.shouldShow(listOf(rec("GPU", 90.0), rec("CPU", 95.0)), m, "GPU"))
        assertFalse("CPU equal", CpuFasterHint.shouldShow(listOf(rec("GPU", 90.0), rec("CPU", 90.0)), m, "GPU"))
        assertFalse("no CPU load", CpuFasterHint.shouldShow(listOf(rec("GPU", 300.0)), m, "GPU"))
        assertFalse("no GPU load", CpuFasterHint.shouldShow(listOf(rec("CPU", 3.0)), m, "GPU"))
        assertFalse("failed CPU load", CpuFasterHint.shouldShow(listOf(rec("GPU", 300.0), rec("CPU", 3.0, ok = false)), m, "GPU"))
        assertFalse("failed GPU load", CpuFasterHint.shouldShow(listOf(rec("GPU", 300.0, ok = false), rec("CPU", 3.0)), m, "GPU"))
        assertFalse("other model", CpuFasterHint.shouldShow(listOf(rec("GPU", 300.0, model = "x"), rec("CPU", 3.0, model = "x")), m, "GPU"))
        assertFalse("already CPU next", CpuFasterHint.shouldShow(listOf(rec("GPU", 300.0), rec("CPU", 3.0)), m, "CPU"))
        assertFalse("NPU next", CpuFasterHint.shouldShow(listOf(rec("GPU", 300.0), rec("CPU", 3.0)), m, "NPU"))
        assertFalse("unknown next", CpuFasterHint.shouldShow(listOf(rec("GPU", 300.0), rec("CPU", 3.0)), m, null))
        assertFalse("empty", CpuFasterHint.shouldShow(emptyList(), m, "GPU"))
    }

    @Test fun hintUsesTheLatestRecordPerBackend() {
        val old = rec("GPU", 300.0, at = 1)
        val new = rec("GPU", 10.0, at = 2)
        assertFalse(CpuFasterHint.shouldShow(listOf(old, new, rec("CPU", 5.0)), "m.litertlm", "GPU"))
        assertTrue(CpuFasterHint.shouldShow(listOf(new.copy(at = 1), old.copy(at = 2), rec("CPU", 5.0)), "m.litertlm", "GPU"))
    }


    // ================================================================ 5. SlowLoadHint unchanged

    @Test fun slowLoadHintUnchanged() {
        assertEquals(45L, SlowLoadHint.NOTICE_SECS)
        assertEquals(180L, SlowLoadHint.LONG_SECS)
        assertNull(SlowLoadHint.notice(Backend.GPU, 44))
        assertNull(SlowLoadHint.notice(Backend.CPU, 500))
        assertEquals("GPU 首次加载要编译着色器并写入缓存，可能需要几分钟，之后再加载会快很多。这一步无法中途取消。", SlowLoadHint.notice(Backend.GPU, 45))
        assertNotNull(SlowLoadHint.notice(Backend.GPU, 200))
        assertEquals("改用 CPU", SlowLoadHint.ACTION)
    }

}
