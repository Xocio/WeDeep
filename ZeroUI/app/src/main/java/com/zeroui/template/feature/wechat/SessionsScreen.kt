package com.zeroui.template.feature.wechat

import com.zeroui.kit.components.SlicePosition
import com.zeroui.kit.components.CardSlice
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.TextField
import com.zeroui.kit.theme.isMaterial
import com.zeroui.template.wechat.SearchHit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import top.yukonga.miuix.kmp.basic.InputField as MiuixInputField
import top.yukonga.miuix.kmp.basic.SearchBar as MiuixSearchBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zeroui.kit.components.Badge
import com.zeroui.kit.components.SectionTitle
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.kit.theme.ZeroTheme
import com.zeroui.template.R
import com.zeroui.template.core.appViewModel
import com.zeroui.template.navigation.AppRoute
import com.zeroui.template.wechat.PrepareResult
import com.zeroui.template.wechat.Reason
import com.zeroui.template.wechat.Session
import com.zeroui.template.wechat.WeChatHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the conversation tab can show. */
enum class SessionsPhase { Loading, NoAccount, Preparing, Failed, Incompatible, Ready }

@Immutable
data class SessionsUi(
    val phase: SessionsPhase = SessionsPhase.Loading,
    val label: String = "",
    val sessions: List<Session> = emptyList(),
    val failure: Reason? = null,
    val progress: Float? = null,
    val hash: String? = null,
)

class SessionsViewModel(private val hub: WeChatHub) : ViewModel() {
    private val state = MutableStateFlow(SessionsUi())
    val ui: StateFlow<SessionsUi> = state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(hub.current, hub.revision, hub.prepareState) { hash, _, prep -> hash to prep }
                .collectLatest { (hash, prep) -> state.value = compute(hash, prep) }
        }
    }

    private suspend fun compute(hash: String?, prep: com.zeroui.template.wechat.PrepareState): SessionsUi = withContext(Dispatchers.IO) {
        if (hash == null) return@withContext SessionsUi(SessionsPhase.NoAccount)
        val label = hub.label(hash)
        if (prep.running && prep.hash == hash) {
            val p = prep.progress
            val fraction = p?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total }
            return@withContext SessionsUi(SessionsPhase.Preparing, label, progress = fraction)
        }
        val db = hub.db(hash)
        if (db != null) {
            if (!db.isCompatible()) return@withContext SessionsUi(SessionsPhase.Incompatible, label)
            return@withContext SessionsUi(SessionsPhase.Ready, label, db.sessions(), hash = hash)
        }
        val failed = (prep.result as? PrepareResult.Failed)?.takeIf { prep.hash == hash }
        if (failed != null) return@withContext SessionsUi(SessionsPhase.Failed, label, failure = failed.reason)
        val saved = hub.workspace.readMeta(hash)
        if (saved?.lastError?.isNotEmpty() == true) {
            return@withContext SessionsUi(SessionsPhase.Failed, label, failure = runCatching { Reason.valueOf(saved.lastError) }.getOrDefault(Reason.Error))
        }
        hub.startPrepare(hash)
        SessionsUi(SessionsPhase.Preparing, label)
    }

    fun retry() {
        hub.current.value?.let { hub.startPrepare(it, force = true) }
    }
}

@Composable
fun reasonText(reason: Reason?): String = stringResource(
    when (reason) {
        Reason.NoDatabase -> R.string.wd_reason_no_db
        Reason.TooSmall -> R.string.wd_reason_too_small
        Reason.NoUin -> R.string.wd_reason_no_uin
        Reason.NoMatch -> R.string.wd_reason_no_match
        Reason.Cancelled -> R.string.wd_reason_cancelled
        Reason.Unavailable -> R.string.wd_reason_unavailable
        else -> R.string.wd_reason_error
    },
)

private enum class Bucket(val titleRes: Int) {
    Today(R.string.wd_group_today),
    Yesterday(R.string.wd_yesterday),
    Week(R.string.wd_group_week),
    Earlier(R.string.wd_group_earlier),
}

private fun bucketOf(time: Long, now: Long): Bucket {
    val start = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24L * 60 * 60 * 1000
    return when {
        time >= start -> Bucket.Today
        time >= start - day -> Bucket.Yesterday
        time >= start - 6 * day -> Bucket.Week
        else -> Bucket.Earlier
    }
}

/** Conversation list tab. */
@Composable
fun SessionsScreen(bottomPadding: Dp, onNavigate: (AppRoute) -> Unit) {
    val vm = appViewModel { SessionsViewModel(wechat) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val yesterday = stringResource(R.string.wd_yesterday)

    val searchVm = appViewModel { ChatSearchViewModel(wechat) }
    val search by searchVm.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }

    ZeroPageFrame(
        title = stringResource(R.string.wd_tab_chats),
        bottomInset = bottomPadding,
    ) { padding ->
        when (ui.phase) {
            SessionsPhase.Loading -> LoadingBox(Modifier.padding(padding))
            SessionsPhase.NoAccount -> AddDataPanel(padding, onOpenAccounts = { onNavigate(AppRoute.Accounts) })
            SessionsPhase.Preparing -> Column(Modifier.padding(padding)) {
                ProgressCard(
                    title = stringResource(R.string.wd_preparing),
                    progress = ui.progress,
                )
            }
            SessionsPhase.Failed -> if (ui.failure == Reason.Cancelled) StateMessage(
                title = stringResource(R.string.wd_cancelled_title),
                detail = stringResource(R.string.wd_cancelled_detail),
                action = stringResource(R.string.wd_redo),
                onAction = vm::retry,
                modifier = Modifier.padding(padding),
            ) else StateMessage(
                title = stringResource(R.string.wd_failed_title),
                detail = reasonText(ui.failure),
                action = stringResource(R.string.wd_open_accounts),
                onAction = { onNavigate(AppRoute.Accounts) },
                modifier = Modifier.padding(padding),
            )
            SessionsPhase.Incompatible -> StateMessage(
                title = stringResource(R.string.wd_incompatible_title),
                detail = stringResource(R.string.wd_incompatible_detail),
                action = stringResource(R.string.action_retry),
                onAction = vm::retry,
                modifier = Modifier.padding(padding),
            )
            SessionsPhase.Ready -> {
                val now = remember(ui.sessions) { System.currentTimeMillis() }
                val grouped = remember(ui.sessions) { ui.sessions.groupBy { bucketOf(it.time, now) } }
                val matched = remember(ui.sessions, search.query) {
                    val q = search.query.trim()
                    if (q.isEmpty()) emptyList() else ui.sessions.filter { it.title.contains(q, ignoreCase = true) }
                }
                if (searching) BackHandler { searching = false; query = ""; searchVm.setQuery("") }
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    item(key = "search") {
                        ChatSearchBar(
                            query = query,
                            onQueryChange = { query = it; searchVm.setQuery(it) },
                            expanded = searching,
                            onExpandedChange = { open ->
                                searching = open
                                if (!open) { query = ""; searchVm.setQuery("") }
                            },
                        )
                    }
                    if (searching) {
                        searchResults(
                            query = search.query.trim(),
                            sessions = matched,
                            hits = search.hits,
                            busy = search.searching,
                            hash = ui.hash,
                            yesterday = yesterday,
                            onNavigate = onNavigate,
                        )
                        return@LazyColumn
                    }
                    if (ui.sessions.isEmpty()) {
                        item(key = "none") { StateMessage(stringResource(R.string.wd_no_sessions)) }
                    }
                    Bucket.entries.forEach { bucket ->
                        val group = grouped[bucket].orEmpty()
                        if (group.isEmpty()) return@forEach
                        item(key = "h_" + bucket.name) { SectionTitle(stringResource(bucket.titleRes)) }
                        itemsIndexed(group, key = { _, s -> s.talker }) { index, s ->
                            CardSlice(
                                position = SlicePosition.of(index, group.size),
                                modifier = Modifier.padding(horizontal = 12.dp),
                                onClick = { onNavigate(AppRoute.Chat(s.talker)) },
                            ) {
                                SessionRow(s, ui.hash, yesterday)
                            }
                        }
                        item(key = "g_" + bucket.name) { Spacer(Modifier.height(6.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: Session, hash: String?, yesterday: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(s.title, size = 48.dp, hash = hash, username = s.talker)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatListTime(s.time, yesterday),
                    fontSize = 12.sp,
                    color = ZeroTheme.colors.contentMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    s.summary.ifBlank { " " },
                    fontSize = 14.sp,
                    color = ZeroTheme.colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (s.unread > 0) {
                    Badge(
                        text = if (s.unread > 99) "99+" else s.unread.toString(),
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = 11.sp,
                        cornerRadius = 9.dp,
                        horizontalPadding = 6.dp,
                        verticalPadding = 1.dp,
                    )
                }
            }
        }
    }
}

data class ChatSearchUi(val query: String = "", val searching: Boolean = false, val hits: List<SearchHit> = emptyList())

/** Message search over the current account, debounced. */
@OptIn(FlowPreview::class)
class ChatSearchViewModel(private val hub: WeChatHub) : ViewModel() {
    private val query = MutableStateFlow("")
    private val state = MutableStateFlow(ChatSearchUi())
    val ui: StateFlow<ChatSearchUi> = state.asStateFlow()

    init {
        viewModelScope.launch {
            query.debounce(250).collectLatest { q ->
                val hash = hub.current.value
                if (q.isBlank() || hash == null) {
                    state.value = ChatSearchUi(q)
                    return@collectLatest
                }
                state.value = state.value.copy(query = q, searching = true)
                val hits = withContext(Dispatchers.IO) { runCatching { hub.db(hash)?.search(q.trim()) }.getOrNull().orEmpty() }
                state.value = ChatSearchUi(q, false, hits)
            }
        }
    }

    fun setQuery(q: String) {
        query.value = q
        state.value = state.value.copy(query = q)
    }
}

private fun snippet(text: String, keyword: String): String {
    val at = if (keyword.isBlank()) -1 else text.indexOf(keyword, ignoreCase = true)
    if (at <= 12) return text
    return "…" + text.substring(at - 12)
}

/** Search field of the conversation tab; [expanded] shows the cancel action. */
@Composable
private fun ChatSearchBar(query: String, onQueryChange: (String) -> Unit, expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    val hint = stringResource(R.string.wd_search_hint)
    val cancel: @Composable () -> Unit = {
        Text(
            stringResource(R.string.wd_search_cancel),
            fontSize = 16.sp,
            color = ZeroTheme.colors.accent,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onExpandedChange(false) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
    if (isMaterial()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                label = hint,
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = { Icon(MiuixIcons.Search, contentDescription = null, tint = ZeroTheme.colors.contentMuted) },
                modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onExpandedChange(true) },
            )
            if (expanded) cancel()
        }
    } else {
        MiuixSearchBar(
            inputField = {
                MiuixInputField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSearch = {},
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                    label = hint,
                )
            },
            onExpandedChange = onExpandedChange,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            expanded = expanded,
            outsideEndAction = cancel,
        ) {}
    }
}

/** Matching conversations by name, then matching messages. */
private fun LazyListScope.searchResults(
    query: String,
    sessions: List<Session>,
    hits: List<SearchHit>,
    busy: Boolean,
    hash: String?,
    yesterday: String,
    onNavigate: (AppRoute) -> Unit,
) {
    if (query.isEmpty()) return
    if (sessions.isNotEmpty()) {
        item(key = "rs_title") { SectionTitle(stringResource(R.string.wd_search_sessions)) }
        itemsIndexed(sessions, key = { _, s -> "rs_" + s.talker }) { index, s ->
            CardSlice(
                position = SlicePosition.of(index, sessions.size),
                modifier = Modifier.padding(horizontal = 12.dp),
                onClick = { onNavigate(AppRoute.Chat(s.talker)) },
            ) { SessionRow(s, hash, yesterday) }
        }
        item(key = "rs_gap") { Spacer(Modifier.height(6.dp)) }
    }
    when {
        busy -> item(key = "rm_busy") { LoadingBox(Modifier.padding(top = 24.dp)) }
        hits.isEmpty() && sessions.isEmpty() -> item(key = "rm_none") {
            StateMessage(stringResource(R.string.wd_search_empty), Modifier.padding(top = 12.dp), detail = stringResource(R.string.wd_search_scope))
        }
        hits.isNotEmpty() -> {
            item(key = "rm_title") { SectionTitle(stringResource(R.string.wd_search_messages)) }
            itemsIndexed(hits, key = { _, hit -> "rm_" + hit.msgId }) { index, hit ->
                CardSlice(
                    position = SlicePosition.of(index, hits.size),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    onClick = { onNavigate(AppRoute.Chat(hit.talker, hit.msgId)) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Avatar(hit.title, size = 40.dp, hash = hash, username = hit.talker)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(hit.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatListTime(hit.time, yesterday), fontSize = 12.sp, color = ZeroTheme.colors.contentMuted, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                            }
                            Text(
                                snippet(hit.text.replace('\n', ' '), query),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = ZeroTheme.colors.contentMuted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
