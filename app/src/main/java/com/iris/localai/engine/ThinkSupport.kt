package com.iris.localai.engine

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File

/**
 * 2.2.2: whether a model can think is read from its chat template, not guessed from its name
 * (Gemma 4's `<|think|>` / `<|channel>` and `enable_thinking` switches were missed before).
 */
object ThinkSupport {
    private val MARKERS = listOf("<think>", "enable_thinking", "<|think|>", "<|channel>", "reasoning_content")

    /**
     * 2.2.8: the templated prompt already opens a reasoning block (`…<think>\n` with no close),
     * so the model's reply starts mid-thinking and only ever writes `</think>`.
     */
    fun promptEndsWithOpenThink(prompt: String?): Boolean {
        if (prompt.isNullOrEmpty()) return false
        val open = prompt.lastIndexOf("<think>")
        return open >= 0 && prompt.indexOf("</think>", open) < 0 && prompt.substring(open + 7).isBlank()
    }

    fun templateThinks(template: String?): Boolean = template != null && MARKERS.any { it in template }

    /** Template of [model] (a .gguf file, or a bundle folder with json / jinja files), or null. */
    fun templateOf(model: File): String? = runCatching {
        when {
            model.isFile && model.name.endsWith(".gguf", true) -> ggufTemplate(model)
            model.isDirectory -> model.listFiles().orEmpty()
                .filter { it.isFile && it.length() < 4_000_000 && (it.name.endsWith(".json") || it.name.endsWith(".jinja")) }
                .joinToString("\n") { it.readText() }.ifEmpty { null }
            else -> null
        }
    }.getOrNull()

    /** Template first; the old name rule only when the file carries no template at all. */
    fun supports(model: File, nameRule: (String) -> Boolean): Boolean {
        val t = templateOf(model)
        return if (t != null) templateThinks(t) else nameRule(model.name)
    }

    /** Reads `tokenizer.chat_template` from a GGUF header without loading the model. */
    fun ggufTemplate(f: File): String? = DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { inp ->
        fun u32(): Long = Integer.reverseBytes(inp.readInt()).toLong() and 0xffffffffL
        fun u64(): Long = java.lang.Long.reverseBytes(inp.readLong())
        fun str(): String { val n = u64().toInt(); val b = ByteArray(n); inp.readFully(b); return String(b, Charsets.UTF_8) }
        fun skipN(n: Long) { var left = n; while (left > 0) { val s = inp.skip(left); if (s <= 0) { inp.readByte(); left-- } else left -= s } }
        fun skip(type: Long) {
            when (type.toInt()) {
                0, 1, 7 -> skipN(1); 2, 3 -> skipN(2); 4, 5, 6 -> skipN(4); 10, 11, 12 -> skipN(8)
                8 -> skipN(u64())
                9 -> { val t = u32(); val n = u64(); val w = when (t.toInt()) { 0, 1, 7 -> 1L; 2, 3 -> 2L; 4, 5, 6 -> 4L; 10, 11, 12 -> 8L; else -> 0L }
                    if (w > 0) skipN(w * n) else for (i in 0 until n) skip(t) }
                else -> throw IllegalStateException("gguf type $type")
            }
        }
        if (inp.readInt() != 0x47475546) return@use null // "GGUF"
        val version = u32()
        if (version < 2) return@use null
        u64() // tensors
        val kvs = u64()
        for (i in 0 until kvs) {
            val key = str()
            val type = u32()
            if (key == "tokenizer.chat_template" && type == 8L) return@use str()
            skip(type)
        }
        null
    }
}
