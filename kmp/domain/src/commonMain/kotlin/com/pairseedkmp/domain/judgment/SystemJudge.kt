package com.pairseedkmp.domain.judgment

/** 放行 / 拦截门槛起步值（2026-10-07 拍：代价 a : b : c = 12 : 6 : 2 → 15 / 65；两处取整向拦截侧靠）。 */
const val RISK_PASS_THRESHOLD: Int = 15
const val RISK_REJECT_THRESHOLD: Int = 65

/** 判定出口三选一（判定物不落库；落库由出口层按 status 做）。 */
sealed interface JudgmentOutcome {
    /** 进清单（IN_LIST）。 */
    data object Pass : JudgmentOutcome

    /** 拒绝、不留痕。 */
    data class Reject(val reason: RejectReason) : JudgmentOutcome

    /** 待确认（PENDING_CONFIRMATION；「拿不准」是一等公民）。 */
    data class Uncertain(val reason: UncertainReason) : JudgmentOutcome
}

/** 拒绝的理由（进日志 / payload）。 */
sealed interface RejectReason {
    /** 硬规则码命中（直通拒、先于一切）。 */
    data class HardRule(val hit: HardRuleHit) : RejectReason

    /** 风险分 ≥ 拦截门槛。 */
    data class Risk(val risk: RiskScore) : RejectReason

    /** API 路线冷读拒（[violatedRule] 必非空白——指不出违反哪条只能落「拿不准」，06 §5 步骤 4）。 */
    data class Ai(val reason: String, val violatedRule: String) : RejectReason
}

/** 待确认的理由。 */
sealed interface UncertainReason {
    /** 材料缺（第 0.5 层 fail-safe）。 */
    data class Missing(val items: List<MissingMaterial>) : UncertainReason

    /** 风险分落在两门槛之间。 */
    data class Risk(val risk: RiskScore) : UncertainReason

    /** API 路线拿不准（AI 的疑问原话，进确认弹框）。 */
    data class Ai(val reason: String) : UncertainReason
}

/** 系统裁决结果：出口 + 事实 +（材料齐时的）风险分。 */
data class SystemJudgment(
    val outcome: JudgmentOutcome,
    val facts: JudgmentFacts,
    /** 硬规则直通 / 缺料时不计算 → null。 */
    val risk: RiskScore?,
)

/**
 * 第 3 层系统三支裁决（预设 / 端侧路线，06 计划 §4）：
 * 硬规则码命中 → 拒（**先于缺料**，不看其余材料）；缺料 → 强制待确认；
 * R ≥ [rejectThreshold] → 拒；R ≤ [passThreshold] → 过；之间 → 待确认。
 * API 路线不走这里（AiJudge 冷读；硬规则码命中同样直通拒，降级时回落本裁决）。
 */
class SystemJudge(
    private val scorer: RiskScorer = RiskScorer(),
    private val passThreshold: Int = RISK_PASS_THRESHOLD,
    private val rejectThreshold: Int = RISK_REJECT_THRESHOLD,
) {

    fun judge(snapshot: JudgmentSnapshot): SystemJudgment {
        val facts = FactExtractor.extract(snapshot)
        facts.hardRuleHit?.let { hit ->
            return SystemJudgment(JudgmentOutcome.Reject(RejectReason.HardRule(hit)), facts, risk = null)
        }
        if (facts.missing.isNotEmpty()) {
            return SystemJudgment(JudgmentOutcome.Uncertain(UncertainReason.Missing(facts.missing)), facts, risk = null)
        }
        val risk = scorer.score(snapshot, facts)
        val outcome = when {
            risk.value >= rejectThreshold -> JudgmentOutcome.Reject(RejectReason.Risk(risk))
            risk.value <= passThreshold -> JudgmentOutcome.Pass
            else -> JudgmentOutcome.Uncertain(UncertainReason.Risk(risk))
        }
        return SystemJudgment(outcome, facts, risk)
    }
}
