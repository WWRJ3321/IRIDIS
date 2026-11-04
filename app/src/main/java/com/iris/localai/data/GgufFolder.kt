package com.iris.localai.data

/**
 * 2.3.5 (#8): a picked folder that holds GGUF files (e.g. LFM3-Omni / WeVisOne-VL = main GGUF + mmproj GGUF).
 * Pure: names (+ which ones a header sniff flagged as CLIP projectors) → what to import and how to link. Unit-tested.
 */
object GgufFolder {
    data class Plan(
        /** top-level GGUF files to copy (every shard of a split model included), each with its target file name. */
        val copy: Map<String, String>,
        /** target names of loadable main models (first shard of split models). */
        val mains: List<String>,
        /** target names of projectors (always contain "mmproj" so they are never listed as models). */
        val projectors: List<String>,
        /** main target → projector target to link via ModelOverrides.mmproj. */
        val pairs: Map<String, String>,
    )

    private val SPLIT = Regex("-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

    fun isGguf(name: String) = name.lowercase().endsWith(".gguf")

    /** 2.3.5: folder name for a GGUF model: main file name without split suffix / .gguf. */
    fun folderFor(mainName: String): String =
        mainName.replace(SPLIT, ".gguf").let { if (isGguf(it)) it.dropLast(5) else it }.ifBlank { "model" }

    /** 2.3.5: a model folder's loadable mains (first shards), or empty when it is not a GGUF folder. */
    fun mainsIn(names: Collection<String>): List<String> = classify(names)?.mains.orEmpty()

    /** True for later shards (-00002-of-…) of a split GGUF. */
    fun isSplitTail(name: String): Boolean = SPLIT.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 1 } == true

    /** GGUF header (first KBs) of a vision projector: clip.* keys or general.type = "mmproj". */
    fun headerIsProjector(head: ByteArray): Boolean {
        val s = String(head, Charsets.ISO_8859_1)
        if (!s.startsWith("GGUF")) return false
        if ("clip.vision." in s || "clip.has_vision_encoder" in s || "clip.projector_type" in s) return true
        val i = s.indexOf("general.type")
        return i >= 0 && "mmproj" in s.substring(i, minOf(s.length, i + 40))
    }

    /** null = no loadable main GGUF in the folder. */
    fun classify(names: Collection<String>, headerProjectors: Set<String> = emptySet()): Plan? {
        val ggufs = names.filter { isGguf(it) && '/' !in it }.distinct().sorted()
        val copy = LinkedHashMap<String, String>()
        val projectors = ArrayList<String>()
        val mains = ArrayList<String>()
        for (n in ggufs) {
            val proj = isProjectorFile(n) || n in headerProjectors
            val target = if (proj && !isProjectorFile(n)) "mmproj-$n" else n
            copy[n] = target
            if (proj) projectors += target
            else {
                val part = SPLIT.find(n)?.groupValues?.get(1)?.toIntOrNull()
                if (part == null || part == 1) mains += target
            }
        }
        if (mains.isEmpty()) return null
        val pairs = LinkedHashMap<String, String>()
        for (m in mains) {
            val p = if (projectors.size == 1) projectors[0]
            else com.iris.localai.engine.llama.MmprojPairing.pick(m, projectors)
            if (p != null) pairs[m] = p
        }
        return Plan(copy, mains, projectors, pairs)
    }
}
