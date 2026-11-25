package com.zeroui.template.feature.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroui.kit.components.AccentBanner
import com.zeroui.kit.components.Badge
import com.zeroui.kit.components.DropdownPreference
import com.zeroui.kit.components.Icon
import com.zeroui.kit.components.IconButton
import com.zeroui.kit.components.ListCard
import com.zeroui.kit.components.ListRow
import com.zeroui.kit.components.LoadingIndicator
import com.zeroui.kit.components.SectionTitle
import com.zeroui.kit.components.SwitchPreference
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.TextField
import com.zeroui.template.R
import com.zeroui.template.ai.AiConfig
import com.zeroui.template.ai.AiLog
import com.zeroui.template.ai.AiProtocol
import com.zeroui.template.ai.net.AiBalance
import com.zeroui.template.ai.net.AiError
import com.zeroui.template.ai.net.AiModels
import com.zeroui.template.ai.net.AiProbe
import com.zeroui.template.ai.net.AiProviderTemplates
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Provider settings: address, key, model and protocol, a connection test and presets. Every edit is saved at once. */
@Composable
fun AiProviderPage(pad: PaddingValues) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var protocol by remember { mutableStateOf(AiConfig.getProtocol(context)) }
    var baseUrl by remember { mutableStateOf(TextFieldValue(AiConfig.getBaseUrl(context))) }
    var model by remember { mutableStateOf(TextFieldValue(AiConfig.getModel(context))) }
    var allowHttp by remember { mutableStateOf(AiConfig.isHttpAllowed(context)) }

    var apiKey by remember { mutableStateOf(TextFieldValue(AiConfig.getApiKey(context))) }
    var keyRevealed by remember { mutableStateOf(false) }
    var keyStoreFailed by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiProbe.Result?>(null) }

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsTruncated by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    var balanceChecking by remember { mutableStateOf(false) }
    var balanceResult by remember { mutableStateOf<AiBalance.Result?>(null) }

    val isPlainHttp = baseUrl.text.trim().startsWith("http://", ignoreCase = true)

    /** Shows [message] and writes [detail] to the log. */
    fun report(message: String, code: String = "", detail: String = "") {
        scope.launch { snackbar.showSnackbar(message) }
        if (detail.isNotBlank()) AiLog.record(context, code, detail)
    }

    /** Clears the results of the previous test and balance check. */
    fun invalidate() {
        result = null
        balanceResult = null
    }

    /** Fills in the address and protocol of [template]; the key and model stay unchanged. */
    fun applyTemplate(template: AiProviderTemplates.Template) {
        baseUrl = TextFieldValue(template.baseUrl)
        protocol = template.protocol
        AiConfig.setBaseUrl(context, template.baseUrl)
        AiConfig.setProtocol(context, template.protocol)
        invalidate()
    }

    fun openSignup(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /** Checks the fields an action needs; true when it can run. */
    fun ready(needModel: Boolean): Boolean {
        val cfg = AiConfig.snapshot(context)
        val missing = if (needModel) !cfg.ready else cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()
        val res = when {
            missing -> if (needModel) R.string.ai_test_need_fields else R.string.ai_models_need_fields
            isPlainHttp && !allowHttp -> R.string.ai_test_need_http
            else -> null
        } ?: return true
        report(resources.getString(res))
        return false
    }

    fun listModels() {
        if (!ready(needModel = false)) return
        listing = true
        scope.launch {
            when (val r = AiModels.list(AiConfig.snapshot(context))) {
                is AiModels.Result.Ok -> {
                    models = r.ids
                    modelsTruncated = r.truncated
                    showPicker = true
                }

                is AiModels.Result.Fail -> {
                    models = emptyList()
                    report(modelsMessage(context, r.kind), codeOf(r.kind), r.detail)
                }
            }
            listing = false
        }
    }

    fun runTest() {
        if (!ready(needModel = true)) return
        result = null
        testing = true
        scope.launch {
            val r = AiProbe.test(AiConfig.snapshot(context))
            result = r
            testing = false
            report(probeMessage(context, r), probeCode(r), probeDetail(context, r))
        }
    }

    fun checkBalance() {
        if (!ready(needModel = false)) return
        balanceResult = null
        balanceChecking = true
        scope.launch {
            val r = AiBalance.fetch(AiConfig.snapshot(context))
            balanceResult = r
            balanceChecking = false
            if (r !is AiBalance.Result.Ok) {
                report(balanceMessage(context, r), balanceCode(r), balanceDetail(r))
            }
        }
    }

    val cardModifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = pad) {

            item { SectionTitle(stringResource(R.string.ai_conn_section)) }
            item {
                ListCard(cardModifier) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                        TextField(
                            value = baseUrl,
                            onValueChange = {
                                baseUrl = it
                                AiConfig.setBaseUrl(context, it.text)
                                invalidate()
                            },
                            label = stringResource(R.string.ai_base_url),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    (balanceResult as? AiBalance.Result.Ok)?.let {
                                        Text(
                                            text = it.value,
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    if (balanceChecking) {
                                        LoadingIndicator(
                                            size = 20.dp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.padding(start = 4.dp, end = 16.dp),
                                        )
                                    } else {
                                        val balanceText = stringResource(R.string.ai_balance_check)
                                        TooltipBox(text = balanceText) {
                                            IconButton(
                                                onClick = { checkBalance() },
                                                minWidth = 36.dp,
                                                minHeight = 36.dp,
                                                modifier = Modifier.padding(end = 8.dp),
                                            ) {
                                                Icon(
                                                    imageVector = MiuixIcons.BankCards,
                                                    contentDescription = balanceText,
                                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (isPlainHttp) {
                            Spacer(Modifier.height(10.dp))
                            AccentBanner(
                                text = stringResource(R.string.ai_http_warn),
                                compact = true,
                                danger = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        val revealToggle: @Composable (() -> Unit)? =
                            if (apiKey.text.isEmpty()) null else {
                                {
                                    IconButton(
                                        onClick = { keyRevealed = !keyRevealed },
                                        minWidth = 36.dp,
                                        minHeight = 36.dp,
                                        modifier = Modifier.padding(end = 8.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (keyRevealed) MiuixIcons.Hide else MiuixIcons.Show,
                                            contentDescription = stringResource(
                                                if (keyRevealed) R.string.ai_api_key_hide else R.string.ai_api_key_show
                                            ),
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        TextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                keyStoreFailed = !AiConfig.setApiKey(context, it.text)
                                invalidate()
                            },
                            label = stringResource(R.string.ai_api_key),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (keyRevealed) VisualTransformation.None
                            else PasswordVisualTransformation('•'),
                            trailingIcon = revealToggle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (keyStoreFailed) {
                            Spacer(Modifier.height(10.dp))
                            AccentBanner(
                                text = stringResource(R.string.ai_api_key_store_failed),
                                compact = true,
                                danger = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        TextField(
                            value = model,
                            onValueChange = {
                                model = it
                                AiConfig.setModel(context, it.text)
                                invalidate()
                            },
                            label = stringResource(R.string.ai_model),
                            singleLine = true,
                            trailingIcon = {
                                if (listing) {
                                    LoadingIndicator(
                                        size = 20.dp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(end = 16.dp),
                                    )
                                } else {
                                    val modelsText = stringResource(R.string.ai_models_list)
                                    TooltipBox(text = modelsText) {
                                        IconButton(
                                            onClick = { listModels() },
                                            minWidth = 36.dp,
                                            minHeight = 36.dp,
                                            modifier = Modifier.padding(end = 8.dp),
                                        ) {
                                            Icon(
                                                imageVector = MiuixIcons.ListView,
                                                contentDescription = modelsText,
                                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    DropdownPreference(
                        title = stringResource(R.string.ai_protocol),
                        summary = stringResource(R.string.ai_protocol_sub),
                        items = listOf(
                            stringResource(R.string.ai_protocol_openai),
                            stringResource(R.string.ai_protocol_anthropic),
                        ),
                        selectedIndex = protocol.ordinal,
                        onSelectedIndexChange = { idx ->
                            protocol = AiProtocol.entries[idx]
                            AiConfig.setProtocol(context, protocol)
                            invalidate()
                        },
                    )

                    if (isPlainHttp) {
                        SwitchPreference(
                            title = stringResource(R.string.ai_http_allow),
                            summary = stringResource(R.string.ai_http_allow_sub),
                            checked = allowHttp,
                            onCheckedChange = {
                                allowHttp = it
                                AiConfig.setHttpAllowed(context, it)
                            },
                        )
                    }

                    ListRow(
                        title = stringResource(R.string.ai_test),
                        summary = stringResource(R.string.ai_test_sub),
                        enabled = !testing,
                        onClick = { runTest() },
                        endActions = { TestBadge(testing = testing, result = result) },
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.ai_template_section)) }
            item {
                ListCard(cardModifier) {
                    AiProviderTemplates.ALL.forEach { template ->
                        ListRow(
                            title = template.name,
                            summary = template.baseUrl,
                            onClick = {
                                applyTemplate(template)
                                report(resources.getString(R.string.ai_template_applied, template.name))
                            },
                            endActions = {
                                val signupText = stringResource(R.string.ai_template_signup, template.name)
                                TooltipBox(text = signupText) {
                                    IconButton(
                                        onClick = { openSignup(template.signupUrl) },
                                        minWidth = 36.dp,
                                        minHeight = 36.dp,
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.Link,
                                            contentDescription = signupText,
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = pad.calculateBottomPadding()),
        )
    }

    if (showPicker) {
        AiModelPicker(
            models = models,
            current = model.text.trim(),
            truncated = modelsTruncated,
            onPick = { picked ->
                model = TextFieldValue(picked)
                AiConfig.setModel(context, picked)
                invalidate()
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Code of an error class shown on screen and written to the log. */
internal fun codeOf(kind: AiError): String = when (kind) {
    AiError.Auth -> "E401"
    AiError.NotFound -> "E404"
    AiError.RateLimit -> "E429"
    AiError.Server -> "E500"
    AiError.Network -> "E-NET"
    AiError.BadResponse -> "E-BODY"
}

/** On-screen text of a failed chat request. */
internal fun errorMessage(context: Context, kind: AiError, detail: String): String = when (kind) {
    AiError.Auth -> context.getString(R.string.ai_err_auth)
    AiError.NotFound -> context.getString(R.string.ai_err_notfound)
    AiError.RateLimit -> context.getString(R.string.ai_err_rate)
    AiError.Server -> context.getString(R.string.ai_err_server)
    AiError.Network -> context.getString(R.string.ai_err_network, detail)
    AiError.BadResponse -> context.getString(R.string.ai_err_body)
}

private fun balanceMessage(context: Context, result: AiBalance.Result): String = when (result) {
    is AiBalance.Result.Ok -> result.value
    is AiBalance.Result.NotFound ->
        if (result.shape != null) context.getString(R.string.ai_balance_shape)
        else context.getString(R.string.ai_balance_notfound)

    is AiBalance.Result.Fail -> when (result.kind) {
        AiError.NotFound, AiError.BadResponse -> context.getString(R.string.ai_balance_notfound)
        else -> errorMessage(context, result.kind, result.detail)
    }
}

private fun balanceCode(result: AiBalance.Result): String = when (result) {
    is AiBalance.Result.Ok -> ""
    is AiBalance.Result.NotFound -> if (result.shape != null) "E-BAL-FMT" else "E-BAL"
    is AiBalance.Result.Fail -> codeOf(result.kind)
}

private fun balanceDetail(result: AiBalance.Result): String = when (result) {
    is AiBalance.Result.Ok -> ""
    is AiBalance.Result.NotFound -> result.shape.orEmpty()
    is AiBalance.Result.Fail -> result.detail
}

private fun modelsMessage(context: Context, kind: AiError): String = when (kind) {
    AiError.NotFound, AiError.BadResponse -> context.getString(R.string.ai_models_unsupported)
    else -> errorMessage(context, kind, "")
}

/** Status badge of the "Test connection" row. */
@Composable
private fun TestBadge(testing: Boolean, result: AiProbe.Result?) = when {
    testing -> Badge(text = stringResource(R.string.ai_testing), accent = false)
    result == null -> Badge(text = stringResource(R.string.ai_state_untested), accent = false)
    result is AiProbe.Result.Ok -> Badge(text = stringResource(R.string.ai_state_ok))
    else -> Badge(text = stringResource(R.string.ai_state_fail), accent = false)
}

private fun probeMessage(context: Context, result: AiProbe.Result): String = when (result) {
    is AiProbe.Result.Ok -> context.getString(R.string.ai_test_ok)
    is AiProbe.Result.Fail -> errorMessage(context, result.kind, result.detail)
}

private fun probeCode(result: AiProbe.Result): String = when (result) {
    is AiProbe.Result.Ok -> ""
    is AiProbe.Result.Fail -> codeOf(result.kind)
}

private fun probeDetail(context: Context, result: AiProbe.Result): String = when (result) {
    is AiProbe.Result.Ok -> ""
    is AiProbe.Result.Fail ->
        if (result.detail.isBlank()) "" else context.getString(R.string.ai_err_detail, result.detail)
}
