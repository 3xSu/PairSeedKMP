package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.common.minuteOfDay
import com.pairseedkmp.domain.repository.EventRepository
import com.pairseedkmp.domain.repository.UserConfirmationRepository

/**
 * 一级确认（《工作链路·确认链路 1.4–1.6》）：用户对"拿不准"的待确认事件答是 / 否。
 *
 * 语义（1.4）：
 * - 答是 → 事件进清单；答否 → 摘掉"当前这个"时段（回答时界面上看到的那个）——
 *   还剩别的时段 → 事件留着（时段已修正）、进清单；摘空 → 已拒（"摘完就空了"就是真不想要）。
 * - 每次回答往 `user_confirmations` 记一行（只插不删、按 `answered_at` 最新生效），记下 `answered_period`。
 *
 * 改答（1.5）：点"已通过 / 已拒绝"重开确认窗口 = 倒着回滚上一答（摘过时段的加回来、拒过的离开已拒）
 * 再落新答——实现上**一次答复原子完成**（回滚 + 应用一起），不单独落中间"待确认"态；
 * 所以任一时刻事件都恰好反映"最新那条回答"的效果。
 *
 * 可答题面 = 回滚上一答后的原始时段（没答过 = `suitable_time` 本身）；
 * 可答门 = 能答的状态 × 没过期 × 当前时段在该面里（调试壳按钮同此规则）。
 * 边界：`suitable_time` 为空（不限）的事件此门为关——本阶段确认事件都带时段；
 * "空 = 不限"只用于触发挑选（见 [EventTrigger]），确认链路以后若接入"不限"候选再另定答法。
 */

/** 能答的状态：还没发生、也没在跑（待确认 / 在清单 / 已拒）。 */
private val ANSWERABLE_STATUSES = setOf(
    EventStatus.PENDING_CONFIRMATION,
    EventStatus.IN_LIST,
    EventStatus.REJECTED,
)

/** 可答题面：回滚上一答（="摘过时段的加回来"）后的原始时段；没答过就是 `suitable_time`。 */
fun answerablePeriods(event: Event, latest: UserConfirmation?): List<Period> {
    val periods = event.suitableTime.toMutableList()
    if (latest?.answer == ConfirmationAnswer.NO && latest.answeredPeriod !in periods) {
        periods += latest.answeredPeriod
    }
    return periods.sortedBy { it.ordinal }
}

/** 可答门（调试壳按钮同此规则）：能答的状态 × 没过期 × 当前时段 ∈ 可答题面。 */
fun isAnswerable(
    event: Event,
    latest: UserConfirmation?,
    now: Long,
    utcOffsetSeconds: Int,
): Boolean {
    if (event.isDeleted || now >= event.expiresAt) return false
    if (event.status !in ANSWERABLE_STATUSES) return false
    // 首答只给待确认；在清单 / 已拒的可答都是"改答"（必须有确认记录）——没答过的清单件（判定直通）没有改答这回事
    if (event.status != EventStatus.PENDING_CONFIRMATION && latest == null) return false
    val period = Period.forMinuteOfDay(minuteOfDay(now, utcOffsetSeconds))
    return period in answerablePeriods(event, latest)
}

/** 一次回答的结果（调试壳反馈话术用）。 */
data class AnswerResult(
    /** 更新后的事件（新 status / suitableTime / updatedAt）。 */
    val event: Event,
    val answer: ConfirmationAnswer,
    /** 这次回答针对的时段（= 回答时界面上看到的那个）。 */
    val answeredPeriod: Period,
    /** 答否且摘空 → 事件已拒。 */
    val rejected: Boolean,
)

/**
 * 一次回答的纯决策（不摸库）：先回滚上一答，再按 1.4 落新答——
 * 答是 → 在清单（可答题面复原）；答否 → 从可答题面摘掉 [answeredPeriod]，摘空 → 已拒。
 */
fun planAnswer(
    event: Event,
    latest: UserConfirmation?,
    answer: ConfirmationAnswer,
    answeredPeriod: Period,
    now: Long,
): AnswerResult {
    val base = answerablePeriods(event, latest)
    require(answeredPeriod in base) { "回答的时段 $answeredPeriod 不在可答题面 $base 里" }
    return when (answer) {
        ConfirmationAnswer.YES -> AnswerResult(
            event = event.copy(status = EventStatus.IN_LIST, suitableTime = base, updatedAt = now),
            answer = answer,
            answeredPeriod = answeredPeriod,
            rejected = false,
        )

        ConfirmationAnswer.NO -> {
            val remaining = base.filter { it != answeredPeriod }
            AnswerResult(
                event = event.copy(
                    status = if (remaining.isEmpty()) EventStatus.REJECTED else EventStatus.IN_LIST,
                    suitableTime = remaining,
                    updatedAt = now,
                ),
                answer = answer,
                answeredPeriod = answeredPeriod,
                rejected = remaining.isEmpty(),
            )
        }
    }
}

/** 确认编排（落库）：可答门 + 一次回答 + 只插不删的记录。 */
class EventConfirmer(
    private val eventRepository: EventRepository,
    private val confirmationRepository: UserConfirmationRepository,
) {
    /** 可答门（同纯函数 [isAnswerable]）；调试壳的答是 / 答否按钮同此规则。 */
    fun canAnswer(eventId: Long, now: Long, utcOffsetSeconds: Int): Boolean {
        val event = eventRepository.byId(eventId) ?: return false
        return isAnswerable(event, confirmationRepository.latestFor(eventId), now, utcOffsetSeconds)
    }

    /** 答一次（可答门不过 → null，什么都不落）。 */
    fun answer(
        eventId: Long,
        answer: ConfirmationAnswer,
        now: Long,
        utcOffsetSeconds: Int,
    ): AnswerResult? {
        val event = eventRepository.byId(eventId) ?: return null
        val latest = confirmationRepository.latestFor(eventId)
        if (!isAnswerable(event, latest, now, utcOffsetSeconds)) return null
        val answeredPeriod = Period.forMinuteOfDay(minuteOfDay(now, utcOffsetSeconds))
        val result = planAnswer(event, latest, answer, answeredPeriod, now)
        confirmationRepository.insert(
            UserConfirmation(
                id = 0,
                eventId = eventId,
                answeredPeriod = answeredPeriod,
                answer = answer,
                answeredAt = now,
            )
        )
        eventRepository.updateStatusAndSuitableTime(eventId, result.event.status, result.event.suitableTime, now)
        return result
    }
}
