package com.iris.localai.engine

/** 2.5.0-beta9: the model list's meta line gets 「MTP」 only for GGUF files whose header has MTP layers. */
object MtpTag {
    const val LABEL = "MTP"

    /** [engineTag] as before (null = the format label); with MTP it becomes "<tag> · MTP". */
    fun join(engineTag: String?, formatLabel: String, mtp: Boolean): String? =
        if (!mtp) engineTag else "${engineTag ?: formatLabel} · $LABEL"
}
