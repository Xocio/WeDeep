package com.zeroui.template.feature.wechat

import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroui.kit.components.AccentBanner
import com.zeroui.kit.components.ArrowPreference
import com.zeroui.kit.components.ConfirmDialog
import com.zeroui.kit.components.ListCard
import com.zeroui.kit.components.ListRow
import com.zeroui.kit.components.SectionTitle
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.kit.theme.ZeroTheme
import com.zeroui.template.R
import com.zeroui.template.core.appViewModel
import com.zeroui.template.wechat.Reason
import com.zeroui.template.wechat.PrepareResult
import com.zeroui.template.wechat.Source

/** Details of one account: overview, cipher parameters, identifiers and actions, each in its own card. */
@Composable
fun AccountDetailScreen(hash: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "account-$hash") { AccountsViewModel(wechat) }
    val hub = LocalHub.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prep by hub.prepareState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCredentials by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val row = ui.accounts.firstOrNull { it.hash == hash }
    val meta = row?.meta
    val current = ui.current == hash
    val none = "—"

    ZeroPageFrame(title = stringResource(R.string.wd_account_detail), onBack = onBack) { padding ->
        if (row == null) {
            LoadingBox(Modifier.padding(padding))
            return@ZeroPageFrame
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "header") { AccountHeader(row, current = current, onClick = null) }
            if (prep.running && prep.hash == hash) {
                item(key = "prep") { PrepareProgressCard(prep, onCancel = vm::cancel) }
            }
            (prep.result as? PrepareResult.Failed)?.takeIf { !prep.running && prep.hash == hash }?.let { failed ->
                item(key = "failed") {
                    AccentBanner(reasonText(failed.reason), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), danger = failed.reason != Reason.Cancelled)
                }
            }

            item { SectionTitle(stringResource(R.string.wd_section_overview)) }
            item {
                ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ValueRow(
                        stringResource(R.string.wd_row_messages),
                        meta?.messageCount?.takeIf { row.ready && it >= 0 }?.let { formatCount(it) } ?: none,
                    )
                    ValueRow(stringResource(R.string.wd_row_size), formatBytes(row.size))
                    ValueRow(
                        stringResource(R.string.wd_row_source),
                        stringResource(if (meta?.source == Source.Root.name) R.string.wd_value_source_root else R.string.wd_value_source_import),
                    )
                    ValueRow(
                        stringResource(R.string.wd_row_decrypted_at),
                        meta?.decryptedAt?.takeIf { it > 0 }?.let { formatFullTime(it) } ?: none,
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.wd_section_cipher)) }
            item {
                ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ValueRow(
                        stringResource(R.string.wd_row_cipher),
                        when (meta?.profile) {
                            "compat1" -> stringResource(R.string.wd_value_compat1)
                            "compat3" -> stringResource(R.string.wd_value_compat3)
                            else -> none
                        },
                    )
                    ValueRow(
                        stringResource(R.string.wd_row_page_size),
                        meta?.pageSize?.takeIf { it > 0 }?.let { stringResource(R.string.wd_value_bytes, it) } ?: none,
                    )
                    ValueRow(stringResource(R.string.wd_row_wal), meta?.walPages?.toString() ?: none)
                }
            }

            item { SectionTitle(stringResource(R.string.wd_section_identity)) }
            item {
                ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ValueRow(stringResource(R.string.wd_row_uin), meta?.uin?.takeIf { it.isNotBlank() } ?: none)
                    ValueRow(stringResource(R.string.wd_row_wxid), meta?.selfUser?.takeIf { it.isNotBlank() } ?: none)
                }
            }

            item { SectionTitle(stringResource(R.string.wd_section_actions)) }
            item {
                ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (!current) {
                        ArrowPreference(
                            title = stringResource(R.string.wd_use_account),
                            summary = stringResource(R.string.wd_use_account_sub),
                            onClick = { vm.use(hash) },
                        )
                    }
                    ArrowPreference(
                        title = stringResource(R.string.wd_redo),
                        summary = stringResource(R.string.wd_redo_sub),
                        enabled = !prep.running,
                        onClick = { vm.redo(hash) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.wd_credentials),
                        summary = stringResource(R.string.wd_credentials_sub),
                        enabled = !prep.running,
                        onClick = { showCredentials = true },
                    )
                }
            }
            item {
                ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.wd_copy_report),
                        summary = stringResource(R.string.wd_copy_report_sub),
                        onClick = { copyToClipboard(context, vm.report(hash)) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.wd_clear_cache),
                        summary = stringResource(R.string.wd_clear_cache_sub),
                        enabled = !prep.running,
                        onClick = { vm.clear(hash) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.wd_delete_account),
                        summary = stringResource(R.string.wd_delete_account_sub),
                        enabled = !prep.running,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
    }

    if (showCredentials) {
        CredentialsDialog(
            initialUin = meta?.manualUin.orEmpty(),
            initialImei = meta?.manualImei.orEmpty(),
            onDismiss = { showCredentials = false },
            onConfirm = { uin, imei ->
                showCredentials = false
                vm.redo(hash, uin, imei)
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.wd_delete_account),
            message = stringResource(R.string.wd_delete_confirm),
            confirmText = stringResource(R.string.wd_delete_account),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                vm.delete(hash)
                onBack()
            },
        )
    }
}

/** Row with a label on the left and a read-only value on the right. */
@Composable
private fun ValueRow(title: String, value: String) {
    ListRow(
        title = title,
        endActions = {
            Text(
                value,
                fontSize = 14.sp,
                color = ZeroTheme.colors.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
