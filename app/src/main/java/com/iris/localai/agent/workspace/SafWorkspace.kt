package com.iris.localai.agent.workspace

import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.web.*
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * [ExternalFiles] over the user's SAF grant of `Download/IRIDIS`
 * (ACTION_OPEN_DOCUMENT_TREE, persisted). 1.10.6: read and write (needs the grant's write permission): listing, reading and editing files the app's direct
 * File access cannot see under scoped storage. No MANAGE_EXTERNAL_STORAGE.
 * every path is resolved under [base] inside the tree — [SafPaths.WORKSPACE] for the agent
 * (so models/, skills/, config/ … stay out of reach), [SafPaths.ROOT] for app-internal readers (知识库).
 */
class SafWorkspaceFiles(private val context: Context, private val tree: Uri, private val base: List<String> = SafPaths.WORKSPACE) : ExternalFiles {

    private data class Doc(val id: String, val name: String, val isDir: Boolean, val size: Long)

    private fun children(parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        return context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            val out = ArrayList<Doc>()
            while (c.moveToNext()) {
                out += Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) 0L else c.getLong(3))
            }
            out
        } ?: emptyList()
    }

    /** Walks [base] + [rel] ("." = the base folder) by display name from the granted tree. */
    private fun find(rel: String): Doc? {
        var cur = Doc(DocumentsContract.getTreeDocumentId(tree), "", true, 0)
        for (seg in SafPaths.join(base, rel) ?: return null) {
            cur = children(cur.id).firstOrNull { it.name == seg } ?: return null
        }
        return cur
    }

    override fun list(relDir: String): List<ExternalFiles.Entry>? = runCatching {
        val d = find(relDir)?.takeIf { it.isDir } ?: return null
        children(d.id).map { ExternalFiles.Entry(it.name, it.isDir, it.size) }
    }.getOrNull()

    override fun read(relPath: String, maxBytes: Long): ByteArray? = runCatching {
        val d = find(relPath)?.takeIf { !it.isDir } ?: return null
        if (d.size > maxBytes) return null
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id))?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) return null
            }
            out.toByteArray()
        }
    }.getOrNull()

    override fun write(relPath: String, bytes: ByteArray): Boolean = runCatching {
        val segs = SafPaths.join(base, relPath) ?: return false
        if (segs.size <= base.size) return false
        val existing = find(relPath)
        val uri = if (existing != null) {
            if (existing.isDir) return false
            DocumentsContract.buildDocumentUriUsingTree(tree, existing.id)
        } else {
            // Create the missing folders, then the file.
            var parent = Doc(DocumentsContract.getTreeDocumentId(tree), "", true, 0)
            for (seg in segs.dropLast(1)) {
                parent = children(parent.id).firstOrNull { it.name == seg && it.isDir } ?: run {
                    val made = DocumentsContract.createDocument(context.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, parent.id), DocumentsContract.Document.MIME_TYPE_DIR, seg) ?: return false
                    Doc(DocumentsContract.getDocumentId(made), seg, true, 0)
                }
            }
            DocumentsContract.createDocument(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, parent.id), "application/octet-stream", segs.last()) ?: return false
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)

    override fun delete(relPath: String): Boolean = runCatching {
        val d = find(relPath)?.takeIf { !it.isDir } ?: return false
        DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, d.id))
    }.getOrDefault(false)

    /**
     * the one-time move (see [StorageLayout.plan]) through the grant, for files the app's own File
     * access cannot see: creates `workspace/` in the tree when missing, renames a clashing entry first,
     * then moves it with DocumentsContract.moveDocument. Only valid for a [SafPaths.ROOT] instance.
     */
    fun migrateLayout(): StorageLayout.Result = runCatching {
        val cr = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val kids = children(rootId)
        val existing = kids.firstOrNull { it.name.equals(StorageLayout.WORKSPACE, ignoreCase = true) }
        if (existing != null && !existing.isDir) return StorageLayout.Result(0, 0, listOf(StorageLayout.WORKSPACE))
        val plan = StorageLayout.plan(kids.map { it.name }, existing?.let { children(it.id).map { c -> c.name } }.orEmpty())
        if (plan.moves.isEmpty()) return StorageLayout.Result.NONE
        val wsUri = existing?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
            ?: DocumentsContract.createDocument(cr, rootUri, DocumentsContract.Document.MIME_TYPE_DIR, StorageLayout.WORKSPACE)
            ?: return StorageLayout.Result(0, 0, plan.moves.map { it.from })
        var moved = 0; var renamed = 0
        val failed = ArrayList<String>()
        for (m in plan.moves) {
            val doc = kids.firstOrNull { it.name == m.from }
            if (doc == null) { failed += m.from; continue }
            val ok = runCatching {
                var uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)
                if (m.renamed) uri = DocumentsContract.renameDocument(cr, uri, m.to) ?: return@runCatching false
                val out = DocumentsContract.moveDocument(cr, uri, rootUri, wsUri)
                if (out == null && m.renamed) runCatching { DocumentsContract.renameDocument(cr, uri, m.from) } // put the name back
                out != null
            }.getOrDefault(false)
            if (ok) { moved++; if (m.renamed) renamed++ } else failed += m.from
        }
        StorageLayout.Result(moved, renamed, failed)
    }.getOrElse { StorageLayout.Result(0, 0, listOf(it.javaClass.simpleName)) }

    companion object {
        /** Initial location for the folder picker: Download/IRIDIS. */
        fun initialUri(): Uri = DocumentsContract.buildDocumentUri(WorkspaceResolver.EXTERNAL_STORAGE_AUTHORITY, WorkspaceResolver.TREE_DOC_ID)

        fun treeDocId(uri: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    }
}
