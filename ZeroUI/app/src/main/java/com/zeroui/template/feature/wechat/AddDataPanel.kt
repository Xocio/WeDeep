package com.zeroui.template.feature.wechat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroui.kit.components.AccentBanner
import com.zeroui.kit.components.ArrowPreference
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.ListCard
import com.zeroui.kit.components.PreferenceIcon
import com.zeroui.kit.components.SectionTitle
import com.zeroui.kit.components.SettingIcons
import com.zeroui.kit.components.Text
import com.zeroui.kit.glass.rememberSquircleShape
import com.zeroui.kit.theme.ZeroTheme
import com.zeroui.template.R
import com.zeroui.template.core.appViewModel

/**
 * Page shown while no account exists: an icon, a short explanation and the two ways to add
 * data, which start right here.
 */
@Composable
fun AddDataPanel(padding: PaddingValues, onOpenAccounts: () -> Unit) {
    val vm = appViewModel { AccountsViewModel(wechat) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    LifecycleResumeEffect(Unit) {
        vm.checkRoot()
        onPauseOrDispose { }
    }
    val idle = ui.busy == null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        item(key = "hero") {
            Spacer(Modifier.height(36.dp))
            EmptyHero()
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.wd_no_account_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.wd_empty_detail),
                modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = ZeroTheme.colors.contentMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
        }
        ui.busy?.let { busy ->
            item(key = "busy") { ProgressCard(title = stringResource(busy), progress = null) }
        }
        ui.notice?.let { notice ->
            item(key = "notice") {
                AccentBanner(stringResource(notice), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), danger = true)
            }
        }
        item(key = "title") {
            Box(Modifier.fillMaxWidth()) { SectionTitle(stringResource(R.string.wd_empty_pick_source)) }
        }
        item(key = "sources") {
            ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.wd_source_root),
                    summary = when (ui.rootAvailable) {
                        null -> stringResource(R.string.wd_root_checking)
                        true -> stringResource(R.string.wd_empty_root_sub)
                        false -> stringResource(R.string.wd_root_unavailable)
                    },
                    startAction = { PreferenceIcon(SettingIcons.tune) },
                    enabled = idle && ui.rootAvailable != false,
                    onClick = vm::pullFromRoot,
                )
                ArrowPreference(
                    title = stringResource(R.string.wd_source_import),
                    summary = stringResource(R.string.wd_source_import_sub),
                    startAction = { PreferenceIcon(SettingIcons.importData) },
                    enabled = idle,
                    onClick = { picker.launch(arrayOf("*/*")) },
                )
            }
        }
        item(key = "more") {
            ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.wd_open_accounts),
                    summary = stringResource(R.string.wd_empty_accounts_sub),
                    onClick = onOpenAccounts,
                )
            }
        }
    }
}

/** Accent squircle with a chat glyph. */
@Composable
private fun EmptyHero() {
    val accent = ZeroTheme.colors.accent
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(rememberSquircleShape(28.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.08f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.wd_ic_chats),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(44.dp),
        )
    }
}
