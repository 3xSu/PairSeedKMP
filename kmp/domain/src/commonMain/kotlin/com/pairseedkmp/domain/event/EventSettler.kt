package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.repository.EventRepository
import com.pairseedkmp.domain.repository.RuntimeProfileRepository
import com.pairseedkmp.domain.runtime.RuntimeProfile

/**
 * 结算（"打开 app 结算"）：一切由 `now` + 库里状态推出，**没有定时任务**。
 *
 * 每次 [settle]：读在清单 / 待确认 / 进行中的事件 → 到点结束的推进"已发生"、到点的逻辑删除
 * （未完的放弃；已发生 / 已拒的也滚出历史页）、算出空闲起点与步骤位置
 * → 落库 + 写一次存活心跳（`runtime_profiles.last_seen_at`）。
 *
 * 空闲计时：无进行中 → 空闲起点 = max(上次事件结束, 本会话锚点)。
 * 会话锚点（打开 / 回前台重计时、离线空白不累积）：壳层在 app 打开 / 回前台时调 [onSessionStart]；
 * 进程内记忆、不落库——重启后再打开 = 新的会话，又按"打开这一刻"重计时。
 */
class EventSettler(
    private val eventRepository: EventRepository,
    private val profileRepository: RuntimeProfileRepository,
    private val durations: BehaviorDurationSource,
) {
    /** 本会话（打开 / 回前台）时刻；null = 还没开始过（首个 settle 自动记为 now）。 */
    private var sessionAnchorMillis: Long? = null

    /** 壳层在 app 打开 / 回前台时调一次：空闲重计时（离线空白不累积）。 */
    fun onSessionStart(now: Long) {
        sessionAnchorMillis = now
    }

    fun settle(now: Long): SettleResult {
        val anchor = sessionAnchorMillis ?: now.also { sessionAnchorMillis = it }

        // 心跳：写 last_seen_at 前先读旧值（步骤 4 判"窗口整段落在离线空白里"要用）
        val profile = profileRepository.current()
        val previousLastSeenAt = profile?.lastSeenAt
        if (profile == null) {
            profileRepository.insert(RuntimeProfile(id = 0, lastSeenAt = now, createdAt = now, updatedAt = now))
        } else {
            profileRepository.heartbeat(profile.id, now)
        }

        // —— 纯决策（不摸库、不摸时钟）——
        val plan = planSettlement(
            activeEvents = eventRepository.activeEvents(),
            lastOccurredEnd = eventRepository.lastOccurredEndMillis(),
            sessionAnchor = anchor,
            now = now,
            durations = durations,
        )

        // 已发生 / 已拒的到点件：也滚出历史页（逻辑删除，数据留）——先取，避免把本次刚完成的又取一遍
        val completedExpired = eventRepository.completedPastExpiry(now)

        // —— 落库：先推进"已发生"，再过期的逻辑删除（进行中的先完成、随后滚出）——
        plan.finished.forEach { eventRepository.updateStatus(it.id, EventStatus.OCCURRED, now) }
        plan.expired.forEach { eventRepository.markExpired(it.id, now) }
        completedExpired.forEach { eventRepository.markExpired(it.id, now) }

        return SettleResult(
            now = now,
            finished = plan.finished.map { it.copy(status = EventStatus.OCCURRED, updatedAt = now) },
            expired = (plan.expired + completedExpired).map { it.copy(isDeleted = true, updatedAt = now) },
            running = plan.running,
            idleSince = plan.idleSince,
            previousLastSeenAt = previousLastSeenAt,
        )
    }
}

/** 一次结算的结果（调试壳显示 + 步骤 4 触发链的输入）。 */
data class SettleResult(
    val now: Long,
    /** 本次推进为"已发生"的（含随后过期的）。 */
    val finished: List<Event>,
    /** 本次过期逻辑删除的：待确认 / 在清单 = 没轮上放弃；已发生 / 已拒 = 滚出历史页。 */
    val expired: List<Event>,
    /** 此刻还在跑的（规则上至多一条）。 */
    val running: List<RunningEvent>,
    /** 空闲起点（null = 有事件在跑）；空闲时长 = now - idleSince。 */
    val idleSince: Long?,
    /** 本次心跳写入前的 `last_seen_at`（步骤 4：判"窗口整段落在离线空白里"）。 */
    val previousLastSeenAt: Long?,
) {
    val idleMillis: Long?
        get() = idleSince?.let { now - it }
}

/** 进行中的事件快照：事件本体 + 播到第几步（播放直接用）。 */
data class RunningEvent(
    val event: Event,
    val position: StepPosition,
)

/** 结算的纯决策（不摸库、不摸时钟）：要落的库动作 + 此刻状态。 */
internal data class SettlementPlan(
    val finished: List<Event>,
    val expired: List<Event>,
    val running: List<RunningEvent>,
    val idleSince: Long?,
)

/**
 * 纯函数：由在清单 / 待确认 / 进行中的事件、上次事件结束时刻、会话锚点与 now 推出结算决策。
 * - 进行中且 `now ≥ 结束时刻` → 已发生；还在跑 → 收进 running（带步骤位置）
 * - `now ≥ expires_at` → 过期逻辑删除（进行中的先完成，同一条会同时进 finished 与 expired）
 * - 有在跑 → 不空闲（idleSince = null）；否则空闲起点 = max(上次事件结束, 会话锚点)
 */
internal fun planSettlement(
    activeEvents: List<Event>,
    lastOccurredEnd: Long?,
    sessionAnchor: Long,
    now: Long,
    durations: BehaviorDurationSource,
): SettlementPlan {
    val finished = mutableListOf<Event>()
    val expired = mutableListOf<Event>()
    val running = mutableListOf<RunningEvent>()

    activeEvents.forEach { event ->
        val end = event.endsAt
        if (event.status == EventStatus.IN_PROGRESS) {
            if (end != null && now >= end) {
                finished += event
                // 已发生也过期：7 天后从历史页滚出（逻辑删除，数据留）
                if (now >= event.expiresAt) expired += event
            } else {
                // 还在跑：过期先不处理（跑完再按上一条滚出），播放位置由 elapsed 推
                val elapsed = event.occurredAt?.let { now - it } ?: 0L
                running += RunningEvent(event, event.steps.stepPositionAt(elapsed, durations))
            }
        } else if (now >= event.expiresAt) {
            // 待确认 / 在清单：到点没轮上 → 放弃（逻辑删除，数据留）
            expired += event
        }
    }

    val lastEnd = listOfNotNull(lastOccurredEnd, finished.mapNotNull { it.endsAt }.maxOrNull()).maxOrNull()
    val idleSince = if (running.isNotEmpty()) null else maxOf(lastEnd ?: sessionAnchor, sessionAnchor)

    return SettlementPlan(finished, expired, running, idleSince)
}
