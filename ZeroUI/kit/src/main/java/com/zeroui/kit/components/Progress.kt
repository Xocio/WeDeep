package com.zeroui.kit.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import com.zeroui.kit.theme.isMaterial
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator as MaterialLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator as MiuixInfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator

/** Linear progress; a null [progress] is indeterminate. */
@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    if (!isMaterial()) {
        MiuixLinearProgressIndicator(modifier = modifier, progress = progress)
    } else if (progress == null) {
        MaterialLinearProgressIndicator(modifier = modifier)
    } else {
        MaterialLinearProgressIndicator(progress = { progress }, modifier = modifier)
    }
}

/** Loading spinner. */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    size: Dp = ProgressIndicatorDefaults.DefaultInfiniteProgressIndicatorSize,
    strokeWidth: Dp = ProgressIndicatorDefaults.DefaultInfiniteProgressIndicatorStrokeWidth,
) {
    if (!isMaterial()) {
        MiuixInfiniteProgressIndicator(
            modifier = modifier,
            color = if (color == Color.Unspecified) Color.Gray else color,
            size = size,
            strokeWidth = strokeWidth,
        )
    } else {
        MaterialCircularProgressIndicator(
            modifier = modifier.size(size),
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color,
            strokeWidth = (size / 10).coerceAtLeast(strokeWidth),
        )
    }
}
