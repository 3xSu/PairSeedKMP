package com.pairseedkmp.domain.judgment

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmException

/** 解析失败两次后的固定话术（fail-safe 待确认的 payload 理由；1.6）。 */
const val JUDGE_PARSE_FAILED_REASON: String = "判定未能完成（模型输出无法解析），先交给你确认。"

/** 解析失败重试次数：一次（1.6「解析失败重试一次」——含首次共 2 次尝试）。 */
private const val JUDGE_PARSE_ATTEMPTS: Int = 2

/**
 * API 路线裁决者（06 计划 §4 第 3 层）：系统三支先跑取事实与 R，AI 冷读定夺。
 * - 硬规则**码**命中 → 直通拒、不经 AI（与预设 / 端侧路线一致，也是免费短路）；
 * - 缺料（0.5 层 fail-safe）→ 强制待确认、先于 AI（路线无关）；
 * - 其余（含系统按 R ≥ 门槛判拒的）→ 把 R 当输入送 AI 冷读：R 只是参考、可被推翻；
 * - "REJECT" 但指不出违反条（violated_rule 缺失 / 空白）→ 降级为「拿不准」（1.5：指不出只能落拿不准）；
 * - 解析失败重试一次 → 仍失败 fail-safe 待确认（1.6，固定话术）；
 * - [LlmException]（超时 / API 失败，瞬时重试已在 [LlmCaller] 内跑完）→ 降级系统裁决（沿用已算好的 R 与事实）。
 */
class AiJudge(
    private val caller: LlmCaller,
    private val systemJudge: SystemJudge = SystemJudge(),
) : JudgmentDecider {

    override suspend fun decide(snapshot: JudgmentSnapshot): JudgeDecision {
        val base = systemJudge.judge(snapshot)
        val baseOutcome = base.outcome
        if (baseOutcome is JudgmentOutcome.Reject && baseOutcome.reason is RejectReason.HardRule) return base.asDecision()
        if (baseOutcome is JudgmentOutcome.Uncertain && baseOutcome.reason is UncertainReason.Missing) return base.asDecision()
        val risk = base.risk ?: return base.asDecision() // 防御：到这一步材料必齐（不该发生）

        val request = buildJudgeRequest(snapshot, risk)
        repeat(JUDGE_PARSE_ATTEMPTS) {
            val text = try {
                caller.complete(request).text
            } catch (e: LlmException) {
                return base.asDecision(degradedReason = e.message ?: "API 调用失败")
            }
            val output = parseJudgeOutput(text) ?: return@repeat
            return output.toDecision(base.facts, risk)
        }
        return JudgeDecision(
            outcome = JudgmentOutcome.Uncertain(UncertainReason.Ai(JUDGE_PARSE_FAILED_REASON)),
            facts = base.facts,
            risk = risk,
            confirmationPayload = aiConfirmationPayload(JUDGE_PARSE_FAILED_REASON),
        )
    }

    /** 三档 → 出口决定；「不会」必须指得出违反条，指不出 → 拿不准。 */
    private fun JudgeOutput.toDecision(facts: JudgmentFacts, risk: RiskScore): JudgeDecision = when (verdict) {
        JudgeVerdictCode.PASS -> JudgeDecision(JudgmentOutcome.Pass, facts, risk)

        JudgeVerdictCode.UNCERTAIN -> JudgeDecision(
            outcome = JudgmentOutcome.Uncertain(UncertainReason.Ai(reason)),
            facts = facts,
            risk = risk,
            confirmationPayload = aiConfirmationPayload(reason),
        )

        JudgeVerdictCode.REJECT -> {
            val cited = violatedRule?.takeIf { it.isNotBlank() }
            if (cited == null) {
                JudgeDecision(
                    outcome = JudgmentOutcome.Uncertain(UncertainReason.Ai(reason)),
                    facts = facts,
                    risk = risk,
                    confirmationPayload = aiConfirmationPayload(reason),
                )
            } else {
                JudgeDecision(JudgmentOutcome.Reject(RejectReason.Ai(reason, cited)), facts, risk)
            }
        }
    }
}
