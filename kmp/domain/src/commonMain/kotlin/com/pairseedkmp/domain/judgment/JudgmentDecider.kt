package com.pairseedkmp.domain.judgment

/**
 * 一条候选的裁决决定（第 3 层输出）：出口 + 事实 +（材料齐时的）风险分 + 待确认 payload + 降级说明。
 * 判定物本身不落库（1.8）；[confirmationPayload] 是唯一进库的例外（待确认事件的
 * `events.confirmation_payload`），形状由裁决者定（系统 `systemConfirmationPayload` /
 * AI `aiConfirmationPayload`）。
 */
data class JudgeDecision(
    val outcome: JudgmentOutcome,
    val facts: JudgmentFacts,
    /** 硬规则直通 / 缺料时不计算 → null。 */
    val risk: RiskScore?,
    /** 待确认时的确认弹框内容（其它出口为 null）。 */
    val confirmationPayload: String? = null,
    /** 非空 = 走了降级（如 API 失败回落系统裁决）；只进端上日志，不落库。 */
    val degradedReason: String? = null,
)

/**
 * 第 3 层裁决端口（06 计划 §4）：常规路线（预设 / 端侧）= 系统三支（[systemDecider]）；
 * API 路线 = `AiJudge` 冷读（R 作输入、可推翻；失败降级回落系统三支）。
 * 两件事与路线无关、先于 AI：[0] 硬规则**码**命中直通拒；[0.5] 缺料强制待确认（调用方保证）。
 */
fun interface JudgmentDecider {
    suspend fun decide(snapshot: JudgmentSnapshot): JudgeDecision
}

/** 系统裁决 → 出口决定（待确认时附系统 payload）。 */
fun SystemJudgment.asDecision(degradedReason: String? = null): JudgeDecision = JudgeDecision(
    outcome = outcome,
    facts = facts,
    risk = risk,
    confirmationPayload = if (outcome is JudgmentOutcome.Uncertain) systemConfirmationPayload(this) else null,
    degradedReason = degradedReason,
)

/** 系统三支裁决者（预设 / 端侧路线；也是 API 路线降级时的回落）。 */
fun systemDecider(judge: SystemJudge = SystemJudge()): JudgmentDecider =
    JudgmentDecider { snapshot -> judge.judge(snapshot).asDecision() }
