package com.zeroui.template.wechat

class AccountService(private val ws: Workspace) {

    fun isFresh(hash: String): Boolean = false

    fun isLoggedInAccount(hash: String): Boolean = false

    fun dropRaw(hash: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    suspend fun prepare(
        hash: String,
        force: Boolean = false,
        manualUin: String? = null,
        manualImei: String? = null,
        cancelled: () -> Boolean = { false },
        onProgress: (PrepareProgress) -> Unit = {},
    ): PrepareResult {
        val meta = (ws.readMeta(hash) ?: AccountMeta(hash)).copy(lastError = Reason.Unavailable.name)
        ws.writeMeta(meta)
        return PrepareResult.Failed(Reason.Unavailable, meta)
    }

    fun report(hash: String): String = "account=$hash\ndecryption=unavailable\n"
}
