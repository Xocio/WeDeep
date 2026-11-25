package com.zeroui.template.feature.wechat

import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.MiuixIcons
import com.zeroui.kit.components.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import com.zeroui.kit.theme.ZeroTheme
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.Card
import com.zeroui.kit.components.SettingIcons
import com.zeroui.kit.components.PreferenceIcon
import com.zeroui.template.wechat.Reason
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.zeroui.kit.components.AccentBanner
import com.zeroui.kit.components.ArrowPreference
import com.zeroui.kit.components.DialogButtons
import com.zeroui.kit.components.ListCard
import com.zeroui.kit.components.Badge
import com.zeroui.kit.components.ManagedDialog
import com.zeroui.kit.components.SectionTitle
import com.zeroui.kit.components.TextField
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.template.R
import com.zeroui.template.core.appViewModel
import com.zeroui.template.wechat.AccountMeta
import com.zeroui.template.wechat.PrepareResult
import com.zeroui.template.wechat.Source
import com.zeroui.template.wechat.WeChatHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountRow(val hash: String, val label: String, val meta: AccountMeta?, val size: Long, val ready: Boolean)

data class AccountsUi(
    val accounts: List<AccountRow> = emptyList(),
    val current: String? = null,
    val rootAvailable: Boolean? = null,
    @StringRes val busy: Int? = null,
    @StringRes val notice: Int? = null,
)

class AccountsViewModel(private val hub: WeChatHub) : ViewModel() {
    private val local = MutableStateFlow(AccountsUi())
    val ui: StateFlow<AccountsUi> = local.asStateFlow()

    init {
        viewModelScope.launch {
            combine(hub.current, hub.revision) { cur, _ -> cur }.collect { cur ->
                val rows = withContext(Dispatchers.IO) {
                    hub.workspace.accounts().map { h ->
                        AccountRow(h, hub.label(h), hub.workspace.readMeta(h), hub.workspace.sizeOf(h), hub.workspace.plain(h).isFile)
                    }
                }
                local.update { it.copy(accounts = rows, current = cur) }
            }
        }
    }

    /** Checks root access again; called when the page is shown. */
    fun checkRoot() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { hub.root.isAvailable() }
            local.update { it.copy(rootAvailable = ok) }
        }
    }

    fun consumeNotice() = local.update { it.copy(notice = null) }

    fun pullFromRoot() {
        if (local.value.busy != null) return
        local.update { it.copy(busy = R.string.wd_busy_root, notice = null) }
        viewModelScope.launch {
            val ws = hub.workspace
            val outcome = withContext(Dispatchers.IO) {
                if (!hub.root.isAvailable()) return@withContext null
                val candidates = hub.root.listAccountsWithDatabase()
                val known = ws.accounts().toSet()
                candidates.forEach { hub.root.pullCredentials(it, ws) }
                val loggedIn = candidates.filter { hub.service.isLoggedInAccount(it) }
                val targets = loggedIn.ifEmpty { candidates }
                (candidates - targets.toSet()).filter { it !in known }.forEach { ws.deleteAccount(it) }
                targets.filter { h ->
                    val ok = hub.root.pullDatabase(h, ws)
                    if (ok) {
                        ws.writeMeta((ws.readMeta(h) ?: AccountMeta(h)).copy(source = Source.Root.name, lastError = "", decryptedAt = 0))
                    } else if (h !in known) {
                        ws.deleteAccount(h)
                    }
                    ok
                }
            }
            local.update {
                it.copy(
                    busy = null,
                    rootAvailable = outcome != null,
                    notice = when {
                        outcome == null -> R.string.wd_root_denied
                        outcome.isEmpty() -> R.string.wd_root_none
                        else -> null
                    },
                )
            }
            val first = outcome?.firstOrNull() ?: return@launch
            hub.select(first)
            hub.invalidate()
            hub.startPrepare(first, force = true)
        }
    }

    fun import(uri: Uri) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = R.string.wd_busy_import) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { hub.importer().import(uri) } }
            val imported = result.getOrNull()
            local.update { it.copy(busy = null, notice = if (imported == null || imported.accounts.isEmpty()) R.string.wd_import_failed else null) }
            if (imported != null && imported.accounts.isNotEmpty()) {
                imported.accounts.forEach { h ->
                    hub.workspace.writeMeta((hub.workspace.readMeta(h) ?: AccountMeta(h)).copy(source = Source.Import.name, lastError = "", decryptedAt = 0))
                }
                hub.select(imported.accounts.first())
                hub.invalidate()
                hub.startPrepare(imported.accounts.first(), force = true)
            }
        }
    }

    fun use(hash: String) {
        hub.select(hash)
        hub.invalidate()
    }

    fun redo(hash: String, uin: String? = null, imei: String? = null) {
        hub.select(hash)
        hub.startPrepare(hash, force = true, manualUin = uin, manualImei = imei)
    }

    fun clear(hash: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { hub.workspace.clearDerived(hash) }
            hub.invalidate()
        }
    }

    fun delete(hash: String) {
        hub.deleteAccount(hash)
    }

    fun report(hash: String): String = hub.service.report(hash)
    fun cancel() = hub.cancelPrepare()
}

internal fun copyToClipboard(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diagnostics", text))
    Toast.makeText(context, context.getString(R.string.wd_copied), Toast.LENGTH_SHORT).show()
}

/** Import, root access, credentials and cache management. */
@Composable
fun AccountsScreen(onBack: () -> Unit, onOpenAccount: (String) -> Unit) {
    val vm = appViewModel { AccountsViewModel(wechat) }
    val hub = LocalHub.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prep by hub.prepareState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    LifecycleResumeEffect(Unit) {
        vm.checkRoot()
        onPauseOrDispose { }
    }

    ZeroPageFrame(title = stringResource(R.string.wd_accounts), onBack = onBack) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            ui.busy?.let { busy ->
                item(key = "busy") { ProgressCard(title = stringResource(busy), progress = null) }
            }
            if (prep.running) {
                item(key = "prep") { PrepareProgressCard(prep, onCancel = vm::cancel) }
            }
            ui.notice?.let { notice ->
                item(key = "notice") { AccentBanner(stringResource(notice), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), danger = true) }
            }
            (prep.result as? PrepareResult.Failed)?.let { failed ->
                if (!prep.running) {
                    item(key = "failed") { AccentBanner(reasonText(failed.reason), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), danger = failed.reason != Reason.Cancelled) }
                }
            }
            item { SectionTitle(stringResource(R.string.wd_section_source)) }
            item {
                ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.wd_source_root),
                        summary = when (ui.rootAvailable) {
                            null -> stringResource(R.string.wd_root_checking)
                            true -> stringResource(R.string.wd_root_available)
                            false -> stringResource(R.string.wd_root_unavailable)
                        },
                        startAction = { PreferenceIcon(SettingIcons.tune) },
                        enabled = ui.busy == null && !prep.running,
                        onClick = vm::pullFromRoot,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.wd_source_import),
                        summary = stringResource(R.string.wd_source_import_sub),
                        startAction = { PreferenceIcon(SettingIcons.importData) },
                        enabled = ui.busy == null && !prep.running,
                        onClick = { picker.launch(arrayOf("*/*")) },
                    )
                }
            }
            if (ui.accounts.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.wd_accounts_empty_hint),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = ZeroTheme.colors.contentMuted,
                    )
                }
            } else {
                item { SectionTitle(stringResource(R.string.wd_section_accounts)) }
            }
            items(ui.accounts, key = { it.hash }) { row ->
                AccountHeader(row, current = row.hash == ui.current, onClick = { onOpenAccount(row.hash) })
            }
        }
    }
}

@Composable
internal fun accountSummary(row: AccountRow): String {
    val meta = row.meta
    if (!row.ready || meta == null) return stringResource(R.string.wd_status_not_ready)
    val parts = buildList {
        if (meta.messageCount >= 0) add(stringResource(R.string.wd_messages_count, formatCount(meta.messageCount)))
        add(formatBytes(row.size))
    }
    return parts.joinToString(" · ")
}

/** Account card: avatar, name, one-line summary, a marker for the selected account and an arrow when it opens a page. */
@Composable
internal fun AccountHeader(row: AccountRow, current: Boolean, onClick: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
        showIndication = onClick != null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(row.label, size = 52.dp, hash = row.hash, username = row.meta?.selfUser?.takeIf { it.isNotBlank() })
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(row.label, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    accountSummary(row),
                    fontSize = 13.sp,
                    color = ZeroTheme.colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (current) Badge(text = stringResource(R.string.wd_current))
            if (onClick != null) {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = ZeroTheme.colors.contentMuted,
                    modifier = Modifier.padding(start = 8.dp).size(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun CredentialsDialog(initialUin: String, initialImei: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var uin by remember { mutableStateOf(initialUin) }
    var imei by remember { mutableStateOf(initialImei) }
    ManagedDialog(onDismiss = onDismiss, title = stringResource(R.string.wd_credentials), summary = stringResource(R.string.wd_credentials_hint)) {
        Column {
            TextField(value = uin, onValueChange = { uin = it }, label = stringResource(R.string.wd_field_uin), singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            TextField(value = imei, onValueChange = { imei = it }, label = stringResource(R.string.wd_field_imei), singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            DialogButtons(
                confirmText = stringResource(R.string.wd_redo),
                onConfirm = { close { onConfirm(uin.trim(), imei.trim()) } },
                onCancel = { close(onDismiss) },
            )
        }
    }
}
