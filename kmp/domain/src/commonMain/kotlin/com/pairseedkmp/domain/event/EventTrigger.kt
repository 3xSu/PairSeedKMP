package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.common.minuteOfDay
import com.pairseedkmp.domain.repository.EventRepository
import com.pairseedkmp.domain.repository.RuntimeProfileRepository
import com.pairseedkmp.domain.repository.SemiFixedTemplateRepository
import kotlin.random.Random

/** 空闲阈值默认值：10 分钟（2026-10-04 拍；调试壳可临时改——构造时传入，持久化配置后置）。 */
const val DEFAULT_IDLE_TRIGGER_THRESHOLD_MILLIS: Long = 10 * MILLIS_PER_MINUTE

/**
 * 检查点"连续在场"的宽容值：两个检查点间隔超过它，中间才算"离线过"（翻篇判定用）。
 * 前台每秒结算，正常间隔是秒级；一分钟足够宽容抖动，又远小于任何真正的离线空白。
 */
private const val CONTINUOUS_CHECKPOINT_GAP_MILLIS: Long = MILLIS_PER_MINUTE

/** 半固定发生要落的 actor / scene：模板表不带这些，由壳层给（角色组主角色 + 当前场景）。 */
data class SemiFixedFireContext(val actorId: Long, val targetId: Long? = null, val sceneId: Long)

/**
 * 触发链（《工作链路·事件触发链路》；纯程序、不用 AI）。
 * 在结算检查点调 [check]，按序问：
 * ① 过最晚的 due（窗口已关、这轮没发生过）→ 立即发生（多个 due 按最晚点早者先）；
 *   例外：整段落在离线空白里的窗口翻篇、不补
 * ② 接续——刚结束的事件带指认、被指认件仍在清单 → 立即接上（让路①后仍接；不查 suitable_time）
 * ③ 窗口开着且这轮没发生过 → 发生它
 * ④ 空闲 ≥ [idleThresholdMillis] → 清单挑一件（`suitable_time` 含当前时段；空 → 继续空闲）
 * 有事件在跑 → 不打断（"当前这件事完成后立马触发"落在下一个检查点，无需定时器）。
 */
class EventTrigger(
    private val eventRepository: EventRepository,
    private val templateRepository: SemiFixedTemplateRepository,
    private val profileRepository: RuntimeProfileRepository,
    private val durations: BehaviorDurationSource,
    private val idleThresholdMillis: Long = DEFAULT_IDLE_TRIGGER_THRESHOLD_MILLIS,
    private val random: Random = Random.Default,
) {
    /**
     * 本会话内已裁决过的轮（键 = "模板@轮起点"）：发生掉的、以及翻篇的。
     * 翻篇判定靠"上次在场早于窗口起点"，而结算会立刻刷新 `last_seen_at`——
     * 没有这份粘性记忆，翻篇的轮下一秒就会"翻回来"被补发。
     */
    private val resolvedRounds = mutableSetOf<String>()

    /** 接续欠账：被指认件还欠着一次「紧接着」的接续（② 消费）；让路①时留着不丢（「让路后仍接」）。 */
    private var pendingContinuationId: Long? = null

    fun check(now: Long, settle: SettleResult, utcOffsetSeconds: Int, context: SemiFixedFireContext): TriggerResult {
        if (settle.running.isNotEmpty()) {
            return TriggerResult(fired = null, decision = TriggerDecision.BLOCKED_BY_RUNNING)
        }

        // 先记欠账再走 ①——① 抢发时欠账留着，等它跑完的下一个检查点接（「让路后仍接」）
        noteContinuation(settle, now)

        val semiFixed = planSemiFixed(
            statuses = loadRoundStatuses(now, utcOffsetSeconds),
            resolvedRounds = resolvedRounds,
            previousSeen = settle.previousLastSeenAt,
            now = now,
        )
        semiFixed.swallowed.forEach { resolvedRounds += it.key }
        val swallowedCodes = semiFixed.swallowed.map { it.template.templateCode }

        // ① 过最晚的 due：立即发生
        semiFixed.overdue?.let { status ->
            resolvedRounds += status.key
            return TriggerResult(
                fired = fireSemiFixed(status.template, now, context),
                decision = TriggerDecision.FIRED_SEMI_FIXED_OVERDUE,
                swallowedOffline = swallowedCodes,
            )
        }

        // ② 接续：① 之后、③ 之前（压过窗内半固定；不查 suitable_time——「紧接着」优先）
        fireContinuation(now)?.let { event ->
            return TriggerResult(event, TriggerDecision.FIRED_CONTINUATION, swallowedCodes)
        }

        // ③ 窗口开着且这轮没发生过：发生它
        semiFixed.window?.let { status ->
            resolvedRounds += status.key
            return TriggerResult(
                fired = fireSemiFixed(status.template, now, context),
                decision = TriggerDecision.FIRED_SEMI_FIXED_IN_WINDOW,
                swallowedOffline = swallowedCodes,
            )
        }

        // ④ 清单补位：空闲达标 → 挑一件"现在能做的"（均匀随机）
        val idleMillis = settle.idleMillis ?: 0L
        if (idleMillis < idleThresholdMillis) {
            return TriggerResult(null, TriggerDecision.IDLE_BELOW_THRESHOLD, swallowedCodes)
        }
        val picked = pickFromList(now, utcOffsetSeconds)
            ?: return TriggerResult(null, TriggerDecision.IDLE_NO_ELIGIBLE_EVENT, swallowedCodes)
        eventRepository.markInProgress(picked.id, occurredAt = now, updatedAt = now)
        return TriggerResult(
            fired = picked.copy(status = EventStatus.IN_PROGRESS, occurredAt = now, updatedAt = now),
            decision = TriggerDecision.FIRED_FROM_LIST,
            swallowedOffline = swallowedCodes,
        )
    }

    /**
     * 观察结算、记欠账：最近结束的一条带接续指认、且不是在离线空白里结束的
     * → 记下被指认件（② 的下一次机会消费）。最新一条没带指认 → 不动已有欠账。
     */
    private fun noteContinuation(settle: SettleResult, now: Long) {
        if (pendingContinuationId != null) return
        val finished = settle.finished.maxByOrNull { it.endsAt ?: it.updatedAt } ?: return
        val afterId = finished.afterEventId ?: return
        val endsAt = finished.endsAt ?: return
        // 链断 = 离线跨过这条结束（沿用「连续在场」1 分钟判据）：不立即接，那件照常留清单等④
        val previousSeen = settle.previousLastSeenAt
        val offlineThroughEnd = previousSeen == null ||
            (now - previousSeen > CONTINUOUS_CHECKPOINT_GAP_MILLIS && previousSeen < endsAt)
        if (offlineThroughEnd) return
        pendingContinuationId = afterId
    }

    /**
     * ② 兑现欠账：被指认件仍 IN_LIST → 立即开工（不查 suitable_time——「紧接着」优先）；
     * 已被做掉 / 不在清单（含过期滚出）→ 清欠、回空闲。
     */
    private fun fireContinuation(now: Long): Event? {
        val targetId = pendingContinuationId ?: return null
        pendingContinuationId = null
        val target = eventRepository.activeEvents()
            .firstOrNull { it.id == targetId && it.status == EventStatus.IN_LIST }
            ?: return null
        eventRepository.markInProgress(target.id, occurredAt = now, updatedAt = now)
        return target.copy(status = EventStatus.IN_PROGRESS, occurredAt = now, updatedAt = now)
    }

    private fun loadRoundStatuses(now: Long, utcOffsetSeconds: Int): List<SemiFixedRoundStatus> {
        val packId = profileRepository.current()?.activePackId ?: return emptyList()
        return templateRepository.byPack(packId).map { template ->
            val round = template.roundAt(now, utcOffsetSeconds)
            SemiFixedRoundStatus(
                template = template,
                round = round,
                windowOpen = round.contains(now),
                // due 区间取 [轮起点, 下一轮起点)：补触发发生在窗口终点之后，记录也要算"这轮发生过"，
                // 否则同一轮会被反复补（窗口段 [start, end) 内判就漏掉这些记录）
                due = !eventRepository.scheduledOccurredBetween(
                    template.templateCode, round.startMillis, round.nextStartMillis,
                ),
            )
        }
    }

    private fun fireSemiFixed(template: SemiFixedEventTemplate, now: Long, context: SemiFixedFireContext): Event {
        val fired = Event(
            id = 0L,
            packId = template.packId,
            actorId = context.actorId,
            targetId = context.targetId,
            sceneId = context.sceneId,
            summary = template.summary,
            steps = template.steps,
            status = EventStatus.IN_PROGRESS, // 半固定不过判定 / 确认，直接发生
            origin = EventOrigin.SCHEDULED,
            semiFixedTemplateCode = template.templateCode,
            occurredAt = now,
            durationSec = template.steps.totalDurationSeconds(durations),
            expiresAt = now + EVENT_EXPIRY_MILLIS, // 这类记录的"生成时刻" = 发生时刻
            createdAt = now,
            updatedAt = now,
        )
        return fired.copy(id = eventRepository.insert(fired))
    }

    private fun pickFromList(now: Long, utcOffsetSeconds: Int): Event? {
        val period = Period.forMinuteOfDay(minuteOfDay(now, utcOffsetSeconds))
        val eligible = eventRepository.activeEvents()
            .filter { it.status == EventStatus.IN_LIST }
            .filter { it.suitableTime.isEmpty() || period in it.suitableTime } // 空 = 不限
        if (eligible.isEmpty()) return null
        return eligible[random.nextInt(eligible.size)] // 均匀随机（2026-10-04 拍）
    }
}

/** 一次触发检查的结果（调试壳"判断与理由"用）。 */
data class TriggerResult(
    /** 本次发生的事件（没有 → null）。 */
    val fired: Event?,
    val decision: TriggerDecision,
    /** 被判"整段离线翻篇"（不补）的半固定模板 code。 */
    val swallowedOffline: List<String> = emptyList(),
)

/** 触发链落到哪一步（调试壳显示理由用）。 */
enum class TriggerDecision {
    /** 有事件在跑：不打断（下一个检查点再问）。 */
    BLOCKED_BY_RUNNING,

    /** ① 过最晚的 due：立即发生（补触发 / 当前事件结束后的第一个检查点）。 */
    FIRED_SEMI_FIXED_OVERDUE,

    /** ② 接续：刚结束的指认兑现——被指认件立即开工。 */
    FIRED_CONTINUATION,

    /** ③ 窗口开着且 due：发生。 */
    FIRED_SEMI_FIXED_IN_WINDOW,

    /** ④ 空闲达标：清单挑了一件。 */
    FIRED_FROM_LIST,

    /** ④ 空闲没到阈值：继续空闲。 */
    IDLE_BELOW_THRESHOLD,

    /** ④ 空闲达标但没有含当前时段的清单件：继续空闲。 */
    IDLE_NO_ELIGIBLE_EVENT,
}

/** 模板 + 它最近一轮的窗口与"这轮发生过没"。 */
internal data class SemiFixedRoundStatus(
    val template: SemiFixedEventTemplate,
    val round: SemiFixedRound,
    /** now 在窗口内（左闭右开）。 */
    val windowOpen: Boolean,
    /** [轮起点, 下一轮起点) 内没有该模板的发生记录。 */
    val due: Boolean,
) {
    /** 已裁决标记的键：同一模板、每轮一个。 */
    val key: String get() = "${template.templateCode}@${round.startMillis}"
}

internal data class SemiFixedPlan(
    /** 翻篇（整段离线、不补）的轮。 */
    val swallowed: List<SemiFixedRoundStatus>,
    /** ① 补触发（窗口已关且 due）：最晚点早者先。 */
    val overdue: SemiFixedRoundStatus?,
    /** ③ 窗内（窗口开着且 due）：最晚点早者先。 */
    val window: SemiFixedRoundStatus?,
)

/**
 * 触发链的 ①③（② 接续与 ④ 在 [EventTrigger.check] 里——② 插在①③之间）——纯决策：
 * ① 窗口已关且 due：按最晚点早者先补发生；整段落在离线空白里的翻篇、不补
 * ③ 窗口开着且 due：按最晚点早者先发生
 */
internal fun planSemiFixed(
    statuses: List<SemiFixedRoundStatus>,
    resolvedRounds: Set<String>,
    previousSeen: Long?,
    now: Long,
): SemiFixedPlan {
    val pending = statuses.filter { it.due && it.key !in resolvedRounds }
    val swallowed = mutableListOf<SemiFixedRoundStatus>()
    val overdue = mutableListOf<SemiFixedRoundStatus>()
    pending.filter { !it.windowOpen }.forEach { status ->
        // 翻篇：整段落在离线空白里 = 检查点之间隔了很久（不是连续在场）且上次在场早于窗口起点；
        // 从没在场过（首启）同理。在线被事件挡住跨过最晚的（连续在场）→ 不翻篇，事件结束后立即补
        val offlineThroughWindow = previousSeen == null ||
            (now - previousSeen > CONTINUOUS_CHECKPOINT_GAP_MILLIS && previousSeen < status.round.startMillis)
        if (offlineThroughWindow) swallowed += status else overdue += status
    }
    // ① 过最晚：最晚点早者先
    overdue.sortedBy { it.round.endMillis }.firstOrNull()?.let {
        return SemiFixedPlan(swallowed, overdue = it, window = null)
    }
    // ③ 窗内：最晚点早者先（② 接续由调用方插在两者之间）
    val windowPick = pending.filter { it.windowOpen }.sortedBy { it.round.endMillis }.firstOrNull()
    return SemiFixedPlan(swallowed, overdue = null, window = windowPick)
}
