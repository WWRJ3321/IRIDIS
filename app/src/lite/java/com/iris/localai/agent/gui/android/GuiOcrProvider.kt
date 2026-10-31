package com.iris.localai.agent.gui.android

import android.content.Context
import com.iris.localai.agent.gui.GuiOcr

/** 2.2.3: lite flavour has no OCR (no ML Kit); the GUI agent uses the accessibility tree only. */
object GuiOcrProvider {
    fun get(context: Context): GuiOcr? = null
}
