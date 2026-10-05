package com.pairseedkmp.domain.event

/**
 * 半固定事件模板（semi_fixed_event_templates 表）：睡觉、吃饭这类生活骨架。
 * 不进事件表；到点发生时才在 events 里落一条 origin=SCHEDULED 的记录（历史页 / 状态机 / 过期机制复用）。
 */
data class SemiFixedEventTemplate(
    val id: Long,
    val packId: Long,
    val templateCode: String,
    val nameCn: String,
    val summary: String,
    val steps: List<EventStep>,
    /** 时间窗最早：当天 00:00 起分钟数（0..1439）。 */
    val windowEarliest: Int,
    /** 时间窗最晚：同上；与 earliest 相等 = 固定时刻；早 > 晚 = 跨天。 */
    val windowLatest: Int,
    val isDeleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
