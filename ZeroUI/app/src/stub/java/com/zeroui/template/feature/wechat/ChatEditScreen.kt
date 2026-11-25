package com.zeroui.template.feature.wechat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.template.R

@Suppress("UNUSED_PARAMETER")
@Composable
fun ChatEditScreen(talker: String, onBack: () -> Unit) {
    ZeroPageFrame(title = stringResource(R.string.wd_edit_title), onBack = onBack) { padding ->
        Text(
            text = stringResource(R.string.wd_unavailable_edit),
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}
