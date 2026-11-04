package com.iris.localai.imagegen

data class ProbeFacts(
    val soc: String,
    val htpArch: Int?,
    val qnnRuntimePresent: Boolean,
    val qnnBackendOk: Boolean,
    val cdspPresent: Boolean,
    val unsignedPd: Boolean,
    val freeRamMb: Long,
    val downloaded: Set<String>,
)

data class TierVerdict(val modelId: String, val ok: Boolean, val reasonZh: String)

object SelfCheck {
    const val QNN_MIN_HTP = 73
    const val QNN_MIN_RAM_MB = 2500L
    const val MNN_MIN_RAM_MB = 3000L
    /** 2.4.0: weights are mmapped, so this is the working set (~4B DiT Q4 + encoder pass + VAE), not the download size. */
    const val SDCPP_MIN_RAM_MB = 4500L
    val HEXAGON_PENDING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_1b8a07)

    fun verdicts(facts: ProbeFacts, models: List<ImageModel> = ImageCatalog.all): List<TierVerdict> =
        models.map { verdict(facts, it) }

    fun verdict(f: ProbeFacts, m: ImageModel): TierVerdict {
        val problems = ArrayList<String>()
        when (m.backend) {
            Backend.QNN -> {
                val minArch = maxOf(QNN_MIN_HTP, m.minHtpArch)
                if ((f.htpArch ?: 0) < minArch) problems.add("NPU 架构不支持（需 HTP v$minArch+，当前 ${f.htpArch?.let { "v$it" } ?: "未知"}）")
                if (!f.qnnRuntimePresent) problems.add(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_aa3110))
                if (!f.qnnBackendOk) problems.add(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_135123))
                if (!f.unsignedPd) problems.add(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_2a754e))
                if (f.freeRamMb < QNN_MIN_RAM_MB) problems.add(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_8812a7, QNN_MIN_RAM_MB, f.freeRamMb))
            }
            Backend.MNN_CPU -> {
                if (f.freeRamMb < MNN_MIN_RAM_MB) problems.add(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_8812a7, MNN_MIN_RAM_MB, f.freeRamMb))
            }
            Backend.HEXAGON -> problems.add(HEXAGON_PENDING)
            Backend.SDCPP -> {
                if (f.freeRamMb < SDCPP_MIN_RAM_MB) problems.add(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_8812a7, SDCPP_MIN_RAM_MB, f.freeRamMb))
            }
        }
        val ok = problems.isEmpty()
        val parts = ArrayList<String>()
        parts.add(if (ok) com.iris.localai.I18n.s(com.iris.localai.R.string.engine_ad6b70) else problems.joinToString(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c4530d)))
        if (ok && m.id !in f.downloaded) parts.add(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_336189))
        return TierVerdict(m.id, ok, parts.joinToString(com.iris.localai.I18n.s(com.iris.localai.R.string.files_d3b98f)))
    }

    fun report(facts: ProbeFacts, verdicts: List<TierVerdict>): String = buildString {
        fun yn(b: Boolean) = if (b) com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_0a60ac) else com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_c9744f)
        appendLine(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_04ac56))
        appendLine(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_60f471, facts.soc))
        appendLine("HTP 架构：${facts.htpArch?.let { "v$it" } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1622dc)}")
        appendLine(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_7777a5, yn(facts.qnnRuntimePresent)))
        appendLine(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_c2180a, if (facts.qnnBackendOk) com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_fd6e80) else com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_acd5cb)))
        appendLine(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_05b9ee, yn(facts.cdspPresent)))
        appendLine(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_b88c6f, yn(facts.unsignedPd)))
        appendLine(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_f97000, facts.freeRamMb))
        appendLine(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_a639d3))
        for (v in verdicts) {
            val name = ImageCatalog.byId(v.modelId)?.name ?: v.modelId
            appendLine("${if (v.ok) "✓" else "✗"} $name（${v.modelId}）：${v.reasonZh}")
        }
    }.trimEnd()
}
