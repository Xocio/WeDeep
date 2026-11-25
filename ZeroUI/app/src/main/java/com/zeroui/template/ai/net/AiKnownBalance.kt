package com.zeroui.template.ai.net

/** Balance endpoints of known providers, keyed by host. */
object AiKnownBalance {

    /** [path] is relative to the origin; [expr] is a path read by [AiJsonPath]. */
    data class Preset(val path: String, val expr: String)

    private val TABLE = listOf(
        "api.deepseek.com" to Preset(
            path = "/user/balance",
            expr = "balance_infos[0].total_balance",
        ),
        "api.moonshot.cn" to Preset(
            path = "/v1/users/me/balance",
            expr = "data.available_balance",
        ),
        "openrouter.ai" to Preset(
            path = "/api/v1/key",
            expr = "data.limit_remaining",
        ),
        "api.siliconflow.com" to Preset(
            path = "/v1/user/info",
            expr = "data.totalBalance",
        ),
        "api.siliconflow.cn" to Preset(
            path = "/v1/user/info",
            expr = "data.totalBalance",
        ),
    )

    /** Preset whose host equals the host of [baseUrl] or is a parent domain of it. */
    fun find(baseUrl: String): Preset? {
        val host = hostOf(baseUrl) ?: return null
        return TABLE.firstOrNull { (known, _) -> host == known || host.endsWith(".$known") }?.second
    }

    /** Every distinct preset, in trial order. */
    fun all(): List<Preset> = TABLE.map { it.second }.distinct()

    private fun hostOf(baseUrl: String): String? {
        val origin = originOf(baseUrl) ?: return null
        return origin.substringAfter("://")
            .substringBefore(':')
            .lowercase()
            .takeIf { it.isNotEmpty() }
    }

    /** Scheme and host of [baseUrl] without the path, or null without a scheme. */
    fun originOf(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return null
        val pathStart = trimmed.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) trimmed else trimmed.substring(0, pathStart)
    }
}
