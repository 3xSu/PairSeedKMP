package com.pairseedkmp.domain.judgment

import com.pairseedkmp.domain.event.ComparisonAxes
import com.pairseedkmp.domain.event.EventCandidate
import com.pairseedkmp.domain.event.EventStep
import com.pairseedkmp.domain.event.GrowCharacter
import com.pairseedkmp.domain.event.GrowRelation
import com.pairseedkmp.domain.event.GrowScene

/** 基础动作库投影（测试用）：17 码全表，档位按 2026-10-11 定档（单人全 1 / 牵手·靠 3 / 拥抱·抱起 4）。 */
internal val TEST_BEHAVIOR_LEVELS: Map<String, Int> = mapOf(
    "stand" to 1, "walk" to 1, "sit" to 1, "lie_down" to 1, "turn" to 1, "wave" to 1,
    "say" to 1, "eat" to 1, "drink" to 1, "pick_up" to 1, "put_down" to 1, "push_pull" to 1,
    "hand_item" to 1, "hold_hands" to 3, "lean" to 3, "hug" to 4, "hold" to 4,
)

/** 软轴 JSON 片段：给定维度键 + 档位（每维带占位 text）。 */
internal fun softAxes(vararg levels: Pair<String, Int>): String =
    levels.joinToString(prefix = "{", postfix = "}") { (key, level) -> """"$key":{"level":$level,"text":"x"}""" }

internal fun phys(level: Int) = AXIS_PHYSICAL_INTIMACY to level
internal fun verb(level: Int) = AXIS_VERBAL_DIRECTNESS to level
internal fun emo(level: Int) = AXIS_EMOTION_VISIBILITY to level

internal fun char(
    id: Long,
    axes: String? = null,
    rules: List<JudgeHardRule> = emptyList(),
    summary: String = "角色$id 的摘要",
    aliases: List<String> = emptyList(),
    speech: String? = null,
): JudgeCharacter = JudgeCharacter(
    card = GrowCharacter(
        id = id,
        name = "角色$id",
        summary = summary,
        aliases = aliases,
        softAxesRaw = axes,
        speechStyleSummary = speech,
    ),
    hardRules = rules,
)

internal fun rule(
    characterId: Long,
    scope: HardRuleScope = HardRuleScope.ALWAYS,
    vararg codes: String,
    key: String = "r",
    description: String? = null,
): JudgeHardRule = JudgeHardRule(
    characterId = characterId,
    ruleKey = key,
    scope = scope,
    forbiddenCodes = codes.toSet(),
    description = description,
)

internal fun relation(
    from: Long = 1L,
    to: Long = 2L,
    summary: String = "依赖与照顾",
): GrowRelation = GrowRelation(
    fromCharacterId = from,
    toCharacterId = to,
    towardTargetSummary = summary,
    defaultDistance = "亲近",
)

internal fun candidate(
    vararg codes: String,
    verbal: Int? = null,
    emotion: Int = 1,
    targetId: Long? = 2L,
): EventCandidate = EventCandidate(
    packId = 7,
    actorId = 1,
    targetId = targetId,
    sceneId = 9,
    summary = "概要",
    steps = codes.map { EventStep(it) },
    axes = ComparisonAxes(verbal, emotion),
    suitableTime = emptyList(),
)

internal fun snapshot(
    candidate: EventCandidate,
    actor: JudgeCharacter,
    target: JudgeCharacter? = null,
    visibility: SceneVisibility? = SceneVisibility.PRIVATE,
    relations: List<GrowRelation> = emptyList(),
    scene: GrowScene? = null,
    pairSummary: String? = null,
    relationToneSummary: String? = null,
    sharedTags: List<String> = emptyList(),
    recent: List<String> = emptyList(),
    behaviorNames: Map<String, String> = emptyMap(),
): JudgmentSnapshot = JudgmentSnapshot(
    candidate = candidate,
    actor = actor,
    target = target,
    sceneVisibility = visibility,
    behaviorLevels = TEST_BEHAVIOR_LEVELS,
    scene = scene,
    relations = relations,
    pairSummary = pairSummary,
    relationToneSummary = relationToneSummary,
    sharedForbiddenTags = sharedTags,
    recentSummaries = recent,
    behaviorNames = behaviorNames,
)
