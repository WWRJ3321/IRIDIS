package com.iris.localai.util.streaming

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.thinking.*

/** how hard 停止 tries before it ends the request itself (≈1.5 s). */
object StopPolicy {
    const val RETRIES = 5
    const val RETRY_MS = 300L
}
