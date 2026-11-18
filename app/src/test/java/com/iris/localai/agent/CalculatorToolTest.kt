package com.iris.localai.agent

import com.iris.localai.agent.core.ParsedReply
import com.iris.localai.agent.core.ToolCallParser
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.tools.Calc
import com.iris.localai.agent.tools.CalculatorTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CalculatorToolTest {
    private fun ev(s: String) = Calc.eval(s)
    private fun near(expected: Double, s: String) = assertEquals(s, expected, ev(s), 1e-9)

    @Test fun arithmeticAndPrecedence() {
        near(14.0, "2+3*4")
        near(20.0, "(2+3)*4")
        near(2.5, "5/2")
        near(1.0, "10 % 3")
        near(-7.0, "-3-4")
        near(1024.0, "2^10")
        near(1024.0, "2**10")
        near(2.0.let { Math.pow(it, 9.0) }, "2^3^2") // right associative
        near(-4.0, "-2^2")
        near(0.3, "0.1+0.2")
        near(1.5e5, "1.5e5")
        near(120.0, "5!")
    }

    @Test fun functionsAndConstants() {
        near(4.0, "sqrt(16)")
        near(4.0, "sqrt 16")
        near(0.0, "sin(0)")
        near(1.0, "cos(0)")
        near(1.0, "tan(pi/4)")
        near(2.0, "log(100)")
        near(3.0, "log(8, 2)")
        near(1.0, "ln(e)")
        near(5.0, "abs(-5)")
        near(8.0, "pow(2, 3)")
        near(2 * Math.PI, "2pi")
        near(Math.PI, "π")
        near(21.0, "(1+2)(3+4)")
    }

    @Test fun fullWidthAndSymbols() {
        near(6.0, "２×３")
        near(2.0, "６÷３")
        near(1.0, "3−2")
        near(9.0, "（1+2）*3=")
    }

    @Test fun formatting() {
        assertEquals("14", Calc.format(14.0))
        assertEquals("0.3", Calc.format(ev("0.1+0.2")))
        assertEquals("3.14159265359", Calc.format(Math.PI))
        assertEquals("-2", Calc.format(-2.0))
    }

    @Test fun errorsAreReportedNotThrownByTool() {
        for (bad in listOf("1/0", "2+", "(1+2", "foo(3)", "sqrt(-1)", "", "1 % 0", "System.exit(0)")) {
            try { ev(bad); fail("should fail: $bad") } catch (e: Calc.CalcException) { assertNotNull(e.message) }
        }
        val r = CalculatorTool.run(kotlinx.serialization.json.buildJsonObject { put("expression", kotlinx.serialization.json.JsonPrimitive("1/0")) }, env())
        assertFalse(r.ok)
        val ok = CalculatorTool.run(kotlinx.serialization.json.buildJsonObject { put("expression", kotlinx.serialization.json.JsonPrimitive("12*(3+4)")) }, env())
        assertTrue(ok.ok)
        assertTrue(ok.output, ok.output.endsWith("= 84"))
    }

    @Test fun deepNestingIsRejected() {
        try { ev("(".repeat(200) + "1" + ")".repeat(200)); fail() } catch (_: Calc.CalcException) {}
    }

    @Test fun registeredAndAskedToBeUsed() {
        assertTrue(AgentTools.all.any { it.name == "calculator" })
        assertEquals(CalculatorTool, AgentTools.byName("calculator"))
        assertTrue(CalculatorTool.promptLine.contains("ALWAYS"))
    }

    @Test fun parserReadsCalculatorCalls() {
        val known = ToolCallParser.knownTools
        try {
            ToolCallParser.knownTools = setOf("calculator", "web_search")
            fun expr(p: ParsedReply) = ((p as ParsedReply.Call).call.args["expression"] as kotlinx.serialization.json.JsonPrimitive).content
            assertEquals("1+1", expr(ToolCallParser.parse("好的，我来算：\n```json\n{\"tool\": \"calculator\", \"args\": {\"expression\": \"1+1\"}}\n```")))
            // 放宽：正文中夹着的 JSON 调用（前后有文字、无代码块）
            val inline = ToolCallParser.parse("我来算一下 {\"name\": \"calculator\", \"arguments\": {\"expression\": \"2*3\"}} 稍等")
            assertEquals("2*3", expr(inline))
            assertEquals("我来算一下", (inline as ParsedReply.Call).before)
            assertEquals("(1+2)*3", expr(ToolCallParser.parse("calculator(\"(1+2)*3\")")))
            // 未注册的名字不会被当成调用
            assertTrue(ToolCallParser.parse("示例：{\"name\": \"张三\", \"age\": 3}") is ParsedReply.Final)
        } finally { ToolCallParser.knownTools = known }
    }

    private fun env(): com.iris.localai.agent.tools.ToolEnv {
        val dir = java.nio.file.Files.createTempDirectory("calc").toFile()
        return com.iris.localai.agent.tools.ToolEnv(com.iris.localai.agent.workspace.WorkspaceSandbox(java.io.File(dir, "ws")), com.iris.localai.agent.workspace.UndoStore(java.io.File(dir, "undo")))
    }
}
