package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.dedup.DedupParseException
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.seed.IdeaSeed
import com.pairseedkmp.domain.seed.SeedPicker
import kotlinx.coroutines.CancellationException

/** 每种子生成尝试上限：技术失败 + 内容重复合计计次（05 计划 §4 失败矩阵）。 */
const val DEFAULT_GROW_ATTEMPTS_PER_SEED: Int = 3

/** 一轮生长最多换几颗种子（共 ≤3 颗；用尽 → 本轮空手）。 */
const val DEFAULT_GROW_SEEDS_PER_ROUND: Int = 3

/** 一轮生成的结果。 */
sealed interface EventGrowResult {
    /** 成功产出候选（05 不落库；交 06 判定 → 落库）。 */
    data class Grown(val candidate: EventCandidate, val seedId: Long, val attempts: Int) : EventGrowResult

    /**
     * 种子用尽（每颗 ≤[DEFAULT_GROW_ATTEMPTS_PER_SEED] 次、共 ≤[DEFAULT_GROW_SEEDS_PER_ROUND] 颗）
     * 仍无果 → 本轮空手（日志由调用方记）。
     */
    data class Exhausted(val seedsTried: Int, val attempts: Int) : EventGrowResult

    /** 种子池空（含已试过的不再重挑后为空）。 */
    data object NoSeed : EventGrowResult

    /** 组装不出上下文（没运行配置 / 角色不足两人 / 没场景）→ 长不出一条。 */
    data object NoContext : EventGrowResult

    /** 环境持续失败（加载失败 / Key 无效额度尽）：放弃本轮；[error] 供日志 / 面板提示。 */
    data class EnvFailed(val error: LlmException) : EventGrowResult
}

/**
 * 事件生长（《工作链路·事件生长链路》，05 计划 §4）：挑种子 → 组装上下文 → 两段生成
 * （A1 生成段出概要 / 步骤 / 台词 / 接续指认 → 长文查重 → A2 打标段读整理稿出两维档位 + 适合时段）
 * → 复检 → 候选交 06。
 *
 * 失败矩阵：环境瞬时由 [LlmCaller] 内部重试（不占种子额度）；[LlmException] = 环境持续 → 放弃本轮；
 * 无效输出 / [DedupParseException] = 技术失败 → 同种子重试、无反馈；长文查重命中 = 内容重复 →
 * 把「像的那条」写进下一轮提示词；技术 + 重复合计 ≤[attemptsPerSeed] 次后换种子（不重挑同颗）；
 * OOC / 种子没体现不算失败（前者交 06，后者放行）。
 */
class EventGrower(
    private val picker: SeedPicker,
    private val contextSource: EventContextSource,
    private val caller: LlmCaller,
    private val deduplicator: Deduplicator,
    private val attemptsPerSeed: Int = DEFAULT_GROW_ATTEMPTS_PER_SEED,
    private val seedsPerRound: Int = DEFAULT_GROW_SEEDS_PER_ROUND,
) {
    /** 长一条（[now] 时刻挑种子；[utcOffsetSeconds] = 本地时区偏移，定当前时段用）。 */
    suspend fun grow(now: Long, utcOffsetSeconds: Int): EventGrowResult {
        val context = contextSource.snapshot(now, utcOffsetSeconds) ?: return EventGrowResult.NoContext
        val allowedCodes = context.allowedBehaviors.map { it.code }.toSet()
        val tried = mutableSetOf<Long>()
        var attempts = 0

        repeat(seedsPerRound) {
            val seed = picker.pick(now, exclude = tried)
                ?: return if (tried.isEmpty()) {
                    EventGrowResult.NoSeed
                } else {
                    EventGrowResult.Exhausted(seedsTried = tried.size, attempts = attempts)
                }
            tried += seed.id
            val duplicates = mutableListOf<GrowDuplicateFeedback>()
            repeat(attemptsPerSeed) {
                attempts++
                val attempt = try {
                    runAttempt(context, allowedCodes, seed, duplicates)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LlmException) {
                    // 环境持续（瞬时重试已在 LlmCaller 里用完）→ 放弃本轮
                    return EventGrowResult.EnvFailed(e)
                }
                when (attempt) {
                    is Attempt.Grown -> return EventGrowResult.Grown(attempt.candidate, seed.id, attempts)
                    is Attempt.Duplicate -> duplicates += attempt.feedback
                    Attempt.Technical -> Unit // 技术失败：无反馈重试（计次）
                }
            }
        }
        return EventGrowResult.Exhausted(seedsTried = tried.size, attempts = attempts)
    }

    /** 单次尝试：A1 生成 → 查重 → A2 打标 → 复检出候选。 */
    private suspend fun runAttempt(
        context: EventGrowContext,
        allowedCodes: Set<String>,
        seed: IdeaSeed,
        duplicates: List<GrowDuplicateFeedback>,
    ): Attempt {
        // A1 生成段：概要 + 步骤（含台词）+ 接续指认
        val growText = caller.complete(buildGrowRequest(context, seed, duplicates)).text
        val grow = parseGrowOutput(growText, allowedCodes, context.checklist.size) ?: return Attempt.Technical

        // 长文查重（目标 = 近期 + 清单；命中的那条进下一轮提示词）
        val dedupTargets = context.config.recentSummaries + context.checklist.map { it.summary }
        val hit = try {
            deduplicator.longText(grow.summary, dedupTargets)
        } catch (e: DedupParseException) {
            return Attempt.Technical
        }
        if (hit.isDuplicate) {
            return Attempt.Duplicate(GrowDuplicateFeedback(hit.matchedText!!, hit.type!!))
        }

        // A2 打标段：读整理稿出两维档位 + 适合时段（「说」步可空性固化进 schema）
        val steps = grow.steps.withSayDurations()
        val hasSpeech = digestSayLines(steps).isNotEmpty()
        val digest = renderEventDigest(grow.summary, steps)
        val tag = parseTagOutput(caller.complete(buildTagRequest(digest, hasSpeech)).text, hasSpeech)
            ?: return Attempt.Technical

        // 复检通过 → 候选（afterNumber 按本次清单快照解析成事件 id）
        return Attempt.Grown(
            EventCandidate(
                packId = context.config.packId,
                actorId = context.config.actor.id,
                targetId = context.config.target.id,
                sceneId = context.config.scene.id,
                summary = grow.summary,
                steps = steps,
                axes = ComparisonAxes(tag.verbalDirectness, tag.emotionVisibility),
                suitableTime = tag.suitableTime,
                afterEventId = grow.afterNumber?.let { context.checklist[it - 1].eventId },
                sourceSeedId = seed.id,
            ),
        )
    }
}

/** 单次尝试的中间态。 */
private sealed interface Attempt {
    /** 复检通过：出候选。 */
    data class Grown(val candidate: EventCandidate) : Attempt

    /** 内容重复：带「像的那条」进下一轮提示词。 */
    data class Duplicate(val feedback: GrowDuplicateFeedback) : Attempt

    /** 技术失败（无效输出 / 查重解析失败）：同种子重试，无反馈。 */
    data object Technical : Attempt
}
