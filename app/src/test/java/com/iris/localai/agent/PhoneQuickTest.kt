package com.iris.localai.agent

import com.iris.localai.agent.tools.PhoneQuick
import com.iris.localai.agent.tools.PhoneQuick.Act
import com.iris.localai.ui.imagegen.DlSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneQuickTest {
    @Test fun torch() {
        assertEquals(Act.Torch("on"), PhoneQuick.parse("打开手电筒"))
        assertEquals(Act.Torch("on"), PhoneQuick.parse("帮我开一下手电筒吧"))
        assertEquals(Act.Torch("on"), PhoneQuick.parse("手电筒打开"))
        assertEquals(Act.Torch("off"), PhoneQuick.parse("关掉手电筒"))
        assertEquals(Act.Torch("off"), PhoneQuick.parse("把闪光灯关了"))
        assertEquals(Act.Torch("toggle"), PhoneQuick.parse("手电筒"))
    }

    @Test fun levels() {
        assertEquals(Act.Brightness(100), PhoneQuick.parse("把亮度调到最高"))
        assertEquals(Act.Brightness(30), PhoneQuick.parse("亮度调到30%"))
        assertEquals(Act.Volume(0), PhoneQuick.parse("音量调到最小"))
        assertEquals(Act.Ringer("silent"), PhoneQuick.parse("静音"))
        assertEquals(Act.Ringer("vibrate"), PhoneQuick.parse("切换到振动模式"))
        assertEquals(Act.Ringer("normal"), PhoneQuick.parse("关闭静音"))
    }

    @Test fun notQuick() {
        for (t in listOf("手电筒怎么用", "亮度对眼睛有什么影响", "打开设置把亮度调最高", "亮度调到200", "写一首关于手电筒的诗")) assertNull(t, PhoneQuick.parse(t))
    }

    @Test fun dlSize() {
        assertEquals("54 / 68 MB", DlSize.pair(54L shl 20, 68L shl 20))
        assertEquals("0.50 / 1.39 GB", DlSize.pair(512L shl 20, 1494940128L))
    }
}
