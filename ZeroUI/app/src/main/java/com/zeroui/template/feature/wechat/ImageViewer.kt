package com.zeroui.template.feature.wechat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.LoadingIndicator
import com.zeroui.kit.components.Text
import com.zeroui.kit.glass.LiquidBackdrop
import com.zeroui.kit.glass.LocalLiquidIntensity
import com.zeroui.kit.glass.liquidSource
import com.zeroui.kit.glass.liquidGlass
import com.zeroui.kit.glass.rememberLiquidBackdrop
import com.zeroui.template.R
import com.zeroui.template.wechat.WeChatHub
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Share

/** One picture shown by [ImageViewer]. */
data class ViewerItem(val msgId: Long, val mediaName: String?, val sender: String, val time: Long)

private const val MaxScale = 5f
private const val DoubleTapScale = 2.5f
private val ThumbSize = 44.dp
private val ControlSize = 44.dp

/** Color of icons and text on the floating controls. */
private val ControlInk = Color.Black.copy(alpha = 0.86f)

/** Liquid Glass over the picture when it is enabled, otherwise an opaque white surface lifted by a soft shadow. */
private fun Modifier.surface(backdrop: LiquidBackdrop?, shape: Shape, intensity: Float): Modifier = if (backdrop != null) {
    liquidGlass(backdrop, { shape }, tint = Color.White.copy(alpha = 0.65f), intensity = intensity)
} else {
    shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f)).clip(shape).background(Color.White)
}

/** Rectangle a picture of [image] size takes when fitted inside [container]. */
private fun fittedRect(container: Size, image: IntSize): Rect {
    if (image.width <= 0 || image.height <= 0 || container.width <= 0f) return Rect(Offset.Zero, container)
    val s = min(container.width / image.width, container.height / image.height)
    val w = image.width * s
    val h = image.height * s
    return Rect(Offset((container.width - w) / 2f, (container.height - h) / 2f), Size(w, h))
}

/**
 * Full screen picture viewer.
 *
 * Opens by growing from [sourceBounds] (the tapped thumbnail, window coordinates) and closes back into it.
 * Swipe between [items], pinch or double tap to zoom, drag down to close, tap to toggle the controls.
 */
@Composable
fun ImageViewer(
    items: List<ViewerItem>,
    startIndex: Int,
    hash: String?,
    sourceBounds: Rect?,
    sourceCorner: Dp = 12.dp,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    val start = startIndex.coerceIn(0, items.lastIndex)
    val hub = LocalHub.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start) { items.size }

    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var closing by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val reveal = remember { Animatable(0f) }
    var morphFrom by remember { mutableStateOf<Rect?>(null) }

    val saved = stringResource(R.string.wd_viewer_saved)
    val saveFailed = stringResource(R.string.wd_viewer_save_failed)
    val thumbOfStart = rememberMediaBitmap("thumb:${items[start].msgId}", 480, hub) {
        hash?.let { hub.media.image(it, items[start].mediaName) }
    }.first

    LaunchedEffect(Unit) { reveal.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1600)
            toast = null
        }
    }

    Dialog(
        onDismissRequest = { closing = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        LaunchedEffect(window) {
            val w = window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screen = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val dismissDistance = with(density) { 110.dp.toPx() }
            val dragProgress = (abs(drag.y) / screen.height).coerceIn(0f, 1f)
            val dragScale = 1f - dragProgress * 0.45f
            val backdropAlpha = reveal.value * (1f - dragProgress * 1.6f).coerceIn(0f, 1f)

            val startBitmapSize = thumbOfStart?.let { IntSize(it.width, it.height) }
            val backdrop = rememberLiquidBackdrop()
            val intensity = LocalLiquidIntensity.current
            val canMorph = sourceBounds != null && pager.currentPage == start && startBitmapSize != null

            fun currentRect(): Rect? {
                val size = startBitmapSize ?: return null
                val fit = fittedRect(screen, size)
                val w = fit.width * dragScale
                val h = fit.height * dragScale
                return Rect(Offset(fit.center.x - w / 2f + drag.x, fit.center.y - h / 2f + drag.y), Size(w, h))
            }

            LaunchedEffect(closing) {
                if (!closing) return@LaunchedEffect
                chrome = false
                morphFrom = if (canMorph) currentRect() else null
                drag = Offset.Zero
                reveal.animateTo(0f, tween(durationMillis = 260))
                onDismiss()
            }

            val morphing = canMorph && (reveal.value < 0.999f || closing)

            Box(
                Modifier
                    .fillMaxSize()
                    .liquidSource(backdrop)
                    .background(Color.Black.copy(alpha = backdropAlpha)),
            ) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (morphing) 0f else if (sourceBounds == null || pager.currentPage != start) reveal.value else 1f
                            val s = if (sourceBounds == null) 0.92f + 0.08f * reveal.value else 1f
                            scaleX = s
                            scaleY = s
                        }
                        .pointerInput(zoomed) {
                            if (zoomed) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                                    change.consume()
                                    drag += Offset(0f, over)
                                } ?: return@awaitEachGesture
                                chrome = false
                                tracker.addPosition(slop.uptimeMillis, slop.position)
                                drag(slop.id) { change ->
                                    drag += change.positionChange()
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                }
                                val velocity = tracker.calculateVelocity().y
                                if (abs(drag.y) > dismissDistance || abs(velocity) > 1800f) {
                                    closing = true
                                } else {
                                    val from = drag
                                    scope.launch {
                                        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                                            drag = from * (1f - t)
                                        }
                                        chrome = true
                                    }
                                }
                            }
                        },
                    userScrollEnabled = !zoomed && drag == Offset.Zero,
                    beyondViewportPageCount = 1,
                    pageSpacing = 16.dp,
                    key = { items[it].msgId },
                ) { page ->
                    ZoomablePicture(
                        item = items[page],
                        hash = hash,
                        hub = hub,
                        drag = if (page == pager.currentPage) drag else Offset.Zero,
                        dragScale = if (page == pager.currentPage) dragScale else 1f,
                        onTap = { chrome = !chrome },
                        onZoomChange = { if (page == pager.currentPage) zoomed = it },
                    )
                }

                if (morphing && thumbOfStart != null && sourceBounds != null) {
                    val target = morphFrom ?: fittedRect(screen, IntSize(thumbOfStart.width, thumbOfStart.height))
                    val r = lerp(sourceBounds, target, reveal.value)
                    val corner = lerp(sourceCorner, 0.dp, reveal.value)
                    Box(
                        Modifier
                            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                            .clip(RoundedCornerShape(corner)),
                    ) {
                        Image(thumbOfStart, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            val current = items[pager.currentPage]
            val showChrome = chrome && !closing && drag == Offset.Zero && reveal.value > 0.9f

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 3 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 3 },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassButton(backdrop, intensity, MiuixIcons.Close, stringResource(R.string.close)) { closing = true }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .surface(backdrop, RoundedCornerShape(22.dp), intensity)
                                .padding(horizontal = 18.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (current.sender.isNotBlank()) {
                                Text(current.sender, color = ControlInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(formatChatTime(current.time), color = ControlInk.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GlassButton(backdrop, intensity, MiuixIcons.Share, stringResource(R.string.wd_viewer_share)) {
                            scope.launch {
                                val bitmap = withContext(Dispatchers.IO) { loadFull(hub, hash, current) }
                                if (bitmap != null) sharePicture(context, bitmap, current.msgId) else toast = saveFailed
                            }
                        }
                        GlassButton(backdrop, intensity, MiuixIcons.Download, stringResource(R.string.wd_viewer_save)) {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    loadFull(hub, hash, current)?.let { saveToGallery(context, it, current.msgId) } ?: false
                                }
                                if (ok) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                toast = if (ok) saved else saveFailed
                            }
                        }
                    }
                }
            }

            if (items.size > 1) {
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
                    exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 3 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Filmstrip(
                        items = items,
                        current = pager.currentPage,
                        hash = hash,
                        hub = hub,
                        backdrop = backdrop,
                        intensity = intensity,
                        onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                    )
                }
            }

            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.9f),
                exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.95f),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Row(
                    modifier = Modifier.surface(backdrop, RoundedCornerShape(24.dp), intensity).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(MiuixIcons.Ok, contentDescription = null, tint = ControlInk, modifier = Modifier.size(18.dp))
                    Text(toast.orEmpty(), color = ControlInk, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun GlassButton(backdrop: LiquidBackdrop?, intensity: Float, icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(ControlSize)
            .surface(backdrop, CircleShape, intensity)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = ControlInk, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Filmstrip(
    items: List<ViewerItem>,
    current: Int,
    hash: String?,
    hub: WeChatHub,
    backdrop: LiquidBackdrop?,
    intensity: Float,
    onSelect: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(current) { state.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    Box(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        LazyRow(
            state = state,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .surface(backdrop, RoundedCornerShape(18.dp), intensity)
                .height(ThumbSize + 16.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(items, key = { _, it -> it.msgId }) { index, item ->
                val selected = index == current
                val size by animateDpAsState(if (selected) ThumbSize else ThumbSize - 8.dp, label = "thumb")
                val alpha by animateFloatAsState(if (selected) 1f else 0.55f, label = "thumbAlpha")
                val bmp = rememberMediaBitmap("thumb:${item.msgId}", 480, hub) { hash?.let { hub.media.image(it, item.mediaName) } }.first
                val shape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .size(size)
                        .graphicsLayer { this.alpha = alpha }
                        .clip(shape)
                        .background(ControlInk.copy(alpha = 0.06f))
                        .then(if (selected) Modifier.border(2.dp, ControlInk, shape) else Modifier)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                ) {
                    if (bmp != null) CoverImage(bmp, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** One page: thumbnail first, then the full picture; pinch, pan and double tap zoom. */
@Composable
private fun ZoomablePicture(
    item: ViewerItem,
    hash: String?,
    hub: WeChatHub,
    drag: Offset,
    dragScale: Float,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val (thumb, _) = rememberMediaBitmap("thumb:${item.msgId}", 480, hub) { hash?.let { hub.media.image(it, item.mediaName) } }
    val (full, fullDone) = rememberMediaBitmap("full:${item.msgId}", 2048, hub) { hash?.let { hub.media.image(it, item.mediaName, thumb = false) } }
    val bitmap: ImageBitmap? = full ?: thumb

    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (size.width * (s - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (size.height * (s - 1f) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun zoomTo(target: Float, focus: Offset) {
        val startScale = scale
        val startOffset = offset
        val c = focus - Offset(size.width / 2f, size.height / 2f)
        val endOffset = if (target <= 1f) Offset.Zero else clamp(c - (c - startOffset) * (target / startScale), target)
        onZoomChange(target > 1.01f)
        scope.launch {
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                scale = startScale + (target - startScale) * t
                offset = startOffset + (endOffset - startOffset) * t
            }
        }
    }

    val transform = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, MaxScale)
        val c = centroid - Offset(size.width / 2f, size.height / 2f)
        val next = c - (c - offset) * (newScale / scale) + panChange
        scale = newScale
        offset = clamp(next, newScale)
        onZoomChange(newScale > 1.01f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.msgId) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p -> zoomTo(if (scale > 1.01f) 1f else DoubleTapScale, p) },
                )
            }
            .transformable(state = transform, canPan = { scale > 1.01f }),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale * dragScale
                    scaleY = scale * dragScale
                    translationX = offset.x + drag.x
                    translationY = offset.y + drag.y
                },
            )
            if (full == null && !fullDone) {
                LoadingIndicator(color = Color.White.copy(alpha = 0.85f), size = 22.dp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
            }
        } else if (fullDone) {
            Text(stringResource(R.string.wd_image_missing), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
        } else {
            LoadingIndicator(color = Color.White)
        }
    }
}

private fun loadFull(hub: WeChatHub, hash: String?, item: ViewerItem): Bitmap? {
    val file = hash?.let { hub.media.image(it, item.mediaName, thumb = false) } ?: return null
    return android.graphics.BitmapFactory.decodeFile(file.path)
}

/** Writes [bitmap] to `Pictures/WeDeep`; returns false when the platform has no scoped media store. */
private fun saveToGallery(context: Context, bitmap: Bitmap, msgId: Long): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "WeDeep_$msgId.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeDeep")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: false
    }.getOrDefault(false)
    if (!ok) {
        resolver.delete(uri, null, null)
        return false
    }
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return true
}

private suspend fun sharePicture(context: Context, bitmap: Bitmap, msgId: Long) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, "WeDeep_$msgId.jpg").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/jpeg")
        .putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
