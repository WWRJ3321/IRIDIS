package com.iris.localai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 侧栏对话拖动排序（2.7.0-beta3）。 */
class ConversationOrderTest {
    private fun c(id: String, at: Long, pinned: Boolean = false) = ConversationSummary(id, id, at, pinned)
    private val list = listOf(c("a", 10), c("b", 30), c("p", 5, pinned = true), c("c", 20))

    @Test fun noOrderIsRecentFirst() {
        assertEquals(listOf("p", "b", "c", "a"), ConversationOrder.sort(list, emptyList()).map { it.id })
    }

    @Test fun customOrderKeptAndNewChatsOnTop() {
        val sorted = ConversationOrder.sort(list + c("new", 1), listOf("p", "a", "c", "b"))
        assertEquals(listOf("p", "new", "a", "c", "b"), sorted.map { it.id })
        // a later update does not move a chat out of its place
        val touched = ConversationOrder.sort(list.map { if (it.id == "a") it.copy(updatedAt = 999) else it }, listOf("p", "c", "a", "b"))
        assertEquals(listOf("p", "c", "a", "b"), touched.map { it.id })
    }

    @Test fun moveStaysInGroup() {
        val shown = ConversationOrder.sort(list, emptyList())
        assertEquals(listOf("p", "a", "b", "c"), ConversationOrder.move(shown, "a", "b").map { it.id })
        assertEquals(listOf("p", "c", "a", "b"), ConversationOrder.move(ConversationOrder.move(shown, "a", "b"), "c", "a").map { it.id })
        assertSame(shown, ConversationOrder.move(shown, "a", "p"))
        assertSame(shown, ConversationOrder.move(shown, "x", "a"))
    }

    @Test fun pinnedStillFirst() {
        val l = list + c("q", 1, pinned = true)
        assertEquals(listOf("q", "p"), ConversationOrder.sort(l, listOf("a", "q", "p")).take(2).map { it.id })
    }
}
