package com.zeroui.template.feature.wechat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroui.kit.components.ArrowPreference
import com.zeroui.kit.components.ListCard
import com.zeroui.kit.components.PreferenceIcon
import com.zeroui.kit.components.SectionTitle
import com.zeroui.kit.components.SettingIcons
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.template.R
import com.zeroui.template.navigation.AppRoute

/** Account and export tab. */
@Composable
fun MeScreen(bottomPadding: Dp, onNavigate: (AppRoute) -> Unit) {
    val hub = LocalHub.current
    val current by hub.current.collectAsStateWithLifecycle()
    val modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)

    ZeroPageFrame(title = stringResource(R.string.wd_tab_me), bottomInset = bottomPadding) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item { SectionTitle(stringResource(R.string.wd_section_data)) }
            item {
                ListCard(modifier) {
                    ArrowPreference(
                        title = stringResource(R.string.wd_accounts),
                        summary = current?.let { hub.label(it) } ?: stringResource(R.string.wd_no_account_title),
                        startAction = { PreferenceIcon(SettingIcons.layers) },
                        onClick = { onNavigate(AppRoute.Accounts) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.wd_export_all),
                        summary = stringResource(R.string.wd_export_all_sub),
                        startAction = { PreferenceIcon(SettingIcons.tune) },
                        enabled = current != null,
                        onClick = { onNavigate(AppRoute.Export("")) },
                    )
                }
            }
        }
    }
}
