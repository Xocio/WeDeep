package com.zeroui.template.wechat

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

private val HashRegex = Regex("^[0-9a-f]{32}$")
private const val WeChatPackage = "com.tencent.mm"

fun isAccountHash(name: String) = HashRegex.matches(name)

/** Reads the private WeChat directory through `su`. */
/** Shell access to the WeChat data directory. */
interface WeChatShell {
    /** Runs [script]; returns the exit code and stdout with stderr. */
    fun exec(script: String): Pair<Int, String>

    /** Copies the file at [remote] to [local]; false when it cannot be read. */
    fun copy(remote: String, local: File): Boolean

    /** Directory of account [hash]. */
    fun accountDir(hash: String): String

    /** True when the WeChat data directory is reachable. */
    fun canReachWeChat(): Boolean
}

class RootChannel : WeChatShell {
    /** One way of reaching the WeChat data directory: extra `su` flags and the directory path. */
    private data class Access(val flags: List<String>, val root: String)

    private val accessModes = listOf(
        Access(listOf("--mount-master"), "/data/data/$WeChatPackage"),
        Access(emptyList(), "/data_mirror/data_ce/null/0/$WeChatPackage"),
        Access(emptyList(), "/data/data/$WeChatPackage"),
    )

    @Volatile
    private var access: Access? = null

    private fun start(flags: List<String>, command: String): Process =
        ProcessBuilder(listOf("su") + flags + listOf("-c", command)).redirectErrorStream(false).start()

    private fun output(flags: List<String>, command: String): Pair<Int, String> = runCatching {
        val p = start(flags, command)
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.errorStream.readBytes()
        p.waitFor() to out
    }.getOrDefault(-1 to "")

    /** Picks the first access mode that can list the WeChat directory. */
    private fun resolveAccess(): Access? {
        access?.let { return it }
        val found = accessModes.firstOrNull { mode ->
            val (code, out) = output(mode.flags, "ls ${mode.root}/MicroMsg")
            code == 0 && out.isNotBlank()
        }
        access = found
        return found
    }

    private fun run(command: String): Process = start(resolveAccess()?.flags ?: emptyList(), command)

    private val weChatRoot: String get() = resolveAccess()?.root ?: accessModes.last().root

    /** Runs [script] in the root shell that reaches WeChat; returns the exit code and stdout with stderr. */
    override fun exec(script: String): Pair<Int, String> = runCatching {
        val flags = resolveAccess()?.flags ?: emptyList()
        val p = ProcessBuilder(listOf("su") + flags + listOf("-c", script)).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor() to out
    }.getOrElse { -1 to (it.message ?: it.toString()) }

    /** Directory of account [hash] inside the WeChat data directory. */
    override fun accountDir(hash: String): String {
        require(isAccountHash(hash))
        return "$weChatRoot/MicroMsg/$hash"
    }

    /** True when a root shell can be obtained. */
    fun isAvailable(): Boolean = output(emptyList(), "id").second.contains("uid=0")

    /** True when the WeChat data directory is reachable. */
    override fun canReachWeChat(): Boolean = resolveAccess() != null

    fun listAccounts(): List<String> = runCatching {
        val p = run("ls $weChatRoot/MicroMsg")
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        out.lines().map { it.trim() }.filter(::isAccountHash)
    }.getOrDefault(emptyList())

    /** Copies one remote file; returns false when it is missing or empty. */
    override fun copy(remote: String, local: File): Boolean = runCatching {
        local.parentFile?.mkdirs()
        val p = run("cat '$remote'")
        local.outputStream().use { out -> p.inputStream.copyTo(out) }
        p.errorStream.readBytes()
        val ok = p.waitFor() == 0 && local.length() > 0
        if (!ok) local.delete()
        ok
    }.getOrDefault(false)

    fun mediaRemote(hash: String, relative: String) = "$weChatRoot/MicroMsg/$hash/$relative"

    /** Accounts whose directory holds a main database. */
    fun listAccountsWithDatabase(): List<String> = runCatching {
        val p = run("cd $weChatRoot/MicroMsg && ls -d */EnMicroMsg.db")
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        out.lines().map { it.trim().substringBefore('/') }.filter(::isAccountHash).distinct()
    }.getOrDefault(emptyList())

    /** Copies the account number and device id files used for the password. */
    fun pullCredentials(hash: String, ws: Workspace) {
        require(isAccountHash(hash))
        ws.prefsDir(hash).mkdirs()
        for (name in listOf("system_config_prefs.xml", "auth_info_key_prefs.xml")) {
            copy("$weChatRoot/shared_prefs/$name", File(ws.prefsDir(hash), name))
        }
        copy("$weChatRoot/files/KeyInfo.bin", ws.keyInfo(hash))
        copy("$weChatRoot/shared_prefs/com.tencent.mm_preferences.xml", ws.profilePrefs(hash))
    }

    /** File names inside a directory below the account directory; empty when it is missing. */
    fun listMedia(hash: String, relativeDir: String): List<String> = runCatching {
        val p = run("ls '${mediaRemote(hash, relativeDir)}'")
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.errorStream.readBytes()
        p.waitFor()
        out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** Copies the main database and its WAL; returns false when the database cannot be read. */
    fun pullDatabase(hash: String, ws: Workspace): Boolean {
        require(isAccountHash(hash))
        val base = "$weChatRoot/MicroMsg/$hash"
        ws.rawWal(hash).delete()
        if (!copy("$base/EnMicroMsg.db", ws.rawDb(hash))) return false
        copy("$base/EnMicroMsg.db-wal", ws.rawWal(hash))
        return true
    }

    /** Pulls the database, WAL and credential files of [hash] into the workspace. */
    fun pull(hash: String, ws: Workspace): Boolean {
        pullCredentials(hash, ws)
        return pullDatabase(hash, ws)
    }
}

/** Result of importing a package. */
data class ImportResult(val accounts: List<String>, val files: Int)

/** Reads a user supplied zip or a bare `EnMicroMsg.db`. */
class ImportChannel(private val resolver: ContentResolver, private val ws: Workspace) {
    private val mediaRoots = listOf("image2/", "voice2/", "video/", "avatar/")

    fun import(uri: Uri, onFile: (String) -> Unit = {}): ImportResult {
        val stream = resolver.openInputStream(uri) ?: error("cannot open $uri")
        stream.buffered().use { input ->
            input.mark(4)
            val magic = ByteArray(4)
            val n = input.read(magic)
            input.reset()
            val isZip = n == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
            return if (isZip) importZip(input, onFile) else importBareDb(input)
        }
    }

    private fun importBareDb(input: InputStream): ImportResult {
        val hash = "manual"
        ws.dir(hash).mkdirs()
        ws.rawWal(hash).delete()
        ws.rawDb(hash).outputStream().use { input.copyTo(it) }
        return ImportResult(listOf(hash), 1)
    }

    private fun importZip(input: InputStream, onFile: (String) -> Unit): ImportResult {
        val staging = ws.staging()
        val accounts = LinkedHashSet<String>()
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty() || parts.any { it == ".." }) continue
                val hashIdx = parts.indexOfFirst(::isAccountHash)
                val name = parts.last()
                val target: File? = when {
                    hashIdx >= 0 && hashIdx < parts.size - 1 -> {
                        val hash = parts[hashIdx]
                        val rel = parts.drop(hashIdx + 1).joinToString("/")
                        when {
                            rel == "EnMicroMsg.db" -> ws.rawDb(hash).also { accounts += hash }
                            rel == "EnMicroMsg.db-wal" -> ws.rawWal(hash).also { accounts += hash }
                            mediaRoots.any { rel.startsWith(it) } -> File(ws.media(hash), rel)
                            else -> null
                        }
                    }
                    name == "EnMicroMsg.db" -> ws.rawDb("manual").also { accounts += "manual" }
                    name == "EnMicroMsg.db-wal" -> ws.rawWal("manual")
                    name == "KeyInfo.bin" -> File(staging, "KeyInfo.bin")
                    name.endsWith(".xml") && parts.contains("shared_prefs") -> File(File(staging, "prefs"), name)
                    else -> null
                }
                if (target == null) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                count++
                onFile(entry.name)
            }
        }
        for (hash in accounts) {
            val keyInfo = File(staging, "KeyInfo.bin")
            if (keyInfo.isFile) keyInfo.copyTo(ws.keyInfo(hash), overwrite = true)
            File(staging, "prefs").listFiles()?.forEach {
                ws.prefsDir(hash).mkdirs()
                val target = if (it.name == "com.tencent.mm_preferences.xml") ws.profilePrefs(hash) else File(ws.prefsDir(hash), it.name)
                it.copyTo(target, overwrite = true)
            }
        }
        staging.deleteRecursively()
        return ImportResult(accounts.toList(), count)
    }
}
