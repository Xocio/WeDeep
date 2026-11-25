package com.zeroui.template.navigation

import com.zeroui.template.data.Feature
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/** Destinations of the app navigation stack. */
@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Main : AppRoute

    @Serializable
    data object AppSettings : AppRoute

    @Serializable
    data object Theme : AppRoute

    @Serializable
    data object NavTabs : AppRoute

    @Serializable
    data object HomeBackground : AppRoute

    @Serializable
    data object GlassIntensity : AppRoute

    @Serializable
    data object About : AppRoute

    @Serializable
    data object Licenses : AppRoute

    @Serializable
    data object Contributors : AppRoute

    @Serializable
    data class Chat(val talker: String, val focus: Long = 0L) : AppRoute

    @Serializable
    data object Accounts : AppRoute

    @Serializable
    data class AccountDetail(val hash: String) : AppRoute

    @Serializable
    data class Export(val talker: String = "") : AppRoute

    @Serializable
    data class MediaGrid(val talker: String) : AppRoute

    @Serializable
    data class ChatEdit(val talker: String) : AppRoute

    @Serializable
    data class AiAnalysis(val talker: String = "") : AppRoute

    @Serializable
    data object AiProvider : AppRoute

    @Serializable
    data object AiHistory : AppRoute

    /** Agreement document; [terms] picks the user agreement, otherwise the disclaimer. */
    @Serializable
    data class Document(val terms: Boolean) : AppRoute
}

/** The feature a page belongs to, or null for pages the service cannot turn off on their own. */
fun AppRoute.feature(): Feature? = when (this) {
    AppRoute.Accounts, is AppRoute.AccountDetail -> Feature.Accounts
    is AppRoute.Chat, is AppRoute.MediaGrid -> Feature.Chat
    is AppRoute.Export -> Feature.Export
    is AppRoute.ChatEdit -> Feature.Edit
    is AppRoute.AiAnalysis, AppRoute.AiHistory, AppRoute.AiProvider -> Feature.Ai
    else -> null
}
