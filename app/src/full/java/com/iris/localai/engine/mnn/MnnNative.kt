package com.iris.localai.engine.mnn

import androidx.annotation.Keep

/**
 * Called from native code with `onPiece([B)Z`. Implemented by a named class ([MnnSink]), never a
 * Kotlin lambda: native code resolves the method by name on the object's class (see the 1.1.0
 * sherpa-onnx lesson). Kept by proguard-rules.pro.
 */
@Keep
interface MnnPieceSink {
    /** UTF-8 bytes ending on a code-point boundary. Return false to stop generating. */
    fun onPiece(utf8: ByteArray): Boolean
}

/** JNI surface of libiris_mnn.so (MNN 3.6.1 + LLM, CPU/ARM82 + OpenCL). */
@Keep
object MnnNative {
    /** null when the library is usable, otherwise a user-facing reason. */
    val unavailableReason: String? by lazy {
        try {
            System.loadLibrary("iris_mnn")
            null
        } catch (t: Throwable) {
            "无法加载 MNN 原生库：${t.message ?: t.javaClass.simpleName}"
        }
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    /** 2.0.4: "" when the OpenCL driver opens, otherwise the dlerror() text of every candidate. */
    @JvmStatic external fun nativeOpenClProbe(): String
    /** 1.8.0: directory for iris_mnn_load.log ("" = off); exported by the settings button. */
    @JvmStatic external fun nativeSetDiagDir(dirUtf8: ByteArray)

    @JvmStatic external fun nativeSetNoVisual(enabled: Boolean)
    /** Returns null on success, otherwise an error message. */
    @JvmStatic external fun nativeLoad(
        handle: Long, configPathUtf8: ByteArray, backend: String, threads: Int, nCtx: Int,
        tmpDirUtf8: ByteArray, useMmap: Boolean,
        /** 1.7.4: tier cores (empty = all) + nice for the loading thread; restored after load. */
        cpus: IntArray, nice: Int, applyPolicy: Boolean,
    ): String?
    /** 1.7.5: re-pin MNN's existing threads; JSON {targets, affinityOk, niceOk, gone, generating, coresWidened, readback}. */
    @JvmStatic external fun nativeApplyLive(handle: Long, cpus: IntArray, nice: Int): String
    /** 1.7.5: pool worker tids still alive + the last generating thread. */
    @JvmStatic external fun nativeThreadIds(handle: Long): IntArray
    @JvmStatic external fun nativeUnload(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeClearCancel(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeMeta(handle: Long): String
    /**
     * floats = [temperature, topP, minP, repeatPenalty];
     * ints = [topK, maxNewTokens, disableThinking, nice, applyPolicy (1/0)]; cpus = tier cores (empty = all).
     */
    @JvmStatic external fun nativeGenerate(
        handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>,
        floats: FloatArray, ints: IntArray, cpus: IntArray, sink: MnnPieceSink?,
    ): String
}
