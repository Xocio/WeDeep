package com.zeroui.template

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.kit.motion.ZeroMotion
import com.zeroui.kit.navigation.PageTransition
import com.zeroui.kit.navigation.ZeroNavHost
import com.zeroui.kit.navigation.page
import com.zeroui.kit.navigation.pushUnique
import com.zeroui.template.data.Block
import com.zeroui.template.data.Feature
import com.zeroui.template.feature.ai.AiAnalysisScreen
import com.zeroui.template.feature.ai.AiHistoryPage
import com.zeroui.template.feature.ai.AiProviderPage
import com.zeroui.template.feature.main.MainScreen
import com.zeroui.template.feature.service.AgreementUpdateScreen
import com.zeroui.template.feature.service.BlockedScreen
import com.zeroui.template.feature.service.FeatureBlockedDialog
import com.zeroui.template.feature.service.NoticeHost
import com.zeroui.template.feature.service.ServiceEffects
import com.zeroui.template.feature.service.StatsConsentHost
import com.zeroui.template.feature.service.appContainer
import com.zeroui.template.feature.settings.AboutPage
import com.zeroui.template.feature.settings.AppSettingsPage
import com.zeroui.template.feature.settings.ContributorsPage
import com.zeroui.template.feature.settings.GlassIntensityPage
import com.zeroui.template.feature.settings.HomeBackgroundPage
import com.zeroui.template.feature.settings.LicensesPage
import com.zeroui.template.feature.settings.LocalAppController
import com.zeroui.template.feature.settings.NavTabsPage
import com.zeroui.template.feature.settings.ThemeSettingsPage
import com.zeroui.template.feature.welcome.AgreementDoc
import com.zeroui.template.feature.welcome.AgreementPage
import com.zeroui.template.feature.welcome.WelcomeScreen
import com.zeroui.template.feature.wechat.AccountDetailScreen
import com.zeroui.template.feature.wechat.AccountsScreen
import com.zeroui.template.feature.wechat.ChatEditScreen
import com.zeroui.template.feature.wechat.ChatScreen
import com.zeroui.template.feature.wechat.ExportScreen
import com.zeroui.template.feature.wechat.MediaGridScreen
import com.zeroui.template.navigation.AppRoute
import com.zeroui.template.navigation.feature
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.rememberNavController
import kotlinx.coroutines.launch

/** Top level stage of the app. */
private enum class RootStage { Welcome, Agreement, App }

/** Chooses between the welcome flow, the updated agreement and the main navigation graph. */
@Composable
fun AppRoot(welcomeCompleted: Boolean, agreementAccepted: Boolean, pageTransition: PageTransition) {
    val stage = when {
        !welcomeCompleted -> RootStage.Welcome
        !agreementAccepted -> RootStage.Agreement
        else -> RootStage.App
    }
    AnimatedContent(
        targetState = stage,
        transitionSpec = { ZeroMotion.crossFade() },
        label = "appRoot",
    ) { current ->
        when (current) {
            RootStage.Welcome -> WelcomeScreen()
            RootStage.Agreement -> AgreementGate()
            RootStage.App -> ServiceGate(pageTransition)
        }
    }
}

/** The updated agreement, accepted before anything connects to the service. */
@Composable
private fun AgreementGate() {
    val controller = LocalAppController.current
    var reading by rememberSaveable { mutableStateOf(false) }
    if (reading) {
        BackHandler { reading = false }
        AgreementPage(doc = AgreementDoc.Terms, onBack = { reading = false }, modifier = Modifier.fillMaxSize())
    } else {
        AgreementUpdateScreen(onOpenTerms = { reading = true }, onAccept = controller::acceptAgreement)
    }
}

/** The app, or the blocked page while the service turns the whole app off. */
@Composable
private fun ServiceGate(pageTransition: PageTransition) {
    val service = appContainer().service
    ServiceEffects()
    val blocks by service.blocks.collectAsStateWithLifecycle()
    val appBlock = service.blockFor(Feature.App, blocks)
    if (appBlock != null) {
        BlockedScreen(appBlock)
    } else {
        AppNavGraph(pageTransition, blocks)
        NoticeHost()
        StatsConsentHost()
    }
}

@Composable
private fun AppNavGraph(pageTransition: PageTransition, blocks: List<Block>) {
    val controller = rememberNavController<AppRoute>(AppRoute.Main)
    val container = appContainer()
    var aiOpenSession by remember { mutableStateOf<String?>(null) }
    var blockedNotice by remember { mutableStateOf<Block?>(null) }
    val navigate: (AppRoute) -> Unit = remember(controller, container) {
        { route ->
            val feature = route.feature()
            val block = feature?.let { container.service.blockFor(it) }
            if (block != null) {
                blockedNotice = block
            } else {
                if (feature != null && route !in controller.backStack) container.telemetry.count("open." + feature.key)
                controller.pushUnique(route)
            }
        }
    }
    // Closes the pages of a feature the service has just turned off.
    LaunchedEffect(blocks) {
        val stack = controller.backStack.toList()
        val first = stack.indexOfFirst { key ->
            (key as? AppRoute)?.feature()?.let { container.service.blockFor(it, blocks) } != null
        }
        if (first > 0) repeat(stack.size - first) { controller.pop() }
    }
    ZeroNavHost(
        controller = controller,
        transition = pageTransition,
    ) {
        page<AppRoute.Main>(controller) {
            MainScreen(
                // Main stays composed under pushed pages; this tells it when it is covered.
                isActive = controller.backStack.size == 1,
                onNavigate = navigate,
            )
        }
        page<AppRoute.AppSettings>(controller) {
            SettingsPage(controller, R.string.app_settings) { padding ->
                AppSettingsPage(
                    pad = padding,
                    onTheme = { navigate(AppRoute.Theme) },
                    onAbout = { navigate(AppRoute.About) },
                    onDocument = { terms -> navigate(AppRoute.Document(terms)) },
                )
            }
        }
        page<AppRoute.Theme>(controller) {
            SettingsPage(controller, R.string.theme_settings) { padding ->
                ThemeSettingsPage(
                    pad = padding,
                    onGlassIntensity = { navigate(AppRoute.GlassIntensity) },
                    onNavTabs = { navigate(AppRoute.NavTabs) },
                    onHomeBackground = { navigate(AppRoute.HomeBackground) },
                )
            }
        }
        page<AppRoute.NavTabs>(controller) {
            SettingsPage(controller, R.string.nav_tabs_title) { padding -> NavTabsPage(padding) }
        }
        page<AppRoute.HomeBackground>(controller) {
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            SettingsPage(controller, R.string.home_bg_title, snackbar) { padding ->
                HomeBackgroundPage(
                    pad = padding,
                    toast = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
            }
        }
        page<AppRoute.GlassIntensity>(controller) {
            SettingsPage(controller, R.string.glass_intensity) { padding -> GlassIntensityPage(padding) }
        }
        page<AppRoute.About>(controller) {
            SettingsPage(controller, R.string.about) { padding ->
                AboutPage(
                    pad = padding,
                    onLicenses = { navigate(AppRoute.Licenses) },
                    onContributors = { navigate(AppRoute.Contributors) },
                )
            }
        }
        page<AppRoute.Licenses>(controller) {
            SettingsPage(controller, R.string.open_source_licenses) { padding -> LicensesPage(padding) }
        }
        page<AppRoute.Contributors>(controller) {
            SettingsPage(controller, R.string.contributors_title) { padding -> ContributorsPage(padding) }
        }
        page<AppRoute.Chat>(controller) { route ->
            ChatScreen(
                talker = route.talker,
                focus = route.focus,
                onBack = { controller.pop() },
                onNavigate = navigate,
            )
        }
        page<AppRoute.Accounts>(controller) {
            AccountsScreen(onBack = { controller.pop() }, onOpenAccount = { navigate(AppRoute.AccountDetail(it)) })
        }
        page<AppRoute.AccountDetail>(controller) { route -> AccountDetailScreen(hash = route.hash, onBack = { controller.pop() }) }
        page<AppRoute.Export>(controller) { route -> ExportScreen(talker = route.talker, onBack = { controller.pop() }) }
        page<AppRoute.MediaGrid>(controller) { route -> MediaGridScreen(talker = route.talker, onBack = { controller.pop() }) }
        page<AppRoute.ChatEdit>(controller) { route -> ChatEditScreen(talker = route.talker, onBack = { controller.pop() }) }
        page<AppRoute.AiAnalysis>(controller) { route ->
            AiAnalysisScreen(
                talker = route.talker,
                isActive = controller.backStack.lastOrNull() == route,
                openSession = aiOpenSession,
                onSessionOpened = { aiOpenSession = null },
                onBack = { controller.pop() },
                onOpenProvider = { navigate(AppRoute.AiProvider) },
                onOpenHistory = { navigate(AppRoute.AiHistory) },
            )
        }
        page<AppRoute.AiHistory>(controller) {
            AiHistoryPage(
                onBack = { controller.pop() },
                onOpen = { id ->
                    aiOpenSession = id
                    controller.pop()
                },
            )
        }
        page<AppRoute.AiProvider>(controller) {
            SettingsPage(controller, R.string.ai_provider) { padding -> AiProviderPage(padding) }
        }
        page<AppRoute.Document>(controller) { route ->
            AgreementPage(
                doc = if (route.terms) AgreementDoc.Terms else AgreementDoc.Disclaimer,
                onBack = { controller.pop() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    blockedNotice?.let { block ->
        key(block.id) { FeatureBlockedDialog(block, onDismiss = { blockedNotice = null }) }
    }
}

@Composable
private fun SettingsPage(
    controller: NavController,
    titleRes: Int,
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    ZeroPageFrame(
        title = stringResource(titleRes),
        onBack = { controller.pop() },
        bottomInset = 96.dp,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        content = content,
    )
}
