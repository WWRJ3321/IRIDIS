package com.iris.localai.ui.chat

/**
 * a message sent while a reply / 智能体 run is still going. Either it stops the run and
 * becomes a new turn ([Kind.INTERRUPT]: 「停」「等等」「不对」「算了」…, or 「打断并发送」 on a long
 * press of 发送), or it is a 补充 ([Kind.SUPPLEMENT]): the 智能体 reads it before its next step,
 * a plain reply answers it right after it finishes. Pure (unit-tested).
 */
object SendIntent {
    enum class Kind { INTERRUPT, SUPPLEMENT }

    /** Leading words that mean "stop what you are doing" — only when they stand alone (see [classify]). */
    private val WORDS = listOf("停下来", "停下", "停止", "停一下", "停", "等一下", "等一等", "等下", "等等", "不对", "不是这样", "算了", "取消", "打住", "stop", "wait")
    private const val PARTICLES = "吧啊呀啦了嘛哈喔哦"
    /** 别 is an interrupt alone or as 「别说了 / 别回答了 / 别写了 / 别继续」 (「别忘了…」 is a 补充). */
    private val BIE = listOf("别说了", "别回答了", "别写了", "别继续", "别生成了", "别讲了", "别弄了")
    private const val EDGE_PUNCT = " \t\r\n，,。.！!？?、；;：:…~～\"'“”‘’()（）「」【】-—"

    fun normalize(text: String): String = text.trim { it in EDGE_PUNCT || it.isWhitespace() }

    fun classify(text: String): Kind {
        val t = normalize(text)
        if (t.isEmpty()) return Kind.SUPPLEMENT
        val lower = t.lowercase()
        if (t == "别" || t.all { it == '别' }) return Kind.INTERRUPT
        if (BIE.any { t.startsWith(it) }) return Kind.INTERRUPT
        for (w in WORDS) {
            if (!lower.startsWith(w)) continue
            var i = w.length
            while (i < t.length && t[i] in PARTICLES) i++
            // The word alone, or followed by punctuation / a space: 「不对，是明天」 yes; 「不对称加密」「停车场」 no.
            if (i >= t.length || t[i] in EDGE_PUNCT || t[i].isWhitespace()) return Kind.INTERRUPT
        }
        return Kind.SUPPLEMENT
    }

    fun isInterrupt(text: String): Boolean = classify(text) == Kind.INTERRUPT

    /** How a 补充 is told to the 智能体 before its next step. */
    fun agentNote(texts: List<String>): String = texts.joinToString("\n") { "用户补充：${it.trim()}" }

    const val QUEUED_LABEL = "待回复"
    const val QUEUED_AGENT_LABEL = "下一步会告诉智能体"
    const val INTERRUPT_AND_SEND = "打断并发送"
    const val QUEUED_HINT = "已加入队列，回复完自动处理；点按可取消"
}

/** One message waiting to be answered (plain chat) or read by the 智能体 (补充). */
data class QueuedMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val convId: String,
    val text: String,
    /** True when it was sent during a 智能体 run (it goes into the next step's prompt). */
    val forAgent: Boolean = false,
)

/** the 补充 queue (FIFO; per conversation; any item can be cancelled). Pure. */
class SendQueue {
    private val items = ArrayList<QueuedMessage>()

    val all: List<QueuedMessage> get() = synchronized(this) { items.toList() }

    fun add(m: QueuedMessage): List<QueuedMessage> = synchronized(this) { items += m; items.toList() }

    fun cancel(id: String): List<QueuedMessage> = synchronized(this) { items.removeAll { it.id == id }; items.toList() }

    /** The 补充 the 智能体 has not read yet, for [convId]; they leave the queue. */
    fun takeAgentNotes(convId: String): List<QueuedMessage> = synchronized(this) {
        val taken = items.filter { it.convId == convId && it.forAgent }
        items.removeAll(taken.toSet())
        taken
    }

    /** The next message to answer as its own turn (any left after a run, agent ones included). */
    fun next(convId: String): QueuedMessage? = synchronized(this) {
        val m = items.firstOrNull { it.convId == convId } ?: return null
        items.remove(m)
        m
    }

    /** Messages of other conversations are dropped (the user left that chat). */
    fun retainOnly(convId: String): List<QueuedMessage> = synchronized(this) { items.removeAll { it.convId != convId }; items.toList() }

    fun clear(): List<QueuedMessage> = synchronized(this) { items.clear(); emptyList() }
}
