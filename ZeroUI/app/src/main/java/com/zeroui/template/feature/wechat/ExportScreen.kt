package com.zeroui.template.feature.wechat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zeroui.kit.components.AccentBanner
import com.zeroui.kit.components.ArrowPreference
import com.zeroui.kit.components.ButtonVariant
import com.zeroui.kit.components.DropdownPreference
import com.zeroui.kit.components.ListCard
import com.zeroui.kit.components.SwitchPreference
import com.zeroui.kit.components.TextButton
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.template.R
import com.zeroui.template.core.appViewModel
import com.zeroui.template.wechat.ExportFormat
import com.zeroui.template.wechat.ExportProgress
import com.zeroui.template.wechat.ExportSummary
import com.zeroui.template.wechat.WeChatHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: ExportProgress?) : ExportState
    data class Done(val summary: ExportSummary) : ExportState
    data object Failed : ExportState
}

class ExportViewModel(private val hub: WeChatHub, private val talker: String) : ViewModel() {
    private val state = MutableStateFlow<ExportState>(ExportState.Idle)
    val ui: StateFlow<ExportState> = state.asStateFlow()
    private var job: Job? = null

    fun start(tree: Uri, format: ExportFormat, withMedia: Boolean, incremental: Boolean) {
        val hash = hub.current.value ?: return
        if (state.value is ExportState.Running) return
        state.value = ExportState.Running(null)
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val db = hub.db(hash) ?: error("no database")
                    val sessions = if (talker.isEmpty()) db.sessions() else db.sessions().filter { it.talker == talker }
                    hub.exporter.export(
                        hash = hash,
                        db = db,
                        talkers = sessions,
                        treeUri = tree,
                        format = format,
                        withMedia = withMedia,
                        incremental = incremental,
                        cancelled = { !isActive },
                        onProgress = { p -> state.value = ExportState.Running(p) },
                    )
                }
            }
            state.value = result.fold({ ExportState.Done(it) }, { ExportState.Failed })
        }
    }

    fun cancel() {
        job?.cancel()
        state.value = ExportState.Idle
    }

    fun resetIncremental() {
        hub.current.value?.let { hub.exporter.resetIncremental(it) }
    }
}

/** Export wizard for one conversation or the whole account. */
@Composable
fun ExportScreen(talker: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "export-$talker") { ExportViewModel(wechat, talker) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var format by rememberSaveable { mutableIntStateOf(2) }
    var withMedia by rememberSaveable { mutableStateOf(true) }
    var incremental by rememberSaveable { mutableStateOf(false) }
    val formats = ExportFormat.entries
    val running = ui is ExportState.Running
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.start(uri, formats[format], withMedia, incremental)
    }
    val itemModifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)

    ZeroPageFrame(title = stringResource(R.string.wd_export), onBack = onBack) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                AccentBanner(
                    stringResource(if (talker.isEmpty()) R.string.wd_export_scope_all else R.string.wd_export_scope_one),
                    itemModifier,
                )
            }
            item {
                ListCard(itemModifier) {
                    DropdownPreference(
                        title = stringResource(R.string.wd_export_format),
                        items = listOf("JSON", "HTML", "JSON + HTML"),
                        selectedIndex = format,
                        onSelectedIndexChange = { format = it },
                        enabled = !running,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.wd_export_media),
                        summary = stringResource(R.string.wd_export_media_sub),
                        checked = withMedia,
                        onCheckedChange = { withMedia = it },
                        enabled = !running,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.wd_export_incremental),
                        summary = stringResource(R.string.wd_export_incremental_sub),
                        checked = incremental,
                        onCheckedChange = { incremental = it },
                        enabled = !running,
                    )
                    if (incremental) {
                        ArrowPreference(
                            title = stringResource(R.string.wd_export_reset),
                            summary = stringResource(R.string.wd_export_reset_sub),
                            enabled = !running,
                            onClick = {
                                vm.resetIncremental()
                                Toast.makeText(context, R.string.wd_export_reset_done, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
            when (val s = ui) {
                is ExportState.Running -> item {
                    val p = s.progress
                    ProgressCard(
                        title = stringResource(R.string.wd_export_running_title),
                        progress = p?.let { (it.session - 1).toFloat() / it.sessions.coerceAtLeast(1) },
                        detail = if (p == null) {
                            stringResource(R.string.wd_export_running)
                        } else {
                            stringResource(R.string.wd_export_progress, p.session, p.sessions, p.title, p.messages)
                        },
                        cancelText = stringResource(R.string.cancel),
                        onCancel = vm::cancel,
                    )
                }
                is ExportState.Done -> item {
                    AccentBanner(stringResource(R.string.wd_export_done, s.summary.sessions, s.summary.messages, s.summary.media), itemModifier)
                }
                ExportState.Failed -> item {
                    AccentBanner(stringResource(R.string.wd_export_failed), itemModifier, danger = true)
                }
                ExportState.Idle -> Unit
            }
            if (!running) {
                item {
                    TextButton(
                        text = stringResource(R.string.wd_export_choose),
                        onClick = { picker.launch(null) },
                        variant = ButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
