package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.judgment.JudgeDecision
import com.pairseedkmp.domain.judgment.JudgmentDecider
import com.pairseedkmp.domain.judgment.JudgmentOutcome
import com.pairseedkmp.domain.judgment.JudgmentSnapshotSource
import com.pairseedkmp.domain.judgment.systemDecider
import com.pairseedkmp.domain.repository.EventRepository

/** 一条候选走完判定闸门的结果（06 第 4 层出口三选一 + 组装失败）。 */
sealed interface EventJudgeResult {
    /** 进清单（已落库；[event] 带库分配 id；[decision] 供调用方记日志 / 面板）。 */
    data class InList(val event: Event, val decision: JudgeDecision) : EventJudgeResult

    /** 待确认（已落库，带 `confirmation_payload`）。 */
    data class Pending(val event: Event, val decision: JudgeDecision) : EventJudgeResult

    /** 拒绝、不留任何记录（理由在 [decision] 里，只进端上日志）。 */
    data class Rejected(val decision: JudgeDecision) : EventJudgeResult

    /** 组不出判定快照（角色 / 场景读不到）→ 不收下本条（供补货停本轮）。 */
    data object NoSnapshot : EventJudgeResult
}

/**
 * 判定出口（06 计划 §4 第 4 层 + §5 步骤 3/4）：候选 → 快照 → 裁决 → 落库（或不留痕）。
 * - Pass → INSERT（`IN_LIST`）；`origin = SEED`、`expires_at = now + 7 天`、
 *   种子 / 接续 / 适合时段原样透传、`occurred_at = null`；
 * - Uncertain → INSERT（`PENDING_CONFIRMATION`；`confirmation_payload` 由裁决者给出——
 *   系统形状 [com.pairseedkmp.domain.judgment.systemConfirmationPayload]、AI 形状
 *   [com.pairseedkmp.domain.judgment.aiConfirmationPayload]）——答是后由确认链路进清单；
 * - Reject → 不落任何行（判定理由 / R / 原始输出不落库，排查看端上日志）；
 * - 快照 null → 整条不收下（不落库、不改状态），补货据此停本轮。
 * 落库是单条 INSERT（库内单语句原子），无跨语句事务。
 * 裁决者默认 = 系统三支（预设 / 端侧路线，[systemDecider]）；API 路线换 `AiJudge`（步骤 4），
 * 其失败降级同样回落系统三支（降级说明在 [JudgeDecision.degradedReason]）。
 */
class EventJudge(
    private val snapshotSource: JudgmentSnapshotSource,
    private val durations: BehaviorDurationSource,
    private val repository: EventRepository,
    private val decider: JudgmentDecider = systemDecider(),
) {

    /** 判一条候选并落库（[now] = 生成时刻：算 `expires_at` 与时间戳）。 */
    suspend fun deliver(candidate: EventCandidate, now: Long): EventJudgeResult {
        val snapshot = snapshotSource.snapshot(candidate) ?: return EventJudgeResult.NoSnapshot
        val decision = decider.decide(snapshot)
        return when (decision.outcome) {
            JudgmentOutcome.Pass -> {
                val event = insert(candidate, now, EventStatus.IN_LIST, confirmationPayload = null)
                EventJudgeResult.InList(event, decision)
            }

            is JudgmentOutcome.Uncertain -> {
                val event = insert(
                    candidate = candidate,
                    now = now,
                    status = EventStatus.PENDING_CONFIRMATION,
                    confirmationPayload = requireNotNull(decision.confirmationPayload) {
                        "待确认的结果必须带 payload（裁决者契约）"
                    },
                )
                EventJudgeResult.Pending(event, decision)
            }

            is JudgmentOutcome.Reject -> EventJudgeResult.Rejected(decision)
        }
    }

    /**
     * 接 05 补货口（[GrowCandidateSink]）：过 / 待确认 / 拒都算「处理了」（继续补别的条——
     * 拒的那条反正不占清单位，循环条件 `inListCount() < capacity` 兜着）；快照组不出才算没处理（停本轮）。
     */
    fun asCandidateSink(now: Long): GrowCandidateSink = GrowCandidateSink { candidate ->
        deliver(candidate, now) !is EventJudgeResult.NoSnapshot
    }

    private fun insert(
        candidate: EventCandidate,
        now: Long,
        status: EventStatus,
        confirmationPayload: String?,
    ): Event {
        val event = Event(
            id = 0L, // 库分配
            packId = candidate.packId,
            actorId = candidate.actorId,
            targetId = candidate.targetId,
            sceneId = candidate.sceneId,
            summary = candidate.summary,
            steps = candidate.steps,
            comparisonAxes = candidate.axes.encodeJson(),
            confirmationPayload = confirmationPayload,
            suitableTime = candidate.suitableTime,
            status = status,
            origin = EventOrigin.SEED,
            sourceSeedId = candidate.sourceSeedId,
            afterEventId = candidate.afterEventId,
            semiFixedTemplateCode = null,
            occurredAt = null,
            durationSec = candidate.durationSeconds(durations),
            expiresAt = now + EVENT_EXPIRY_MILLIS,
            createdAt = now,
            updatedAt = now,
        )
        return event.copy(id = repository.insert(event))
    }
}
