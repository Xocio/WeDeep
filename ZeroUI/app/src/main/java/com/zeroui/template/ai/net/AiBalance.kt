package com.zeroui.template.ai.net

import com.zeroui.template.ai.AiConfig
import org.json.JSONArray
import org.json.JSONObject

/** Reads the remaining balance of the configured key. */
object AiBalance {

    sealed interface Result {
        /** [value] is ready to display. */
        data class Ok(val value: String) : Result

        data class Fail(val kind: AiError, val detail: String) : Result

        /** No preset produced a value; [shape] lists the keys of the last readable body. */
        data class NotFound(val shape: String?) : Result
    }

    /** Uses the preset of a known host, or tries every preset against the configured host. */
    suspend fun fetch(cfg: AiConfig.Snapshot): Result {
        val origin = AiKnownBalance.originOf(cfg.baseUrl)
            ?: return Result.Fail(AiError.Network, "URL")
        val headers = codecFor(cfg.protocol).headers(cfg.apiKey)

        val candidates = AiKnownBalance.find(cfg.baseUrl)
            ?.let { listOf(it) }
            ?: AiKnownBalance.all()

        var sawAuth = false
        var shape: String? = null

        for (preset in candidates) {
            when (val fetched = AiTransport.getJson(origin + preset.path, headers)) {
                is AiTransport.Fetched.Ok -> {
                    val value = AiJsonPath.read(preset.expr, fetched.body)
                    if (value != null) return Result.Ok(value)
                    shape = shapeOf(fetched.body)
                }

                is AiTransport.Fetched.Err -> when (fetched.kind) {
                    AiError.NotFound, AiError.BadResponse -> Unit
                    AiError.Auth -> sawAuth = true
                    else -> return Result.Fail(fetched.kind, fetched.detail)
                }
            }
        }

        return if (sawAuth) Result.Fail(AiError.Auth, "") else Result.NotFound(shape)
    }

    /** Key structure of [json] without any values. */
    private fun shapeOf(json: JSONObject, depth: Int = 0): String {
        if (depth > 2) return "{…}"
        val keys = json.keys().asSequence().take(12).toList()
        if (keys.isEmpty()) return "{}"
        return keys.joinToString(", ", "{", "}") { key ->
            when (val value = json.opt(key)) {
                is JSONObject -> key + shapeOf(value, depth + 1)
                is JSONArray -> "$key[]"
                else -> key
            }
        }
    }
}

/** Reads a value at a path such as `data.items[0].total`. */
object AiJsonPath {

    private val segment = Regex("""([A-Za-z_][A-Za-z0-9_]*)((?:\[\d+])*)""")
    private val index = Regex("""\[(\d+)]""")

    /** Formatted value at [path], or null when the path does not resolve to a number or string. */
    fun read(path: String, root: JSONObject): String? {
        var cur: Any? = root
        for (part in path.split('.')) {
            val m = segment.matchEntire(part.trim()) ?: return null
            cur = (cur as? JSONObject)?.opt(m.groupValues[1]) ?: return null
            for (i in index.findAll(m.groupValues[2])) {
                cur = (cur as? JSONArray)?.opt(i.groupValues[1].toInt()) ?: return null
            }
        }
        return when (cur) {
            is Number -> format(cur.toDouble())
            is String -> cur.toDoubleOrNull()?.let { format(it) } ?: cur.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun format(n: Double): String =
        if (n == n.toLong().toDouble()) n.toLong().toString() else "%.2f".format(n)
}
