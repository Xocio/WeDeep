package com.zeroui.template.wechat

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a snapshot came from. */
enum class Source { Root, Import }

/** Persistent facts about one account snapshot. */
@Serializable
data class AccountMeta(
    val hash: String,
    val source: String = Source.Import.name,
    val uin: String = "",
    val imei: String = "",
    val profile: String = "",
    val pageSize: Int = 0,
    val rawStamp: Long = 0,
    val decryptedAt: Long = 0,
    val messageCount: Long = 0,
    val walPages: Int = 0,
    val manualUin: String = "",
    val manualImei: String = "",
    val lastTried: Int = 0,
    val lastError: String = "",
    val nickname: String = "",
    /** Hex of the 16 byte salt of the encrypted database; lets a re-encrypt run without the raw file. */
    val salt: String = "",
    val selfUser: String = "",
)

private val MetaJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

/** Private storage layout: `wx/<hash>/{raw,media,cache,plain.db,meta.json}`. */
class Workspace(context: Context) {
    val root = File(context.applicationContext.filesDir, "wx").apply { mkdirs() }

    fun dir(hash: String) = File(root, hash)
    fun raw(hash: String) = File(dir(hash), "raw").apply { mkdirs() }
    fun media(hash: String) = File(dir(hash), "media")
    fun cache(hash: String) = File(dir(hash), "cache")
    fun plain(hash: String) = File(dir(hash), "plain.db")
    fun rawDb(hash: String) = File(raw(hash), "EnMicroMsg.db")
    fun rawWal(hash: String) = File(raw(hash), "EnMicroMsg.db-wal")
    fun prefsDir(hash: String) = File(raw(hash), "prefs")
    fun keyInfo(hash: String) = File(raw(hash), "KeyInfo.bin")
    fun profilePrefs(hash: String) = File(raw(hash), "profile.xml")
    private fun metaFile(hash: String) = File(dir(hash), "meta.json")

    fun accounts(): List<String> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith("_") }?.map { it.name }?.sorted().orEmpty()

    fun readMeta(hash: String): AccountMeta? = runCatching {
        MetaJson.decodeFromString<AccountMeta>(metaFile(hash).readText())
    }.getOrNull()

    fun writeMeta(meta: AccountMeta) {
        dir(meta.hash).mkdirs()
        metaFile(meta.hash).writeText(MetaJson.encodeToString(AccountMeta.serializer(), meta))
    }

    /** Removes decrypted data and caches, keeping the raw snapshot. */
    fun clearDerived(hash: String) {
        plain(hash).delete()
        File(dir(hash), "plain.db.tmp").delete()
        cache(hash).deleteRecursively()
        readMeta(hash)?.let { writeMeta(it.copy(decryptedAt = 0, messageCount = 0)) }
    }

    fun deleteAccount(hash: String) {
        dir(hash).deleteRecursively()
    }

    fun sizeOf(hash: String): Long = dir(hash).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Staging area for files that belong to every account of an import. */
    fun staging(): File = File(root, "_staging").apply { deleteRecursively(); mkdirs() }
}
