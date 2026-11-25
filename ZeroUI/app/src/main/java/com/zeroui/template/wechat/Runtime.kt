package com.zeroui.template.wechat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State of the background prepare job. */
data class PrepareState(
    val hash: String? = null,
    val running: Boolean = false,
    val progress: PrepareProgress? = null,
    val result: PrepareResult? = null,
    /** True after cancel was requested and before the job has stopped. */
    val cancelling: Boolean = false,
)

/** Decodes and caches bitmaps from media files. */
class ImageCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(file: File, maxEdge: Int): ImageBitmap? {
        val key = "${file.path}:$maxEdge:${file.lastModified()}"
        cache.get(key)?.let { return it.asImageBitmap() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdge || bounds.outHeight / (sample * 2) >= maxEdge) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bmp)
        return bmp.asImageBitmap()
    }
}

/** Plays one voice file at a time. */
class VoicePlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<Long?>(null)
    val playing: StateFlow<Long?> = _playing.asStateFlow()

    /** Starts playback of [file] for message [msgId]; returns false when the file cannot be played. */
    fun play(msgId: Long, file: File): Boolean {
        stop()
        return runCatching {
            val p = MediaPlayer()
            p.setDataSource(file.path)
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepare()
            p.start()
            player = p
            _playing.value = msgId
            true
        }.getOrDefault(false)
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        _playing.value = null
    }
}
