package com.iris.localai.memory

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.iris.localai.agent.tools.RecallHit
import com.iris.localai.agent.tools.HistorySearch
import com.iris.localai.data.Conversation
import com.iris.localai.data.Role
import com.iris.localai.util.thinking.ThinkParser

/** Conversation → raw history records (messages + one record per tool step). Pure. */
object HistoryRecords {
    fun of(conv: Conversation): List<HistoryRecord> {
        val out = ArrayList<HistoryRecord>()
        conv.messages.forEachIndexed { i, m ->
            val role = if (m.role == Role.USER) "user" else "assistant"
            val search = if (m.role == Role.USER) m.content else ThinkParser.stripForHistory(m.content)
            if (m.content.isNotBlank()) {
                out += HistoryRecord(m.id, conv.id, m.id, i * 100L, role, null, m.content, search, m.createdAt)
            }
            m.agentSteps.forEachIndexed { j, s ->
                val text = listOf(s.tool, s.args, s.result, s.detail).filter { it.isNotBlank() }.joinToString("\n")
                out += HistoryRecord("${m.id}#${s.id}", conv.id, m.id, i * 100L + 1 + j.coerceAtMost(98), "tool", s.tool, text, text, m.createdAt)
            }
        }
        return out
    }
}

/** The store behind the agent's `recall` tool. */
class HistorySearchAdapter(private val store: HistoryStore) : HistorySearch {
    override fun search(query: String, conversationId: String?, limit: Int, exclude: Set<String>): List<RecallHit> =
        store.search(query, conversationId, limit, exclude).map {
            RecallHit(it.conversationId, it.role, it.tool, it.snippet, it.createdAt, it.score)
        }
}

/** Android's own SQLite behind [SqlDriver]. */
class AndroidSqlDriver(private val db: SQLiteDatabase) : SqlDriver {
    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
    }

    override fun query(sql: String, args: List<Any?>): List<Array<Any?>> =
        db.rawQuery(sql, args.map { it?.toString() ?: "" }.toTypedArray()).use { c ->
            val out = ArrayList<Array<Any?>>()
            while (c.moveToNext()) {
                out += Array(c.columnCount) { i ->
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
            }
            out
        }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }
}
