package com.zeroui.template.ai.net

import com.zeroui.template.ai.AiConfig
import com.zeroui.template.ai.AiMessage
import com.zeroui.template.ai.AiTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sends one chat turn or one GET request to the provider. */
object AiTransport {

    private const val CONNECT_TIMEOUT_MS = 10_000

    /** Read timeout of a chat request. */
    private const val READ_TIMEOUT_MS = 180_000

    /** Read timeout of a GET request. */
    private const val GET_TIMEOUT_MS = 20_000

    /** Length limit of the error text kept from the server. */
    private const val DETAIL_MAX = 200

    /** End marker of an OpenAI stream. */
    private const val DONE = "[DONE]"

    /** Size limit of a non-SSE body collected during a stream. */
    private const val MAX_WHOLE = 1024 * 1024

    sealed interface Outcome {
        data class Ok(val turn: AiTurn) : Outcome
        data class Err(val kind: AiError, val detail: String) : Outcome
    }

    sealed interface Fetched {
        data class Ok(val body: JSONObject) : Fetched
        data class Err(val kind: AiError, val detail: String) : Fetched
    }

    /** Sends a GET request and returns the JSON body. */
    suspend fun getJson(url: String, headers: Map<String, String>): Fetched =
        withContext(Dispatchers.IO) {
            val conn = try {
                open(url, headers, method = "GET", readTimeoutMs = GET_TIMEOUT_MS)
            } catch (e: Exception) {
                return@withContext Fetched.Err(AiError.Network, diagOf(e))
            }
            try {
                when (val raw = readJson(conn)) {
                    is Raw.Ok -> Fetched.Ok(raw.body)
                    is Raw.Err -> Fetched.Err(raw.kind, raw.detail)
                }
            } catch (e: Exception) {
                Fetched.Err(AiError.Network, diagOf(e))
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Sends one chat turn. With [onDelta] the reply is streamed; a stream that yields
     * nothing is retried once without streaming.
     */
    suspend fun send(
        cfg: AiConfig.Snapshot,
        codec: AiCodec,
        system: String,
        history: List<AiMessage>,
        onDelta: ((String) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        val url = codec.endpoint(cfg.baseUrl)
        val headers = codec.headers(cfg.apiKey)

        if (onDelta != null) {
            val body = codec.buildRequest(system, history, cfg.model, stream = true)
            stream(url, headers, body, codec, onDelta)?.let { return@withContext it }
        }

        val body = codec.buildRequest(system, history, cfg.model, stream = false)
        when (val raw = post(url, headers, body)) {
            is Raw.Err -> Outcome.Err(raw.kind, raw.detail)
            is Raw.Ok -> codec.parseResponse(raw.body)
                ?.let { Outcome.Ok(it) }
                ?: Outcome.Err(AiError.BadResponse, snippet(raw.body.toString()))
        }
    }

    /** One SSE request; null when the stream produced nothing. */
    private suspend fun stream(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        codec: AiCodec,
        onDelta: (String) -> Unit,
    ): Outcome? {
        val conn = try {
            open(url, headers).apply { setRequestProperty("Accept", "text/event-stream") }
        } catch (e: Exception) {
            return Outcome.Err(AiError.Network, diagOf(e))
        }

        val parser = codec.newStreamParser()
        val whole = StringBuilder()
        val context = currentCoroutineContext()

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return Outcome.Err(kindOf(code), errorMessageOf(text, code))
            }

            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    context.ensureActive()
                    val line = reader.readLine() ?: break
                    val data = sseData(line)
                    if (data == null) {
                        if (whole.length < MAX_WHOLE) whole.append(line)
                        continue
                    }
                    if (data == DONE) break
                    parser.onData(data)?.let(onDelta)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return parser.finish()?.takeIf { it.message.text.isNotEmpty() }?.let { Outcome.Ok(it) }
                ?: Outcome.Err(AiError.Network, diagOf(e))
        } finally {
            conn.disconnect()
        }

        parser.finish()?.let { return Outcome.Ok(it) }

        val fallbackBody = runCatching { JSONObject(whole.toString()) }.getOrNull() ?: return null
        fallbackBody.optJSONObject("error")?.let {
            return Outcome.Err(AiError.BadResponse, snippet(it.stringOrEmpty("message")))
        }
        return codec.parseResponse(fallbackBody)?.let { Outcome.Ok(it) }
    }

    /** Payload of an SSE `data:` line, or null for any other line. */
    private fun sseData(line: String): String? {
        if (line.isBlank() || line.startsWith(":")) return null
        if (!line.startsWith("data:")) return null
        return line.removePrefix("data:").trim()
    }

    private sealed interface Raw {
        data class Ok(val body: JSONObject) : Raw
        data class Err(val kind: AiError, val detail: String) : Raw
    }

    private fun open(
        url: String,
        headers: Map<String, String>,
        method: String = "POST",
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doOutput = method == "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
        }

    /** Reads the response as JSON, treating a body with an `error` object as a failure. */
    private fun readJson(conn: HttpURLConnection): Raw {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) return Raw.Err(kindOf(code), errorMessageOf(text, code))

        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return Raw.Err(AiError.BadResponse, snippet(text))

        val err = json.optJSONObject("error")
        if (err != null) return Raw.Err(AiError.BadResponse, snippet(err.stringOrEmpty("message")))
        return Raw.Ok(json)
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): Raw {
        val conn = try {
            open(url, headers)
        } catch (e: Exception) {
            return Raw.Err(AiError.Network, diagOf(e))
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readJson(conn)
        } catch (e: Exception) {
            Raw.Err(AiError.Network, diagOf(e))
        } finally {
            conn.disconnect()
        }
    }

    private fun kindOf(code: Int): AiError = when {
        code == 401 || code == 403 -> AiError.Auth
        code == 404 -> AiError.NotFound
        code == 429 -> AiError.RateLimit
        code >= 500 -> AiError.Server
        else -> AiError.BadResponse
    }

    /** Message the server wrote into an error body, or the status code with a short excerpt. */
    private fun errorMessageOf(text: String, code: Int): String {
        val obj = runCatching { JSONObject(text) }.getOrNull()
        val message = obj?.optJSONObject("error")?.stringOrEmpty("message")?.takeIf { it.isNotBlank() }
            ?: obj?.stringOrEmpty("message")?.takeIf { it.isNotBlank() }
        return message?.let { snippet(it) } ?: ("HTTP$code " + snippet(text)).trim()
    }

    private fun snippet(text: String): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= DETAIL_MAX) one else one.take(DETAIL_MAX) + "…"
    }

    /** Short code of a connection failure. */
    private fun diagOf(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "TIMEOUT"
        is java.net.UnknownHostException -> "DNS"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "CONN"
        is javax.net.ssl.SSLException -> "TLS"
        is java.net.MalformedURLException -> "URL"
        is java.io.IOException -> "IO"
        else -> "ERR"
    }
}
