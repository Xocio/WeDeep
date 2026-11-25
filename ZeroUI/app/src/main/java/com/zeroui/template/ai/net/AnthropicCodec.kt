package com.zeroui.template.ai.net

import com.zeroui.template.ai.AiConfig
import com.zeroui.template.ai.AiMessage
import com.zeroui.template.ai.AiProtocol
import com.zeroui.template.ai.AiStop
import com.zeroui.template.ai.AiTurn
import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Messages format. */
object AnthropicCodec : AiCodec {

    /** Output limit of one reply. */
    private const val MAX_TOKENS = 8192

    private const val API_VERSION = "2023-06-01"

    override fun endpoint(baseUrl: String): String =
        AiConfig.endpointOf(baseUrl, AiProtocol.Anthropic)

    override fun headers(apiKey: String): Map<String, String> = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to API_VERSION,
    )

    override fun buildRequest(system: String, history: List<AiMessage>, model: String, stream: Boolean): JSONObject {
        val messages = JSONArray()
        history.forEach { message ->
            val role = if (message is AiMessage.User) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", message.text))
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("messages", messages)
        if (system.isNotBlank()) body.put("system", system)
        if (stream) body.put("stream", true)
        return body
    }

    override fun parseResponse(body: JSONObject): AiTurn? {
        val content = body.optJSONArray("content") ?: return null
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") text.append(block.stringOrEmpty("text"))
        }
        return AiTurn(AiMessage.Assistant(text.toString()), stopOf(body.stringOrEmpty("stop_reason")))
    }

    override fun newStreamParser(): AiStreamParser = StreamParser()

    private fun stopOf(reason: String): AiStop = when (reason) {
        "end_turn", "stop_sequence" -> AiStop.EndTurn
        "max_tokens" -> AiStop.MaxTokens
        else -> AiStop.Other
    }

    /** Collects the `text_delta` events of the stream. */
    private class StreamParser : AiStreamParser {
        private val text = StringBuilder()
        private var stopReason = ""
        private var sawAny = false

        override fun onData(line: String): String? {
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: return null
            sawAny = true
            return when (obj.optString("type")) {
                "content_block_delta" -> {
                    val delta = obj.optJSONObject("delta") ?: return null
                    if (delta.optString("type") != "text_delta") return null
                    delta.stringOrEmpty("text").takeIf { it.isNotEmpty() }?.also { text.append(it) }
                }

                "message_delta" -> {
                    obj.optJSONObject("delta")?.stringOrEmpty("stop_reason")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stopReason = it }
                    null
                }

                else -> null
            }
        }

        override fun finish(): AiTurn? {
            if (!sawAny) return null
            return AiTurn(AiMessage.Assistant(text.toString()), stopOf(stopReason))
        }
    }
}
