package com.zeroui.template.feature.ai

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroui.kit.components.Badge
import com.zeroui.kit.components.Card
import com.zeroui.kit.components.ConfirmDialog
import com.zeroui.kit.components.HorizontalDivider
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.IconButton
import com.zeroui.kit.components.SectionTitle
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.template.R
import com.zeroui.template.ZeroApp
import com.zeroui.template.ai.AiSession
import com.zeroui.template.ai.AiSessionStore
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Stored analysis conversations of the selected account, grouped by day. */
@Composable
fun AiHistoryPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = remember { (context.applicationContext as ZeroApp).container.wechat.current.value }

    var sessions by remember { mutableStateOf<List<AiSession>?>(null) }
    var deleting by remember { mutableStateOf<AiSession?>(null) }
    var deletingAll by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { sessions = if (account == null) emptyList() else AiSessionStore.list(context, account) }
    }
    LaunchedEffect(Unit) { reload() }

    val list = sessions.orEmpty()

    ZeroPageFrame(
        title = stringResource(R.string.ai_history),
        onBack = onBack,
        actions = {
            if (list.isNotEmpty()) {
                val clearText = stringResource(R.string.ai_history_clear)
                TooltipBox(text = clearText) {
                    IconButton(onClick = { deletingAll = true }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = clearText,
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    ) { pad ->
        if (sessions != null && list.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                AiSlogan(stringResource(R.string.ai_history_empty))
            }
            return@ZeroPageFrame
        }

        val now = System.currentTimeMillis()
        val grouped = list.groupBy { bucketOf(it.updatedAt, now) }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = pad) {
            Bucket.entries.forEach { bucket ->
                val group = grouped[bucket].orEmpty()
                if (group.isEmpty()) return@forEach
                item(key = "h_" + bucket.name) { SectionTitle(stringResource(bucket.titleRes)) }
                items(group, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onOpen = { onOpen(session.id) },
                        onDelete = { deleting = session },
                    )
                }
            }
        }
    }

    deleting?.let { session ->
        ConfirmDialog(
            title = stringResource(R.string.ai_history_delete),
            message = stringResource(R.string.ai_history_delete_msg, session.title.ifBlank { session.chatTitle }),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    AiSessionStore.delete(context, session.id)
                    reload()
                }
            },
        )
    }

    if (deletingAll) {
        ConfirmDialog(
            title = stringResource(R.string.ai_history_clear),
            message = stringResource(R.string.ai_history_clear_msg, list.size),
            onDismiss = { deletingAll = false },
            onConfirm = {
                deletingAll = false
                scope.launch {
                    account?.let { AiSessionStore.deleteAll(context, it) }
                    reload()
                }
            },
        )
    }
}

private enum class Bucket(@StringRes val titleRes: Int) {
    Today(R.string.ai_group_today),
    Yesterday(R.string.ai_group_yesterday),
    Week(R.string.ai_group_week),
    Earlier(R.string.ai_group_earlier),
}

private fun bucketOf(updatedAt: Long, now: Long): Bucket {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24L * 60 * 60 * 1000
    return when {
        updatedAt >= startOfToday -> Bucket.Today
        updatedAt >= startOfToday - day -> Bucket.Yesterday
        updatedAt >= startOfToday - 6 * day -> Bucket.Week
        else -> Bucket.Earlier
    }
}

@Composable
private fun SessionCard(session: AiSession, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentPadding = PaddingValues(0.dp),
        onClick = onOpen,
        showIndication = true,
    ) {
        Column(Modifier.padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
            Text(
                text = session.title.ifBlank { stringResource(R.string.ai_history_untitled) },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = session.chatTitle + " · " + formatTime(session.updatedAt),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MiuixTheme.colorScheme.dividerLine)
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(text = stringResource(R.string.ai_history_count, session.messageCount), accent = false)
                Spacer(Modifier.weight(1f))
                val deleteText = stringResource(R.string.ai_history_delete)
                TooltipBox(text = deleteText) {
                    IconButton(onClick = onDelete, minWidth = 38.dp, minHeight = 38.dp) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = deleteText,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
