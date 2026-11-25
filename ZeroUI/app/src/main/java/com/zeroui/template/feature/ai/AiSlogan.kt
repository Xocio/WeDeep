package com.zeroui.template.feature.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.zeroui.kit.components.BreathingDot
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SLOGAN_TICK_MS = 140L
private const val SLOGAN_HOLD_MS = 1400L
private const val SLOGAN_PAUSE_MS = 500L
private const val DOT_ID = "ai_slogan_dot"

private fun sloganFontSize(text: String): TextUnit = when {
    text.length <= 8 -> 32.sp
    text.length <= 14 -> 26.sp
    text.length <= 20 -> 22.sp
    else -> 18.sp
}

/** Empty-state slogan typed out and erased in a loop, followed by a breathing dot. */
@Composable
fun AiSlogan(text: String) {
    var shown by remember(text) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(text, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                shown = 0
                while (shown < text.length) {
                    delay(SLOGAN_TICK_MS)
                    shown++
                }
                delay(SLOGAN_HOLD_MS)
                while (shown > 0) {
                    delay(SLOGAN_TICK_MS)
                    shown--
                }
                delay(SLOGAN_PAUSE_MS)
            }
        }
    }

    val dotSize = with(LocalDensity.current) { 14.dp.toSp() }
    val inlineContent = remember(dotSize) {
        mapOf(
            DOT_ID to InlineTextContent(
                Placeholder(dotSize, dotSize, PlaceholderVerticalAlign.Center)
            ) { BreathingDot(modifier = Modifier.fillMaxSize()) }
        )
    }

    Box(modifier = Modifier.padding(horizontal = 24.dp)) {
        BasicText(
            text = buildAnnotatedString {
                append(text.take(shown))
                append(' ')
                appendInlineContent(DOT_ID, "●")
            },
            style = TextStyle(
                fontSize = sloganFontSize(text),
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
            inlineContent = inlineContent,
        )
    }
}
