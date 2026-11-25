package com.zeroui.template.feature.service

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zeroui.kit.components.ButtonVariant
import com.zeroui.kit.components.DialogButtons
import com.zeroui.kit.components.ManagedDialog
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.TextButton
import com.zeroui.template.R
import com.zeroui.template.ZeroApp
import com.zeroui.template.core.AppContainer
import com.zeroui.template.data.Block
import com.zeroui.template.data.Notice
import com.zeroui.template.feature.settings.LocalAppController
import com.zeroui.template.feature.update.openDownload
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The dependency container of the running app. */
@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as ZeroApp).container

/**
 * Checks the service while the app is in the foreground: on start, on every return to the foreground
 * and then every [com.zeroui.template.data.ServiceStatus.refreshMinutes]. Also sends the daily statistics when allowed.
 */
@Composable
fun ServiceEffects() {
    val container = appContainer()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(container, lifecycle) {
        container.telemetry.countLaunch()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                container.service.refresh()
                container.telemetry.sendIfDue()
                delay(container.service.refreshMinutes * 60_000L)
            }
        }
    }
}

/** Full-screen page shown while the whole app is turned off by the service. */
@Composable
fun BlockedScreen(block: Block) {
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    BackHandler { (context as? Activity)?.finish() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = block.title.ifEmpty { stringResource(R.string.service_blocked_app_title) },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = block.message,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.service_blocked_app_note),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (block.hasButton) {
                    TextButton(
                        text = block.buttonText,
                        onClick = { block.buttonUrl?.let { openDownload(context, it) } },
                        variant = ButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    text = stringResource(if (checking) R.string.service_checking else R.string.service_retry),
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            container.service.refresh(force = true)
                            checking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = stringResource(R.string.exit),
                    onClick = { (context as? Activity)?.finish() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Explains that a feature is turned off by the service. */
@Composable
fun FeatureBlockedDialog(block: Block, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ManagedDialog(
        onDismiss = onDismiss,
        title = block.title.ifEmpty { stringResource(R.string.service_blocked_feature_title) },
        summary = block.message,
    ) {
        if (block.hasButton) {
            DialogButtons(
                confirmText = block.buttonText,
                cancelText = stringResource(R.string.ok),
                onConfirm = { close { block.buttonUrl?.let { openDownload(context, it) } } },
                onCancel = { close(onDismiss) },
            )
        } else {
            TextButton(
                text = stringResource(R.string.ok),
                onClick = { close(onDismiss) },
                variant = ButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Shows the service notices one after another. */
@Composable
fun NoticeHost() {
    val context = LocalContext.current
    val service = appContainer().service
    val dialogs by service.dialogs.collectAsStateWithLifecycle()
    val notice = dialogs.firstOrNull() ?: return
    key(notice.id) { NoticeDialog(notice, onClosed = { service.dialogClosed(notice) }, onOpen = { openDownload(context, it) }) }
}

@Composable
private fun NoticeDialog(notice: Notice, onClosed: () -> Unit, onOpen: (String) -> Unit) {
    ManagedDialog(onDismiss = onClosed, title = notice.title) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = notice.body,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = if (notice.isWarning) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(20.dp))
            if (notice.hasButton) {
                DialogButtons(
                    confirmText = notice.buttonText,
                    cancelText = stringResource(R.string.ok),
                    onConfirm = {
                        close {
                            notice.buttonUrl?.let(onOpen)
                            onClosed()
                        }
                    },
                    onCancel = { close(onClosed) },
                )
            } else {
                TextButton(
                    text = stringResource(R.string.ok),
                    onClick = { close(onClosed) },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Asks once whether anonymous usage statistics may be sent. */
@Composable
fun StatsConsentHost() {
    val container = appContainer()
    val controller = LocalAppController.current
    val scope = rememberCoroutineScope()
    if (!container.telemetry.available || controller.settings.statsConsent != null) return
    ManagedDialog(
        onDismiss = {},
        title = stringResource(R.string.stats_consent_title),
        summary = stringResource(R.string.stats_consent_msg),
        dismissible = false,
    ) {
        DialogButtons(
            confirmText = stringResource(R.string.stats_consent_allow),
            cancelText = stringResource(R.string.stats_consent_deny),
            onConfirm = {
                close {
                    container.telemetry.setConsent(true)
                    container.telemetry.count("launch")
                    scope.launch { container.telemetry.sendIfDue() }
                }
            },
            onCancel = { close { container.telemetry.setConsent(false) } },
        )
    }
}

/** Asks an existing user to accept the updated agreement before the app goes on. */
@Composable
fun AgreementUpdateScreen(onOpenTerms: () -> Unit, onAccept: () -> Unit) {
    val context = LocalContext.current
    BackHandler { (context as? Activity)?.finish() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.agreement_update_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.agreement_update_msg),
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.agreement_update_read),
                    onClick = onOpenTerms,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = stringResource(R.string.agreement_update_accept),
                    onClick = onAccept,
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = stringResource(R.string.agreement_update_decline),
                    onClick = { (context as? Activity)?.finish() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
