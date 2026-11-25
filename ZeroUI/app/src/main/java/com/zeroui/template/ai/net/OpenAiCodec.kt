package com.zeroui.template.ai.net

import com.zeroui.template.ai.AiConfig
import com.zeroui.template.ai.AiMessage
import com.zeroui.template.ai.AiProtocol
import com.zeroui.template.ai.AiStop
import com.zeroui.template.ai.AiTurn
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI Chat Completions format. */
object OpenAiCodec : AiCodec {

    override fun endpoint(baseUrl: String): String =
        AiConfig.endpointOf(baseUrl, AiProtocol.OpenAi)

    override fun headers(apiKey: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey")

    override fun buildRequest(system: String, history: List<AiMessage>, model: String, stream: Boolean): JSONObject {
        val messages = JSONArray()
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        history.forEach { message ->
            val role = if (message is AiMessage.User) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", message.text))
        }
        val body = JSONObject().put("model", model).put("messages", messages)
        if (stream) body.put("stream", true)
        return body
    }

    override fun parseResponse(body: JSONObject): AiTurn? {
        val choice = body.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val message = choice.optJSONObject("message") ?: return null
        return AiTurn(
            message = AiMessage.Assistant(message.stringOrEmpty("content")),
            stop = stopOf(choice.stringOrEmpty("finish_reason")),
        )
    }

    override fun newStreamParser(): AiStreamParser = StreamParser()

    private fun stopOf(reason: String): AiStop = when (reason) {
        "stop" -> AiStop.EndTurn
        "length" -> AiStop.MaxTokens
        else -> AiStop.Other
    }

    /** Collects `choices[0].delta.content` of every chunk. */
    private class StreamParser : AiStreamParser {
        private val text = StringBuilder()
        private var finishReason = ""
        private var sawAny = false

        override fun onData(line: String): String? {
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: return null
            sawAny = true
            val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return null
            choice.stringOrEmpty("finish_reason").takeIf { it.isNotBlank() }?.let { finishReason = it }
            val delta = choice.optJSONObject("delta") ?: return null
            return delta.stringOrEmpty("content")
                .takeIf { it.isNotEmpty() }
                ?.also { text.append(it) }
        }

        override fun finish(): AiTurn? {
            if (!sawAny) return null
            return AiTurn(AiMessage.Assistant(text.toString()), stopOf(finishReason))
        }
    }
}
