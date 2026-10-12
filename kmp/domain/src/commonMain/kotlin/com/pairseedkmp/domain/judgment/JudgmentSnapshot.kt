package com.pairseedkmp.domain.judgment

import com.pairseedkmp.domain.event.EventCandidate
import com.pairseedkmp.domain.event.GrowCharacter
import com.pairseedkmp.domain.event.GrowRelation
import com.pairseedkmp.domain.event.GrowScene
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 场景可见性旗标（scenes.location_type，06 场景旗标事实）。 */
enum class SceneVisibility(val code: String) {
    PUBLIC("PUBLIC"),
    PRIVATE("PRIVATE");

    companion object {
        /** 未知 / 空 → null（无旗标 = 场景没标公开 / 私密，判定按缺料规则处理）。 */
        fun fromCode(code: String?): SceneVisibility? = entries.firstOrNull { it.code == code }
    }
}

/** 硬规则作用域（character_hard_rules.scope）：always 恒生效；public / private 限场景。 */
enum class HardRuleScope(val code: String) {
    ALWAYS("always"),
    PUBLIC("public"),
    PRIVATE("private");

    companion object {
        /** 未知 code → 按 [ALWAYS] 从严（判不准的规则宁多拦——码集命中就先拒）。 */
        fun fromCode(code: String?): HardRuleScope = entries.firstOrNull { it.code == code } ?: ALWAYS
    }

    /** 场景是否落在该作用域内（always 恒 true）。 */
    fun matches(visibility: SceneVisibility): Boolean = when (this) {
        ALWAYS -> true
        PUBLIC -> visibility == SceneVisibility.PUBLIC
        PRIVATE -> visibility == SceneVisibility.PRIVATE
    }
}

/** 判定用硬规则一行（character_hard_rules；forbidden_codes 已在读侧解析成表）。 */
data class JudgeHardRule(
    val characterId: Long,
    val ruleKey: String,
    val scope: HardRuleScope,
    val forbiddenCodes: Set<String>,
    /** 规则原文（character_hard_rules.description）——AI 冷读通道用（06 步骤 4）：码为空的规则只有它说得出「违反了哪条」。 */
    val description: String? = null,
)

/**
 * 生成侧码头投影（06 §4 [0]）：算「按场景禁用的码集」——恒生效规则恒减；
 * 限场景的规则只在旗标已知且匹配时减（旗标缺只减恒生效，模棱处留给判定层的场景旗标缺料）。
 */
fun Iterable<JudgeHardRule>.projectForbiddenCodes(visibility: SceneVisibility?): Set<String> =
    filter { it.scope == HardRuleScope.ALWAYS || (visibility != null && it.scope.matches(visibility)) }
        .flatMapTo(mutableSetOf()) { it.forbiddenCodes }

/**
 * 判定用角色：05 同源的角色卡（data 侧复用生成侧映射，设定卡六表 → [GrowCharacter]）+ 硬规则。
 * 包装而非平铺：别名 / 说话风格 / 喜恶这些 AI 冷读块字段与生成侧共用一份映射，不重复搬。
 */
data class JudgeCharacter(
    val card: GrowCharacter,
    val hardRules: List<JudgeHardRule> = emptyList(),
) {
    /** 系统三支直用字段（软轴原文 = 五维四档 JSON，与 05 组装同源）。 */
    val id: Long get() = card.id
    val name: String get() = card.name
    val softAxesRaw: String? get() = card.softAxesRaw
}

/**
 * 判定快照（06 输入）：候选 + 出场角色 + 场景旗标 + 码表投影；
 * 尾部冷读块（步骤 4：场景 / 关系 / 共享设定 / 最近已发生 / 动作名）只喂 AI 路线——系统三支忽略。
 * 候选的物理档不在这里——由 [behaviorLevels] 从步骤码推（AI 零话语权，2026-10-07 拍）。
 * [target] null = 单人事件（容忍档 = 本人档，只查 actor 的规则）。
 */
data class JudgmentSnapshot(
    val candidate: EventCandidate,
    val actor: JudgeCharacter,
    val target: JudgeCharacter? = null,
    /** scenes.location_type；null = 场景没标公开 / 私密（只有按场景生效的禁码规则在局中时才算缺料）。 */
    val sceneVisibility: SceneVisibility? = null,
    /** 码 → physical_level（基础动作库投影；不在表里 = 步骤码非法 → 缺料）。 */
    val behaviorLevels: Map<String, Int> = emptyMap(),
    // —— 冷读块（06 步骤 4：AI 路线用；系统三支与降级回落都忽略）——
    /** 场景行（旗标另有 [sceneVisibility]）。 */
    val scene: GrowScene? = null,
    /** 两人关系（有向；最多两行——甲对乙、乙对甲）。 */
    val relations: List<GrowRelation> = emptyList(),
    /** 共享设定（pair_shared_specs；没有 → null / 空）。 */
    val pairSummary: String? = null,
    val relationToneSummary: String? = null,
    val sharedForbiddenTags: List<String> = emptyList(),
    /** 最近已发生（概要；新的在前）。 */
    val recentSummaries: List<String> = emptyList(),
    /** 步骤码 → 动作名（把码渲染成人看得懂的动作名；缺 → 只显示码）。 */
    val behaviorNames: Map<String, String> = emptyMap(),
) {
    /** 出场角色（单人 = 只有 actor）。 */
    val participants: List<JudgeCharacter> get() = listOfNotNull(actor, target)
}

// —— 软轴档位读取（判定三维：身体接触 / 说话直接度 / 情绪外露）——

/** 软轴维度键（character_specs.soft_axes 的对象键；与 05 渲染同一份约定）。 */
const val AXIS_PHYSICAL_INTIMACY: String = "physical_intimacy"
const val AXIS_VERBAL_DIRECTNESS: String = "verbal_directness"
const val AXIS_EMOTION_VISIBILITY: String = "emotion_visibility"

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 软轴 JSON → 维度键 → 档位：只收判定三维、只收 1–4 的合法档；
 * 缺 / 坏 / 越界一律不进表（= 该维缺料候选）。「不选 = 不进提示词」的省略项自然缺席。
 */
fun parseSoftAxisLevels(raw: String?): Map<String, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    val obj = runCatching { lenientJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyMap()
    val keys = listOf(AXIS_PHYSICAL_INTIMACY, AXIS_VERBAL_DIRECTNESS, AXIS_EMOTION_VISIBILITY)
    return buildMap {
        for (key in keys) {
            val entry = obj[key] as? JsonObject ?: continue
            val level = (entry["level"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: continue
            if (level in 1..4) put(key, level)
        }
    }
}
