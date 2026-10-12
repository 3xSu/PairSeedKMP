package com.pairseedkmp.domain.judgment

/** 起步权重（2026-10-07 拍：物理 1.0 / 语言 0.6 / 情感 0.4）；以 tenths 整型参与计算，避免浮点误差。 */
data class RiskWeights(
    val physicalTenths: Int = 10,
    val verbalTenths: Int = 6,
    val emotionTenths: Int = 4,
) {
    fun tenthsOf(dim: RiskDim): Int = when (dim) {
        RiskDim.PHYSICAL -> physicalTenths
        RiskDim.VERBAL -> verbalTenths
        RiskDim.EMOTION -> emotionTenths
    }
}

/** 单维偏离事实（进日志 / 面板 / confirmation_payload；不落库）。 */
data class DimDeviation(
    val dim: RiskDim,
    /** 事件档（物理 = 码表 max；语言 / 情感 = 生成时指认）。 */
    val eventLevel: Int,
    /** 容忍档 = 5 − max(双方软档)（单人 = 5 − 本人档）。 */
    val tolerance: Int,
    val delta: Int,
    /** 该维得分 = round(100 × w × δ / 3)。 */
    val score: Int,
)

/** 风险分 R（0–100 整数）：取最大维得分；[deviations] 留全部三维事实。 */
data class RiskScore(val value: Int, val deviations: List<DimDeviation>)

/**
 * 第 2 层偏离计算（06 计划 §4；公式《工作链路·判定链路 1.9》）：
 * 容忍档 **t = 5 − max(双方软档)**（最保守者定容忍；单人 = 5 − 本人档）——角色轴 1 = 最主动、
 * 事件档 4 = 最强，方向相反，先翻到事件量表再取更保守的那位（2026-10-11 校正）；
 * δ = max(0, e − t)；R = round(100 × max(w·δ) / 3)（取最大维，单维严重不稀释）。
 *
 * 前置：`facts.missing` 为空（调用方保证）；缺档兜底按 t = 4（δ 恒 0，宁松不误杀——不该发生）。
 */
class RiskScorer(private val weights: RiskWeights = RiskWeights()) {

    fun score(snapshot: JudgmentSnapshot, facts: JudgmentFacts): RiskScore {
        val dims = buildList {
            add(RiskDim.PHYSICAL)
            if (facts.verbalLevel != null) add(RiskDim.VERBAL)
            add(RiskDim.EMOTION)
        }
        val deviations = dims.map { dim ->
            val eventLevel = facts.levelOf(dim) ?: 0
            val tolerance = tolerance(snapshot, dim)
            val delta = (eventLevel - tolerance).coerceAtLeast(0)
            DimDeviation(dim, eventLevel, tolerance, delta, scoreOf(dim, delta))
        }
        return RiskScore(value = deviations.maxOf { it.score }, deviations = deviations)
    }

    /** t = 5 − max(双方软档)；单人 = 5 − 本人档。缺档兜底 1（→ t = 4）。 */
    private fun tolerance(snapshot: JudgmentSnapshot, dim: RiskDim): Int {
        val level = snapshot.participants
            .mapNotNull { parseSoftAxisLevels(it.softAxesRaw)[dim.axisKey] }
            .maxOrNull() ?: 1
        return 5 - level
    }

    /** 该维得分 = round(100 × w × δ / 3)（w 以 tenths 整型算：round(10 × tenths × δ / 3)）。 */
    private fun scoreOf(dim: RiskDim, delta: Int): Int =
        roundDiv(10 * weights.tenthsOf(dim) * delta, 3)
}

/** 正整数四舍五入除法 round(a / b)（半进位；b > 0；a ≥ 0）。 */
private fun roundDiv(a: Int, b: Int): Int = (2 * a + b) / (2 * b)
