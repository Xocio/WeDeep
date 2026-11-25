package com.zeroui.template.feature.wechat

import com.zeroui.kit.glass.rememberSquircleShape
import com.zeroui.kit.components.HorizontalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import com.zeroui.kit.components.Badge
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.colorResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.wedeep.core.model.Kind
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.IconButton
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.kit.theme.ZeroTheme
import com.zeroui.template.R
import com.zeroui.template.core.appViewModel
import com.zeroui.template.navigation.AppRoute
import com.zeroui.template.wechat.ChatMessage
import com.zeroui.template.wechat.WeChatHub
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image as ImageIcon
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Edit

private const val GapMs = 5 * 60 * 1000L

data class ChatUi(
    val loading: Boolean = true,
    val title: String = "",
    val hash: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val scrollTo: Long? = null,
    val error: Boolean = false,
    val selfUser: String? = null,
)

class ChatViewModel(private val hub: WeChatHub, private val talker: String, private val focus: Long) : ViewModel() {
    private val state = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = state.asStateFlow()
    private var busy = false

    init {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val hash = hub.current.value ?: return@withContext null
                val db = hub.db(hash) ?: return@withContext null
                val messages = if (focus > 0) db.window(talker, focus) else db.older(talker, null, PageSize)
                val oldest = messages.firstOrNull()?.msgId
                val newest = messages.lastOrNull()?.msgId
                ChatUi(
                    loading = false,
                    title = db.displayName(talker).let { if (it == talker && talker.endsWith("@chatroom")) "群聊" else it },
                    hash = hash,
                    selfUser = hub.workspace.readMeta(hash)?.selfUser?.takeIf { it.isNotBlank() },
                    messages = messages,
                    hasOlder = oldest != null && db.older(talker, oldest, 1).isNotEmpty(),
                    hasNewer = newest != null && db.newer(talker, newest, 1).isNotEmpty(),
                    scrollTo = if (focus > 0) focus else messages.lastOrNull()?.msgId,
                )
            }
            state.value = result ?: ChatUi(loading = false, error = true)
        }
    }

    fun consumeScroll() = state.update { it.copy(scrollTo = null) }

    fun loadOlder() {
        val cur = state.value
        val first = cur.messages.firstOrNull() ?: return
        if (busy || !cur.hasOlder) return
        busy = true
        viewModelScope.launch {
            val batch = withContext(Dispatchers.IO) { hub.db(cur.hash ?: return@withContext emptyList())?.older(talker, first.msgId, PageSize).orEmpty() }
            state.update { it.copy(messages = batch + it.messages, hasOlder = batch.size >= PageSize) }
            busy = false
        }
    }

    fun loadNewer() {
        val cur = state.value
        val last = cur.messages.lastOrNull() ?: return
        if (busy || !cur.hasNewer) return
        busy = true
        viewModelScope.launch {
            val batch = withContext(Dispatchers.IO) { hub.db(cur.hash ?: return@withContext emptyList())?.newer(talker, last.msgId, PageSize).orEmpty() }
            state.update { it.copy(messages = it.messages + batch, hasNewer = batch.size >= PageSize) }
            busy = false
        }
    }

    private companion object {
        const val PageSize = 50
    }
}

fun contentUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

fun openExternally(context: Context, file: File, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(context, file), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, context.getString(R.string.wd_no_app_to_open), Toast.LENGTH_SHORT).show()
    }
}

private fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, context.getString(R.string.wd_copied), Toast.LENGTH_SHORT).show()
}

@Composable
fun ChatScreen(talker: String, focus: Long, onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val vm = appViewModel(key = "chat-$talker-$focus") { ChatViewModel(wechat, talker, focus) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var viewerStart by remember { mutableStateOf<Pair<Long, Rect?>?>(null) }

    LaunchedEffect(ui.scrollTo, ui.messages.size) {
        val target = ui.scrollTo ?: return@LaunchedEffect
        val index = ui.messages.indexOfFirst { it.msgId == target }
        if (index >= 0) {
            listState.scrollToItem(index)
            vm.consumeScroll()
        }
    }
    LaunchedEffect(listState, ui.messages.size) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { (first, last) ->
                if (first <= 3) vm.loadOlder()
                if (last != null && last >= ui.messages.size - 4) vm.loadNewer()
            }
    }

    ZeroPageFrame(
        title = ui.title.ifBlank { talker },
        onBack = onBack,
        actions = {
            IconButton(onClick = { onNavigate(AppRoute.MediaGrid(talker)) }) {
                Icon(MiuixIcons.ImageIcon, contentDescription = stringResource(R.string.wd_media), tint = ZeroTheme.colors.content)
            }
            IconButton(onClick = { onNavigate(AppRoute.Export(talker)) }) {
                Icon(MiuixIcons.Import, contentDescription = stringResource(R.string.wd_export), tint = ZeroTheme.colors.content)
            }
            IconButton(onClick = { onNavigate(AppRoute.ChatEdit(talker)) }) {
                Icon(MiuixIcons.Edit, contentDescription = stringResource(R.string.wd_edit_title), tint = ZeroTheme.colors.content)
            }
        },
    ) { padding ->
        when {
            ui.loading -> LoadingBox(Modifier.padding(padding))
            ui.error -> StateMessage(stringResource(R.string.wd_chat_error), modifier = Modifier.padding(padding))
            ui.messages.isEmpty() -> StateMessage(stringResource(R.string.wd_chat_empty), modifier = Modifier.padding(padding))
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                itemsIndexed(ui.messages, key = { _, it -> it.msgId }) { index, m ->
                    val prev = ui.messages.getOrNull(index - 1)
                    if (prev == null || m.time - prev.time > GapMs) Pill(formatChatTime(m.time))
                    MessageRow(hash = ui.hash, selfUser = ui.selfUser, hub = LocalHub.current, m = m, showSender = talker.endsWith("@chatroom"), onImage = { msg, bounds -> viewerStart = msg.msgId to bounds })
                }
            }
        }
    }

    viewerStart?.let { (start, bounds) ->
        val images = remember(ui.messages) {
            ui.messages.filter { it.content.kind == Kind.Image }.map { ViewerItem(it.msgId, it.mediaName, it.senderName, it.time) }
        }
        ImageViewer(images, images.indexOfFirst { it.msgId == start }, ui.hash, sourceBounds = bounds, sourceCorner = 12.dp) { viewerStart = null }
    }
}

private val BodySize = 16.sp
private val BodyLineHeight = 23.sp
private val AvatarSize = 40.dp
private val SideGap = 52.dp

/** Centered label on a tinted block; used for time separators and system notices. */
@Composable
private fun Pill(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Badge(
            text = text,
            accent = false,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            cornerRadius = 8.dp,
            horizontalPadding = 8.dp,
            verticalPadding = 3.dp,
        )
    }
}

/** Time separator text: time only for today, then day and month, then the full date. */
fun formatChatTime(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val locale = java.util.Locale.getDefault()
    val skeleton = when {
        now.get(java.util.Calendar.YEAR) != then.get(java.util.Calendar.YEAR) -> "yMMMdHHmm"
        now.get(java.util.Calendar.DAY_OF_YEAR) != then.get(java.util.Calendar.DAY_OF_YEAR) -> "MMMdHHmm"
        else -> "HHmm"
    }
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(ms))
}

@Composable
private fun bubbleColor(mine: Boolean): Color = if (mine) colorResource(R.color.wd_bubble_me) else ZeroTheme.colors.card

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    hash: String?,
    selfUser: String?,
    hub: WeChatHub,
    m: ChatMessage,
    showSender: Boolean,
    onImage: (ChatMessage, Rect?) -> Unit,
) {
    val c = m.content
    if (c.kind == Kind.System) {
        Pill(c.text)
        return
    }
    val mine = m.isSend
    val sender = if (showSender && !mine) m.senderName else null
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (mine) {
            Spacer(Modifier.width(SideGap))
        } else {
            Avatar(m.senderName, size = AvatarSize, hash = hash, username = m.senderId ?: m.talker)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            MessageContent(hash, hub, m, mine, sender, onImage)
        }
        if (mine) {
            Spacer(Modifier.width(8.dp))
            Avatar(stringResource(R.string.wd_me_initial), size = AvatarSize, hash = hash, username = selfUser)
        } else {
            Spacer(Modifier.width(SideGap))
        }
    }
}

/** Bubble surface shared by text, voice and card messages. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    mine: Boolean,
    sender: String?,
    modifier: Modifier = Modifier,
    copyText: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val shape = rememberSquircleShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(bubbleColor(mine))
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = copyText?.let { t -> { copyText(context, t) } },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (sender != null) {
            Text(
                sender,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ZeroTheme.colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        content()
    }
}

/** Sender label placed above media that has no bubble. */
@Composable
private fun MediaSender(sender: String?) {
    if (sender == null) return
    Box(Modifier.padding(bottom = 4.dp)) {
        Badge(text = sender, accent = false, fontSize = 11.sp, fontWeight = FontWeight.Medium, cornerRadius = 6.dp)
    }
}

@Composable
private fun MessageContent(
    hash: String?,
    hub: WeChatHub,
    m: ChatMessage,
    mine: Boolean,
    sender: String?,
    onImage: (ChatMessage, Rect?) -> Unit,
) {
    val context = LocalContext.current
    val c = m.content
    val voiceMissing = stringResource(R.string.wd_voice_missing)
    val voiceSilk = stringResource(R.string.wd_voice_silk)
    val voiceFailed = stringResource(R.string.wd_voice_failed)
    val videoMissing = stringResource(R.string.wd_video_missing)
    val scope = rememberCoroutineScope()
    when (c.kind) {
        Kind.Text, Kind.Emoji -> Bubble(mine, sender, copyText = c.text) {
            Text(c.text, fontSize = BodySize, lineHeight = BodyLineHeight)
        }
        Kind.Quote -> Bubble(mine, sender, copyText = c.text) {
            Text(c.text, fontSize = BodySize, lineHeight = BodyLineHeight)
            c.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = ZeroTheme.colors.contentMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZeroTheme.colors.contentMuted.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Kind.Image -> {
            MediaSender(sender)
            val (bmp, done) = rememberMediaBitmap(m.msgId, 480, hub) { hash?.let { hub.media.image(it, m.mediaName) } }
            if (bmp != null) {
                MediaThumb(bmp.width, bmp.height, onClick = { bounds -> onImage(m, bounds) }) { CoverImage(bmp, Modifier.fillMaxSize()) }
            } else {
                Bubble(mine, null) {
                    Text(if (done) stringResource(R.string.wd_image_missing) else "…", fontSize = 14.sp, color = ZeroTheme.colors.contentMuted)
                }
            }
        }
        Kind.Video -> {
            MediaSender(sender)
            val (bmp, _) = rememberMediaBitmap(m.msgId, 480, hub) { hash?.let { hub.media.videoThumb(it, m.mediaName) } }
            MediaThumb(
                bmp?.width ?: 4,
                bmp?.height ?: 3,
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { hash?.let { hub.media.video(it, m.mediaName) } }
                        if (file != null) openExternally(context, file, "video/mp4")
                        else Toast.makeText(context, videoMissing, Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                if (bmp != null) CoverImage(bmp, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(ZeroTheme.colors.card))
                Box(
                    modifier = Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MiuixIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                if (c.seconds > 0) {
                    Text(
                        formatDuration(c.seconds),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    )
                }
            }
        }
        Kind.Voice -> {
            val playing by hub.voice.playing.collectAsStateWithLifecycle()
            val isPlaying = playing == m.msgId
            val width = (84 + c.seconds.coerceAtLeast(1) * 5).coerceAtMost(216).dp
            Bubble(
                mine,
                sender,
                modifier = Modifier.width(width),
                onClick = {
                    if (isPlaying) {
                        hub.voice.stop()
                    } else {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { hash?.let { hub.media.voice(it, m.mediaName) } }
                            val silk = file != null && withContext(Dispatchers.IO) {
                                app.wedeep.core.media.MediaPaths.isSilk(file.inputStream().use { ins -> ByteArray(16).also { b -> ins.read(b) } })
                            }
                            val msg = when {
                                file == null -> voiceMissing
                                silk -> voiceSilk
                                !hub.voice.play(m.msgId, file) -> voiceFailed
                                else -> null
                            }
                            if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    modifier = Modifier.fillMaxWidth().heightIn(min = BodyLineHeight.value.dp),
                ) {
                    val seconds = "${c.seconds.coerceAtLeast(1)}\""
                    if (mine) {
                        Text(seconds, fontSize = BodySize, modifier = Modifier.padding(end = 6.dp))
                        VoiceWave(playing = isPlaying, facingStart = true)
                    } else {
                        VoiceWave(playing = isPlaying, facingStart = false)
                        Text(seconds, fontSize = BodySize, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        else -> {
            val url = c.url?.takeIf { it.startsWith("http") }
            var open by remember(m.msgId) { mutableStateOf(false) }
            val label = c.text.takeIf { it.startsWith("[") && c.kind != Kind.Unknown }?.substringAfter('[')?.substringBefore(']')
            Bubble(
                mine,
                sender,
                modifier = Modifier.width(248.dp),
                copyText = c.title ?: c.text,
                onClick = {
                    if (url != null) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    } else if (c.raw != null) {
                        open = !open
                    }
                },
            ) {
                Text(
                    c.title?.takeIf { it.isNotBlank() } ?: c.text.removePrefix("[").removeSuffix("]"),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                c.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = ZeroTheme.colors.contentMuted,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (open && c.raw != null) {
                    Text(c.raw!!, fontSize = 11.sp, color = ZeroTheme.colors.contentMuted, modifier = Modifier.padding(top = 6.dp))
                }
                if (label != null) {
                    HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    Text(label, fontSize = 12.sp, color = ZeroTheme.colors.contentMuted)
                }
            }
        }
    }
}

/** Rounded media frame that keeps the picture ratio within a fixed box. */
@Composable
private fun MediaThumb(width: Int, height: Int, onClick: (Rect?) -> Unit, content: @Composable BoxScope.() -> Unit) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val ratio = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 2f) else 1f
    val w = if (ratio >= 1f) 200.dp else (200 * ratio).dp.coerceAtLeast(110.dp)
    val h = if (ratio >= 1f) (200 / ratio).dp else 200.dp
    Box(
        modifier = Modifier
            .size(w, h)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .clip(rememberSquircleShape(12.dp))
            .clickable { onClick(bounds) },
        content = content,
    )
}

private fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** Sound wave glyph of a voice bubble: a dot and two arcs; while [playing] the arcs light up in turn. */
@Composable
fun VoiceWave(playing: Boolean, facingStart: Boolean, modifier: Modifier = Modifier) {
    val ink = ZeroTheme.colors.content
    val step = if (playing) {
        val transition = rememberInfiniteTransition(label = "voice")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
            label = "voicePhase",
        )
        phase.toInt().coerceIn(0, 2)
    } else {
        2
    }
    Canvas(modifier.size(width = 16.dp, height = 20.dp).graphicsLayer { scaleX = if (facingStart) -1f else 1f }) {
        val cy = size.height / 2f
        val stroke = 2.dp.toPx()
        val dot = 2.4.dp.toPx()
        val ox = dot
        drawCircle(ink, radius = dot, center = Offset(ox, cy))
        listOf(6.5.dp.toPx(), 12.dp.toPx()).forEachIndexed { i, r ->
            if (i + 1 <= step) {
                drawArc(
                    color = ink,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(ox - r, cy - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}
