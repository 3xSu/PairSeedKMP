package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.common.minuteOfDay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 近期已发生事件默认取几条进生成上下文（05 起步值 N = 4）。 */
const val DEFAULT_RECENT_EVENT_COUNT: Int = 4

/** 文档片段每类默认取几条（voice_ref / activity_ref 各 ≤6 条）。 */
const val DEFAULT_DOC_SNIPPETS_PER_TYPE: Int = 6

/** 文档片段正文喂进提示词时截几个字（当前口径：截 200 字）。 */
const val DEFAULT_DOC_BODY_CHARS: Int = 200

// —— 生成上下文（05）的值类型：data 读侧读出静态配置，再经 [assemble] 拼成快照。 ——

/** 角色（生成上下文用）：基础卡 + 设定卡。 */
data class GrowCharacter(
    val id: Long,
    val name: String,
    val summary: String,
    /** 名字变体 / 别名（设定卡 aliases；空列 → 空表）。 */
    val aliases: List<String> = emptyList(),
    /** 软性格轴 JSON 原文（五维四档；渲染时解析成「维度：档位」行）。 */
    val softAxesRaw: String? = null,
    /** 说话风格摘要（一句话）。 */
    val speechStyleSummary: String? = null,
    /** 偏好的示好方式（设定卡 JSON；空 → 空表）。 */
    val preferredAffectionModes: List<String> = emptyList(),
    /** 不偏好的示好方式（同上）。 */
    val dislikedAffectionModes: List<String> = emptyList(),
)

/** 角色关系（有方向：这一角色对那一角色；双向两条各一行）。 */
data class GrowRelation(
    val fromCharacterId: Long,
    val toCharacterId: Long,
    val towardTargetSummary: String,
    val defaultDistance: String,
    val likelyInitiationModes: List<String> = emptyList(),
    val sensitiveProgressionTags: List<String> = emptyList(),
    val safeComfortActions: List<String> = emptyList(),
    val directAddressingSummary: String? = null,
    val referenceAddressingSummary: String? = null,
)

/** 场景（生成上下文用）。 */
data class GrowScene(
    val id: Long,
    val name: String,
    val locationType: String?,
    val description: String?,
)

/** 动作库一行（只读给 AI 挑：behavior_name + description，中文展示随 07）。 */
data class GrowBehavior(
    val code: String,
    val name: String,
    val description: String?,
)

/** 文档片段（参考素材：角色说话样本 / 活动素材等）。 */
data class GrowDocumentRef(
    val docType: String,
    val title: String?,
    val body: String?,
)

/** 清单事件（id + 概要）——组装时按 id 序编号；`after` 指认的就是编号。 */
data class ChecklistEvent(val eventId: Long, val summary: String)

/**
 * 静态生成配置（data 读侧读出：角色六表 / 场景 / 动作库 / 文档库；不含时间与事件）。
 * [forbiddenActionCodes] = 硬规则码投影（`character_hard_rules.forbidden_codes` 按场景匹配后
 * 的禁码集——列随 06 判定节点加；05 先把"只给 allowed 码"的过滤管线建好，接线前恒空 = 全码可用）。
 */
data class GrowPackConfig(
    val packId: Long,
    val packName: String,
    val actor: GrowCharacter,
    val target: GrowCharacter,
    val relations: List<GrowRelation> = emptyList(),
    val pairSummary: String? = null,
    val relationToneSummary: String? = null,
    val sharedForbiddenTags: List<String> = emptyList(),
    val scene: GrowScene,
    val behaviors: List<GrowBehavior>,
    val forbiddenActionCodes: Set<String> = emptySet(),
    val documents: List<GrowDocumentRef> = emptyList(),
    /** 最近已发生事件的概要（新的在前）。 */
    val recentSummaries: List<String> = emptyList(),
    /** 清单（IN_LIST、未过期），按 id 升序。 */
    val checklistEvents: List<ChecklistEvent> = emptyList(),
)

/** 组装完成的生成上下文（提示词渲染 + 复检都用它）。 */
data class EventGrowContext(
    val config: GrowPackConfig,
    /** 当前时段（挑种子 / 生成时刻所在）。 */
    val period: Period,
    /** 可用动作库 = 全量动作库 − 硬规则禁码（"只给 allowed 码"，2026-10-07）。 */
    val allowedBehaviors: List<GrowBehavior>,
    /** 清单快照（编号 = 下标 + 1）；复检按它校验 `after` 指认。 */
    val checklist: List<GrowChecklistEntry>,
)

/** 清单快照条目：[number] 是提示词里给 AI 看的编号（1 起）。 */
data class GrowChecklistEntry(val number: Int, val eventId: Long, val summary: String)

/** 组装（纯函数）：定当前时段、滤掉禁码、给清单编上号。 */
fun GrowPackConfig.assemble(now: Long, utcOffsetSeconds: Int): EventGrowContext = EventGrowContext(
    config = this,
    period = Period.forMinuteOfDay(minuteOfDay(now, utcOffsetSeconds)),
    allowedBehaviors = behaviors.filter { it.code !in forbiddenActionCodes },
    checklist = checklistEvents.mapIndexed { index, event ->
        GrowChecklistEntry(number = index + 1, eventId = event.eventId, summary = event.summary)
    },
)

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** JSON 字符串数组列 → 文本表（空列 / 非法 / 非数组 → 空表；null 元素、去空白后空串丢弃）。 */
fun parseJsonStringList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { lenientJson.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        if (element is JsonNull) return@mapNotNull null
        (element as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}
