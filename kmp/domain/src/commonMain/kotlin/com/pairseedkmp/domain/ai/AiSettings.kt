package com.pairseedkmp.domain.ai

/** 运行模式（《AI 接入-计划》三模式口径）。 */
enum class AiMode {
    /** 预设：预置响应，无模型也把全链路跑通 / 做确定性回归。 */
    PRESET,

    /** 端侧小模型（02）。 */
    ON_DEVICE,

    /** 远程 API Key（OpenAI 兼容）。 */
    API_KEY,
}

/** AI 运行配置（非密钥区）；Key 本体走 [SecretStore]，不落库 / 进日志。 */
data class AiSettings(
    val mode: AiMode = AiMode.PRESET,
    val baseUrl: String = "",
    val model: String = "",
)

/**
 * 配置读写端口（实现：壳层 prefs；`runtime_profiles.api_key_configured` 是库侧镜像位）。
 * 异步口径：suspend + interface（iOS 互操作就绪）。
 */
interface AiSettingsStore {
    suspend fun current(): AiSettings

    suspend fun update(settings: AiSettings)
}

/** 密钥存取端口（实现：Android Keystore 加密，密文落 prefs；不落库、不进日志）。 */
interface SecretStore {
    /** 未配置 → null。 */
    suspend fun get(): String?

    suspend fun set(value: String)

    suspend fun clear()
}
