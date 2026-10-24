package com.iris.localai.engine

/**
 * Whether an MNN CPU load may pass `enable_kleidiai=1`.
 *
 * MNN 3.6.1 (KleidiAI 1.14) runs an SVE instruction inside `KleidiAI::initKernelInfo` while
 * `Llm::load` builds the session. On Oryon (Snapdragon 8 Elite / 8 Elite Gen 5) that instruction
 * is illegal and the process dies inside `MnnNative_nativeLoad` (alibaba/MNN#4431). Those SoCs
 * stay on MNN's own ARM kernels. A previous `nativeLoad` death turns KleidiAI off on any SoC for
 * the next attempt.
 */
object MnnKleidiPolicy {
    private val ORYON = setOf("sm8750", "sm8850")

    fun enabled(socModel: String?, lastNativeLoadCrashed: Boolean = false): Boolean {
        if (lastNativeLoadCrashed) return false
        val id = NpuIds.baseSocId(socModel) ?: return true
        return id !in ORYON
    }
}
