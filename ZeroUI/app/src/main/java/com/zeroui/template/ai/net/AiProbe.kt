package com.zeroui.template.ai.net

import com.zeroui.template.ai.AiConfig
import com.zeroui.template.ai.AiMessage

/** One-shot request of the "Test connection" row. */
object AiProbe {

    private const val PROBE_PROMPT = "Reply with the single word OK."

    /** Length limit of the reply kept for the log. */
    private const val REPLY_MAX = 200

    sealed interface Result {
        /** The provider answered with a readable reply; [reply] is its text. */
        data class Ok(val reply: String) : Result

        /** [detail] is the server message or a short error code. */
        data class Fail(val kind: AiError, val detail: String) : Result
    }

    suspend fun test(cfg: AiConfig.Snapshot): Result {
        val outcome = AiTransport.send(
            cfg = cfg,
            codec = codecFor(cfg.protocol),
            system = "",
            history = listOf(AiMessage.User(PROBE_PROMPT)),
        )
        return when (outcome) {
            is AiTransport.Outcome.Ok -> {
                val text = outcome.turn.message.text.trim()
                if (text.isEmpty()) {
                    Result.Fail(AiError.BadResponse, "")
                } else {
                    Result.Ok(if (text.length <= REPLY_MAX) text else text.take(REPLY_MAX) + "…")
                }
            }

            is AiTransport.Outcome.Err -> Result.Fail(outcome.kind, outcome.detail)
        }
    }
}
