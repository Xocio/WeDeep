package com.zeroui.template.data

import com.zeroui.template.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL

/** Available application update. */
data class AvailableUpdate(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
)

/** Result of an update check. */
sealed interface UpdateResult {
    data class Available(val update: AvailableUpdate) : UpdateResult
    data object UpToDate : UpdateResult
    data object Failed : UpdateResult
}

/** Source of update information. */
fun interface UpdateChecker {
    suspend fun check(currentVersion: String): UpdateResult
}

/** WeDeep service settings written at build time. */
object WeDeepService {
    /** Base address of the service, or null in builds without one. */
    val baseUrl: String? = BuildConfig.SERVICE_URL.trim().trimEnd('/').takeIf { it.startsWith("https://") || it.startsWith("http://") }

    /** Client name sent with every request. */
    val userAgent: String = "WeDeep-Android/" + BuildConfig.VERSION_NAME

    /** Update checker, or null in builds without a service address. */
    val updateChecker: UpdateChecker? = baseUrl?.let { ServiceUpdateChecker(it) }

    private const val TimeoutMillis = 8_000

    /** Downloads the service manifest; null when it cannot be reached. */
    suspend fun fetchManifest(baseUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$baseUrl/v1/manifest").openConnection() as HttpURLConnection
            connection.connectTimeout = TimeoutMillis
            connection.readTimeout = TimeoutMillis
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

/** Reads the release from the service manifest. */
class ServiceUpdateChecker(private val baseUrl: String) : UpdateChecker {
    override suspend fun check(currentVersion: String): UpdateResult {
        val body = WeDeepService.fetchManifest(baseUrl) ?: return UpdateResult.Failed
        return runCatching { parseUpdate(body, currentVersion) }.getOrDefault(UpdateResult.Failed)
    }
}

/** The release of a manifest when it is newer than [currentVersion]. */
internal fun parseUpdate(body: String, currentVersion: String): UpdateResult {
    val android = Json.parseToJsonElement(body).jsonObject["android"] as? JsonObject ?: return UpdateResult.UpToDate
    val version = (android["version"] as? JsonPrimitive)?.content.orEmpty()
    if (!VersionPattern.matches(version) || !isNewerVersion(version, currentVersion)) return UpdateResult.UpToDate
    val download = (android["downloads"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.firstOrNull { it.startsWith("https://") }
        ?: return UpdateResult.UpToDate
    val notes = (android["notes"] as? JsonPrimitive)?.content.orEmpty()
    return UpdateResult.Available(AvailableUpdate(version, notes, download))
}

private val VersionPattern = Regex("^\\d+\\.\\d+\\.\\d+$")

/**
 * Compares dotted version strings numerically.
 * Suffixes are not understood: "0-beta" reads as 0, so 1.2.0-beta equals 1.2.0.
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val left = candidate.split(".").map { it.toIntOrNull() ?: 0 }
    val right = current.split(".").map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(left.size, right.size)) {
        val difference = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
        if (difference != 0) return difference > 0
    }
    return false
}
