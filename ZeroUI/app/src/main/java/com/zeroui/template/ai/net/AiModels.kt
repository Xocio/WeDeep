package com.zeroui.template.ai.net

import com.zeroui.template.ai.AiConfig
import org.json.JSONArray
import org.json.JSONObject

/** Reads the model list of the provider. */
object AiModels {

    /** Most entries kept from one list. */
    private const val MAX = 500

    sealed interface Result {
        /** [truncated] is true when the provider listed more than [ids]. */
        data class Ok(val ids: List<String>, val truncated: Boolean) : Result

        data class Fail(val kind: AiError, val detail: String) : Result
    }

    suspend fun list(cfg: AiConfig.Snapshot): Result {
        val url = AiConfig.modelsEndpointOf(cfg.baseUrl, cfg.protocol)
        if (url.isEmpty()) return Result.Fail(AiError.Network, "URL")

        val headers = codecFor(cfg.protocol).headers(cfg.apiKey)
        return when (val fetched = AiTransport.getJson(url, headers)) {
            is AiTransport.Fetched.Err -> Result.Fail(fetched.kind, fetched.detail)
            is AiTransport.Fetched.Ok -> {
                val all = parseIds(fetched.body)
                if (all.isEmpty()) Result.Fail(AiError.BadResponse, "")
                else Result.Ok(all.take(MAX), truncated = all.size > MAX)
            }
        }
    }

    /** Model names from `data` or `models`, as strings or objects with `id` / `name`. */
    private fun parseIds(body: JSONObject): List<String> {
        val array = body.optJSONArray("data") ?: body.optJSONArray("models") ?: return emptyList()
        return collect(array).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun collect(array: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is String -> if (item.isNotBlank()) out += item
                is JSONObject -> {
                    val id = item.stringOrEmpty("id").ifBlank { item.stringOrEmpty("name") }
                    if (id.isNotBlank()) out += id
                }
            }
        }
        return out
    }
}
