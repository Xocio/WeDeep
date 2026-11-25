package com.zeroui.template.wechat

import app.wedeep.core.media.MediaPaths
import java.io.File
import java.util.concurrent.Semaphore

/** Suffix of the bookmark file left in place of a deduplicated media file. */
private const val RefMarker = "⌖"
private val SafeRelative = Regex("[A-Za-z0-9_./\\-]+")
private val RefId = Regex("^[0-9a-fA-F-]{8,64}$")

/** Locates media files of an account: imported copies first, then the live WeChat directory through root. */
class MediaResolver(private val ws: Workspace, private val root: RootChannel) {

    /** Returns the first candidate that exists, fetching it into the cache when the account is root-backed. */
    fun resolve(hash: String, candidates: List<String>): File? {
        val safe = candidates.filter { !it.contains("..") && SafeRelative.matches(it) }
        for (rel in safe) {
            val staged = File(ws.dir(hash), "edit/media/$rel")
            if (staged.isFile && staged.length() > 0) return staged
            val local = File(ws.media(hash), rel)
            if (local.isFile && local.length() > 0) return local
            refTarget(local.parentFile, local.name, File(ws.media(hash), topDir(rel)))?.let { return it }
        }
        if (ws.readMeta(hash)?.source != Source.Root.name || !isAccountHash(hash)) return null
        for (rel in safe) {
            val cached = File(ws.cache(hash), rel)
            if (cached.isFile && cached.length() > 0) return cached
            if (root.copy(root.mediaRemote(hash, rel), cached)) return cached
            val marker = File(ws.cache(hash), "$rel.ref")
            if (root.copy(root.mediaRemote(hash, rel + RefMarker), marker)) {
                val id = marker.readText().trim()
                marker.delete()
                if (RefId.matches(id) && root.copy(root.mediaRemote(hash, "${topDir(rel)}/.ref/d/$id"), cached)) return cached
            }
        }
        return null
    }

    /** Follows a bookmark file next to [name] inside [dir] to the stored copy under [top]. */
    private fun refTarget(dir: File?, name: String, top: File): File? {
        val marker = File(dir ?: return null, name + RefMarker)
        if (!marker.isFile || marker.length() > 128) return null
        val id = marker.readText().trim()
        if (!RefId.matches(id)) return null
        return File(top, ".ref/d/$id").takeIf { it.isFile && it.length() > 0 }
    }

    private fun topDir(rel: String): String = rel.substringBefore('/')

    private val rootGate = Semaphore(3)

    /** Avatar picture of [username]; null when WeChat has none stored. */
    fun avatar(hash: String, username: String?): File? {
        if (username.isNullOrBlank()) return null
        val dir = MediaPaths.avatarDir(username)
        val localDir = File(ws.media(hash), dir)
        localDir.list()?.let { names -> MediaPaths.pickAvatar(names.toList()) }?.let { return File(localDir, it) }
        if (ws.readMeta(hash)?.source != Source.Root.name || !isAccountHash(hash)) return null
        val cached = File(ws.cache(hash), "$dir.jpg")
        if (cached.isFile && cached.length() > 0) return cached
        val missing = File(ws.cache(hash), "$dir.none")
        if (missing.exists()) return null
        rootGate.acquire()
        try {
            if (cached.isFile && cached.length() > 0) return cached
            val name = MediaPaths.pickAvatar(root.listMedia(hash, dir))
            if (name != null && root.copy(root.mediaRemote(hash, "$dir/$name"), cached)) return cached
            missing.parentFile?.mkdirs()
            missing.createNewFile()
            return null
        } finally {
            rootGate.release()
        }
    }

    fun image(hash: String, stored: String?, thumb: Boolean = true) = resolve(hash, MediaPaths.image(stored, thumb))
    fun voice(hash: String, stored: String?) = resolve(hash, MediaPaths.voice(stored))
    fun video(hash: String, stored: String?) = resolve(hash, MediaPaths.video(stored))
    fun videoThumb(hash: String, stored: String?) = resolve(hash, MediaPaths.videoThumb(stored))
}
