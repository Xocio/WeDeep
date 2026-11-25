package com.zeroui.template.core

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zeroui.template.ZeroApp
import com.zeroui.template.data.ServiceStatus
import com.zeroui.template.data.SettingsRepository
import com.zeroui.template.data.SharedPreferencesSettingsStorage
import com.zeroui.template.data.Telemetry
import com.zeroui.template.feature.welcome.AndroidSetupChecks
import com.zeroui.template.feature.welcome.SetupChecks
import com.zeroui.template.wechat.WeChatHub

/** Application-wide dependencies. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings: SettingsRepository by lazy {
        SettingsRepository(SharedPreferencesSettingsStorage(appContext)) {
            Log.w("Settings", "Stored settings were unreadable; defaults loaded", it)
        }
    }
    val wechat: WeChatHub by lazy { WeChatHub(appContext) }
    val service: ServiceStatus by lazy { ServiceStatus(appContext, settings) }
    val telemetry: Telemetry by lazy { Telemetry(appContext, settings) }
    val setupChecks: SetupChecks by lazy { AndroidSetupChecks(appContext, minimumSdk = Build.VERSION_CODES.N) }
}

/** Returns the [VM] of the current store owner, creating it from the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: AppContainer.() -> VM): VM {
    val container = (LocalContext.current.applicationContext as ZeroApp).container
    return viewModel<VM>(
        key = key,
        factory = viewModelFactory {
            initializer { container.create() }
        },
    )
}
