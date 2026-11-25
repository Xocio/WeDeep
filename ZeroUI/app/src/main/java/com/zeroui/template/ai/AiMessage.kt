package com.zeroui.template.ai

/** Protocol-independent chat message. */
sealed interface AiMessage {
    val text: String

    data class User(override val text: String) : AiMessage

    data class Assistant(override val text: String) : AiMessage
}

/** Why the model stopped. */
enum class AiStop { EndTurn, MaxTokens, Other }

/** One model reply. */
data class AiTurn(val message: AiMessage.Assistant, val stop: AiStop)
