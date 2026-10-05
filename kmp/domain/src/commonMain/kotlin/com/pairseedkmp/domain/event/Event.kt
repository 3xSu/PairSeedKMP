package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.MILLIS_PER_DAY
import com.pairseedkmp.domain.common.MILLIS_PER_SECOND
import com.pairseedkmp.domain.common.Period

/** 过期时长（《数据设计》）：生成时刻 + 7 天；已发生 / 已拒的也照此从历史页滚出（逻辑删除）。 */
const val EVENT_EXPIRY_MILLIS: Long = 7 * MILLIS_PER_DAY

/**
 * 事件（events 表一行的域模型）——"一条记录走完一生"。
 * 时间一律 Unix 毫秒；steps 库里是 TEXT，域内按定稿信封解析成步骤表。
 * "过期"由 expires_at 算出来（生成时刻 + 7 天），不落库；半固定发生记录的"生成时刻" = 发生时刻。
 */
data class Event(
    val id: Long,
    val packId: Long,
    val actorId: Long,
    val targetId: Long? = null,
    val sceneId: Long,
    val summary: String,
    val steps: List<EventStep>,
    val comparisonAxes: String? = null,
    val confirmationPayload: String? = null,
    /** 可答 / 可触发时段；空 = 不限（半固定为 NULL）。 */
    val suitableTime: List<Period> = emptyList(),
    val status: EventStatus,
    val origin: EventOrigin,
    val sourceSeedId: Long? = null,
    /** 半固定凭据（仅 origin=SCHEDULED 有值）；列随步骤 2 落库。 */
    val semiFixedTemplateCode: String? = null,
    /** 事件开始那一刻（进历史的时间）；未发生 = null。 */
    val occurredAt: Long? = null,
    val durationSec: Int,
    val expiresAt: Long,
    val isDeleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 结束时刻算出来（没有结束时刻字段：occurred_at + duration_sec）。 */
    val endsAt: Long?
        get() = occurredAt?.let { it + durationSec * MILLIS_PER_SECOND }

    /** 时长加总 ↔ duration_sec 是否对得上。 */
    fun durationMatchesSteps(durations: BehaviorDurationSource): Boolean =
        durationSec == steps.totalDurationSeconds(durations)
}
