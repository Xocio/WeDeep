package com.zeroui.template.feature.wechat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.kit.theme.ZeroTheme
import com.zeroui.template.R
import com.zeroui.template.wechat.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Play

/** Grid of the images and videos of one conversation. */
@Composable
fun MediaGridScreen(talker: String, onBack: () -> Unit) {
    val hub = LocalHub.current
    val context = LocalContext.current
    val hash by hub.current.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var viewerStart by remember { mutableStateOf<Pair<Long, Rect?>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(talker) {
        items = withContext(Dispatchers.IO) { hash?.let { hub.db(it)?.media(talker) }.orEmpty() }
    }

    ZeroPageFrame(title = stringResource(R.string.wd_media), onBack = onBack) { padding ->
        val list = items
        when {
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> StateMessage(stringResource(R.string.wd_media_empty), modifier = Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp, end = 8.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                ),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                items(list, key = { it.msgId }) { item ->
                    val isVideo = item.type != 3
                    var tileBounds by remember { mutableStateOf<Rect?>(null) }
                    val (bmp, done) = rememberMediaBitmap(item.msgId, 320, hub) {
                        hash?.let { if (isVideo) hub.media.videoThumb(it, item.mediaName) else hub.media.image(it, item.mediaName) }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .onGloballyPositioned { tileBounds = it.boundsInWindow() }
                            .clip(RoundedCornerShape(6.dp))
                            .background(ZeroTheme.colors.card)
                            .clickable {
                                if (!isVideo) {
                                    viewerStart = item.msgId to tileBounds
                                } else {
                                    scope.launch {
                                        val f = withContext(Dispatchers.IO) { hash?.let { hub.media.video(it, item.mediaName) } }
                                        if (f != null) openExternally(context, f, "video/mp4")
                                    }
                                }
                            },
                    ) {
                        if (bmp != null) CoverImage(bmp, Modifier.fillMaxSize())
                        else if (done) Text(stringResource(R.string.wd_image_missing), fontSize = 11.sp, color = ZeroTheme.colors.contentMuted)
                        if (isVideo) Icon(MiuixIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
    }
    viewerStart?.let { (start, bounds) ->
        val images = remember(items) { items.orEmpty().filter { it.type == 3 }.map { ViewerItem(it.msgId, it.mediaName, "", it.time) } }
        ImageViewer(images, images.indexOfFirst { it.msgId == start }, hash, sourceBounds = bounds, sourceCorner = 6.dp) { viewerStart = null }
    }
}
