package com.iris.localai.engine.edge0

/** JNI of libiris_edge0.so (app/src/main/cpp/edge0/edge0_jni.cpp). Load only after [Edge0Support.deviceOk]. */
object Edge0Native {
    fun interface Events { fun onEvent(kind: Int, index: Int, text: ByteArray) }

    const val ANSWER = 0
    const val REASONING = 1
    const val TOOL_BEGIN = 2
    const val TOOL_ARGS = 3
    const val TOOL_END = 4
    const val TOOL_REJECTED = 5

    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("iris_edge0") }.isSuccess }
    fun ensureLoaded(): Boolean = loaded

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeInspect(dir: String): String
    @JvmStatic external fun nativeLoad(handle: Long, dir: String, context: Long, threads: Int): String
    @JvmStatic external fun nativeGenerate(
        handle: Long, inputJson: String, thinking: Boolean, maxTokens: Int,
        temperature: Double, topP: Double, topK: Int, events: Events,
    ): String
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeUnload(handle: Long)
    @JvmStatic external fun nativeClose(handle: Long)
}
