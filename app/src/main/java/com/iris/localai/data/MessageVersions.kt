package com.iris.localai.data

/**
 * 2.0.x message versions. Pure, unit-tested.
 *
 * The conversation's `messages` list is always the *active path*: what the chat shows and the
 * only thing the model, the history index and the API ever read. Older versions live inside the
 * message ([ChatMessage.versions]); the slot at [ChatMessage.activeVersion] is a placeholder
 * because the live message is that version.
 *
 *  - editing a user message → a new version; the old one keeps its text *and* the conversation
 *    that followed it ([MessageVersion.tail]); a new reply is then generated for the new text;
 *  - regenerating → the new reply becomes a new version of the old reply;
 *  - editing a reply in place → a new, edited version (nothing regenerated);
 *  - switching a user message's version swaps the conversation tail back in.
 */
object MessageVersions {
    private val PLACEHOLDER = MessageVersion()

    fun count(m: ChatMessage): Int = if (m.versions.isEmpty()) 1 else m.versions.size

    /** 0-based index of the version shown. */
    fun active(m: ChatMessage): Int = if (m.versions.isEmpty()) 0 else m.activeVersion.coerceIn(0, m.versions.size - 1)

    /** The live message (plus, for a user message, its [tail]) as a stored version. */
    fun snapshot(m: ChatMessage, tail: List<ChatMessage> = emptyList()): MessageVersion = MessageVersion(
        content = m.content, createdAt = m.createdAt, thinkingMs = m.thinkingMs, stats = m.stats,
        modelName = m.modelName, interrupted = m.interrupted, error = m.error,
        outputChars = m.outputChars, outputMs = m.outputMs, totalMs = m.totalMs,
        agentSteps = m.agentSteps, attachments = m.attachments, edited = m.edited,
        tail = if (m.role == Role.USER) tail else emptyList(),
    )

    private fun apply(m: ChatMessage, v: MessageVersion): ChatMessage = m.copy(
        content = v.content, createdAt = v.createdAt, thinkingMs = v.thinkingMs, stats = v.stats,
        modelName = v.modelName, interrupted = v.interrupted, error = v.error,
        outputChars = v.outputChars, outputMs = v.outputMs, totalMs = v.totalMs,
        agentSteps = v.agentSteps, attachments = v.attachments, edited = v.edited,
    )

    /** Every version with the active slot filled in from the live message. */
    private fun stored(m: ChatMessage, tail: List<ChatMessage>): MutableList<MessageVersion> {
        val list = if (m.versions.isEmpty()) mutableListOf(PLACEHOLDER) else m.versions.toMutableList()
        list[active(m)] = snapshot(m, tail)
        return list
    }

    /** [m] with every old version kept and [next] (the live fields of [m]) added as the newest, active one. */
    private fun pushed(m: ChatMessage, tail: List<ChatMessage>, next: ChatMessage): ChatMessage {
        val list = stored(m, tail)
        return next.copy(versions = list + PLACEHOLDER, activeVersion = list.size)
    }

    /**
     * Editing user message [id]: the old text and everything after it become a stored version, the
     * returned path ends with the edited message (the caller generates the reply). null = not found
     * or not a user message.
     */
    fun editUser(messages: List<ChatMessage>, id: String, newText: String, now: Long = System.currentTimeMillis()): List<ChatMessage>? {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0 || messages[i].role != Role.USER) return null
        val m = messages[i]
        val next = pushed(m, messages.drop(i + 1), m.copy(content = newText.trim(), createdAt = now, edited = false))
        return messages.take(i) + next
    }

    /**
     * Editing assistant reply [id] in place: the reply's old text becomes a stored version, the new
     * text the active one (reasoning and speed readouts belong to the old text and stay with it).
     * The rest of the conversation is untouched. null = not found / not a reply / unchanged.
     */
    fun editAssistant(messages: List<ChatMessage>, id: String, newText: String): List<ChatMessage>? {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0 || messages[i].role != Role.ASSISTANT) return null
        val m = messages[i]
        val text = newText.trim()
        if (text.isEmpty() || text == m.content.trim()) return null
        val next = pushed(
            m, emptyList(),
            m.copy(
                content = text, thinkingMs = 0, stats = null, interrupted = false, error = null,
                outputChars = 0, outputMs = 0, totalMs = 0, edited = true,
            ),
        )
        return messages.toMutableList().also { it[i] = next }
    }

    /** Regenerate: [fresh] (the new reply bubble) becomes the newest version of [previous]. */
    fun asNewVersionOf(previous: ChatMessage, fresh: ChatMessage): ChatMessage = pushed(previous, emptyList(), fresh)

    /**
     * The active version of [m] is thrown away (a regenerated reply that never started): the newest
     * remaining version becomes the live one. null = [m] had no other version.
     */
    fun dropActive(m: ChatMessage): ChatMessage? {
        if (m.versions.size < 2) return null
        val list = m.versions.toMutableList().also { it.removeAt(active(m)) }
        val k = list.size - 1
        val back = apply(m, list[k])
        return back.copy(versions = if (list.size == 1) emptyList() else list.also { it[k] = PLACEHOLDER }, activeVersion = if (list.size == 1) 0 else k)
    }

    /**
     * Shows version [target] (0-based) of message [id]. For a user message the conversation after it
     * is swapped too (the current tail is stored with the current version). null = nothing to do.
     */
    fun switchTo(messages: List<ChatMessage>, id: String, target: Int): List<ChatMessage>? {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0) return null
        val m = messages[i]
        if (count(m) < 2 || target !in 0 until count(m) || target == active(m)) return null
        val user = m.role == Role.USER
        val tail = messages.drop(i + 1)
        val list = stored(m, if (user) tail else emptyList())
        val v = list[target]
        list[target] = PLACEHOLDER
        val shown = apply(m, v).copy(versions = list, activeVersion = target)
        return messages.take(i) + shown + (if (user) v.tail else tail)
    }

    /** Every message of the path *and* of every stored branch (attachment bookkeeping). */
    fun allMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        fun walk(list: List<ChatMessage>) {
            for (m in list) {
                out += m
                for (v in m.versions) walk(v.tail)
            }
        }
        walk(messages)
        return out
    }

    /** Attachments of the path and of every stored version / branch. */
    fun allAttachments(messages: List<ChatMessage>): List<Attachment> =
        allMessages(messages).flatMap { m -> m.attachments + m.versions.flatMap { it.attachments } }

    /**
     * 2.4.4: deleting message [id] from the active path (its stored versions go with it). A marker that
     * pointed at it (compaction end, context break) moves to the message before it; null = not found.
     */
    fun delete(messages: List<ChatMessage>, id: String): List<ChatMessage>? {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0) return null
        return messages.toMutableList().apply { removeAt(i) }
    }

    /** The id a marker on [id] moves to once [id] is deleted (the previous message), null when none is left. */
    fun markerAfterDelete(messages: List<ChatMessage>, marker: String?, id: String): String? {
        if (marker != id) return marker
        val i = messages.indexOfFirst { it.id == id }
        return if (i > 0) messages[i - 1].id else null
    }
}
