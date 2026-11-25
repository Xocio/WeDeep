package com.zeroui.template.ai.net

import com.zeroui.template.ai.AiMessage
import com.zeroui.template.ai.AiProtocol
import com.zeroui.template.ai.AiTurn
import org.json.JSONObject

/** Encoder and decoder of one wire format. */
interface AiCodec {

    /** Request address of a chat turn. */
    fun endpoint(baseUrl: String): String

    /** Authentication and version headers. */
    fun headers(apiKey: String): Map<String, String>

    /** Request body; an empty [system] is left out. */
    fun buildRequest(system: String, history: List<AiMessage>, model: String, stream: Boolean): JSONObject

    /** Parsed reply, or null when [body] is not a model response. */
    fun parseResponse(body: JSONObject): AiTurn?

    /** New parser for one SSE stream. */
    fun newStreamParser(): AiStreamParser
}

/** Parser of one SSE stream; unreadable lines are ignored. */
interface AiStreamParser {

    /** Takes the payload of one `data:` line and returns the new text, if any. */
    fun onData(line: String): String?

    /** Whole reply, or null when no line could be read. */
    fun finish(): AiTurn?
}

fun codecFor(protocol: AiProtocol): AiCodec = when (protocol) {
    AiProtocol.Anthropic -> AnthropicCodec
    AiProtocol.OpenAi -> OpenAiCodec
}

/** Failure class of a request. */
enum class AiError {
    /** 401 / 403. */
    Auth,

    /** 404. */
    NotFound,

    /** 429. */
    RateLimit,

    /** 5xx. */
    Server,

    /** Timeout, DNS, TLS or connection failure. */
    Network,

    /** The body is not a model response. */
    BadResponse,
}

/** String value of [key]; empty for a missing key or a JSON null. */
internal fun JSONObject.stringOrEmpty(key: String): String =
    if (isNull(key)) "" else optString(key)
