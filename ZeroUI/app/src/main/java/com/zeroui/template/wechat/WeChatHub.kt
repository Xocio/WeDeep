package com.zeroui.template.wechat

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Entry point of the WeChat data layer shared by all screens. */
class WeChatHub(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("wedeep", Context.MODE_PRIVATE)

    val workspace = Workspace(appContext)
    val root = RootChannel()
    val service = AccountService(workspace)
    val media = MediaResolver(workspace, root)
    val exporter = Exporter(appContext, media)
    val editorRepo: ChatEditorRepository by lazy { ChatEditorRepository(this) }
    val liveWriter: LiveWriter by lazy { LiveWriter(this, editorRepo) }
    fun importer() = ImportChannel(appContext.contentResolver, workspace)

    private val _current = MutableStateFlow(initialAccount())
    /** Hash of the selected account, or null when nothing is available. */
    val current: StateFlow<String?> = _current.asStateFlow()

    private val _revision = MutableStateFlow(0)
    /** Increases whenever decrypted data changes. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    val images = ImageCache()
    val voice = VoicePlayer()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _prepare = MutableStateFlow(PrepareState())
    val prepareState: StateFlow<PrepareState> = _prepare.asStateFlow()

    /** Runs the decrypt pipeline for [hash] in the background. */
    fun startPrepare(hash: String, force: Boolean = false, manualUin: String? = null, manualImei: String? = null) {
        if (_prepare.value.running || hash !in workspace.accounts()) return
        _prepare.value = PrepareState(hash, running = true)
        job = scope.launch {
            var result: PrepareResult = PrepareResult.Failed(Reason.Cancelled, null)
            try {
                val fromRoot = workspace.readMeta(hash)?.source == Source.Root.name
                if (fromRoot && !workspace.rawDb(hash).isFile && (force || !service.isFresh(hash)) && isAccountHash(hash)) {
                    root.pull(hash, workspace)
                }
                ensureActive()
                result = service.prepare(
                    hash, force, manualUin, manualImei,
                    cancelled = { !isActive },
                    onProgress = { p -> _prepare.update { if (it.cancelling) it else it.copy(progress = p) } },
                )
                if (fromRoot && result is PrepareResult.Ready) service.dropRaw(hash)
            } catch (e: CancellationException) {
                result = PrepareResult.Failed(Reason.Cancelled, null)
            } catch (e: Exception) {
                result = PrepareResult.Failed(Reason.Error, null)
            } finally {
                withContext(NonCancellable) {
                    if (result is PrepareResult.Failed && hash in workspace.accounts()) {
                        workspace.readMeta(hash)?.let { workspace.writeMeta(it.copy(lastError = (result as PrepareResult.Failed).reason.name)) }
                    }
                    invalidate()
                    _prepare.value = PrepareState(hash, running = false, result = result)
                }
            }
        }
    }

    fun cancelPrepare() {
        if (!_prepare.value.running) return
        _prepare.update { it.copy(cancelling = true) }
        job?.cancel()
    }

    /** Stops work on [hash], moves the selection away from it and removes its files. */
    fun deleteAccount(hash: String): Job = scope.launch(Dispatchers.IO) {
        if (_prepare.value.running && _prepare.value.hash == hash) job?.cancelAndJoin()
        if (_current.value == hash) select(workspace.accounts().firstOrNull { it != hash })
        synchronized(this@WeChatHub) {
            open?.takeIf { it.first == hash }?.second?.close()
            if (open?.first == hash) open = null
        }
        workspace.deleteAccount(hash)
        if (_prepare.value.hash == hash) _prepare.value = PrepareState()
        invalidate()
    }

    fun clearPrepareResult() {
        if (!_prepare.value.running) _prepare.value = PrepareState()
    }

    private var open: Pair<String, ChatDb>? = null

    private fun initialAccount(): String? {
        val saved = prefs.getString("current", null)
        val all = workspace.accounts()
        return saved?.takeIf { it in all } ?: all.firstOrNull()
    }

    fun select(hash: String?) {
        prefs.edit().putString("current", hash).apply()
        _current.value = hash
    }

    /** Called after a prepare run or a deletion so open screens reload. */
    @Synchronized
    fun invalidate() {
        open?.second?.close()
        open = null
        if (_current.value !in workspace.accounts()) _current.value = workspace.accounts().firstOrNull()
        _revision.value += 1
    }

    /** Opens the decrypted database of [hash]; null when it has not been decrypted yet. */
    @Synchronized
    fun db(hash: String): ChatDb? {
        open?.takeIf { it.first == hash }?.let { return it.second }
        open?.second?.close()
        open = null
        val plain = workspace.plain(hash)
        if (!plain.isFile) return null
        val db = runCatching { ChatDb(plain) }.getOrNull() ?: return null
        open = hash to db
        return db
    }

    fun label(hash: String): String {
        val meta = workspace.readMeta(hash)
        meta?.nickname?.takeIf { it.isNotBlank() }?.let { return it }
        return if (hash == "manual") "手动导入" else (meta?.uin?.takeIf { it.isNotBlank() }?.let { "uin $it" } ?: hash.take(8))
    }
}
