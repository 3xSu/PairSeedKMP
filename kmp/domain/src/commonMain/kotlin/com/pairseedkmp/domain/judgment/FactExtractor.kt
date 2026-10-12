package com.pairseedkmp.domain.judgment

/** 风险三维（与《工作链路·判定链路 1.9》的覆盖范围一致）；[axisKey] = 对应软轴维度键。 */
enum class RiskDim(val axisKey: String) {
    PHYSICAL(AXIS_PHYSICAL_INTIMACY),
    VERBAL(AXIS_VERBAL_DIRECTNESS),
    EMOTION(AXIS_EMOTION_VISIBILITY),
}

/** 硬规则码命中（第 1 层事实；命中即拒、先于一切，两路线直通）。 */
data class HardRuleHit(
    val characterId: Long,
    val ruleKey: String,
    val scope: HardRuleScope,
    /** 命中的步骤码。 */
    val hitCodes: Set<String>,
)

/** 缺料项（第 0.5 层结构检查；fail-safe → 强制待确认，不跳过也不误判）。 */
sealed interface MissingMaterial {
    /** 步骤码不在码表（物理档推不出）。 */
    data class StepCodeNotInTable(val code: String) : MissingMaterial

    /**
     * 角色软档缺判定所需维：该维事件档 ≥ 2（容忍档不管取几都可能算出 δ > 0，结论会变）
     * 而这一位没填 → 容忍档算不出。事件档 ≤ 1 的维（δ 恒 0）不算所需。
     */
    data class SoftAxisMissing(val characterId: Long, val dim: RiskDim) : MissingMaterial

    /** 场景没标公开 / 私密，却有按场景生效的禁码规则命中码集 → 命中与否判不出。 */
    data object SceneVisibilityMissing : MissingMaterial

    /** 候选档位越界（防御层；05 复检过后不该出现）。 */
    data class AxisLevelOutOfRange(val dim: RiskDim, val level: Int) : MissingMaterial
}

/** 第 1 层事实提取结果（系统裁决与 AI 快照共用；判定物不落库）。 */
data class JudgmentFacts(
    val hardRuleHit: HardRuleHit?,
    /** 物理档 = 步骤各码 `physical_level` 取 max（空步骤兜 1）；有未知码时按已知码兜底（反正已缺料）。 */
    val physicalLevel: Int,
    /** 语言档（`verbal_directness`；null = 整件事没人说话 → 跳过该维）。 */
    val verbalLevel: Int?,
    val emotionLevel: Int,
    val sceneVisibility: SceneVisibility?,
    /** 按发现顺序；空 = 材料齐。 */
    val missing: List<MissingMaterial>,
) {
    /** 该维事件档（verbal 为 null 时不该被问）。 */
    fun levelOf(dim: RiskDim): Int? = when (dim) {
        RiskDim.PHYSICAL -> physicalLevel
        RiskDim.VERBAL -> verbalLevel
        RiskDim.EMOTION -> emotionLevel
    }
}

/**
 * 第 1 层事实提取（06 计划 §4）：硬规则码命中 / 物理档（码表 max）/ 语言·情感档（闭集指认）/
 * 场景旗标；缺料按第 0.5 层汇集。硬规则命中不受缺料影响（命中即拒，不看其余材料），
 * 所以两者都算、谁先谁后由裁决层定序。
 */
object FactExtractor {

    fun extract(snapshot: JudgmentSnapshot): JudgmentFacts {
        val stepCodes = snapshot.candidate.steps.map { it.action }.toSet()
        val missing = mutableListOf<MissingMaterial>()

        // —— 硬规则码命中：双方规则合并（命中条件 = forbidden_codes ∩ 步骤码 ≠ ∅ 且 scope 匹配场景）——
        val (hit, sceneVisibilityNeeded) = findHardRuleHit(snapshot, stepCodes)

        // —— 物理档：码表取 max；不在表里 → 缺料 ——
        val distinctCodes = snapshot.candidate.steps.map { it.action }.distinct()
        distinctCodes.filterNot { it in snapshot.behaviorLevels }
            .forEach { missing += MissingMaterial.StepCodeNotInTable(it) }
        val physicalLevel = distinctCodes.mapNotNull { snapshot.behaviorLevels[it] }.maxOrNull() ?: 1

        // —— 语言 / 情感档（闭集指认；越界 = 防御性缺料）——
        val axes = snapshot.candidate.axes
        val verbalLevel = axes.verbalDirectness
        if (verbalLevel != null && verbalLevel !in 1..4) {
            missing += MissingMaterial.AxisLevelOutOfRange(RiskDim.VERBAL, verbalLevel)
        }
        val emotionLevel = axes.emotionVisibility
        if (emotionLevel !in 1..4) {
            missing += MissingMaterial.AxisLevelOutOfRange(RiskDim.EMOTION, emotionLevel)
        }

        // —— 软档缺料：只查「事件档 ≥ 2 的维」（事件档 ≤ 1 → δ 恒 0，缺了也不影响结论）——
        val dimsInPlay = buildList {
            add(RiskDim.PHYSICAL to physicalLevel)
            if (verbalLevel != null) add(RiskDim.VERBAL to verbalLevel)
            add(RiskDim.EMOTION to emotionLevel)
        }
        for ((dim, eventLevel) in dimsInPlay) {
            if (eventLevel < 2) continue
            for (character in snapshot.participants) {
                if (dim.axisKey !in parseSoftAxisLevels(character.softAxesRaw)) {
                    missing += MissingMaterial.SoftAxisMissing(character.id, dim)
                }
            }
        }

        // —— 场景旗标：按场景生效的规则在局中（码集相交）而旗标缺 → 命中与否判不出 ——
        if (sceneVisibilityNeeded) missing += MissingMaterial.SceneVisibilityMissing

        return JudgmentFacts(
            hardRuleHit = hit,
            physicalLevel = physicalLevel,
            verbalLevel = verbalLevel,
            emotionLevel = emotionLevel,
            sceneVisibility = snapshot.sceneVisibility,
            missing = missing,
        )
    }

    /**
     * 找第一条命中的硬规则（actor 的规则在前）。
     * 按场景生效的规则旗标缺时不算命中、只记「需要旗标」——但恒生效的规则照常命中（命中即拒优先）。
     */
    private fun findHardRuleHit(
        snapshot: JudgmentSnapshot,
        stepCodes: Set<String>,
    ): Pair<HardRuleHit?, Boolean> {
        var sceneVisibilityNeeded = false
        for (character in snapshot.participants) {
            for (rule in character.hardRules) {
                val hitCodes = rule.forbiddenCodes intersect stepCodes
                if (hitCodes.isEmpty()) continue
                when (rule.scope) {
                    HardRuleScope.ALWAYS ->
                        return HardRuleHit(character.id, rule.ruleKey, rule.scope, hitCodes) to sceneVisibilityNeeded

                    HardRuleScope.PUBLIC, HardRuleScope.PRIVATE -> {
                        val visibility = snapshot.sceneVisibility
                        if (visibility == null) {
                            sceneVisibilityNeeded = true
                        } else if (rule.scope.matches(visibility)) {
                            return HardRuleHit(character.id, rule.ruleKey, rule.scope, hitCodes) to sceneVisibilityNeeded
                        }
                    }
                }
            }
        }
        return null to sceneVisibilityNeeded
    }
}
