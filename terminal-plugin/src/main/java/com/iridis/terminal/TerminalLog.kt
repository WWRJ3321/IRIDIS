package com.iridis.terminal

/**
 * One terminal history shared by the terminal page and IRIDIS' run_command, so the user sees every
 * command the agent ran (and its output) next to their own. In memory only.
 */
object TerminalLog {
    data class Entry(val id: Long, val command: String, val output: String = "", val exit: Int? = null, val byAgent: Boolean = false, val at: Long = System.currentTimeMillis())

    const val MAX = 100
    @Volatile var entries: List<Entry> = emptyList(); private set
    @Volatile var revision = 0; private set
    private var next = 1L

    @Synchronized fun start(command: String, byAgent: Boolean): Long {
        val id = next++
        entries = (entries + Entry(id, command, byAgent = byAgent)).takeLast(MAX); revision++
        return id
    }

    @Synchronized fun finish(id: Long, output: String, exit: Int) {
        entries = entries.map { if (it.id == id) it.copy(output = output, exit = exit) else it }; revision++
    }

    @Synchronized fun clear() { entries = emptyList(); revision++ }
}
