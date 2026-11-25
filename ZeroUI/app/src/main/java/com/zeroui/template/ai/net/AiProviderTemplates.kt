package com.zeroui.template.ai.net

import com.zeroui.template.ai.AiProtocol

/** Provider presets that fill in the address and protocol. */
object AiProviderTemplates {

    data class Template(
        val name: String,
        val baseUrl: String,
        val protocol: AiProtocol,
        val signupUrl: String,
    )

    val ALL = listOf(
        Template(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            protocol = AiProtocol.OpenAi,
            signupUrl = "https://platform.deepseek.com",
        ),
        Template(
            name = "MiMo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            protocol = AiProtocol.OpenAi,
            signupUrl = "https://platform.xiaomimimo.com?ref=3F2W47",
        ),
        Template(
            name = "AiHubMix",
            baseUrl = "https://aihubmix.com/v1",
            protocol = AiProtocol.OpenAi,
            signupUrl = "https://aihubmix.com/?aff=cwdh",
        ),
        Template(
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            protocol = AiProtocol.OpenAi,
            signupUrl = "https://cloud.siliconflow.cn/i/PRgCXz55",
        ),
    )
}
