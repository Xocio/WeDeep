package com.zeroui.template.feature.wechat

import androidx.compose.foundation.border
import com.zeroui.kit.components.LinearProgressIndicator
import com.zeroui.kit.components.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.zeroui.template.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroui.kit.components.Button
import com.zeroui.kit.components.Card
import com.zeroui.kit.components.ButtonVariant
import com.zeroui.kit.components.LoadingIndicator
import com.zeroui.kit.components.SectionTitle
import androidx.compose.ui.graphics.takeOrElse
import com.zeroui.kit.components.Text
import com.zeroui.kit.glass.rememberSquircleShape
import com.zeroui.kit.theme.ZeroTheme
import androidx.compose.ui.platform.LocalContext
import com.zeroui.template.ZeroApp
import com.zeroui.template.wechat.PrepareProgress
import com.zeroui.template.wechat.PrepareState
import com.zeroui.template.wechat.WeChatHub
import androidx.compose.ui.res.stringResource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Access to the shared data layer from composables. */
object LocalHub {
    val current: WeChatHub
        @Composable get() = (LocalContext.current.applicationContext as ZeroApp).container.wechat
}

/**
 * Contact avatar: the picture WeChat stored for [username], or a neutral person glyph.
 * The shape is a squircle sized relative to [size]; [title] is the accessible name.
 */
@Composable
fun Avatar(
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    hash: String? = null,
    username: String? = null,
) {
    val hub = LocalHub.current
    val shape = rememberSquircleShape(size * 0.26f)
    val key = if (hash != null && !username.isNullOrBlank()) "avatar:$hash:$username" else null
    val bitmap = if (key != null) {
        rememberMediaBitmap(key, 160, hub) { hub.media.avatar(hash!!, username) }.first
    } else {
        null
    }
    Box(
        modifier = modifier.size(size).clip(shape).semantics { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            CoverImage(bitmap, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().border(0.5.dp, ZeroTheme.colors.content.copy(alpha = 0.12f), shape))
        } else {
            PlaceholderAvatar(Modifier.fillMaxSize())
        }
    }
}

/** Grey person glyph on a soft tile, shown for any avatar without a picture. */
@Composable
fun PlaceholderAvatar(modifier: Modifier = Modifier) {
    Box(modifier.background(colorResource(R.color.wd_avatar_placeholder)), contentAlignment = Alignment.BottomCenter) {
        Image(
            painter = painterResource(R.drawable.wd_avatar_placeholder),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.86f),
        )
    }
}

/** Formats a count with grouping separators. */
fun formatCount(n: Long): String = java.text.NumberFormat.getIntegerInstance().format(n)

/** Message block inside a card with an optional action; used for empty and error states. */
@Composable
fun StateMessage(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    progress: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        contentPadding = PaddingValues(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress != null) {
                progress()
                Spacer(Modifier.height(16.dp))
            }
            Text(title, textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (detail != null) {
                Text(
                    detail,
                    modifier = Modifier.padding(top = 8.dp),
                    color = ZeroTheme.colors.contentMuted,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                )
            }
            if (action != null && onAction != null) {
                Button(onClick = onAction, variant = ButtonVariant.Primary, modifier = Modifier.padding(top = 20.dp)) {
                    Text(action, color = ZeroTheme.colors.onAccent)
                }
            }
        }
    }
}

/** Card showing a running task: title with percentage, a progress bar and an optional cancel action. */
@Composable
fun ProgressCard(
    title: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    cancelText: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    detail ?: progress?.let { "${(it * 100).toInt().coerceIn(0, 100)}%" } ?: "…",
                    fontSize = 13.sp,
                    color = ZeroTheme.colors.contentMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (cancelText != null && onCancel != null) {
                TextButton(
                    text = cancelText,
                    onClick = onCancel,
                    minWidth = 64.dp,
                    minHeight = 34.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/** Progress of the running prepare job, with a cancel action that turns into a cancelling note. */
@Composable
fun PrepareProgressCard(prep: PrepareState, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val p = prep.progress
    if (prep.cancelling) {
        ProgressCard(title = stringResource(R.string.wd_cancelling), progress = null, modifier = modifier, detail = "…")
        return
    }
    val title = when (p?.phase) {
        PrepareProgress.Phase.Decrypting -> stringResource(R.string.wd_preparing)
        PrepareProgress.Phase.Indexing -> stringResource(R.string.wd_phase_indexing)
        else -> stringResource(R.string.wd_phase_probing_title)
    }
    val fraction = p?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total }
    ProgressCard(
        title = title,
        progress = if (p?.phase == PrepareProgress.Phase.Indexing) null else fraction,
        modifier = modifier,
        detail = if (p?.phase == PrepareProgress.Phase.Probing && p.total > 0) "${p.done} / ${p.total}" else null,
        cancelText = stringResource(R.string.cancel),
        onCancel = onCancel,
    )
}

/** Start inset of text in a sheet; matches the content start of list rows. */
val SheetTextInset: Dp = 16.dp

/** Section heading inside a sheet, aligned with the rows below it. */
@Composable
fun SheetSectionTitle(text: String) {
    SectionTitle(text, insideMargin = PaddingValues(horizontal = SheetTextInset, vertical = 8.dp))
}

/** Heading line of a sheet, aligned with list row content. */
@Composable
fun SheetHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = SheetTextInset),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** Explanatory paragraph of a sheet, aligned with list row content. */
@Composable
fun SheetNote(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = SheetTextInset),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = color.takeOrElse { ZeroTheme.colors.contentMuted },
    )
}

/** Centered spinner. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

/** Formats a millisecond timestamp for list rows. */
fun formatListTime(ms: Long, yesterday: String): String {
    if (ms <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val dayDiff = (now.get(Calendar.YEAR) * 366 + now.get(Calendar.DAY_OF_YEAR)) -
        (then.get(Calendar.YEAR) * 366 + then.get(Calendar.DAY_OF_YEAR))
    return when {
        dayDiff == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        dayDiff == 1 -> yesterday
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
    }
}

fun formatFullTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}

/** Loads a file produced by [locate] and decodes it to at most [maxEdge] pixels. */
@Composable
fun rememberMediaBitmap(key: Any, maxEdge: Int, hub: WeChatHub, locate: () -> File?): Pair<ImageBitmap?, Boolean> {
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var done by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        withContext(Dispatchers.IO) {
            val file = runCatching(locate).getOrNull()
            bitmap = file?.let { runCatching { hub.images.load(it, maxEdge) }.getOrNull() }
        }
        done = true
    }
    return bitmap to done
}

/** Draws [bitmap] cropped to fill its bounds. */
@Composable
fun CoverImage(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}
