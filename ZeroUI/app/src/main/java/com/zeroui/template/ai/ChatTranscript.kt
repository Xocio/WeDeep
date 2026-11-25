package com.zeroui.template.ai

import app.wedeep.core.model.Kind
import com.zeroui.template.wechat.ChatDb
import com.zeroui.template.wechat.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain text form of the newest messages of one conversation. */
data class ChatTranscript(
    val title: String,
    val isGroup: Boolean,
    val text: String,
    /** Messages included in [text]. */
    val count: Int,
    /** Messages stored for the conversation. */
    val total: Long,
    val firstTime: Long,
    val lastTime: Long,
    /** True when older messages were left out by a limit. */
    val truncated: Boolean,
) {
    companion object {
        /** Character limit of one message line. */
        private const val LINE_MAX = 600

        private const val BATCH = 500

        fun formatDay(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

        private fun formatClock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

        /**
         * Reads at most [maxMessages] of the newest messages of [talker] within [maxChars],
         * oldest first, with one date line per day.
         */
        fun build(db: ChatDb, talker: String, title: String, maxMessages: Int, maxChars: Int): ChatTranscript {
            val lines = ArrayList<Pair<ChatMessage, String>>()
            var chars = 0
            var before: Long? = null
            var truncated = false
            reading@ while (true) {
                val batch = db.older(talker, before, BATCH)
                if (batch.isEmpty()) break
                for (m in batch.asReversed()) {
                    val line = lineOf(m) ?: continue
                    if (chars + line.length > maxChars || lines.size >= maxMessages) {
                        truncated = true
                        break@reading
                    }
                    lines += m to line
                    chars += line.length + 1
                }
                before = batch.first().msgId
            }
            lines.reverse()

            val text = StringBuilder()
            var day = ""
            for ((m, line) in lines) {
                val d = formatDay(m.time)
                if (d != day) {
                    day = d
                    text.append("—— ").append(d).append(" ——\n")
                }
                text.append(line).append('\n')
            }
            return ChatTranscript(
                title = title,
                isGroup = talker.endsWith("@chatroom"),
                text = text.toString(),
                count = lines.size,
                total = db.countMessages(talker),
                firstTime = lines.firstOrNull()?.first?.time ?: 0L,
                lastTime = lines.lastOrNull()?.first?.time ?: 0L,
                truncated = truncated,
            )
        }

        /** `HH:mm name: text`, or null for a message without readable content. */
        private fun lineOf(m: ChatMessage): String? {
            val c = m.content
            val body = when (c.kind) {
                Kind.Unknown -> return null
                Kind.System -> "[系统消息] ${c.text}"
                Kind.Quote -> buildString {
                    append(c.text)
                    c.detail?.takeIf { it.isNotBlank() }?.let { append("（引用 ").append(it.replace('\n', ' ')).append("）") }
                }
                Kind.Link, Kind.File, Kind.Forward, Kind.MiniProgram -> buildString {
                    append(c.text)
                    c.detail?.takeIf { it.isNotBlank() }?.let { append("：").append(it.replace('\n', ' ')) }
                }
                Kind.Voice, Kind.Video -> if (c.seconds > 0) "${c.text} ${c.seconds}秒" else c.text
                else -> c.text
            }.trim()
            if (body.isEmpty()) return null
            val clipped = if (body.length > LINE_MAX) body.take(LINE_MAX) + "…" else body
            val name = if (c.kind == Kind.System) "" else "${m.senderName}: "
            return "${formatClock(m.time)} $name${clipped.replace("\r\n", "\n").replace("\n", " / ")}"
        }
    }
}
