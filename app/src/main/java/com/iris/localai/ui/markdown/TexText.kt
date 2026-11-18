package com.iris.localai.ui.markdown

/**
 * 常见 LaTeX → 可读的 Unicode 文本（不排版，只让公式能看懂）。
 * 覆盖：希腊字母、运算符/关系符、\frac、\sqrt、上下标、\text/\mathrm/\mathbf、\mathbb、\left/\right、空格命令。
 * 认不出的命令保留原样（去掉反斜杠），不会吞内容。
 */
object TexText {
    private val SYMBOLS: Map<String, String> = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ",
        "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
        "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ",
        "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗", "circ" to "∘", "bullet" to "•",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "simeq" to "≃", "propto" to "∝", "ll" to "≪", "gg" to "≫",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬", "oint" to "∮",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪",
        "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅", "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧",
        "lor" to "∨", "wedge" to "∧", "vee" to "∨",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐", "leftrightarrow" to "↔",
        "Leftrightarrow" to "⇔", "iff" to "⇔", "implies" to "⇒", "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
        "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱", "angle" to "∠", "perp" to "⊥",
        "parallel" to "∥", "degree" to "°", "prime" to "′", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
        "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", ":" to " ", "!" to "", " " to " ",
        "{" to "{", "}" to "}", "%" to "%", "$" to "$", "&" to "&", "#" to "#", "_" to "_", "|" to "‖",
        "lim" to "lim", "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln", "exp" to "exp",
        "max" to "max", "min" to "min", "det" to "det", "arg" to "arg", "deg" to "deg",
        "gcd" to "gcd", "lcm" to "lcm", "bmod" to " mod ", "pmod" to " mod ", "mid" to "∣", "nmid" to "∤", "sqrt" to "√",
    )
    private val BB = mapOf('R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ")
    private const val SUP_SRC = "0123456789+-=()niabcdefghjklmoprstuvwxyzABDEGHIJKLMNOPRTUVW"
    private const val SUP_DST = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱᵃᵇᶜᵈᵉᶠᵍʰʲᵏˡᵐᵒᵖʳˢᵗᵘᵛʷˣʸᶻᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ"
    private const val SUB_SRC = "0123456789+-=()aehijklmnoprstuvx"
    private const val SUB_DST = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ"

    /**
     * 模型常把公式写进反引号（`2^{2m} + 3^m`、`\gcd(a,b)=1`）。像数学且不像代码时按公式渲染。
     */
    private val TEX_CMD = Regex("\\\\[a-zA-Z]+")
    private val POWER = Regex("[\\w)}]\\^[\\w{(]|[\\w)}]_\\{")
    private val CODE_MARK = Regex(";|\"|'|==|=>|->|::|//|&&|\\|\\||\\b(def|return|print|import|function|const|let|var|val|fun|class|if|for|while)\\b|[A-Za-z_]\\.[A-Za-z_]|\\*\\*|\\[|]")
    fun looksMath(s: String): Boolean {
        if (s.length > 160 || CODE_MARK.containsMatchIn(s)) return false
        return TEX_CMD.containsMatchIn(s) || POWER.containsMatchIn(s)
    }

    fun render(tex: String): String = runCatching { Reader(tex.trim()).all().replace(Regex(" {3,}"), "  ").trim() }.getOrDefault(tex)

    private fun script(s: String, sup: Boolean): String {
        val src = if (sup) SUP_SRC else SUB_SRC
        val dst = if (sup) SUP_DST else SUB_DST
        val t = s.trim()
        if (t.isNotEmpty() && t.all { it in src }) {
            // SUP_DST 里有多码元字符时按索引取会错位，这里逐字查表（两串长度一致，都是 BMP 字符）
            return t.map { dst[src.indexOf(it)] }.joinToString("")
        }
        if (sup && t == "′") return "′"
        return (if (sup) "^" else "_") + (if (t.length == 1) t else "($t)")
    }

    private fun simple(s: String): Boolean = s.length <= 1 || s.all { it.isLetterOrDigit() || it == '.' }

    private class Reader(val s: String) {
        var i = 0
        fun all(): String { val b = StringBuilder(); while (i < s.length) b.append(atom()); return b.toString() }

        /** `{...}` 或单个原子。 */
        fun arg(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            if (s[i] == '{') {
                i++
                val b = StringBuilder()
                while (i < s.length && s[i] != '}') b.append(atom())
                if (i < s.length) i++
                return b.toString()
            }
            return atom()
        }

        fun optional(): String? {
            if (i < s.length && s[i] == '[') {
                val end = s.indexOf(']', i)
                if (end > i) { val v = s.substring(i + 1, end); i = end + 1; return Reader(v).all() }
            }
            return null
        }

        fun atom(): String {
            val ch = s[i]
            return when (ch) {
                '\\' -> command()
                '^' -> { i++; script(arg(), true) }
                '_' -> { i++; script(arg(), false) }
                '{' -> arg()
                '}' -> { i++; "" }
                '~' -> { i++; " " }
                '&' -> { i++; "  " }
                else -> { i++; ch.toString() }
            }
        }

        fun command(): String {
            i++ // backslash
            if (i >= s.length) return ""
            if (!s[i].isLetter()) { val c = s[i]; i++; return if (c == '\\') "\n" else SYMBOLS[c.toString()] ?: c.toString() }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return when (name) {
                "frac", "dfrac", "tfrac" -> { val a = arg(); val b = arg(); (if (simple(a)) a else "($a)") + "/" + (if (b.length <= 1 || b.all { it.isDigit() || it == '.' }) b else "($b)") }
                "sqrt" -> { val n = optional(); val a = arg(); (when (n) { null -> ""; "3" -> ""; else -> script(n, true) }) + (if (n == "3") "∛" else "√") + (if (simple(a)) a else "($a)") }
                "text", "mathrm", "mathbf", "mathit", "textbf", "textit", "operatorname", "mathsf", "mathtt", "boldsymbol", "bm" -> arg()
                "mathbb" -> { val a = arg(); a.map { BB[it] ?: it.toString() }.joinToString("") }
                "vec" -> arg() + "\u20D7"
                "hat" -> arg() + "\u0302"
                "bar", "overline" -> arg() + "\u0305"
                "dot" -> arg() + "\u0307"
                "tilde" -> arg() + "\u0303"
                "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "displaystyle", "textstyle", "limits", "nolimits" -> {
                    if (i < s.length && s[i] == '.') i++
                    ""
                }
                "begin", "end" -> { arg(); "" }
                "binom" -> { val a = arg(); val b = arg(); "C($a, $b)" }
                else -> SYMBOLS[name] ?: name
            }
        }
    }
}
