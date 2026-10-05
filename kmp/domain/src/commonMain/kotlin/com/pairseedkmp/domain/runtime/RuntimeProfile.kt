package com.pairseedkmp.domain.runtime

/**
 * 本地运行配置（runtime_profiles 表）：纯单机一套（单行）。
 * API Key 不落库——存 Android Keystore 加密的本地偏好；这里只有"是否已配置"。
 */
data class RuntimeProfile(
    val id: Long,
    /** 当前生效的角色组；首启还没有 → null。 */
    val activePackId: Long? = null,
    /** 当前生效的场景；首启还没有 → null。 */
    val activeSceneId: Long? = null,
    /** 端侧小模型标识；未选 → null。 */
    val onDeviceModel: String? = null,
    val apiKeyConfigured: Boolean = false,
    /** app 存活心跳（Unix 毫秒）：结算时更新；判"窗口是否整段落在离线空白里"；首启未记 → null。 */
    val lastSeenAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
