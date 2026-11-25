package com.zeroui.template.wechat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.wedeep.core.export.ExportMessage
import app.wedeep.core.export.ExportWriter
import app.wedeep.core.model.Kind
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Output formats of an export. */
enum class ExportFormat { Json, Html, Both }

/** Progress of an export run. */
data class ExportProgress(val session: Int, val sessions: Int, val title: String, val messages: Long)

/** Final numbers of an export run. */
data class ExportSummary(val folder: Uri?, val sessions: Int, val messages: Long, val media: Int)

/** Writes conversations into a folder chosen through the document picker. */
class Exporter(private val context: Context, private val resolver: MediaResolver) {
    private val cr: ContentResolver = context.contentResolver
    private val state = context.getSharedPreferences("wedeep_export", Context.MODE_PRIVATE)

    private fun child(parent: Uri, name: String, mime: String): Uri =
        DocumentsContract.createDocument(cr, parent, mime, name) ?: error("cannot create $name")

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun cursorKey(hash: String, talker: String) = "$hash|$talker"

    fun resetIncremental(hash: String) {
        state.edit().also { e -> state.all.keys.filter { it.startsWith("$hash|") }.forEach(e::remove) }.apply()
    }

    fun export(
        hash: String,
        db: ChatDb,
        talkers: List<Session>,
        treeUri: Uri,
        format: ExportFormat,
        withMedia: Boolean,
        incremental: Boolean,
        cancelled: () -> Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportSummary {
        val treeRoot = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val uin = ws(hash)
        val folder = child(treeRoot, "WeDeep-$uin-$stamp", DocumentsContract.Document.MIME_TYPE_DIR)
        val sessionsDir = child(folder, "sessions", DocumentsContract.Document.MIME_TYPE_DIR)
        val mediaDir = if (withMedia) child(folder, "media", DocumentsContract.Document.MIME_TYPE_DIR) else null

        var total = 0L
        var mediaCount = 0
        val manifest = StringBuilder("{\"account\":\"$hash\",\"exportedAt\":${System.currentTimeMillis()},\"sessions\":[")
        var exportedSessions = 0
        talkers.forEachIndexed { index, s ->
            if (cancelled()) return@forEachIndexed
            val after = if (incremental) state.getLong(cursorKey(hash, s.talker), 0L) else 0L
            val pending = db.older(s.talker, null, 1).lastOrNull()
            if (pending == null || pending.msgId <= after) return@forEachIndexed

            val base = safe(s.talker)
            val json = if (format != ExportFormat.Html) OutputStreamWriter(cr.openOutputStream(child(sessionsDir, "$base.json", "application/json"))!!, Charsets.UTF_8).buffered() else null
            val html = if (format != ExportFormat.Json) OutputStreamWriter(cr.openOutputStream(child(sessionsDir, "$base.html", "text/html"))!!, Charsets.UTF_8).buffered() else null
            var first = true
            var day: String? = null
            var count = 0L
            var lastId = after
            try {
                json?.let { ExportWriter.jsonHeader(it, s.title, s.talker) }
                html?.let { ExportWriter.htmlHeader(it, s.title) }
                db.forEachMessage(s.talker, after) { m ->
                    if (cancelled()) return@forEachMessage
                    var mediaPath: String? = null
                    if (mediaDir != null && m.content.kind in setOf(Kind.Image, Kind.Voice, Kind.Video)) {
                        val file = when (m.content.kind) {
                            Kind.Image -> resolver.image(hash, m.mediaName, thumb = false) ?: resolver.image(hash, m.mediaName)
                            Kind.Voice -> resolver.voice(hash, m.mediaName)
                            else -> resolver.video(hash, m.mediaName)
                        }
                        if (file != null) {
                            val ext = when (m.content.kind) { Kind.Image -> "jpg"; Kind.Voice -> "amr"; else -> "mp4" }
                            val name = "${m.msgId}.$ext"
                            runCatching {
                                val out = cr.openOutputStream(child(mediaDir, name, "application/octet-stream"))!!
                                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                                mediaPath = "../media/$name"
                                mediaCount++
                            }
                        }
                    }
                    val em = ExportMessage(m.msgId, m.svrId, m.type, m.isSend, m.time, m.senderName, m.content.kind, m.content.text, mediaPath)
                    json?.let { ExportWriter.jsonMessage(it, em, first) }
                    html?.let { day = ExportWriter.htmlMessage(it, em, day) }
                    first = false
                    count++
                    lastId = m.msgId
                    if (count % 200 == 0L) onProgress(ExportProgress(index + 1, talkers.size, s.title, total + count))
                }
                json?.let { ExportWriter.jsonFooter(it) }
                html?.let { ExportWriter.htmlFooter(it) }
            } finally {
                json?.close()
                html?.close()
            }
            total += count
            exportedSessions++
            state.edit().putLong(cursorKey(hash, s.talker), lastId).apply()
            if (exportedSessions > 1) manifest.append(',')
            manifest.append("{\"talker\":").append(app.wedeep.core.export.Json.quote(s.talker))
                .append(",\"title\":").append(app.wedeep.core.export.Json.quote(s.title))
                .append(",\"messages\":").append(count).append('}')
            onProgress(ExportProgress(index + 1, talkers.size, s.title, total))
        }
        manifest.append("]}")
        cr.openOutputStream(child(folder, "manifest.json", "application/json"))!!.use { it.write(manifest.toString().toByteArray()) }
        return ExportSummary(folder, exportedSessions, total, mediaCount)
    }

    private fun ws(hash: String): String = hash.take(8)
}
