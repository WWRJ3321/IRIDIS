package com.iris.localai.agent.tools

import com.iris.localai.agent.core.Args
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.floor

/**
 * `calculator`：精确计算数学表达式。小模型心算常出错，凡涉及数值计算都应调用它。
 * 纯 Kotlin 递归下降求值（[Calc]），不用脚本引擎。
 */
object CalculatorTool : AgentTool {
    override val name = "calculator"
    override val label = "计算器"
    override val description = "精确计算数学表达式，算数都交给它"
    override val promptLine = "calculator(expression): exact math: + - * / % ^ ( ), sqrt sin cos tan log ln abs pow exp, pi e (radians). " +
        "ALWAYS call it for ANY arithmetic or numeric result instead of computing yourself"
    override val risk = ToolRisk.SAFE

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val expr = Args.str(args, "expression", "expr", "input", "query", "q", "formula", "arg0")?.trim()
            ?: throw IllegalArgumentException("缺少参数 expression")
        return try {
            val v = Calc.eval(expr)
            val shown = Calc.format(v)
            ToolResult(true, "$expr = $shown", "= $shown")
        } catch (e: Calc.CalcException) {
            ToolResult(false, "Error: ${e.message}", (e.message ?: "无法计算").take(40))
        }
    }
}

/** 安全的表达式求值：只认数字、运算符、括号、白名单函数和常量。纯函数，单元测试覆盖。 */
object Calc {
    class CalcException(msg: String) : IllegalArgumentException(msg)

    private const val MAX_LEN = 500
    private const val MAX_DEPTH = 64

    private val CONSTANTS = mapOf("pi" to Math.PI, "π" to Math.PI, "e" to Math.E, "tau" to 2 * Math.PI)

    private val FUNCS: Map<String, (List<Double>) -> Double> = mapOf(
        "sqrt" to one { Math.sqrt(it) }, "cbrt" to one { Math.cbrt(it) },
        "sin" to one { Math.sin(it) }, "cos" to one { Math.cos(it) }, "tan" to one { Math.tan(it) },
        "asin" to one { Math.asin(it) }, "acos" to one { Math.acos(it) }, "atan" to one { Math.atan(it) },
        "ln" to one { Math.log(it) }, "exp" to one { Math.exp(it) },
        "abs" to one { abs(it) }, "floor" to one { floor(it) }, "ceil" to one { Math.ceil(it) },
        "round" to one { Math.round(it).toDouble() },
        "log" to { a ->
            when (a.size) {
                1 -> Math.log10(a[0])
                2 -> Math.log(a[0]) / Math.log(a[1])
                else -> throw CalcException("log takes 1 or 2 arguments")
            }
        },
        "pow" to { a -> if (a.size != 2) throw CalcException("pow takes 2 arguments") else Math.pow(a[0], a[1]) },
        "min" to { a -> if (a.isEmpty()) throw CalcException("min needs arguments") else a.min() },
        "max" to { a -> if (a.isEmpty()) throw CalcException("max needs arguments") else a.max() },
    )

    private fun one(f: (Double) -> Double): (List<Double>) -> Double = { a ->
        if (a.size != 1) throw CalcException("function takes 1 argument") else f(a[0])
    }

    /** 全角符号、× ÷ − 等统一成 ASCII。 */
    internal fun normalize(s: String): String = buildString {
        for (ch in s) append(
            when (ch) {
                '×', '·', '∙' -> '*'
                '÷' -> '/'
                '−', '–', '—' -> '-'
                '（' -> '('
                '）' -> ')'
                '，' -> ','
                '＾' -> '^'
                in '０'..'９' -> '0' + (ch - '０')
                '＋' -> '+'
                '＊' -> '*'
                '／' -> '/'
                '％' -> '%'
                '．', '。' -> '.'
                else -> ch
            },
        )
    }.replace("**", "^").trim().removeSuffix("=").trim()

    fun eval(expression: String): Double {
        val s = normalize(expression)
        if (s.isEmpty()) throw CalcException("empty expression")
        if (s.length > MAX_LEN) throw CalcException("expression too long")
        val p = Parser(s)
        val v = p.parseAll()
        if (v.isNaN()) throw CalcException("undefined result (NaN)")
        if (v.isInfinite()) throw CalcException("result is infinite (division by zero or overflow)")
        return v
    }

    /** 整数值按整数显示；其余保留 12 位有效数字。 */
    fun format(v: Double): String {
        if (v == floor(v) && abs(v) < 1e15) return BigDecimal(v).toBigInteger().toString()
        val bd = BigDecimal(v).round(MathContext(12)).stripTrailingZeros()
        return if (abs(v) >= 1e-6 && abs(v) < 1e15) bd.toPlainString() else bd.toString()
    }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0

        fun parseAll(): Double {
            val v = expr()
            skip()
            if (i < s.length) throw CalcException("unexpected '${s[i]}' at ${i + 1}")
            return v
        }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char? { skip(); return s.getOrNull(i) }
        private fun eat(c: Char): Boolean { if (peek() == c) { i++; return true }; return false }

        private fun enter() { if (++depth > MAX_DEPTH) throw CalcException("expression nested too deeply") }

        fun expr(): Double {
            enter()
            var v = term()
            while (true) {
                v = when {
                    eat('+') -> v + term()
                    eat('-') -> v - term()
                    else -> { depth--; return v }
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                val c = peek()
                v = when {
                    c == '*' -> { i++; v * unary() }
                    c == '/' -> { i++; v / unary() }
                    c == '%' -> { i++; val d = unary(); if (d == 0.0) throw CalcException("modulo by zero"); v % d }
                    // 隐式乘法：2pi、2(3+1)、(1+2)(3+4)
                    c == '(' || (c != null && (c.isLetter() || c == 'π')) -> v * unary()
                    else -> return v
                }
            }
        }

        private fun unary(): Double {
            enter()
            val v = when {
                eat('-') -> -unary()
                eat('+') -> unary()
                else -> power()
            }
            depth--
            return v
        }

        private fun power(): Double {
            val base = postfix()
            return if (eat('^')) Math.pow(base, unary()) else base
        }

        private fun postfix(): Double {
            var v = primary()
            while (peek() == '!') {
                i++
                if (v < 0 || v != floor(v) || v > 170) throw CalcException("factorial needs an integer 0..170")
                var f = 1.0
                for (k in 2..v.toInt()) f *= k
                v = f
            }
            return v
        }

        private fun primary(): Double {
            val c = peek() ?: throw CalcException("unexpected end of expression")
            if (c == '(') {
                i++
                val v = expr()
                if (!eat(')')) throw CalcException("missing ')'")
                return v
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter() || c == 'π') {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == 'π')) i++
                val id = s.substring(start, i).lowercase()
                CONSTANTS[id]?.let { if (peek() != '(') return it }
                val f = FUNCS[id] ?: throw CalcException("unknown name '$id'")
                if (!eat('(')) {
                    // sqrt 16 这种不带括号的一元写法
                    return f(listOf(unary()))
                }
                val argsList = ArrayList<Double>()
                if (!eat(')')) {
                    do { argsList += expr() } while (eat(','))
                    if (!eat(')')) throw CalcException("missing ')' after arguments of $id")
                }
                return f(argsList)
            }
            throw CalcException("unexpected '$c' at ${i + 1}")
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == '_')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                // 科学计数法 1e5 / 2.5E-3（e 后面必须跟数字或正负号加数字，否则是常量 e）
                val save = i
                var j = i + 1
                if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                if (j < s.length && s[j].isDigit()) {
                    i = j
                    while (i < s.length && s[i].isDigit()) i++
                } else i = save
            }
            val t = s.substring(start, i).replace("_", "")
            return t.toDoubleOrNull() ?: throw CalcException("bad number '$t'")
        }
    }
}
