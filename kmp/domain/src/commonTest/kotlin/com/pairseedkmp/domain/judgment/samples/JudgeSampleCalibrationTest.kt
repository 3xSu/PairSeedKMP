package com.pairseedkmp.domain.judgment.samples

import com.pairseedkmp.domain.judgment.JudgmentOutcome
import com.pairseedkmp.domain.judgment.RejectReason
import com.pairseedkmp.domain.judgment.SystemJudge
import com.pairseedkmp.domain.judgment.SystemJudgment
import com.pairseedkmp.domain.judgment.UncertainReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 06 步骤 6 校准：`JudgeSampleSet.ALL` 全量过 `SystemJudge`（起步参数：门槛 15 / 65），
 * 核对每条的实际结论 + 理由通道与期望标注一致，并输出 R 分布（标记模式校准记录）。
 * 日志：`rm -rf kmp/domain/build/test-results && ./gradlew :kmp:domain:allTests` 后从测试 XML 提取。
 */
class JudgeSampleCalibrationTest {

    // —— 结构 ——

    @Test
    fun `样例集结构完整——编号唯一、四组齐全、三档组与期望相容`() {
        val all = JudgeSampleSet.ALL
        assertEquals(18, all.size, "样例条数变了——期望标注 / 覆盖表须同步重审")

        val ids = all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "编号有重复：${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")

        SampleGroup.entries.forEach { group ->
            assertTrue(all.any { it.group == group }, "分组 ${group.name}（${group.label}）没有任何样例")
        }

        // 三档组的期望结论必须与组名同义；EDGE 组允许任意期望（两侧成对）
        all.filter { it.group != SampleGroup.EDGE }.forEach { sample ->
            assertEquals(
                SampleVerdict.valueOf(sample.group.name), sample.expected,
                "样例 ${sample.id} 在「${sample.group.label}」组里，期望却是 ${sample.expected}",
            )
        }
    }

    // —— 校准 ——

    @Test
    fun `全量校准——实际结论与理由通道逐条对齐期望，输出 R 分布`() {
        val judge = SystemJudge()
        val rows = JudgeSampleSet.ALL.map { sample -> run(sample, judge.judge(sample.snapshot)) }

        println("")
        println("== 06 步骤 6 · 判定样例集校准（系统三支，门槛 15 / 65）==")
        println("| # | 编号 | 分组 | 标题 | 期望 | 实际 | R | 通道 | 一致 |")
        println("|---|---|---|---|---|---|---|---|---|")
        rows.forEachIndexed { index, row ->
            val mark = if (row.verdictOk && row.causeOk) "OK" else "MISMATCH"
            println(
                "| ${index + 1} | ${row.sample.id} | ${row.sample.group.label} | ${row.sample.title} " +
                    "| ${row.sample.expected} | ${row.actualVerdict} | ${row.riskValue?.toString() ?: "—"} " +
                    "| ${row.actualCause ?: "—"} | $mark |",
            )
        }

        val rDistribution = rows.mapNotNull { it.riskValue }.distinct().sorted()
        println("R 分布：$rDistribution（共 ${rows.count { it.riskValue != null }} 条算分 / " +
            "${rows.size} 条；直通拒与缺料不算分）")
        println("三档命中：过 ${rows.count { it.sample.expected == SampleVerdict.PASS }} / " +
            "拿不准 ${rows.count { it.sample.expected == SampleVerdict.UNCERTAIN }} / " +
            "拒 ${rows.count { it.sample.expected == SampleVerdict.REJECT }}（期望）")

        val mismatches = rows.filter { !it.verdictOk || !it.causeOk }
        assertTrue(
            mismatches.isEmpty(),
            "不一致样例：" + mismatches.joinToString("；") {
                "${it.sample.id}（期望 ${it.sample.expected}/${it.sample.expectedCause}，" +
                    "实际 ${it.actualVerdict}/${it.actualCause}）"
            },
        )

        // 验收口径：严重越界（物理 +2 档及以上 / 硬规则码命中）100% 拦截
        val severeIds = setOf("reject-01", "reject-02", "reject-03", "edge-02")
        val severeLeaks = rows.filter { it.sample.id in severeIds && it.actualVerdict != SampleVerdict.REJECT }
        assertTrue(severeLeaks.isEmpty(), "严重越界漏放：${severeLeaks.map { it.sample.id }}")

        // 验收口径：擦边组不误拒（成对样例里贴着边的都留在过 / 拿不准侧）
        val edgeMisrejects = rows.filter { it.sample.group == SampleGroup.EDGE && it.actualVerdict == SampleVerdict.REJECT }
            .filter { it.sample.expected != SampleVerdict.REJECT }
        assertTrue(edgeMisrejects.isEmpty(), "擦边组误拒：${edgeMisrejects.map { it.sample.id }}")
    }

    // —— 行 / 映射 ——

    private data class Row(
        val sample: JudgeSample,
        val actualVerdict: SampleVerdict,
        val actualCause: SampleCause?,
        val riskValue: Int?,
        val verdictOk: Boolean,
        val causeOk: Boolean,
    )

    private fun run(sample: JudgeSample, judgment: SystemJudgment): Row {
        val actualVerdict = verdictOf(judgment.outcome)
        val actualCause = causeOf(judgment.outcome)
        return Row(
            sample = sample,
            actualVerdict = actualVerdict,
            actualCause = actualCause,
            riskValue = judgment.risk?.value,
            verdictOk = actualVerdict == sample.expected,
            causeOk = sample.expectedCause == null || actualCause == sample.expectedCause,
        )
    }

    private fun verdictOf(outcome: JudgmentOutcome): SampleVerdict = when (outcome) {
        JudgmentOutcome.Pass -> SampleVerdict.PASS
        is JudgmentOutcome.Uncertain -> SampleVerdict.UNCERTAIN
        is JudgmentOutcome.Reject -> SampleVerdict.REJECT
    }

    /** 通道映射：过 = 风险带；AI 类理由（路由侧才会有）映射 null = 不参与通道核对。 */
    private fun causeOf(outcome: JudgmentOutcome): SampleCause? = when (outcome) {
        JudgmentOutcome.Pass -> SampleCause.RISK
        is JudgmentOutcome.Uncertain -> when (outcome.reason) {
            is UncertainReason.Missing -> SampleCause.MISSING
            is UncertainReason.Risk -> SampleCause.RISK
            is UncertainReason.Ai -> null
        }
        is JudgmentOutcome.Reject -> when (outcome.reason) {
            is RejectReason.HardRule -> SampleCause.HARD_RULE
            is RejectReason.Risk -> SampleCause.RISK
            is RejectReason.Ai -> null
        }
    }
}
