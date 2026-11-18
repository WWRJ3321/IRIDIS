package com.iris.localai.imagegen

import com.iris.localai.imagegen.Family
import com.iris.localai.imagegen.FileHead
import com.iris.localai.imagegen.FileRole
import com.iris.localai.imagegen.ImportDetect
import com.iris.localai.imagegen.ImportedImage
import com.iris.localai.imagegen.ImportedImages
import com.iris.localai.imagegen.SdFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class GeneralImportTest {
    private fun st(vararg keys: String): ByteArray {
        val js = keys.joinToString(",", "{", "}") { "\"$it\":{\"dtype\":\"F16\",\"shape\":[1],\"data_offsets\":[0,2]}" }.toByteArray()
        val o = ByteArrayOutputStream()
        var n = js.size.toLong(); repeat(8) { o.write((n and 0xFF).toInt()); n = n shr 8 }
        o.write(js); o.write(ByteArray(2)); return o.toByteArray()
    }

    private fun gguf(arch: String?, vararg tensors: String): ByteArray {
        val o = ByteArrayOutputStream()
        fun u32(v: Long) { var x = v; repeat(4) { o.write((x and 0xFF).toInt()); x = x shr 8 } }
        fun u64(v: Long) { var x = v; repeat(8) { o.write((x and 0xFF).toInt()); x = x shr 8 } }
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); o.write(b) }
        u32(0x46554747L); u32(3); u64(tensors.size.toLong()); u64(if (arch != null) 2 else 1)
        str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(2); str("a"); str("b")
        if (arch != null) { str("general.architecture"); u32(8); str(arch) }
        for (t in tensors) { str(t); u32(2); u64(4); u64(4); u32(1); u64(0) }
        return o.toByteArray()
    }

    private fun role(name: String, bytes: ByteArray) = ImportDetect.role(name, ImportDetect.readHead(name, ByteArrayInputStream(bytes)))

    @Test fun rolesFromHeaders() {
        assertEquals(FileRole.CHECKPOINT, role("a.safetensors", st("model.diffusion_model.input_blocks.0.0.weight", "cond_stage_model.transformer.text_model.embeddings.position_ids", "first_stage_model.decoder.conv_in.weight")))
        assertEquals(FileRole.CHECKPOINT, role("x.safetensors", st("model.diffusion_model.input_blocks.0.0.weight", "conditioner.embedders.1.model.ln_final.weight")))
        assertEquals(FileRole.DIFFUSION, role("f.safetensors", st("double_blocks.0.img_attn.qkv.weight", "img_in.weight")))
        assertEquals(FileRole.DIFFUSION, role("z.gguf", gguf("z_image", "cap_embedder.0.weight", "layers.0.attention.qkv.weight")))
        assertEquals(FileRole.LLM, role("q.gguf", gguf("qwen3", "token_embd.weight", "blk.0.attn_q.weight")))
        assertEquals(FileRole.LLM, role("q.safetensors", st("model.embed_tokens.weight", "model.layers.0.self_attn.q_proj.weight")))
        assertEquals(FileRole.T5, role("t5.safetensors", st("encoder.block.0.layer.0.SelfAttention.q.weight", "shared.weight")))
        assertEquals(FileRole.T5, role("t5.gguf", gguf("t5encoder", "enc.blk.0.attn_q.weight", "token_embd.weight")))
        assertEquals(FileRole.CLIP_L, role("l.safetensors", st("text_model.encoder.layers.11.mlp.fc1.weight")))
        assertEquals(FileRole.CLIP_G, role("g.safetensors", st("text_model.encoder.layers.31.mlp.fc1.weight")))
        assertEquals(FileRole.VAE, role("ae.safetensors", st("decoder.conv_in.weight", "encoder.down.0.block.0.conv1.weight")))
        assertEquals(FileRole.NPU, ImportDetect.role("text.bin", null))
        assertEquals(FileRole.UNKNOWN, role("x.safetensors", st("foo.bar")))
        assertEquals(FileRole.UNKNOWN, ImportDetect.role("x.ckpt", null))
    }

    @Test fun familyAndDefaults() {
        assertEquals(Family.SD_CHECKPOINT, ImportDetect.family(FileRole.CHECKPOINT, null))
        assertEquals(Family.ANIMA, ImportDetect.family(FileRole.DIFFUSION, FileHead(listOf("net.llm_adapter.x"))))
        assertEquals(Family.Z_IMAGE, ImportDetect.family(FileRole.DIFFUSION, FileHead(listOf("cap_embedder.0.weight"))))
        assertEquals(20, ImportDetect.defaults(Family.SD_CHECKPOINT, "realistic.safetensors").steps)
        assertEquals(6, ImportDetect.defaults(Family.SD_CHECKPOINT, "sdxl_lightning_4step.safetensors").steps)
        assertTrue(ImportDetect.defaults(Family.SD_CHECKPOINT, "illustriousXL.safetensors").tagStyle)
        assertTrue(ImportDetect.tooBig(10_000_000_000, 8_000_000_000))
        assertFalse(ImportDetect.tooBig(4_000_000_000, 12_000_000_000))
    }

    @Test fun manifestKeepsOldAndNewEntries() {
        val old = ImportedImage("imp-00000001", "a", "ANIMA", "imp-00000001/a.safetensors", "s/q.gguf", "s/v.safetensors", 30, 4f)
        val ckpt = ImportedImage("imp-00000002", "b", ImportedImages.AUTO, "", "", "", 20, 7f, model = "imp-00000002/b.safetensors", tagStyle = true)
        val l = ImportedImages.decode(ImportedImages.encode(listOf(old, ckpt)))
        assertEquals(2, l.size)
        assertEquals(SdFiles("", "", "", model = "imp-00000002/b.safetensors"), l[1].toModel().sdFiles)
        assertTrue(l[1].toModel().sdFiles!!.extended)
        assertFalse(l[0].toModel().sdFiles!!.extended)
        assertTrue(l[1].toModel().tagStyle)
        assertEquals("", l[1].toModel().note)
    }

}
