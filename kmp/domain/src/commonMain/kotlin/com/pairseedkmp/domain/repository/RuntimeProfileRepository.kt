package com.pairseedkmp.domain.repository

import com.pairseedkmp.domain.runtime.RuntimeProfile

/** 运行配置读写端口（纯单机一套）。 */
interface RuntimeProfileRepository {
    /** 当前配置（单机单行）；首启还没有 → null。 */
    fun current(): RuntimeProfile?

    /** 首启建行，返回库分配的 id（传入的 `RuntimeProfile.id` 忽略）。 */
    fun insert(profile: RuntimeProfile): Long

    /** 存活心跳：结算时更新 `last_seen_at`（供"窗口是否整段落在离线空白里"判定）。 */
    fun heartbeat(id: Long, now: Long)

    /** 镜像位：API Key 配置状态（Key 本体只存壳层 Keystore 密文，不落库 / 不进日志；01）。 */
    fun setApiKeyConfigured(id: Long, configured: Boolean, now: Long)
}
