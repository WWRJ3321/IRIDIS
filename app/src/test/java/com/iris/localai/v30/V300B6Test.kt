package com.iris.localai.v30

import com.iris.localai.data.HomeItem
import com.iris.localai.data.HomeLayout
import com.iris.localai.data.HomeLayout.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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

    /** r1 bug: moving one step aside did nothing on the home page. Now every step reorders. */
    @Test fun oneStepAsideReorders() {
        val a = HomeLayout.DEFAULT.move(HomeItem.AGENT, Row.UPPER, false, 1)
        assertNotSame(HomeLayout.DEFAULT, a)
        assertEquals(listOf(HomeItem.SKILLS, HomeItem.AGENT), a.start(Row.UPPER))
        val b = HomeLayout.DEFAULT.move(HomeItem.CHARACTER, Row.TOP, true, 0)
        assertEquals(listOf(HomeItem.MENU, HomeItem.MODEL), b.start(Row.TOP))
        assertEquals(listOf(HomeItem.CHARACTER, HomeItem.NEW_CHAT), b.end(Row.TOP))
        assertEquals(b, HomeLayout.parse(b.encode()))
    }

    @Test fun acrossRowsAndCap() {
        val a = HomeLayout.DEFAULT.move(HomeItem.VOICE, Row.UPPER, true, 0)
        assertEquals(listOf(HomeItem.VOICE, HomeItem.ATTACH), a.end(Row.UPPER))
        assertTrue(a.end(Row.LOWER).isEmpty())
        assertSame(a, a.move(HomeItem.THINK, Row.UPPER, false, 0)) // 5th in a row → refused
    }

    @Test fun groupsStaySeparate() {
        val d = HomeLayout.DEFAULT
        assertSame(d, d.move(HomeItem.MENU, Row.UPPER, false, 0))
        assertSame(d, d.move(HomeItem.VOICE, Row.TOP, true, 0))
        assertSame(d, d.move(HomeItem.AGENT, Row.UPPER, false, 0))
    }

    @Test fun parseRepairs() {
        assertSame(HomeLayout.DEFAULT, HomeLayout.parse("MENU@TOP:5,NEW_CHAT@TOP:5")) // r1 format → default
        val l = HomeLayout.parse("v2|TOP:NEW_CHAT.MENU.NEW_CHAT/VOICE|UPPER:AGENT.SKILLS.ATTACH.THINK.SPEED/|bad")
        val all = Row.entries.flatMap { l.start(it) + l.end(it) }
        assertEquals(HomeItem.entries.size, all.size)
        assertEquals(HomeItem.entries.toSet(), all.toSet())
        assertEquals(HomeItem.NEW_CHAT, l.start(Row.TOP).first()); assertTrue(HomeItem.MENU in l.start(Row.TOP))
        assertTrue(Row.entries.all { r -> (l.start(r) + l.end(r)).all { it.group == r.group } })
        assertTrue(l.count(Row.UPPER) <= HomeLayout.MAX_COMPOSER)
    }
}
