package com.iris.localai.character

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 2.3.2 角色卡 → prompt (pure, unit-tested):
 *  - [system]: the card's system_prompt (`{{original}}` = the user's own system prompt) or a short
 *    default, then description / personality / scenario / example dialogue, macros replaced.
 *  - [greetings]: first_mes + alternate_greetings (the opening message and its swipes).
 *  - [turnSuffix]: lorebook entries triggered by the recent messages + post_history_instructions,
 *    appended to the newest user turn only (the stored history and the system prompt stay as they
 *    were, so the engine's KV cache of the earlier turns is reused).
 */
object CardPrompt {
    const val DEFAULT_SYSTEM = "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}. Stay in character and keep to the scenario."

    /**
     * Replaces `{{char}} {{user}} {{original}} {{newline}} {{trim}} {{// …}} {{random:a,b}}
     * {{pick:a,b}} {{roll:N}} / {{roll:dN}}` and the legacy `<USER>` / `<BOT>`. [seed] keeps
     * `{{pick}}` stable for one conversation; [rnd] drives `{{random}}` / `{{roll}}`.
     */
    fun macros(text: String, char: String, user: String, original: String = "", seed: Int = 0, rnd: java.util.Random = java.util.Random()): String {
        if (text.isEmpty()) return text
        var s = text.replace(Regex("\\{\\{//[\\s\\S]*?}}"), "")
        s = s.replace(Regex("\\{\\{(char|user|original|newline|trim)}}", RegexOption.IGNORE_CASE)) { m ->
            when (m.groupValues[1].lowercase()) {
                "char" -> char; "user" -> user; "original" -> original; "newline" -> "\n"; else -> "\u0000TRIM\u0000"
            }
        }
        s = s.replace("<USER>", user).replace("<BOT>", char)
        s = s.replace(Regex("\\{\\{(random|pick)\\s*:\\s*([^}]*)}}", RegexOption.IGNORE_CASE)) { m ->
            val opts = splitOptions(m.groupValues[2])
            if (opts.isEmpty()) "" else if (m.groupValues[1].equals("pick", true)) opts[Math.floorMod(seed * 31 + m.range.first, opts.size)] else opts[rnd.nextInt(opts.size)]
        }
        s = s.replace(Regex("\\{\\{roll\\s*:\\s*d?(\\d{1,6})}}", RegexOption.IGNORE_CASE)) { m ->
            val n = m.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
            (rnd.nextInt(n) + 1).toString()
        }
        if ("\u0000TRIM\u0000" in s) s = s.split("\u0000TRIM\u0000").joinToString("") { it.trim('\n', ' ') }
        return s
    }

    /** `a,b,c` (`\,` keeps a comma; `::` separates when present, as in SillyTavern). */
    private fun splitOptions(v: String): List<String> =
        (if ("::" in v) v.split("::") else v.replace("\\,", "\u0001").split(',').map { it.replace('\u0001', ',') }).map { it.trim() }

    /** Example dialogue split at `<START>` into blocks (empty ones dropped). */
    fun exampleBlocks(mesExample: String): List<String> =
        mesExample.split(Regex("<START>", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The system prompt of a card chat. [original] is the user's own system prompt (used for
     * `{{original}}`); [maxExampleTokens] caps the example dialogue (later blocks dropped first).
     */
    fun system(card: CharacterCard, user: String, original: String, seed: Int = 0, maxExampleTokens: Int = 1200, tokens: (String) -> Int = ::estimate): String {
        val char = card.charName.ifBlank { "Character" }
        fun m(t: String) = macros(t, char, user, original, seed).trim()
        val head = m(card.systemPrompt.ifBlank { DEFAULT_SYSTEM })
        val parts = ArrayList<String>()
        if (head.isNotEmpty()) parts += head
        m(card.description).takeIf { it.isNotEmpty() }?.let { parts += "[$char]\n$it" }
        m(card.personality).takeIf { it.isNotEmpty() }?.let { parts += "[$char's personality]\n$it" }
        m(card.scenario).takeIf { it.isNotEmpty() }?.let { parts += "[Scenario]\n$it" }
        val examples = ArrayList<String>()
        var used = 0
        for (b in exampleBlocks(card.mesExample)) {
            val t = m(b); val n = tokens(t)
            if (used + n > maxExampleTokens) break
            examples += t; used += n
        }
        if (examples.isNotEmpty()) parts += "[Example dialogue — style reference only]\n" + examples.joinToString("\n\n")
        return parts.joinToString("\n\n")
    }

    /** The opening messages: first_mes then alternate_greetings (blank ones dropped), macros replaced. */
    fun greetings(card: CharacterCard, user: String, seed: Int = 0): List<String> {
        val char = card.charName.ifBlank { "Character" }
        return (listOf(card.firstMes) + card.alternateGreetings).map { macros(it, char, user, "", seed).trim() }.filter { it.isNotEmpty() }
    }

    /** post_history_instructions with macros (`{{original}}` → nothing: IRIDIS has no default one). */
    fun postHistory(card: CharacterCard, user: String, seed: Int = 0): String =
        macros(card.postHistory, card.charName.ifBlank { "Character" }, user, "", seed).trim()

    /**
     * What is appended to the newest user message: triggered lorebook entries (within [loreBudget]
     * tokens) and the post-history instructions. "" when the card has neither.
     */
    fun turnSuffix(card: CharacterCard, user: String, recent: List<String>, loreBudget: Int, seed: Int = 0): String {
        val char = card.charName.ifBlank { "Character" }
        val lore = Lorebook.of(card)?.let { b -> b.select(recent, loreBudget).map { macros(it.content, char, user, "", seed).trim() }.filter { it.isNotEmpty() } }.orEmpty()
        val post = postHistory(card, user, seed)
        val out = StringBuilder()
        if (lore.isNotEmpty()) out.append("[World info]\n").append(lore.joinToString("\n"))
        if (post.isNotEmpty()) { if (out.isNotEmpty()) out.append("\n\n"); out.append("[Instruction]\n").append(post) }
        return out.toString()
    }

    /** Lorebook budget: the book's token_budget, at most 10% of the window, never under 0. */
    fun loreBudget(card: CharacterCard, contextTokens: Int): Int {
        val book = Lorebook.of(card) ?: return 0
        val cap = (contextTokens * 0.10).toInt()
        return minOf(book.tokenBudget ?: Lorebook.DEFAULT_BUDGET, cap).coerceAtLeast(0)
    }

    /** Rough token count (same rule as the app's estimator: CJK ~1/char, others ~1/4 char). */
    fun estimate(s: String): Int {
        var cjk = 0; var other = 0
        for (ch in s) if (ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF || ch.code in 0xF900..0xFAFF || ch.code in 0x3040..0x30FF) cjk++ else other++
        return cjk + (other + 3) / 4
    }
}

/**
 * 2.3.2 v1 lorebook (character_book): keyword-triggered entries. Supported: `enabled`, `constant`,
 * `keys` (any), `selective` + `secondary_keys` (needs one of each), `case_sensitive`, `use_regex`
 * (`/pattern/flags` or plain pattern), `insertion_order` (ascending), `priority` (kept first when
 * over budget), book `scan_depth` (default 4 messages) and `token_budget`. Not supported (ignored):
 * recursive scanning, `@@` decorators, position / depth / role — every entry goes into the newest
 * user turn.
 */
class Lorebook(val entries: List<Entry>, val scanDepth: Int, val tokenBudget: Int?) {
    data class Entry(
        val keys: List<String>, val secondary: List<String>, val content: String, val enabled: Boolean, val constant: Boolean,
        val selective: Boolean, val caseSensitive: Boolean, val regex: Boolean, val order: Int, val priority: Int,
    )

    fun matches(e: Entry, text: String): Boolean {
        if (e.constant) return true
        if (e.keys.none { hit(it, text, e) }) return false
        return !e.selective || e.secondary.isEmpty() || e.secondary.any { hit(it, text, e) }
    }

    private fun hit(key: String, text: String, e: Entry): Boolean {
        if (key.isBlank()) return false
        val rx = key.trim()
        if (e.regex || (rx.length > 2 && rx.startsWith("/") && rx.lastIndexOf('/') > 0)) {
            val body = if (rx.startsWith("/") && rx.lastIndexOf('/') > 0) rx.substring(1, rx.lastIndexOf('/')) else rx
            val flags = if (rx.startsWith("/")) rx.substring(rx.lastIndexOf('/') + 1) else ""
            val opts = if ('i' in flags || !e.caseSensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return runCatching { Regex(body, opts).containsMatchIn(text) }.getOrDefault(false)
        }
        return text.contains(key.trim(), ignoreCase = !e.caseSensitive)
    }

    /** Entries triggered by the last [scanDepth] of [recent] (oldest first), within [budget] tokens. */
    fun select(recent: List<String>, budget: Int, tokens: (String) -> Int = CardPrompt::estimate): List<Entry> {
        if (budget <= 0) return emptyList()
        val text = recent.takeLast(scanDepth.coerceIn(1, 20)).joinToString("\n")
        val hits = entries.filter { it.enabled && it.content.isNotBlank() && matches(it, text) }
        val kept = ArrayList<Entry>()
        var used = 0
        for (e in hits.sortedWith(compareByDescending<Entry> { it.constant }.thenByDescending { it.priority }.thenBy { it.order })) {
            val n = tokens(e.content)
            if (used + n > budget) continue
            kept += e; used += n
        }
        return kept.sortedBy { it.order }
    }

    companion object {
        const val DEFAULT_BUDGET = 400

        fun of(card: CharacterCard): Lorebook? {
            val book = card.characterBook ?: return null
            val list = (book["entries"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
            if (list.isEmpty()) return null
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
            fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
            fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
            fun JsonObject.l(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val entries = list.mapIndexed { idx, e ->
                Entry(
                    keys = e.l("keys"), secondary = e.l("secondary_keys"), content = e.s("content").orEmpty(),
                    enabled = e.b("enabled") ?: true, constant = e.b("constant") ?: false, selective = e.b("selective") ?: false,
                    caseSensitive = e.b("case_sensitive") ?: false, regex = e.b("use_regex") ?: false,
                    order = e.i("insertion_order") ?: idx, priority = e.i("priority") ?: 0,
                )
            }
            return Lorebook(entries, book.i("scan_depth") ?: 4, book.i("token_budget"))
        }
    }
}
