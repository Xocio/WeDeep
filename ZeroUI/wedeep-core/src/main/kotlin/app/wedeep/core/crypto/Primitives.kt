package app.wedeep.core.crypto

import java.security.MessageDigest

/** Lowercase hex MD5 of the UTF-8 bytes of [text]. */
fun md5Hex(text: String): String = md5Hex(text.toByteArray(Charsets.UTF_8))

/** Lowercase hex MD5 of [bytes]. */
fun md5Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
