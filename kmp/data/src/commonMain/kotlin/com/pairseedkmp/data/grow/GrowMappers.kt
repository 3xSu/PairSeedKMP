package com.pairseedkmp.data.grow

import com.pairseedkmp.data.Behavior_templates
import com.pairseedkmp.data.Character_specs
import com.pairseedkmp.data.Character_target_relations
import com.pairseedkmp.data.Characters
import com.pairseedkmp.data.Document_snippets
import com.pairseedkmp.data.Scenes
import com.pairseedkmp.domain.event.GrowBehavior
import com.pairseedkmp.domain.event.GrowCharacter
import com.pairseedkmp.domain.event.GrowDocumentRef
import com.pairseedkmp.domain.event.GrowRelation
import com.pairseedkmp.domain.event.GrowScene
import com.pairseedkmp.domain.event.parseJsonStringList

// 生成上下文（05）读侧的行 ↔ 值类型映射：角色六表 / 场景 / 动作库 / 文档库。

/** 角色行 + 设定卡行（可能没有）→ 生成上下文角色。 */
internal fun Characters.toGrowCharacter(spec: Character_specs?): GrowCharacter = GrowCharacter(
    id = id,
    name = character_name,
    summary = summary,
    aliases = parseJsonStringList(spec?.aliases),
    softAxesRaw = spec?.soft_axes,
    speechStyleSummary = spec?.speech_style_summary,
    preferredAffectionModes = parseJsonStringList(spec?.preferred_affection_modes),
    dislikedAffectionModes = parseJsonStringList(spec?.disliked_affection_modes),
)

internal fun Character_target_relations.toGrowRelation(): GrowRelation = GrowRelation(
    fromCharacterId = character_id,
    toCharacterId = target_character_id,
    towardTargetSummary = toward_target_summary,
    defaultDistance = default_distance,
    likelyInitiationModes = parseJsonStringList(likely_initiation_modes),
    sensitiveProgressionTags = parseJsonStringList(sensitive_progression_tags),
    safeComfortActions = parseJsonStringList(safe_comfort_actions),
    directAddressingSummary = direct_addressing_summary,
    referenceAddressingSummary = reference_addressing_summary,
)

internal fun Scenes.toGrowScene(): GrowScene = GrowScene(
    id = id,
    name = scene_name,
    locationType = location_type,
    description = description,
)

internal fun Behavior_templates.toGrowBehavior(): GrowBehavior = GrowBehavior(
    code = behavior_code,
    name = behavior_name,
    description = description,
)

internal fun Document_snippets.toGrowDocumentRef(): GrowDocumentRef = GrowDocumentRef(
    docType = doc_type,
    title = title,
    body = body,
)
