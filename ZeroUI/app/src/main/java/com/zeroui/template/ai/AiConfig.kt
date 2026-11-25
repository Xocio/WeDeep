package com.zeroui.template.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wire format of the model provider. */
enum class AiProtocol { OpenAi, Anthropic }

/**
 * Connection settings of the model provider: address, key, model and protocol.
 * The key is encrypted with an AES-GCM key held in the Android Keystore.
 */
object AiConfig {

    private const val PREF_NAME = "wedeep_ai"

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key_enc"
    private const val KEY_MODEL = "model"
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_ALLOW_HTTP = "allow_http"
    private const val KEY_CONSENTED = "consented"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wedeep_ai_apikey"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        cached ?: context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .also { cached = it }

    /** Settings read in one go for use off the main thread. */
    data class Snapshot(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val protocol: AiProtocol,
    ) {
        val ready: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    fun snapshot(context: Context) = Snapshot(
        baseUrl = getBaseUrl(context),
        apiKey = getApiKey(context),
        model = getModel(context),
        protocol = getProtocol(context),
    )

    /** Request address of a chat turn. */
    fun endpointOf(baseUrl: String, protocol: AiProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return when (protocol) {
            AiProtocol.Anthropic -> "$base/v1/messages"
            AiProtocol.OpenAi -> "$base/chat/completions"
        }
    }

    /** Request address of the model list. */
    fun modelsEndpointOf(baseUrl: String, protocol: AiProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return when (protocol) {
            AiProtocol.Anthropic -> "$base/v1/models"
            AiProtocol.OpenAi -> "$base/models"
        }
    }

    fun getBaseUrl(context: Context): String = prefs(context).getString(KEY_BASE_URL, "").orEmpty()

    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BASE_URL, value.trim()).apply()
    }

    fun getModel(context: Context): String = prefs(context).getString(KEY_MODEL, "").orEmpty()

    fun setModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MODEL, value.trim()).apply()
    }

    fun getProtocol(context: Context): AiProtocol =
        AiProtocol.entries.getOrElse(prefs(context).getInt(KEY_PROTOCOL, 0)) { AiProtocol.OpenAi }

    fun setProtocol(context: Context, value: AiProtocol) {
        prefs(context).edit().putInt(KEY_PROTOCOL, value.ordinal).apply()
    }

    /** True when the user allowed plain http:// requests. */
    fun isHttpAllowed(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_HTTP, false)

    fun setHttpAllowed(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_HTTP, value).apply()
    }

    /** True when the user accepted that chat records are sent to the configured provider. */
    fun isConsented(context: Context): Boolean = prefs(context).getBoolean(KEY_CONSENTED, false)

    fun setConsented(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONSENTED, value).apply()
    }

    /** Stored key, or an empty string when none is stored or it cannot be decrypted. */
    fun getApiKey(context: Context): String {
        val stored = prefs(context).getString(KEY_API_KEY, null) ?: return ""
        return decrypt(stored) ?: ""
    }

    /** Stores the key; false when the Keystore is unusable and nothing was saved. */
    fun setApiKey(context: Context, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clearApiKey(context)
            return true
        }
        val enc = encrypt(trimmed) ?: return false
        prefs(context).edit().putString(KEY_API_KEY, enc).apply()
        return true
    }

    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(KEY_API_KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    /** Ciphertext layout: base64(iv ‖ ciphertext+tag). */
    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        if (raw.size <= IV_LEN) return null
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
        }
        String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }.getOrNull()
}
