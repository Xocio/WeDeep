package com.zeroui.template.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** One line of a stored conversation as shown on screen. */
sealed interface AiSavedItem {
    data class User(val text: String) : AiSavedItem
    data class Assistant(val text: String, val cut: Boolean, val stopped: Boolean) : AiSavedItem
    data class Attached(val title: String, val count: Int, val total: Long, val truncated: Boolean) : AiSavedItem
}

/**
 * Stored analysis conversation. [history] is what the model receives; [items] is what the
 * page shows; [attached] tells whether [history] already carries the chat records.
 */
class AiSession(
    val id: String,
    val account: String,
    val talker: String,
    val chatTitle: String,
    val range: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val attached: Boolean,
    val history: List<AiMessage>,
    val items: List<AiSavedItem>,
) {
    /** Text of the first question, used as the conversation title. */
    val title: String
        get() = items.firstNotNullOfOrNull { it as? AiSavedItem.User }?.text
            ?.replace(Regex("\\s+"), " ")?.trim()
            ?.let { if (it.length <= TITLE_MAX) it else it.take(TITLE_MAX) + "…" }
            .orEmpty()

    /** Number of questions and answers. */
    val messageCount: Int get() = items.count { it !is AiSavedItem.Attached }

    companion object {
        const val TITLE_MAX = 16
    }
}

/** Conversations stored as one JSON file each under `files/ai_sessions`. */
object AiSessionStore {

    private const val DIR = "ai_sessions"
    private const val EXT = ".json"
    private const val MAX_SESSIONS = 100

    private val random = SecureRandom()

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun fileOf(context: Context, id: String) = File(dir(context), id + EXT)

    fun newId(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return System.currentTimeMillis().toString(36) + "_" + bytes.joinToString("") { "%02x".format(it) }
    }

    /** Conversations of [account], newest first. */
    suspend fun list(context: Context, account: String): List<AiSession> = withContext(Dispatchers.IO) {
        files(context)
            .mapNotNull { read(it) }
            .filter { it.account == account }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun load(context: Context, id: String): AiSession? = withContext(Dispatchers.IO) {
        fileOf(context, id).takeIf { it.isFile }?.let { read(it) }
    }

    fun exists(context: Context, id: String): Boolean = fileOf(context, id).isFile

    suspend fun save(context: Context, session: AiSession): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { fileOf(context, session.id).writeText(toJson(session).toString()) }.isSuccess
        if (ok) trim(context)
        ok
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        runCatching { fileOf(context, id).delete() }
        Unit
    }

    /** Deletes every conversation of [account]. */
    suspend fun deleteAll(context: Context, account: String) = withContext(Dispatchers.IO) {
        files(context).forEach { file ->
            if (read(file)?.account == account) runCatching { file.delete() }
        }
    }

    private fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(EXT) }.orEmpty().toList()

    private fun trim(context: Context) {
        val files = files(context)
        if (files.size <= MAX_SESSIONS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_SESSIONS)
            .forEach { runCatching { it.delete() } }
    }

    private fun read(file: File): AiSession? = runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()

    private fun toJson(s: AiSession): JSONObject = JSONObject()
        .put("id", s.id)
        .put("account", s.account)
        .put("talker", s.talker)
        .put("chat_title", s.chatTitle)
        .put("range", s.range)
        .put("created_at", s.createdAt)
        .put("updated_at", s.updatedAt)
        .put("attached", s.attached)
        .put("history", JSONArray(s.history.map { m ->
            JSONObject().put("role", if (m is AiMessage.User) "user" else "assistant").put("text", m.text)
        }))
        .put("items", JSONArray(s.items.map { item ->
            when (item) {
                is AiSavedItem.User -> JSONObject().put("type", "user").put("text", item.text)
                is AiSavedItem.Assistant -> JSONObject().put("type", "assistant").put("text", item.text)
                    .put("cut", item.cut).put("stopped", item.stopped)
                is AiSavedItem.Attached -> JSONObject().put("type", "attached").put("title", item.title)
                    .put("count", item.count).put("total", item.total).put("truncated", item.truncated)
            }
        }))

    private fun fromJson(obj: JSONObject): AiSession? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val history = obj.optJSONArray("history").objects().map { m ->
            val text = m.optString("text")
            if (m.optString("role") == "user") AiMessage.User(text) else AiMessage.Assistant(text)
        }
        val items = obj.optJSONArray("items").objects().mapNotNull { i ->
            when (i.optString("type")) {
                "user" -> AiSavedItem.User(i.optString("text"))
                "assistant" -> AiSavedItem.Assistant(i.optString("text"), i.optBoolean("cut"), i.optBoolean("stopped"))
                "attached" -> AiSavedItem.Attached(
                    i.optString("title"), i.optInt("count"), i.optLong("total"), i.optBoolean("truncated"),
                )
                else -> null
            }
        }
        return AiSession(
            id = id,
            account = obj.optString("account"),
            talker = obj.optString("talker"),
            chatTitle = obj.optString("chat_title"),
            range = obj.optInt("range", 1),
            createdAt = obj.optLong("created_at"),
            updatedAt = obj.optLong("updated_at"),
            attached = obj.optBoolean("attached"),
            history = history,
            items = items,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
