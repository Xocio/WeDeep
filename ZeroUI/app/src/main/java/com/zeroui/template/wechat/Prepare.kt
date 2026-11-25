package com.zeroui.template.wechat

/** Outcome of preparing one account for reading. */
sealed interface PrepareResult {
    data class Ready(val meta: AccountMeta, val reused: Boolean) : PrepareResult
    data class Failed(val reason: Reason, val meta: AccountMeta?, val tried: Int = 0) : PrepareResult
}

/** Failure causes shown to the user. */
enum class Reason { NoDatabase, TooSmall, NoUin, NoMatch, Cancelled, Error, Unavailable }

/** Progress of a prepare run. */
data class PrepareProgress(val phase: Phase, val done: Long = 0, val total: Long = 0) {
    enum class Phase { Probing, Decrypting, Indexing }
}
