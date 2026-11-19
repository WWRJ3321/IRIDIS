package com.iris.localai.imagegen

import com.iris.localai.service.HyperFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 2.7.1：LoRA 头校验与结构识别、潜变量预览映射、低内存默认值。 */
class V271Test {
    private fun header(vararg t: Pair<String, List<Int>>, dtype: String = "F16"): String =
        "{" + (listOf("\"__metadata__\":{\"ss_network_module\":\"networks.lora\"}") +
            t.map { (k, s) -> "\"$k\":{\"dtype\":\"$dtype\",\"shape\":[${s.joinToString(",")}],\"data_offsets\":[0,0]}" }).joinToString(",") + "}"

    private fun file(json: String): ByteArray {
        val b = json.toByteArray()
        return ByteBuffer.allocate(8 + b.size).order(ByteOrder.LITTLE_ENDIAN).putLong(b.size.toLong()).put(b).array()
    }

    @Test fun readsSafetensorsHeader() {
        val j = header("a.lora_down.weight" to listOf(4, 8))
        assertEquals(j, LoraInspect.readHeader(ByteArrayInputStream(file(j))))
        assertNull(LoraInspect.readHeader(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        assertNull(LoraInspect.readHeader(ByteArrayInputStream("GGUF\u0003\u0000\u0000\u0000xxxxxxxx".toByteArray())))
        assertEquals(LoraProblem.NOT_SAFETENSORS, LoraInspect.check(null as String?).problem)
    }

    @Test fun kohyaSd15() {
        val c = LoraInspect.check(header(
            "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_attn2_to_k.lora_down.weight" to listOf(16, 768),
            "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_attn2_to_k.lora_up.weight" to listOf(320, 16),
            "lora_te_text_model_encoder_layers_0_self_attn_q_proj.lora_down.weight" to listOf(16, 768),
        ))
        assertTrue(c.ok)
        assertEquals(LoraFormat.KOHYA, c.format)
        assertEquals(LoraArch.SD1, c.arch)
        assertEquals(16, c.rank)
        assertTrue(c.textEncoderPart)
    }

    @Test fun sdxlByContextWidthAndTe2() {
        assertEquals(LoraArch.SDXL, LoraInspect.check(header(
            "lora_unet_input_blocks_4_1_transformer_blocks_0_attn2_to_k.lora_down.weight" to listOf(8, 2048),
        )).arch)
        assertEquals(LoraArch.SDXL, LoraInspect.check(header(
            "lora_unet_output_blocks_0_1_proj_in.lora_down.weight" to listOf(8, 1280),
            "lora_te2_text_model_encoder_layers_0_mlp_fc1.lora_down.weight" to listOf(8, 1280),
        )).arch)
    }

    @Test fun peftFluxAndAnimaAndZImage() {
        val flux = LoraInspect.check(header(
            "transformer.single_transformer_blocks.37.attn.to_q.lora_A.weight" to listOf(32, 3072),
            "transformer.single_transformer_blocks.37.attn.to_q.lora_B.weight" to listOf(3072, 32),
        ))
        assertEquals(LoraFormat.PEFT, flux.format)
        assertEquals(LoraArch.FLUX1, flux.arch)
        assertEquals(32, flux.rank)
        assertEquals(LoraArch.FLUX_ANY, LoraInspect.check(header("lora_unet_double_blocks_0_img_attn_qkv.lora_down.weight" to listOf(4, 3072))).arch)
        assertEquals(LoraArch.ANIMA, LoraInspect.check(header("diffusion_model.blocks.0.self_attn.q_proj.lora_A.weight" to listOf(16, 2048))).arch)
        assertEquals(LoraArch.ANIMA, LoraInspect.check(header("lora_unet_blocks_3_cross_attn_k_proj.lora_down.weight" to listOf(16, 1024))).arch)
        assertEquals(LoraArch.Z_IMAGE, LoraInspect.check(header("diffusion_model.layers.0.attention.to_q.lora_A.weight" to listOf(16, 3840))).arch)
    }

    @Test fun lycorisFormats() {
        val lokr = LoraInspect.check(header("lycoris_blocks_0_self_attn_q_proj.lokr_w1" to listOf(4, 4), "lycoris_blocks_0_self_attn_q_proj.lokr_w2" to listOf(512, 512)))
        assertEquals(LoraFormat.LOKR, lokr.format)
        assertEquals(LoraArch.ANIMA, lokr.arch)
        val loha = LoraInspect.check(header("lora_unet_down_blocks_1_attentions_0_proj_in.hada_w1_a" to listOf(640, 8), "lora_unet_down_blocks_1_attentions_0_proj_in.hada_w1_b" to listOf(8, 640)))
        assertEquals(LoraFormat.LOHA, loha.format)
        assertEquals(8, loha.rank)
        assertTrue(loha.ok)
    }

    @Test fun problemsAreNamed() {
        assertEquals(LoraProblem.NOT_LORA, LoraInspect.check(header("foo.weight" to listOf(3, 3))).problem)
        val full = (0 until 10).map { "model.diffusion_model.input_blocks.$it.0.weight" to listOf(4, 4) }.toTypedArray()
        assertEquals(LoraProblem.FULL_MODEL, LoraInspect.check(header(*full)).problem)
        assertEquals(LoraProblem.TEXT_ONLY, LoraInspect.check(header("lora_te_text_model_encoder_layers_0_mlp_fc1.lora_down.weight" to listOf(4, 768))).problem)
        assertEquals(LoraProblem.VIDEO, LoraInspect.check(header("diffusion_model.blocks.0.self_attn.q.lora_A.weight" to listOf(16, 5120))).problem)
        assertEquals(LoraProblem.UNKNOWN_ARCH, LoraInspect.check(header("something.lora_A.weight" to listOf(4, 4))).problem)
        assertEquals(LoraProblem.DTYPE, LoraInspect.check(header("a_down_blocks_0_x.lora_down.weight" to listOf(4, 4), dtype = "I8")).problem)
    }

    @Test fun baseArchAndFit() {
        assertEquals(LoraArch.ANIMA, LoraInspect.baseArch(listOf("net.llm_adapter.x.weight")))
        assertEquals(LoraArch.SDXL, LoraInspect.baseArch(listOf("conditioner.embedders.1.model.x")))
        assertEquals(LoraArch.SD1, LoraInspect.baseArch(listOf("cond_stage_model.transformer.text_model.x")))
        assertEquals(LoraArch.Z_IMAGE, LoraInspect.baseArch(listOf("cap_embedder.0.weight")))
        assertEquals(LoraArch.FLUX2, LoraInspect.baseArch(emptyList(), "flux2"))
        assertTrue(LoraArch.FLUX_ANY.fits(LoraArch.FLUX2))
        assertTrue(LoraArch.SDXL.fits(LoraArch.SDXL))
        assertFalse(LoraArch.SD1.fits(LoraArch.SDXL))
        assertFalse(LoraArch.ANIMA.fits(LoraArch.UNKNOWN))
    }

    @Test fun turboAndScale() {
        assertTrue(LoraInspect.isTurbo("anima-turbo-lora.safetensors"))
        assertTrue(LoraInspect.isTurbo("Hyper-SDXL-8steps-lora"))
        assertFalse(LoraInspect.isTurbo("style_watercolor"))
        assertEquals(8, LoraInspect.turboSteps("Hyper-SDXL-8steps-lora"))
        assertEquals(4, LoraInspect.turboSteps("lcm 4 step"))
        assertEquals(8, LoraInspect.turboSteps("turbo"))
        assertEquals(2f, LoraRules.clampScale(5f), 0f)
        assertEquals(0f, LoraRules.clampScale(-1f), 0f)
        assertEquals(1f, LoraRules.clampScale(Float.NaN), 0f)
        assertEquals(0.75f, LoraRules.clampScale(0.76f), 0.001f)
        assertEquals("my_lora_v2.safetensors", LoraRules.safeFileName("my lora v2.safetensors"))
    }

    @Test fun activeLorasOnlyFitting() {
        val l = listOf(
            LoraEntry("a", "a", "a.safetensors", "SDXL", "KOHYA", on = true, scale = 0.8f),
            LoraEntry("b", "b", "b.safetensors", "SD1", "KOHYA", on = true),
            LoraEntry("c", "c", "c.safetensors", "SDXL", "KOHYA", on = false),
            LoraEntry("d", "d", "d.safetensors", "SDXL", "KOHYA", on = true, scale = 0f),
        )
        val u = LoraRules.active(l, LoraArch.SDXL, java.io.File("/x"))
        assertEquals(1, u.size)
        assertEquals("/x/a.safetensors", u[0].path)
        assertEquals(0.8f, u[0].scale, 0.001f)
    }

    @Test fun requestKeepsLorasOnlyForSdCpp() {
        val l = listOf(LoraUse("/x/a.safetensors", 3f))
        val sd = ImageRequest("cat", loras = l).normalize(ImageCatalog.FLUX2_KLEIN)
        assertEquals(1, sd.loras.size)
        assertEquals(2f, sd.loras[0].scale, 0f)
        assertTrue(ImageRequest("cat", loras = l).normalize(ImageCatalog.SD15_QNN).loras.isEmpty())
    }

    @Test fun simpleMapAndX0() {
        // 1 通道、单位映射：z = 0 → 偏置 0 → 中灰；z = 1 → 白
        val m = LatentRgbMap.simple(arrayOf(floatArrayOf(1f, 1f, 1f)), floatArrayOf(0f, 0f, 0f))
        val f = m.render(floatArrayOf(0f, 1f), 1, 2)
        assertEquals(2, f.width); assertEquals(1, f.height)
        assertEquals(0xFF808080.toInt(), f.argb[0])
        assertEquals(0xFFFFFFFF.toInt(), f.argb[1])
        assertEquals(0.5f, LatentPreview.x0Flow(floatArrayOf(1f), floatArrayOf(1f), 0.5f)[0], 0f)
        assertEquals(-1f, LatentPreview.x0Eps(floatArrayOf(1f), floatArrayOf(2f), 1f)[0], 0f)
        // NHWC (1×2×2) → CHW
        assertEquals(listOf(1f, 3f, 2f, 4f), LatentPreview.nhwcToChw(floatArrayOf(1f, 2f, 3f, 4f), 1, 2, 2).toList())
        assertEquals(4, LatentRgbMap.SD1.channels)
    }

    @Test fun hexanimaFormatAndFit() {
        // C=1, B=2, 0 次模糊：W 全 0，偏置 [-1,…] → 黑
        val bb = ByteBuffer.allocate(12 + 4 * 2 * 12).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(2).putInt(0)
        repeat(12) { bb.putFloat(0f) }
        repeat(12) { bb.putFloat(-1f) }
        val m = LatentRgbMap.parse(bb.array())
        assertEquals(2, m.block)
        val f = m.render(floatArrayOf(5f), 1, 1)
        assertEquals(2, f.width)
        assertTrue(f.argb.all { it == 0xFF000000.toInt() })
        val big = PreviewFrame(1024, 512, IntArray(1024 * 512))
        val s = LatentPreview.fit(big, 256)
        assertEquals(256, s.width); assertEquals(128, s.height)
        val rgb = LatentPreview.fromRgb(byteArrayOf(255.toByte(), 0, 0), 1, 1)!!
        assertEquals(0xFFFF0000.toInt(), rgb.argb[0])
        assertNull(LatentPreview.fromRgb(ByteArray(2), 1, 1))
    }

    @Test fun lowMemDefaultByRam() {
        val gb = 1024L * 1024 * 1024
        assertTrue(ImageLowMem.defaultOn(7 * gb + gb / 2))
        assertTrue(ImageLowMem.defaultOn(9 * gb))
        assertFalse(ImageLowMem.defaultOn(11 * gb))
        assertFalse(ImageLowMem.defaultOn(0))
        ImageLowMem.autoOn = true
        assertTrue(ImageLowMem.effective(userSet = false, userValue = false))
        assertFalse(ImageLowMem.effective(userSet = true, userValue = false))
        ImageLowMem.autoOn = false
        assertFalse(com.iris.localai.agent.core.AgentSettings().imageLowMemOn)
    }

    @Test fun hyperFocusPercent() {
        assertEquals(0, HyperFocus.percent(3, 0))
        assertEquals(50, HyperFocus.percent(4, 8))
        assertEquals(100, HyperFocus.percent(9, 8))
    }
}
