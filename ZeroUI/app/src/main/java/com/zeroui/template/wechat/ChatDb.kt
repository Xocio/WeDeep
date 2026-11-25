package com.zeroui.template.wechat

import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import app.wedeep.core.model.Content
import app.wedeep.core.model.MessageParser
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Row of the conversation list. */
data class Session(
    val talker: String,
    val title: String,
    val summary: String,
    val time: Long,
    val unread: Int,
) {
    val isGroup: Boolean get() = talker.endsWith("@chatroom")
}

/** One message ready to render. */
data class ChatMessage(
    val msgId: Long,
    val svrId: Long,
    val type: Int,
    val isSend: Boolean,
    val time: Long,
    val talker: String,
    val senderId: String?,
    val senderName: String,
    val content: Content,
    val mediaName: String?,
)

/**
 * One entry of the address book; [members] is the member count of a group, 0 when unknown.
 * [strangerMark] is true when the stored profile carries a stranger ticket.
 */
data class Contact(
    val username: String,
    val name: String,
    val nickname: String,
    val remark: String,
    val alias: String,
    val isGroup: Boolean,
    val members: Int = 0,
    val strangerMark: Boolean = false,
)

/** Ticket WeChat stores for a profile seen as a stranger, such as `v3_xxx@stranger`. */
private val StrangerTicket = Regex("""[A-Za-z0-9_\-]{8,}@stranger""")

/** True when [text] or the strings inside [blob] contain a stranger ticket. */
private fun hasStrangerTicket(text: String, blob: ByteArray?): Boolean {
    if (StrangerTicket.containsMatchIn(text)) return true
    if (blob == null || blob.isEmpty()) return false
    return StrangerTicket.containsMatchIn(String(blob, Charsets.ISO_8859_1))
}

/** How a friend has cut the account off; [Suspected] rests on a profile mark only. */
enum class Severance { Deleted, Blocked, Suspected }

/** A friend whose latest system notice shows [kind], seen at [time]. */
data class SeveredFriend(val username: String, val kind: Severance, val time: Long)

/** System notice fragments WeChat shows when a message cannot reach a friend. */
private val DeletedNotices = listOf("开启了朋友验证", "你还不是他（她）朋友", "Friend Confirmation", "not his/her friend")
private val BlockedNotices = listOf("被对方拒收", "rejected by the receiver", "rejected by the recipient")

/** A message found by search. */

/** Built-in WeChat entries that are stored as contacts but are not people. */
private val SystemContacts = setOf(
    "filehelper", "weixin", "fmessage", "medianote", "floatbottle", "qmessage", "qqmail", "tmessage",
    "qqsync", "qqfriend", "weibo", "newsapp", "blogapp", "facebookapp", "masssendapp", "feedsapp",
    "voipapp", "officialaccounts", "voicevoipapp", "voiceinputapp", "linkedinplugin", "notifymessage",
    "appbrandcustomerservicemsg", "pc_share", "notification_messages", "helper_entry",
)

data class SearchHit(val msgId: Long, val talker: String, val title: String, val text: String, val time: Long)

/** Media entry of a conversation. */
data class MediaItem(val msgId: Long, val type: Int, val mediaName: String?, val time: Long)

/**
 * Read-only access to a decrypted account database.
 * [close] waits for running queries; after it, every query reads as empty.
 */
class ChatDb(plain: File) : Closeable {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(plain.path, null, SQLiteDatabase.OPEN_READONLY)
    private val lock = ReentrantReadWriteLock()
    @Volatile private var closed = false
    private val names = ConcurrentHashMap<String, String>()
    private val tables: Set<String> = queryStrings("SELECT name FROM sqlite_master WHERE type='table'").toSet()

    /** Runs [sql] and hands the cursor to [block] while the database is held open. */
    private inline fun <T> query(sql: String, args: Array<String>?, block: (Cursor) -> T): T = lock.read {
        val cursor = if (closed) MatrixCursor(emptyArray()) else db.rawQuery(sql, args)
        cursor.use(block)
    }

    private fun columns(table: String): Set<String> =
        query("PRAGMA table_info($table)", null) { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }

    private fun queryStrings(sql: String): List<String> =
        query(sql, null) { c -> buildList { while (c.moveToNext()) add(c.getString(0).orEmpty()) } }

    private val messageColumns by lazy { columns("message") }

    private fun Cursor.str(name: String): String = getColumnIndex(name).let { if (it >= 0) getString(it).orEmpty() else "" }
    private fun Cursor.lng(name: String): Long = getColumnIndex(name).let { if (it >= 0) getLong(it) else 0L }

    /** Display name of a contact: remark, then nickname, then the id. */
    fun displayName(username: String): String = names.getOrPut(username) {
        if ("rcontact" !in tables) return@getOrPut username
        query("SELECT conRemark, nickname FROM rcontact WHERE username = ?", arrayOf(username)) { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty().ifBlank { c.getString(1).orEmpty() }.ifBlank { username } else username
        }
    }

    /**
     * Friends and saved groups of the account, without official accounts, system entries,
     * deleted contacts and [self].
     */
    fun contacts(self: String?): List<Contact> {
        if ("rcontact" !in tables) return emptyList()
        val cols = columns("rcontact")
        fun text(name: String) = if (name in cols) name else "''"
        fun number(name: String) = if (name in cols) name else "0"
        val sql = "SELECT username, ${text("alias")} AS alias, ${text("conRemark")} AS conRemark, ${text("nickname")} AS nickname, " +
            "${number("type")} AS type, ${number("verifyFlag")} AS verifyFlag, ${number("deleteFlag")} AS deleteFlag, " +
            "${text("encryptUsername")} AS encryptUsername, ${if ("lvbuff" in cols) "lvbuff" else "NULL"} AS lvbuff FROM rcontact"
        val counts = memberCounts()
        return query(sql, null) { c ->
            buildList {
                while (c.moveToNext()) {
                    val username = c.str("username")
                    val type = c.lng("type")
                    if (username.isBlank() || username == self || username in SystemContacts) continue
                    if (username.startsWith("gh_") || c.lng("verifyFlag") != 0L || c.lng("deleteFlag") != 0L) continue
                    if (!username.endsWith("@chatroom") && type and 1L == 0L) continue
                    val remark = c.str("conRemark")
                    val nickname = c.str("nickname")
                    val group = username.endsWith("@chatroom")
                    val blobAt = c.getColumnIndex("lvbuff")
                    val blob = if (blobAt >= 0 && c.getType(blobAt) == Cursor.FIELD_TYPE_BLOB) c.getBlob(blobAt) else null
                    add(
                        Contact(
                            username = username,
                            name = remark.ifBlank { nickname }.ifBlank { username },
                            nickname = nickname,
                            remark = remark,
                            alias = c.str("alias"),
                            isGroup = group,
                            members = counts[username] ?: 0,
                            strangerMark = !group && hasStrangerTicket(c.str("encryptUsername"), blob),
                        ),
                    )
                }
            }
        }
    }

    /** Member count of every group the `chatroom` table knows. */
    private fun memberCounts(): Map<String, Int> {
        if ("chatroom" !in tables) return emptyMap()
        val cols = columns("chatroom")
        val key = listOf("chatroomname", "username").firstOrNull { it in cols } ?: return emptyMap()
        if ("memberlist" !in cols) return emptyMap()
        return query("SELECT $key, memberlist FROM chatroom", null) { c ->
            buildMap {
                while (c.moveToNext()) {
                    val room = c.getString(0).orEmpty()
                    val list = c.getString(1).orEmpty()
                    if (room.isNotBlank()) put(room, list.split(';').count { it.isNotBlank() })
                }
            }
        }
    }

    /**
     * Friends whose latest delivery notice says they deleted or blocked the account,
     * unless they wrote again after that notice.
     */
    fun severedFriends(): List<SeveredFriend> {
        if ("message" !in tables) return emptyList()
        val patterns = (DeletedNotices + BlockedNotices).joinToString(" OR ") { "content LIKE ?" }
        val args = (DeletedNotices + BlockedNotices).map { "%$it%" }.toTypedArray()
        val latest = HashMap<String, SeveredFriend>()
        query(
            "SELECT talker, content, createTime FROM message WHERE (type & 65535) = 10000 AND talker NOT LIKE '%@chatroom' AND ($patterns) ORDER BY createTime",
            args,
        ) { c ->
            while (c.moveToNext()) {
                val talker = c.getString(0).orEmpty()
                val content = c.getString(1).orEmpty()
                val kind = if (BlockedNotices.any { content.contains(it, ignoreCase = true) }) Severance.Blocked else Severance.Deleted
                if (talker.isNotBlank()) latest[talker] = SeveredFriend(talker, kind, c.getLong(2))
            }
        }
        if (latest.isEmpty()) return emptyList()
        val lastHeard = HashMap<String, Long>()
        query("SELECT talker, MAX(createTime) FROM message WHERE isSend = 0 AND (type & 65535) != 10000 GROUP BY talker", null) { c ->
            while (c.moveToNext()) lastHeard[c.getString(0).orEmpty()] = c.getLong(1)
        }
        return latest.values.filter { (lastHeard[it.username] ?: 0L) < it.time }.sortedByDescending { it.time }
    }

    private fun titleOf(talker: String): String {
        val name = displayName(talker)
        return if (name == talker && talker.endsWith("@chatroom")) "群聊" else name
    }

    /** Conversation preview: the stored digest for text, a type label otherwise; `%s` is the sender. */
    private fun summaryOf(type: Int, content: String, digest: String, digestUser: String): String {
        val family = MessageParser.family(type)
        val text = if (family == 1 || family == 10000 || family == 10002) {
            digest.ifBlank { MessageParser.summary(type, content) }
        } else {
            MessageParser.summary(type, content).takeIf { !it.startsWith("[暂不支持") } ?: digest
        }
        if (!text.contains("%s")) return text
        val sender = digestUser.takeIf { it.isNotBlank() }?.let { displayName(it) }
        return if (sender == null) text.replace("%s:", "").replace("%s", "").trim() else text.replace("%s", sender)
    }

    fun sessions(limit: Int = 100_000): List<Session> {
        if ("rconversation" in tables) {
            val cols = columns("rconversation")
            val timeCol = listOf("conversationTime", "time").firstOrNull { it in cols } ?: "rowid"
            val digest = if ("digest" in cols) "digest" else "''"
            val content = if ("content" in cols) "content" else "''"
            val msgType = if ("msgType" in cols) "msgType" else "1"
            val unread = if ("unReadCount" in cols) "unReadCount" else "0"
            val digestUser = if ("digestUser" in cols) "digestUser" else "''"
            val sql = "SELECT username, $unread AS u, $msgType AS t, $content AS c, $digest AS d, $timeCol AS tm, $digestUser AS du " +
                "FROM rconversation ORDER BY tm DESC LIMIT $limit"
            val out = ArrayList<Session>()
            query(sql, null) { c ->
                while (c.moveToNext()) {
                    val talker = c.getString(0).orEmpty()
                    if (talker.isBlank()) continue
                    val type = c.getString(2)?.toIntOrNull() ?: 1
                    val summary = summaryOf(type, c.getString(3).orEmpty(), c.getString(4).orEmpty(), c.getString(6).orEmpty())
                    out += Session(talker, titleOf(talker), summary, c.getLong(5), c.getInt(1))
                }
            }
            if (out.isNotEmpty()) return out
        }
        val out = ArrayList<Session>()
        query(
            "SELECT talker, MAX(createTime) AS tm, COUNT(*) FROM message GROUP BY talker ORDER BY tm DESC LIMIT $limit",
            null,
        ) { c ->
            while (c.moveToNext()) {
                val talker = c.getString(0).orEmpty()
                out += Session(talker, titleOf(talker), "", c.getLong(1), 0)
            }
        }
        return out
    }

    private fun readMessage(c: Cursor): ChatMessage {
        val talker = c.str("talker")
        val type = c.getInt(c.getColumnIndexOrThrow("type"))
        val isSend = c.lng("isSend") == 1L
        val raw = c.str("content")
        val isGroup = talker.endsWith("@chatroom")
        val split = if (isGroup && !isSend) MessageParser.splitSender(raw) else null
        val body = split?.body ?: raw
        val senderId = if (isSend) null else split?.sender ?: talker.takeIf { !isGroup }
        val senderName = when {
            isSend -> "我"
            senderId != null -> displayName(senderId)
            else -> talker
        }
        return ChatMessage(
            msgId = c.lng("msgId"),
            svrId = c.lng("msgSvrId"),
            type = type,
            isSend = isSend,
            time = c.lng("createTime"),
            talker = talker,
            senderId = senderId,
            senderName = senderName,
            content = MessageParser.parse(type, body),
            mediaName = c.str("imgPath").ifBlank { null },
        )
    }

    private val select = "SELECT msgId, msgSvrId, type, isSend, createTime, talker, content, imgPath FROM message"

    /** Messages older than [beforeMsgId] (or the newest when null), in ascending order. */
    fun older(talker: String, beforeMsgId: Long?, limit: Int = 50): List<ChatMessage> {
        val where = if (beforeMsgId != null) "WHERE talker = ? AND msgId < ?" else "WHERE talker = ?"
        val args = if (beforeMsgId != null) arrayOf(talker, beforeMsgId.toString()) else arrayOf(talker)
        return query("$select $where ORDER BY msgId DESC LIMIT $limit", args) { c ->
            buildList { while (c.moveToNext()) add(readMessage(c)) }
        }.reversed()
    }

    /** Messages newer than [afterMsgId], in ascending order. */
    fun newer(talker: String, afterMsgId: Long, limit: Int = 50): List<ChatMessage> =
        query("$select WHERE talker = ? AND msgId > ? ORDER BY msgId ASC LIMIT $limit", arrayOf(talker, afterMsgId.toString())) { c ->
            buildList { while (c.moveToNext()) add(readMessage(c)) }
        }

    /** Window starting a little before [msgId] and running forward. */
    fun window(talker: String, msgId: Long, before: Int = 20, after: Int = 60): List<ChatMessage> =
        older(talker, msgId, before) + newer(talker, msgId - 1, after)

    fun search(keyword: String, limit: Int = 200, talker: String? = null): List<SearchHit> {
        if (keyword.isBlank()) return emptyList()
        val escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val scope = if (talker != null) "AND talker = ?" else ""
        val args = buildList { add("%$escaped%"); if (talker != null) add(talker) }.toTypedArray()
        return query(
            "$select WHERE content LIKE ? ESCAPE '\\' AND (type = 1 OR (type & 65535) = 49) $scope ORDER BY msgId DESC LIMIT $limit",
            args,
        ) { c ->
            buildList {
                while (c.moveToNext()) {
                    val m = readMessage(c)
                    add(SearchHit(m.msgId, m.talker, titleOf(m.talker), m.content.text, m.time))
                }
            }
        }
    }

    fun media(talker: String, limit: Int = 300, beforeMsgId: Long? = null): List<MediaItem> {
        val cursor = if (beforeMsgId != null) "AND msgId < $beforeMsgId" else ""
        return query(
            "SELECT msgId, type, imgPath, createTime FROM message WHERE talker = ? AND type IN (3, 43, 62) $cursor ORDER BY msgId DESC LIMIT $limit",
            arrayOf(talker),
        ) { c ->
            buildList { while (c.moveToNext()) add(MediaItem(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3))) }
        }
    }

    /** Calls [block] with every message of [talker] after [afterMsgId] in ascending order. */
    fun forEachMessage(talker: String, afterMsgId: Long = 0, block: (ChatMessage) -> Unit) {
        var last = afterMsgId
        while (true) {
            val batch = newer(talker, last, 500)
            if (batch.isEmpty()) break
            batch.forEach(block)
            last = batch.last().msgId
        }
    }

    fun countMessages(talker: String): Long =
        query("SELECT COUNT(*) FROM message WHERE talker = ?", arrayOf(talker)) { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** True when the schema has the columns the reader needs. */
    fun isCompatible(): Boolean = "message" in tables && listOf("msgId", "type", "createTime", "talker", "content").all { it in messageColumns }

    override fun close() = lock.write {
        if (!closed) {
            closed = true
            db.close()
        }
    }
}
