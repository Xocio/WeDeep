package app.wedeep.core.media

import app.wedeep.core.crypto.md5Hex

/** Candidate relative paths of media files below the account directory. */
object MediaPaths {
    private const val ThumbPrefix = "THUMBNAIL_DIRPATH://"

    /** Strips the storage scheme from a stored media name. */
    fun cleanName(stored: String): String = stored.removePrefix(ThumbPrefix).substringAfterLast('/')

    /** Image candidates for a stored name, best first. */
    fun image(stored: String?, preferThumb: Boolean = true): List<String> {
        val name = cleanName(stored.orEmpty())
        if (name.isBlank()) return emptyList()
        val core = name.removePrefix("th_")
        if (core.length < 4) return emptyList()
        val dir = "image2/${core.substring(0, 2)}/${core.substring(2, 4)}/"
        val names = if (preferThumb) listOf("th_$core", core, "$core.jpg", "${core}hd") else listOf(core, "$core.jpg", "${core}hd", "th_$core")
        return names.distinct().map { dir + it }
    }

    /** Voice candidates for a stored name. */
    fun voice(stored: String?): List<String> {
        val name = stored.orEmpty()
        if (name.isBlank()) return emptyList()
        val h = md5Hex(name)
        return listOf("voice2/${h.substring(0, 2)}/${h.substring(2, 4)}/msg_$name.amr")
    }

    /** Video and thumbnail candidates for a stored name. */
    fun video(stored: String?): List<String> {
        val name = cleanName(stored.orEmpty())
        return if (name.isBlank()) emptyList() else listOf("video/$name.mp4", "video/$name")
    }

    fun videoThumb(stored: String?): List<String> {
        val name = cleanName(stored.orEmpty())
        return if (name.isBlank()) emptyList() else listOf("video/$name.jpg")
    }

    /** Directory holding the avatar files of [username]. */
    fun avatarDir(username: String): String {
        val h = md5Hex(username)
        return "avatar/${h.substring(0, 2)}/${h.substring(2, 4)}/$h"
    }

    /** Picks the avatar file from a directory listing, preferring a downloaded picture over the placeholder. */
    fun pickAvatar(names: List<String>): String? {
        val small = names.filter { it.startsWith("small_") }
        return small.firstOrNull { it != "small_avatar_no_url" } ?: small.firstOrNull()
    }

    /** True when the audio bytes are a SILK stream the platform cannot play. */
    fun isSilk(head: ByteArray): Boolean {
        val s = String(head, 0, minOf(head.size, 12), Charsets.ISO_8859_1)
        return s.contains("#!SILK_V3")
    }
}

/** File names a picture record uses below the account directory. */
object ImageFiles {
    /** Value of `imgPath` and `thumbImgPath` for a picture with [md5]. */
    fun thumbRef(md5: String): String = "THUMBNAIL_DIRPATH://th_$md5"

    /** Value of `bigImgPath`. */
    fun fullName(md5: String): String = "$md5.jpg"

    /** Directory of the picture files, relative to the account directory. */
    fun dir(md5: String): String = "image2/${md5.substring(0, 2)}/${md5.substring(2, 4)}"

    fun thumbPath(md5: String): String = "${dir(md5)}/th_$md5"

    fun fullPath(md5: String): String = "${dir(md5)}/${fullName(md5)}"
}
