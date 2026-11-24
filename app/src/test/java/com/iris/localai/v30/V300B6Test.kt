package com.iris.localai.v30

import com.iris.localai.data.HomeItem
import com.iris.localai.data.HomeLayout
import com.iris.localai.data.HomeLayout.Row
import com.iris.localai.data.HomeLayout.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class V300B6Test {
    @Test fun defaultMatchesBeta5() {
        val d = HomeLayout.DEFAULT
        assertEquals(listOf(HomeItem.MENU, HomeItem.MODEL, HomeItem.CHARACTER), d.start(Row.TOP))
        assertEquals(listOf(HomeItem.NEW_CHAT), d.end(Row.TOP))
        assertEquals(listOf(HomeItem.AGENT, HomeItem.SKILLS), d.start(Row.UPPER))
        assertEquals(listOf(HomeItem.ATTACH), d.end(Row.UPPER))
        assertEquals(listOf(HomeItem.THINK, HomeItem.SPEED), d.start(Row.LOWER))
        assertEquals(listOf(HomeItem.VOICE), d.end(Row.LOWER))
        assertEquals("", d.encode())
        assertSame(HomeLayout.DEFAULT, HomeLayout.parse(""))
    }

    @Test fun moveToEmptyAndSwap() {
        val a = HomeLayout.DEFAULT.move(HomeItem.VOICE, Slot(Row.UPPER, 4))
        assertEquals(listOf(HomeItem.VOICE, HomeItem.ATTACH), a.end(Row.UPPER))
        assertTrue(a.end(Row.LOWER).isEmpty())
        val b = a.move(HomeItem.THINK, Slot(Row.UPPER, 0)) // onto 智能体 → swap
        assertEquals(Slot(Row.UPPER, 0), b.slotOf(HomeItem.THINK))
        assertEquals(Slot(Row.LOWER, 0), b.slotOf(HomeItem.AGENT))
        assertEquals(b, HomeLayout.parse(b.encode()))
    }

    @Test fun groupsStaySeparate() {
        val d = HomeLayout.DEFAULT
        assertSame(d, d.move(HomeItem.MENU, Slot(Row.UPPER, 3)))
        assertSame(d, d.move(HomeItem.VOICE, Slot(Row.TOP, 3)))
        assertSame(d, d.move(HomeItem.VOICE, Slot(Row.LOWER, 9)))
    }

    @Test fun parseRepairs() {
        // junk, a wrong group, a duplicate slot and missing items → every item placed once
        val l = HomeLayout.parse("MENU@TOP:5,NEW_CHAT@TOP:5,VOICE@TOP:1,THINK@UPPER:0,bad,AGENT@LOWER:x")
        assertEquals(HomeItem.entries.size, l.slots.size)
        assertEquals(HomeItem.entries.size, l.slots.values.toSet().size)
        assertEquals(Slot(Row.TOP, 5), l.slotOf(HomeItem.MENU))
        assertEquals(Slot(Row.UPPER, 0), l.slotOf(HomeItem.THINK))
        assertTrue(HomeItem.entries.all { l.slotOf(it).row.group == it.group })
    }
}
