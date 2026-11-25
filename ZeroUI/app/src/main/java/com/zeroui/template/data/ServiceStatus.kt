package com.zeroui.template.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import com.zeroui.template.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** Features the service can turn off; [key] is the name the service uses. [App] is the whole app. */
enum class Feature(val key: String) {
    App("app"),
    Accounts("accounts"),
    Chat("chat"),
    Export("export"),
    Edit("edit"),
    Live("live"),
    Ai("ai"),
}

/** A service rule that turns off one feature, or the whole app, for this version. */
@Immutable
data class Block(
    val id: String,
    val feature: String,
    val title: String,
    val message: String,
    val minVersion: String,
    val maxVersion: String,
    /** End of the rule in milliseconds since the epoch, or 0 when it has no end. */
    val until: Long,
    val buttonText: String,
    /** Web page the button opens; https only. */
    val buttonUrl: String?,
) {
    val hasButton: Boolean get() = buttonText.isNotEmpty() && buttonUrl != null
}

/** Notice published by the service, shown as a dialog. */
@Immutable
data class Notice(
    val id: String,
    val title: String,
    val body: String,
    val repeats: Boolean,
    val isWarning: Boolean,
    val minVersion: String,
    val maxVersion: String,
    /** "zh", "en", "ru", or empty for every language. */
    val language: String,
    val buttonText: String,
    /** Web page the button opens; https only. */
    val buttonUrl: String?,
) {
    val hasButton: Boolean get() = buttonText.isNotEmpty() && buttonUrl != null
}

/**
 * Feature rules and notices of the WeDeep service.
 * The last rules received are kept on the device and stay in force offline until the service lifts them
 * or their end time passes.
 */
class ServiceStatus(
    context: Context,
    private val settings: SettingsRepository,
    private val version: String = BuildConfig.VERSION_NAME,
    private val baseUrl: String? = WeDeepService.baseUrl,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.applicationContext.getSharedPreferences(FileName, Context.MODE_PRIVATE)
    private val _blocks = MutableStateFlow(applicable(parseBlocks(preferences.getString(BlocksKey, null))))
    private val _dialogs = MutableStateFlow<List<Notice>>(emptyList())
    private val shownThisRun = mutableSetOf<String>()

    @Volatile
    private var lastAttempt = 0L

    /** Whether this build connects to the service at all. */
    val available: Boolean get() = baseUrl != null

    /** Rules in force for this version. */
    val blocks: StateFlow<List<Block>> = _blocks.asStateFlow()

    /** Dialog notices in the order they are shown. */
    val dialogs: StateFlow<List<Notice>> = _dialogs.asStateFlow()

    /** Minutes between two checks while the app is in the foreground. */
    @Volatile
    var refreshMinutes: Int = DefaultRefreshMinutes
        private set

    /** The rule that turns off [feature]; a rule for the whole app covers every feature. */
    fun blockFor(feature: Feature, blocks: List<Block> = _blocks.value): Block? {
        val now = clock()
        val live = blocks.filter { it.until == 0L || it.until > now }
        return live.firstOrNull { it.feature == Feature.App.key } ?: live.firstOrNull { it.feature == feature.key }
    }

    /**
     * Fetches the manifest and replaces the stored rules; returns true when the service answered.
     * Calls within [MinIntervalMs] of the previous one are skipped unless [force] is set.
     */
    suspend fun refresh(force: Boolean = false): Boolean {
        val base = baseUrl ?: return false
        val now = clock()
        if (!force && now - lastAttempt < MinIntervalMs) {
            dropExpired()
            return false
        }
        lastAttempt = now
        val root = WeDeepService.fetchManifest(base)
            ?.let { body -> runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() }
        val rules = root?.get("blocks") as? JsonArray
        if (root == null || rules == null) {
            dropExpired()
            return false
        }
        preferences.edit { putString(BlocksKey, rules.toString()) }
        _blocks.value = applicable(parseBlocks(rules))
        refreshMinutes = ((root["config"] as? JsonObject)?.get("refreshMinutes") as? JsonPrimitive)?.intOrNull
            ?.coerceIn(MinRefreshMinutes, MaxRefreshMinutes) ?: DefaultRefreshMinutes
        queueNotices(parseNotices(root))
        return true
    }

    /** The first dialog was answered. */
    fun dialogClosed(notice: Notice) {
        _dialogs.update { list -> list.filterNot { it.id == notice.id } }
        if (notice.repeats) return
        settings.update { current ->
            val ids = current.seenNotices.split(',').filter { it.isNotEmpty() }
            if (notice.id in ids) current else current.copy(seenNotices = (ids + notice.id).takeLast(MaxRemembered).joinToString(","))
        }
    }

    private fun dropExpired() {
        val now = clock()
        _blocks.update { list -> list.filter { it.until == 0L || it.until > now } }
    }

    private fun queueNotices(notices: List<Notice>) {
        val seen = settings.settings.value.seenNotices.split(',').filter { it.isNotEmpty() }.toSet()
        val language = noticeLanguage(settings.settings.value.language)
        val fresh = notices.filter { notice ->
            covers(notice.minVersion, notice.maxVersion) &&
                (notice.language.isEmpty() || notice.language == language) &&
                (notice.repeats || notice.id !in seen) &&
                notice.id !in shownThisRun
        }
        shownThisRun += fresh.map { it.id }
        if (fresh.isNotEmpty()) _dialogs.update { it + fresh }
    }

    /** Rules for this version that have not ended and name a feature this build knows. */
    private fun applicable(blocks: List<Block>): List<Block> {
        val now = clock()
        val known = Feature.entries.map { it.key }.toSet()
        return blocks.filter { it.feature in known && covers(it.minVersion, it.maxVersion) && (it.until == 0L || it.until > now) }
    }

    private fun covers(minVersion: String, maxVersion: String): Boolean =
        (minVersion.isEmpty() || !isNewerVersion(minVersion, version)) &&
            (maxVersion.isEmpty() || !isNewerVersion(version, maxVersion))

    private fun parseBlocks(stored: String?): List<Block> {
        if (stored.isNullOrEmpty()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(stored) as? JsonArray }.getOrNull() ?: return emptyList()
        return parseBlocks(array)
    }

    private fun parseBlocks(array: JsonArray): List<Block> = array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val feature = item.text("feature")
        val message = item.text("message")
        if (feature.isEmpty() || message.isEmpty()) return@mapNotNull null
        Block(
            id = item.text("id"),
            feature = feature,
            title = item.text("title"),
            message = message,
            minVersion = item.text("minVersion"),
            maxVersion = item.text("maxVersion"),
            until = (item["until"] as? JsonPrimitive)?.longOrNull ?: 0L,
            buttonText = item.text("buttonText"),
            buttonUrl = item.text("buttonUrl").takeIf { it.startsWith("https://", ignoreCase = true) },
        )
    }

    private fun parseNotices(root: JsonObject): List<Notice> {
        val items = root["notices"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.text("id")
            val title = item.text("title")
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            Notice(
                id = id,
                title = title,
                body = item.text("body"),
                repeats = item.text("repeat") == "always",
                isWarning = item.text("level") == "warning",
                minVersion = item.text("minVersion"),
                maxVersion = item.text("maxVersion"),
                language = item.text("lang"),
                buttonText = item.text("buttonText"),
                buttonUrl = item.text("buttonUrl").takeIf { it.startsWith("https://", ignoreCase = true) },
            )
        }
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private companion object {
        const val FileName = "service"
        const val BlocksKey = "blocks"
        const val MinIntervalMs = 10 * 60_000L
        const val DefaultRefreshMinutes = 60
        const val MinRefreshMinutes = 10
        const val MaxRefreshMinutes = 1440
        const val MaxRemembered = 100
    }
}

/** Interface language as the service names it: the app setting, or the system language. */
internal fun noticeLanguage(setting: Int): String = when (setting) {
    1 -> "zh"
    2 -> "en"
    3 -> "ru"
    else -> when (Locale.getDefault().language) {
        "zh" -> "zh"
        "ru" -> "ru"
        else -> "en"
    }
}
