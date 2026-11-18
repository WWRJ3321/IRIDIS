package com.iris.localai.agent

import com.iris.localai.agent.web.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSearchUrlTest {
    @Test fun urlQueriesBecomeFetches() {
        assertEquals("https://github.com/WWRJ3321/IRISMusic/tree/main/docs", SearchText.asUrl("  https://github.com/WWRJ3321/IRISMusic/tree/main/docs \n"))
        assertEquals("https://github.com/WWRJ3321/IRISMusic", SearchText.asUrl("github.com/WWRJ3321/IRISMusic"))
        assertEquals("http://example.com", SearchText.asUrl("\"http://example.com\""))
        assertEquals("https://www.bing.com", SearchText.asUrl("www.bing.com"))
    }

    @Test fun ordinaryQueriesStaySearches() {
        assertNull(SearchText.asUrl("香港 天气"))
        assertNull(SearchText.asUrl("node.js"))
        assertNull(SearchText.asUrl("IRIDIS v2.7.0"))
        assertNull(SearchText.asUrl("github.com 是什么"))
        assertNull(SearchText.asUrl("1/2"))
    }
}
