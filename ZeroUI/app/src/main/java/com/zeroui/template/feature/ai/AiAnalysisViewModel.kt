package com.zeroui.template.feature.ai

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zeroui.template.ai.AiConfig
import com.zeroui.template.ai.AiMessage
import com.zeroui.template.ai.AiPrompts
import com.zeroui.template.ai.AiSavedItem
import com.zeroui.template.ai.AiSession
import com.zeroui.template.ai.AiSessionStore
import com.zeroui.template.ai.AiStop
import com.zeroui.template.ai.ChatTranscript
import com.zeroui.template.ai.net.AiError
import com.zeroui.template.ai.net.AiTransport
import com.zeroui.template.ai.net.codecFor
import com.zeroui.template.wechat.Session
import com.zeroui.template.wechat.WeChatHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Message counts offered by the range selector. */
val AiRanges = listOf(200, 500, 1000, 3000)

/** Character limit of the records sent in one conversation. */
const val AI_MAX_CHARS = 100_000

/** One entry of the analysis conversation. */
@Immutable
sealed interface AiChatItem {
    data class User(val text: String) : AiChatItem

    /** [live] is true while the reply is still streaming. */
    data class Assistant(
        val text: String,
        val live: Boolean = false,
        val cut: Boolean = false,
        val stopped: Boolean = false,
    ) : AiChatItem

    /** Records attached to the conversation. */
    data class Attached(val title: String, val count: Int, val total: Long, val truncated: Boolean) : AiChatItem

    /** Progress line; [reading] is true while the records are read. */
    data class Status(val reading: Boolean) : AiChatItem

    /** Failed request; a null [kind] means no records could be read. */
    data class Failed(val kind: AiError?, val detail: String = "") : AiChatItem
}

@Immutable
data class AiChatUi(
    val sessions: List<Session> = emptyList(),
    val loaded: Boolean = false,
    val noDatabase: Boolean = false,
    val talker: String = "",
    val title: String = "",
    val range: Int = 1,
    val items: List<AiChatItem> = emptyList(),
    val busy: Boolean = false,
)

/** Conversation about one chat; the records are attached to the first message. */
class AiAnalysisViewModel(
    private val hub: WeChatHub,
    private val appContext: Context,
    initialTalker: String,
) : ViewModel() {
    private val state = MutableStateFlow(AiChatUi(talker = initialTalker))
    val ui: StateFlow<AiChatUi> = state.asStateFlow()

    private val system = AiPrompts.system()
    private var history: List<AiMessage> = emptyList()
    private var attached = false
    private var lastCarriesRecords = false
    private var job: Job? = null

    private companion object {
        /** Scope of the requests; a reply keeps running and is saved after the page is left. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    private var sessionId: String? = null
    private var createdAt = 0L

    init {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val hash = hub.current.value ?: return@withContext null
                hub.db(hash)?.sessions()
            }
            state.update { s ->
                val sessions = result.orEmpty()
                s.copy(
                    sessions = sessions,
                    loaded = true,
                    noDatabase = result == null,
                    title = sessions.firstOrNull { it.talker == s.talker }?.title ?: s.title,
                )
            }
        }
    }

    /** Switches to [session] and starts a new conversation. */
    fun select(session: Session) {
        if (state.value.busy || session.talker == state.value.talker) return
        reset()
        state.update { it.copy(talker = session.talker, title = session.title) }
    }

    /** Changes the message range and starts a new conversation. */
    fun setRange(index: Int) {
        if (state.value.busy || index == state.value.range) return
        reset()
        state.update { it.copy(range = index) }
    }

    fun newConversation() {
        if (state.value.busy) return
        reset()
    }

    private fun reset() {
        history = emptyList()
        attached = false
        lastCarriesRecords = false
        sessionId = null
        state.update { it.copy(items = emptyList()) }
    }

    /** Opens the stored conversation [id] in place of the current one. */
    fun open(id: String) {
        if (state.value.busy || id == sessionId) return
        viewModelScope.launch {
            val session = AiSessionStore.load(appContext, id) ?: return@launch
            history = session.history
            attached = session.attached
            lastCarriesRecords = false
            sessionId = session.id
            createdAt = session.createdAt
            state.update { s ->
                s.copy(
                    talker = session.talker,
                    title = s.sessions.firstOrNull { it.talker == session.talker }?.title ?: session.chatTitle,
                    range = session.range.coerceIn(0, AiRanges.lastIndex),
                    items = session.items.map { item ->
                        when (item) {
                            is AiSavedItem.User -> AiChatItem.User(item.text)
                            is AiSavedItem.Assistant -> AiChatItem.Assistant(item.text, cut = item.cut, stopped = item.stopped)
                            is AiSavedItem.Attached -> AiChatItem.Attached(item.title, item.count, item.total, item.truncated)
                        }
                    },
                )
            }
        }
    }

    /** Starts a new conversation when the open one was deleted from the history. */
    fun dropIfDeleted() {
        val id = sessionId ?: return
        if (!state.value.busy && !AiSessionStore.exists(appContext, id)) reset()
    }

    /** Writes the current conversation to the history. */
    private fun persist() {
        val s = state.value
        val account = hub.current.value ?: return
        val items = s.items.mapNotNull { item ->
            when (item) {
                is AiChatItem.User -> AiSavedItem.User(item.text)
                is AiChatItem.Assistant -> if (item.live) null else AiSavedItem.Assistant(item.text, item.cut, item.stopped)
                is AiChatItem.Attached -> AiSavedItem.Attached(item.title, item.count, item.total, item.truncated)
                else -> null
            }
        }
        if (items.none { it is AiSavedItem.Assistant }) return
        val now = System.currentTimeMillis()
        val id = sessionId ?: AiSessionStore.newId().also {
            sessionId = it
            createdAt = now
        }
        val session = AiSession(
            id = id,
            account = account,
            talker = s.talker,
            chatTitle = s.title,
            range = s.range,
            createdAt = createdAt,
            updatedAt = now,
            attached = attached,
            history = history,
            items = items,
        )
        appScope.launch { AiSessionStore.save(appContext, session) }
    }

    /** Sends one message; [label] is shown in the bubble and [instruction] is sent to the model. */
    fun ask(cfg: AiConfig.Snapshot, label: String, instruction: String) {
        val s = state.value
        if (s.busy || s.talker.isEmpty() || instruction.isBlank()) return
        state.update { it.copy(busy = true, items = it.items + AiChatItem.User(label)) }
        job = appScope.launch {
            val content = if (attached) {
                lastCarriesRecords = false
                instruction
            } else {
                add(AiChatItem.Status(reading = true))
                val transcript = withContext(Dispatchers.IO) {
                    val hash = hub.current.value ?: return@withContext null
                    val db = hub.db(hash) ?: return@withContext null
                    runCatching {
                        ChatTranscript.build(db, s.talker, s.title, AiRanges[s.range], AI_MAX_CHARS)
                    }.getOrNull()
                }
                dropStatus()
                if (transcript == null || transcript.count == 0) {
                    state.update { it.copy(busy = false, items = it.items + AiChatItem.Failed(null)) }
                    return@launch
                }
                add(AiChatItem.Attached(transcript.title, transcript.count, transcript.total, transcript.truncated))
                attached = true
                lastCarriesRecords = true
                AiPrompts.request(transcript, instruction)
            }
            history = history + AiMessage.User(content)
            add(AiChatItem.Status(reading = false))

            val outcome = AiTransport.send(
                cfg = cfg,
                codec = codecFor(cfg.protocol),
                system = system,
                history = history,
                onDelta = ::appendDelta,
            )
            when (outcome) {
                is AiTransport.Outcome.Ok -> {
                    val streamed = (state.value.items.lastOrNull() as? AiChatItem.Assistant)?.text.orEmpty()
                    val text = outcome.turn.message.text.ifEmpty { streamed }
                    if (text.isBlank()) {
                        fail(AiError.BadResponse, "")
                    } else {
                        history = history + AiMessage.Assistant(text)
                        replaceTail(AiChatItem.Assistant(text, cut = outcome.turn.stop == AiStop.MaxTokens))
                        state.update { it.copy(busy = false) }
                        persist()
                    }
                }

                is AiTransport.Outcome.Err -> fail(outcome.kind, outcome.detail)
            }
        }
    }

    /** Stops the current reply and keeps what has arrived. */
    fun stop() {
        job?.cancel()
        job = null
        state.update { s ->
            val items = s.items.toMutableList()
            when (val last = items.lastOrNull()) {
                is AiChatItem.Status -> items.removeAt(items.lastIndex)
                is AiChatItem.Assistant -> if (last.live) {
                    if (last.text.isBlank()) items.removeAt(items.lastIndex)
                    else items[items.lastIndex] = last.copy(live = false, stopped = true)
                }
                else -> Unit
            }
            s.copy(busy = false, items = items)
        }
        if (history.lastOrNull() is AiMessage.User) {
            val kept = state.value.items.lastOrNull() as? AiChatItem.Assistant
            if (kept != null && kept.stopped) history = history + AiMessage.Assistant(kept.text)
            else dropLastQuestion()
        }
        persist()
    }

    private fun fail(kind: AiError, detail: String) {
        dropLastQuestion()
        state.update { s ->
            val items = s.items.toMutableList()
            val last = items.lastOrNull()
            if (last is AiChatItem.Status || (last is AiChatItem.Assistant && last.live)) items.removeAt(items.lastIndex)
            s.copy(busy = false, items = items + AiChatItem.Failed(kind, detail))
        }
        persist()
    }

    private fun dropLastQuestion() {
        if (history.lastOrNull() !is AiMessage.User) return
        history = history.dropLast(1)
        if (lastCarriesRecords) {
            attached = false
            lastCarriesRecords = false
        }
    }

    private fun appendDelta(delta: String) {
        state.update { s ->
            val items = s.items.toMutableList()
            when (val last = items.lastOrNull()) {
                is AiChatItem.Status -> items[items.lastIndex] = AiChatItem.Assistant(delta, live = true)
                is AiChatItem.Assistant -> if (last.live) items[items.lastIndex] = last.copy(text = last.text + delta)
                else -> return@update s
            }
            s.copy(items = items)
        }
    }

    private fun add(item: AiChatItem) = state.update { it.copy(items = it.items + item) }

    private fun dropStatus() = state.update { s ->
        if (s.items.lastOrNull() is AiChatItem.Status) s.copy(items = s.items.dropLast(1)) else s
    }

    /** Replaces the trailing status or live reply with [item]. */
    private fun replaceTail(item: AiChatItem) = state.update { s ->
        val items = s.items.toMutableList()
        val last = items.lastOrNull()
        if (last is AiChatItem.Status || (last is AiChatItem.Assistant && last.live)) items[items.lastIndex] = item
        else items += item
        s.copy(items = items)
    }
}
