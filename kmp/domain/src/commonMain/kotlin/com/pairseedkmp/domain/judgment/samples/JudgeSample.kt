package com.pairseedkmp.domain.judgment.samples

import com.pairseedkmp.domain.judgment.JudgmentSnapshot

/** 样例期望的三档结论（与 `JudgmentOutcome` 同语义的轻量标注）。 */
enum class SampleVerdict { PASS, UNCERTAIN, REJECT }

/** 期望的理由通道（核对「为什么是这个结论」，不只核对结论）。 */
enum class SampleCause {
    /** 硬规则码命中直通（先于一切、不算风险分）。 */
    HARD_RULE,

    /** 缺料 fail-safe（第 0.5 层）。 */
    MISSING,

    /** 风险分落带（过 / 拿不准 / 拒）。 */
    RISK,
}

/** 样例分组（06 计划 §4：「应拒 / 应确认 / 应通过 + 擦边对抗组」）。 */
enum class SampleGroup(val label: String) {
    PASS("应通过"),
    UNCERTAIN("应确认"),
    REJECT("应拒"),
    EDGE("擦边对抗"),
}

/**
 * 判定样例一条（06 步骤 6）：固定候选 + 角色设定 = 自足快照（码表 / 场景旗标全内嵌，不依赖库），
 * 带期望结论标注。期望标注须与起步参数（权重 1.0 / 0.6 / 0.4、门槛 15 / 65）的裁决带一致
 * ——改一处权重 / 代价后期望须重审（06 计划 §4）。
 *
 * [note] = 这条为什么是这个期望（记录 / 面板显示）；[expectedCause] null = 只核结论不核通道。
 */
data class JudgeSample(
    val id: String,
    val group: SampleGroup,
    /** 一句话样本名（面板列表显示）。 */
    val title: String,
    val expected: SampleVerdict,
    val expectedCause: SampleCause? = null,
    val note: String,
    val snapshot: JudgmentSnapshot,
)
