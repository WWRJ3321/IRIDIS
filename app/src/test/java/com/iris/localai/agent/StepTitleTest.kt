package com.iris.localai.agent

import com.iris.localai.agent.core.StepTitle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** 2.1.0: step card headers for people, one mapping with a label fallback. */
class StepTitleTest {
    private fun t(tool: String, vararg kv: Pair<String, String>) = StepTitle.of(tool, buildJsonObject { kv.forEach { put(it.first, it.second) } })


    @Test fun noKeyValueAndFallback() {
        assertFalse("=" in t("web_search", "query" to "a"))
        assertEquals("计算器", t("calculator"))
        assertEquals("读取文件", t("read_file"))
        assertEquals("some_tool", t("some_tool", "x" to "1"))
        assertTrue(t("web_search", "query" to "很".repeat(100)).length <= 44)
    }
}
