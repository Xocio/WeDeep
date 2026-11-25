package app.wedeep.core.export

import app.wedeep.core.model.Kind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One message prepared for export. */
data class ExportMessage(
    val msgId: Long,
    val msgSvrId: Long,
    val type: Int,
    val isSend: Boolean,
    val createTime: Long,
    val sender: String,
    val kind: Kind,
    val text: String,
    val mediaPath: String? = null,
)

object Json {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}

/** Streaming writers for the export formats. */
object ExportWriter {
    fun jsonHeader(out: Appendable, title: String, talker: String) {
        out.append("{\"title\":").append(Json.quote(title))
        out.append(",\"talker\":").append(Json.quote(talker)).append(",\"messages\":[")
    }

    fun jsonMessage(out: Appendable, m: ExportMessage, first: Boolean) {
        if (!first) out.append(',')
        out.append("\n{\"msgId\":").append(m.msgId.toString())
        out.append(",\"msgSvrId\":").append(m.msgSvrId.toString())
        out.append(",\"type\":").append(m.type.toString())
        out.append(",\"isSend\":").append(m.isSend.toString())
        out.append(",\"createTime\":").append(m.createTime.toString())
        out.append(",\"sender\":").append(Json.quote(m.sender))
        out.append(",\"kind\":").append(Json.quote(m.kind.name))
        out.append(",\"content\":").append(Json.quote(m.text))
        out.append(",\"mediaPath\":").append(m.mediaPath?.let { Json.quote(it) } ?: "null")
        out.append('}')
    }

    fun jsonFooter(out: Appendable) {
        out.append("\n]}\n")
    }

    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun htmlHeader(out: Appendable, title: String) {
        out.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        out.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        out.append("<title>").append(escapeHtml(title)).append("</title><style>")
        out.append(Css).append("</style></head><body><h1>").append(escapeHtml(title)).append("</h1>")
    }

    /** Writes one message; [lastDay] carries the previous day label so day separators are emitted once. */
    fun htmlMessage(out: Appendable, m: ExportMessage, lastDay: String?): String {
        val day = formatDay(m.createTime)
        if (day != lastDay) out.append("<div class=\"day\">").append(day).append("</div>")
        val time = formatTime(m.createTime)
        if (m.kind == Kind.System) {
            out.append("<div class=\"sys\">").append(escapeHtml(m.text)).append("</div>")
            return day
        }
        out.append("<div class=\"row ").append(if (m.isSend) "me" else "peer").append("\">")
        out.append("<div class=\"meta\">").append(escapeHtml(m.sender)).append(" · ").append(time).append("</div>")
        out.append("<div class=\"bubble\">")
        val media = m.mediaPath
        if (media != null && m.kind == Kind.Image) {
            out.append("<img loading=\"lazy\" src=\"").append(escapeHtml(media)).append("\">")
        } else if (media != null && (m.kind == Kind.Voice || m.kind == Kind.Video || m.kind == Kind.File)) {
            out.append("<a href=\"").append(escapeHtml(media)).append("\">").append(escapeHtml(m.text)).append("</a>")
        } else {
            out.append(escapeHtml(m.text).replace("\n", "<br>"))
        }
        out.append("</div></div>")
        return day
    }

    fun htmlFooter(out: Appendable) {
        out.append("</body></html>\n")
    }

    private fun fmt(pattern: String, ms: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ms))

    fun formatDay(ms: Long): String = fmt("yyyy-MM-dd", ms)
    fun formatTime(ms: Long): String = fmt("HH:mm:ss", ms)

    private const val Css = "body{font-family:system-ui,sans-serif;background:#f2f2f2;max-width:720px;margin:0 auto;padding:12px}" +
        "h1{font-size:18px}.day{text-align:center;color:#888;font-size:12px;margin:14px 0}" +
        ".sys{text-align:center;color:#999;font-size:12px;margin:8px 0}.row{margin:8px 0;display:flex;flex-direction:column}" +
        ".row.me{align-items:flex-end}.row.peer{align-items:flex-start}.meta{font-size:11px;color:#999;margin-bottom:2px}" +
        ".bubble{max-width:80%;padding:8px 10px;border-radius:8px;background:#fff;word-break:break-word}" +
        ".me .bubble{background:#95ec69}.bubble img{max-width:100%;border-radius:4px}"
}
