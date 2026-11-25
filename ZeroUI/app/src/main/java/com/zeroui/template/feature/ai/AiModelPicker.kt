package com.zeroui.template.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.ManagedBottomSheet
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.TextField
import com.zeroui.template.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Entry count above which the search field is shown. */
internal const val SEARCH_THRESHOLD = 12

/** Bottom sheet that picks one model from the provider's list. */
@Composable
fun AiModelPicker(
    models: List<String>,
    current: String,
    truncated: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(models, query) {
        val q = query.trim()
        if (q.isEmpty()) models else models.filter { it.contains(q, ignoreCase = true) }
    }

    ManagedBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.ai_model_pick)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (truncated) {
                    stringResource(R.string.ai_model_count_more, models.size)
                } else {
                    stringResource(R.string.ai_model_count, models.size)
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            if (models.size > SEARCH_THRESHOLD) {
                Spacer(Modifier.height(16.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.ai_model_search),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.ai_model_no_match),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it }) { id ->
                        PickerRow(
                            title = id,
                            selected = id == current,
                            onClick = { close { onPick(id) } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One selectable row of a picker sheet; the selected row gets a tinted background and a check. */
@Composable
internal fun PickerRow(title: String, selected: Boolean, onClick: () -> Unit, summary: String? = null) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(shape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
