package com.zeroui.template.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zeroui.template.wechat.WeChatHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of the status card. */
enum class WorkState { Detecting, Working, Idle }

/** Summary of the selected account. */
@Immutable
data class HomeAccount(val label: String, val messages: Long, val sessions: Int, val hash: String, val selfUser: String?)

/** State of the home page. */
@Immutable
data class HomeUiState(
    val workState: WorkState = WorkState.Detecting,
    val account: HomeAccount? = null,
    val refreshing: Boolean = false,
)

/** Loads the data of the home page. */
class HomeViewModel(private val hub: WeChatHub) : ViewModel() {
    private val state = MutableStateFlow(HomeUiState())
    private var loading: Job? = null

    val uiState: StateFlow<HomeUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(hub.current, hub.revision) { _, _ -> }.collect { load() }
        }
    }

    /** Reloads the page data for a pull to refresh. */
    fun refresh() {
        state.value = state.value.copy(refreshing = true)
        load()
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                val hash = hub.current.value ?: return@withContext HomeUiState(WorkState.Idle)
                val meta = hub.workspace.readMeta(hash)
                val db = hub.db(hash)
                if (db == null) {
                    HomeUiState(WorkState.Idle, HomeAccount(hub.label(hash), 0, 0, hash, meta?.selfUser))
                } else {
                    HomeUiState(
                        WorkState.Working,
                        HomeAccount(hub.label(hash), meta?.messageCount?.coerceAtLeast(0) ?: 0, db.sessions().size, hash, meta?.selfUser),
                    )
                }
            }
            state.value = next
        }
    }
}
