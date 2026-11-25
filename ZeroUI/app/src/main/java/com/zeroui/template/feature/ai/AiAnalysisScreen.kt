package com.zeroui.template.feature.ai

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroui.kit.components.AccentBanner
import com.zeroui.kit.components.ArrowPreference
import com.zeroui.kit.components.BreathingDot
import com.zeroui.kit.components.Card
import com.zeroui.kit.components.ConfirmDialog
import com.zeroui.kit.components.PressFeedback
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.IconButton
import com.zeroui.kit.components.ListCard
import com.zeroui.kit.components.ManagedBottomSheet
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.TextField
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.template.R
import com.zeroui.template.ai.AiConfig
import com.zeroui.template.ai.AiLog
import com.zeroui.template.ai.AiMode
import com.zeroui.template.ai.AiPrompts
import com.zeroui.template.core.appViewModel
import com.zeroui.template.feature.wechat.formatCount
import com.zeroui.template.wechat.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val modeTitles = listOf(
    R.string.ai_mode_overview,
    R.string.ai_mode_relationship,
    R.string.ai_mode_personality,
    R.string.ai_mode_timeline,
    R.string.ai_mode_commitments,
    R.string.ai_mode_emotion,
)

private val modeSummaries = listOf(
    R.string.ai_mode_overview_sub,
    R.string.ai_mode_relationship_sub,
    R.string.ai_mode_personality_sub,
    R.string.ai_mode_timeline_sub,
    R.string.ai_mode_commitments_sub,
    R.string.ai_mode_emotion_sub,
)

/** Interval of one typewriter step. */
private const val TYPE_TICK_MS = 25L

private val BodySize = 17.sp
private val BodyLineHeight = 28.sp

/** Conversation with the model about one chat; [isActive] is true while the page is on top. */
@Composable
fun AiAnalysisScreen(
    talker: String,
    isActive: Boolean,
    openSession: String?,
    onSessionOpened: () -> Unit,
    onBack: () -> Unit,
    onOpenProvider: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appContext = context.applicationContext
    val vm = appViewModel(key = "ai-$talker") { AiAnalysisViewModel(wechat, appContext, talker) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    var configReady by remember { mutableStateOf(AiConfig.snapshot(context).ready) }
    LaunchedEffect(isActive) {
        if (isActive) {
            configReady = AiConfig.snapshot(context).ready
            vm.dropIfDeleted()
        }
    }
    LaunchedEffect(openSession) {
        if (openSession != null) {
            vm.open(openSession)
            onSessionOpened()
        }
    }

    var input by remember { mutableStateOf("") }
    var pickingSession by remember { mutableStateOf(false) }
    var pickingRange by remember { mutableStateOf(false) }
    var consentFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var switchTo by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    /** Runs [action] once a conversation is chosen, the provider is set up and sending was accepted. */
    fun guarded(action: () -> Unit) {
        val cfg = AiConfig.snapshot(context)
        when {
            ui.talker.isEmpty() -> {
                toast(resources.getString(R.string.ai_target_first))
                pickingSession = true
            }
            !cfg.ready -> {
                toast(resources.getString(R.string.ai_not_configured))
                onOpenProvider()
            }
            cfg.baseUrl.trim().startsWith("http://", ignoreCase = true) && !AiConfig.isHttpAllowed(context) -> {
                toast(resources.getString(R.string.ai_test_need_http))
                onOpenProvider()
            }
            !AiConfig.isConsented(context) -> consentFor = action
            else -> action()
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || ui.busy) return
        guarded {
            vm.ask(AiConfig.snapshot(context), text, text)
            input = ""
        }
    }

    fun analyze(mode: AiMode, label: String) {
        if (ui.busy) return
        guarded { vm.ask(AiConfig.snapshot(context), label, AiPrompts.instruction(mode)) }
    }

    /** Applies [change] at once on an empty conversation, otherwise after a confirmation. */
    fun switching(change: () -> Unit) {
        if (ui.items.isEmpty()) change() else switchTo = change
    }

    fun copyMessage(text: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("analysis", text))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        toast(resources.getString(R.string.wd_copied))
    }

    val listState = rememberLazyListState()
    var followTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ui.items.size) {
        if (ui.items.isNotEmpty()) {
            listState.animateScrollToItem(ui.items.lastIndex)
            listState.scrollBy(100_000f)
        }
    }
    LaunchedEffect(followTick) {
        if (followTick > 0 && !listState.isScrollInProgress) listState.scrollBy(100_000f)
    }

    ZeroPageFrame(
        title = stringResource(R.string.ai_analysis),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = 150.dp)) },
        actions = {
            val historyText = stringResource(R.string.ai_history)
            TooltipBox(text = historyText) {
                IconButton(onClick = onOpenHistory, enabled = !ui.busy) {
                    Icon(
                        imageVector = MiuixIcons.Recent,
                        contentDescription = historyText,
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            if (ui.items.isNotEmpty() && !ui.busy) {
                val newText = stringResource(R.string.ai_new_chat)
                TooltipBox(text = newText) {
                    IconButton(onClick = vm::newConversation) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = newText,
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            val settingsText = stringResource(R.string.ai_provider)
            TooltipBox(text = settingsText) {
                IconButton(onClick = onOpenProvider) {
                    Icon(
                        imageVector = MiuixIcons.Settings,
                        contentDescription = settingsText,
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pad.calculateTopPadding()),
        ) {
            if (!configReady) {
                ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ai_configure),
                        summary = stringResource(R.string.ai_not_configured),
                        onClick = onOpenProvider,
                    )
                }
            }
            if (ui.loaded && ui.noDatabase) {
                AccentBanner(
                    text = stringResource(R.string.ai_no_db),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    compact = true,
                    danger = true,
                )
            }

            if (ui.items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AiSlogan(stringResource(R.string.ai_slogan))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_empty_hint),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    AiMode.entries.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            pair.forEach { mode ->
                                val label = stringResource(modeTitles[mode.ordinal])
                                ModeCard(
                                    title = label,
                                    summary = stringResource(modeSummaries[mode.ordinal]),
                                    enabled = !ui.busy,
                                    onClick = { analyze(mode, label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                ) {
                    itemsIndexed(ui.items) { _, item ->
                        when (item) {
                            is AiChatItem.User -> Copyable(onCopy = { copyMessage(item.text) }) {
                                UserBubble(item.text)
                            }
                            is AiChatItem.Assistant -> Copyable(onCopy = { copyMessage(item.text) }) {
                                AssistantMessage(item, onTick = { followTick++ })
                            }
                            is AiChatItem.Attached -> AttachedRow(item)
                            is AiChatItem.Status -> StatusRow(item.reading)
                            is AiChatItem.Failed -> FailedRow(item, context)
                        }
                    }
                }
            }

            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(
                        text = ui.title.ifBlank { stringResource(R.string.ai_target_pick) },
                        accent = true,
                        caret = true,
                        enabled = !ui.busy && ui.sessions.isNotEmpty(),
                        onClick = { pickingSession = true },
                    )
                    Chip(
                        text = stringResource(R.string.ai_range_n, AiRanges[ui.range]),
                        accent = true,
                        caret = true,
                        enabled = !ui.busy,
                        onClick = { pickingRange = true },
                    )
                }
                if (ui.items.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AiMode.entries.forEach { mode ->
                            val label = stringResource(modeTitles[mode.ordinal])
                            Chip(text = label, enabled = !ui.busy, onClick = { analyze(mode, label) })
                        }
                    }
                }
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    busy = ui.busy,
                    onSend = ::send,
                    onStop = vm::stop,
                )
            }
        }
    }

    if (pickingSession) {
        SessionPicker(
            sessions = ui.sessions,
            current = ui.talker,
            onPick = { session ->
                pickingSession = false
                if (session.talker != ui.talker) switching { vm.select(session) }
            },
            onDismiss = { pickingSession = false },
        )
    }

    if (pickingRange) {
        ManagedBottomSheet(onDismiss = { pickingRange = false }, title = stringResource(R.string.ai_range)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AiRanges.forEachIndexed { index, count ->
                    PickerRow(
                        title = stringResource(R.string.ai_range_n, count),
                        selected = index == ui.range,
                        onClick = {
                            close {
                                pickingRange = false
                                if (index != ui.range) switching { vm.setRange(index) }
                            }
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.ai_range_sub),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }

    switchTo?.let { change ->
        ConfirmDialog(
            title = stringResource(R.string.ai_new_chat),
            message = stringResource(R.string.ai_switch_msg),
            onDismiss = { switchTo = null },
            onConfirm = {
                switchTo = null
                change()
            },
        )
    }

    consentFor?.let { action ->
        ConfirmDialog(
            title = stringResource(R.string.ai_consent_title),
            message = stringResource(R.string.ai_consent_msg, AiConfig.getBaseUrl(context)),
            confirmText = stringResource(R.string.ai_consent_ok),
            onDismiss = { consentFor = null },
            onConfirm = {
                consentFor = null
                AiConfig.setConsented(context, true)
                action()
            },
        )
    }
}

/** Capsule input with one round button that sends, or stops while a reply is generated. */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canSend = value.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.ai_chat_hint),
            useLabelAsPlaceholder = true,
            cornerRadius = 24.dp,
            modifier = Modifier
                .weight(1f)
                .heightIn(max = 120.dp),
        )
        Spacer(Modifier.width(8.dp))
        val active = busy || canSend
        IconButton(
            onClick = { if (busy) onStop() else if (canSend) onSend() },
            modifier = Modifier.padding(bottom = 4.dp),
            enabled = active,
            backgroundColor = if (active) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.primary.copy(alpha = 0.3f),
            cornerRadius = 22.dp,
            minWidth = 44.dp,
            minHeight = 44.dp,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(if (busy) R.drawable.wd_ic_stop else R.drawable.wd_ic_send),
                contentDescription = stringResource(if (busy) R.string.ai_stop else R.string.ai_send),
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Rounded selectable label; [accent] tints it, [caret] marks it as a selector. */
@Composable
private fun Chip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Boolean = false,
    caret: Boolean = false,
) {
    val tone = if (accent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (accent) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MiuixTheme.colorScheme.surfaceContainer
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (caret) "$text ▾" else text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) tone else tone.copy(alpha = 0.4f),
            maxLines = 1,
        )
    }
}

/** Preset analysis shown on an empty conversation. */
@Composable
private fun ModeCard(
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = if (enabled) onClick else null,
        showIndication = true,
        pressFeedbackType = PressFeedback.Sink,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = summary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            minLines = 2,
        )
    }
}

/** Long press on [content] calls [onCopy] without a press indication. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Copyable(onCopy: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = onCopy,
        )
    ) {
        content()
    }
}

/** User message: right-aligned rounded block. */
@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .background(color = colorResource(R.color.wd_bubble_me), shape = RoundedCornerShape(22.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            fontSize = BodySize,
            lineHeight = 26.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** Model reply laid out full width and revealed a few characters per tick while it streams. */
@Composable
private fun AssistantMessage(item: AiChatItem.Assistant, onTick: () -> Unit) {
    var shown by remember { mutableIntStateOf(if (item.live) 0 else item.text.length) }
    val text by rememberUpdatedState(item.text)
    val live by rememberUpdatedState(item.live)
    val tick by rememberUpdatedState(onTick)
    LaunchedEffect(Unit) {
        while (true) {
            if (shown < text.length) {
                val behind = text.length - shown
                shown = (shown + (behind / 10).coerceIn(1, 8)).coerceAtMost(text.length)
                tick()
            } else if (!live) {
                break
            }
            delay(TYPE_TICK_MS)
        }
    }
    val visible = text.take(shown)
    val typing = live || shown < text.length
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        AiReport(markdown = visible, typing = typing, modifier = Modifier.fillMaxWidth())
        if ((item.cut || item.stopped) && shown >= text.length) {
            Spacer(Modifier.height(10.dp))
            AccentBanner(
                text = stringResource(if (item.cut) R.string.ai_cut else R.string.ai_stopped),
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Note that the records of a chat were attached. */
@Composable
private fun AttachedRow(item: AiChatItem.Attached) {
    Text(
        text = if (item.truncated) {
            stringResource(R.string.ai_attached_truncated, item.title, formatCount(item.count.toLong()), formatCount(item.total))
        } else {
            stringResource(R.string.ai_attached, item.title, formatCount(item.count.toLong()))
        },
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 4.dp),
    )
}

/** Progress line with a breathing dot. */
@Composable
private fun StatusRow(reading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreathingDot(modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(if (reading) R.string.ai_reading else R.string.ai_thinking),
            fontSize = BodySize,
            lineHeight = BodyLineHeight,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FailedRow(item: AiChatItem.Failed, context: Context) {
    val kind = item.kind
    val text = if (kind == null) stringResource(R.string.ai_no_records) else errorMessage(context, kind, item.detail)
    LaunchedEffect(item) {
        if (kind != null && item.detail.isNotBlank()) {
            AiLog.record(context, codeOf(kind), context.getString(R.string.ai_err_detail, item.detail))
        }
    }
    AccentBanner(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        compact = true,
        danger = true,
    )
}

/** Bottom sheet that picks the conversation to analyse. */
@Composable
private fun SessionPicker(
    sessions: List<Session>,
    current: String,
    onPick: (Session) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(sessions, query) {
        val q = query.trim()
        if (q.isEmpty()) sessions
        else sessions.filter { it.title.contains(q, ignoreCase = true) || it.talker.contains(q, ignoreCase = true) }
    }
    ManagedBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.ai_target_pick)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (sessions.size > SEARCH_THRESHOLD) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.ai_target_search),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.ai_target_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    items(shown, key = { it.talker }) { session ->
                        PickerRow(
                            title = session.title,
                            summary = session.summary,
                            selected = session.talker == current,
                            onClick = { close { onPick(session) } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
