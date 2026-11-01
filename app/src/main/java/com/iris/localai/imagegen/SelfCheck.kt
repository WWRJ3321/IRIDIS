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
    const val HEXAGON_PENDING = "需要 Hexagon 运行库（待发布）"

    fun verdicts(facts: ProbeFacts, models: List<ImageModel> = ImageCatalog.all): List<TierVerdict> =
        models.map { verdict(facts, it) }

    fun verdict(f: ProbeFacts, m: ImageModel): TierVerdict {
        val problems = ArrayList<String>()
        when (m.backend) {
            Backend.QNN -> {
                val minArch = maxOf(QNN_MIN_HTP, m.minHtpArch)
                if ((f.htpArch ?: 0) < minArch) problems.add("NPU 架构不支持（需 HTP v$minArch+，当前 ${f.htpArch?.let { "v$it" } ?: "未知"}）")
                if (!f.qnnRuntimePresent) problems.add("缺少 QNN 运行库")
                if (!f.qnnBackendOk) problems.add("QNN 后端初始化失败")
                if (!f.unsignedPd) problems.add("不支持 unsigned PD")
                if (f.freeRamMb < QNN_MIN_RAM_MB) problems.add("可用内存不足（需 ${QNN_MIN_RAM_MB}MB，当前 ${f.freeRamMb}MB）")
            }
            Backend.MNN_CPU -> {
                if (f.freeRamMb < MNN_MIN_RAM_MB) problems.add("可用内存不足（需 ${MNN_MIN_RAM_MB}MB，当前 ${f.freeRamMb}MB）")
            }
            Backend.HEXAGON -> problems.add(HEXAGON_PENDING)
        }
        val ok = problems.isEmpty()
        val parts = ArrayList<String>()
        parts.add(if (ok) "可用" else problems.joinToString("；"))
        if (ok && m.id !in f.downloaded) parts.add("未下载")
        return TierVerdict(m.id, ok, parts.joinToString("，"))
    }

    fun report(facts: ProbeFacts, verdicts: List<TierVerdict>): String = buildString {
        fun yn(b: Boolean) = if (b) "是" else "否"
        appendLine("【IRIDIS 图像生成自检】")
        appendLine("芯片：${facts.soc}")
        appendLine("HTP 架构：${facts.htpArch?.let { "v$it" } ?: "未知"}")
        appendLine("QNN 运行库：${yn(facts.qnnRuntimePresent)}")
        appendLine("QNN 后端：${if (facts.qnnBackendOk) "正常" else "失败"}")
        appendLine("CDSP：${yn(facts.cdspPresent)}")
        appendLine("Unsigned PD：${yn(facts.unsignedPd)}")
        appendLine("可用内存：${facts.freeRamMb} MB")
        appendLine("—— 模型 ——")
        for (v in verdicts) {
            val name = ImageCatalog.byId(v.modelId)?.name ?: v.modelId
            appendLine("${if (v.ok) "✓" else "✗"} $name（${v.modelId}）：${v.reasonZh}")
        }
    }.trimEnd()
}
