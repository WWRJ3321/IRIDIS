package com.iris.localai.engine

/** 2.5.0-beta9: the model list's meta line gets 「MTP」 only for GGUF files whose header has MTP layers. */
object MtpTag {
    const val LABEL = "MTP"

    /** [engineTag] as before (null = the format label); with MTP it becomes "<tag> · MTP". */
    /** 2.5.0-beta10: the switch subtitle adds 「GPU 上为实验…」 when the model is set to, or loaded on, the GPU. */
    fun gpuNote(chosen: Backend?, loadedBackend: String?): Boolean =
        chosen == Backend.GPU || (chosen == null && loadedBackend?.startsWith("GPU") == true)

    fun join(engineTag: String?, formatLabel: String, mtp: Boolean): String? =
        if (!mtp) engineTag else "${engineTag ?: formatLabel} · $LABEL"
}
