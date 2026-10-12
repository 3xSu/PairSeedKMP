package com.pairseedkmp.data.grow_impl

import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.grow.toGrowBehavior
import com.pairseedkmp.data.grow.toGrowCharacter
import com.pairseedkmp.data.grow.toGrowDocumentRef
import com.pairseedkmp.data.grow.toGrowRelation
import com.pairseedkmp.data.grow.toGrowScene
import com.pairseedkmp.data.judgment.toJudgeHardRule
import com.pairseedkmp.domain.event.ChecklistEvent
import com.pairseedkmp.domain.event.DEFAULT_DOC_SNIPPETS_PER_TYPE
import com.pairseedkmp.domain.event.DEFAULT_RECENT_EVENT_COUNT
import com.pairseedkmp.domain.event.EventContextSource
import com.pairseedkmp.domain.event.EventGrowContext
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.GrowDocumentRef
import com.pairseedkmp.domain.event.GrowPackConfig
import com.pairseedkmp.domain.event.assemble
import com.pairseedkmp.domain.event.parseJsonStringList
import com.pairseedkmp.domain.judgment.JudgeHardRule
import com.pairseedkmp.domain.judgment.SceneVisibility
import com.pairseedkmp.domain.judgment.projectForbiddenCodes

/**
 * [EventContextSource] 的 SQLDelight 实现（读法照壳层 resolveContext）：
 * 运行配置 → 角色组 → 角色（第一个 = actor、第二个 = target；不足两人 → null）→ 场景（配置指向的，
 * 没指取第一个）→ 设定卡 / 关系 / 共享设定 / 动作库 / 文档库 / 近期已发生 / 清单 → `assemble` 成快照。
 */
class EventContextSourceImpl(private val database: PairSeedDatabase) : EventContextSource {

    override fun snapshot(now: Long, utcOffsetSeconds: Int): EventGrowContext? {
        val profile = database.runtime_profilesQueries.selectRuntimeProfile().executeAsOneOrNull() ?: return null
        val packId = profile.active_pack_id ?: return null
        val pack = database.character_packsQueries.selectCharacterPackById(packId).executeAsOneOrNull() ?: return null
        val characters = database.charactersQueries.selectCharactersByPack(packId).executeAsList()
        val actorRow = characters.getOrNull(0) ?: return null
        val targetRow = characters.getOrNull(1) ?: return null
        val sceneRow = profile.active_scene_id
            ?.let { database.scenesQueries.selectSceneById(it).executeAsOneOrNull() }
            ?: database.scenesQueries.selectScenesByPack(packId).executeAsList().firstOrNull()
            ?: return null

        val pair = database.pair_shared_specsQueries.selectPairSharedSpecByPackId(packId).executeAsOneOrNull()
        val config = GrowPackConfig(
            packId = packId,
            packName = pack.pack_name,
            actor = actorRow.toGrowCharacter(spec(actorRow.id)),
            target = targetRow.toGrowCharacter(spec(targetRow.id)),
            relations = database.character_target_relationsQueries
                .selectRelationsBetween(actorRow.id, targetRow.id)
                .executeAsList()
                .filter {
                    (it.character_id == actorRow.id && it.target_character_id == targetRow.id) ||
                        (it.character_id == targetRow.id && it.target_character_id == actorRow.id)
                }
                .map { it.toGrowRelation() },
            pairSummary = pair?.pair_summary,
            relationToneSummary = pair?.relation_tone_summary,
            sharedForbiddenTags = parseJsonStringList(pair?.shared_forbidden_tags),
            scene = sceneRow.toGrowScene(),
            behaviors = database.behavior_templatesQueries.selectAllBehaviors()
                .executeAsList().map { it.toGrowBehavior() },
            forbiddenActionCodes = (judgeRules(actorRow.id) + judgeRules(targetRow.id))
                .projectForbiddenCodes(SceneVisibility.fromCode(sceneRow.location_type)),
            documents = documents(pack.pack_code),
            recentSummaries = database.eventsQueries.selectRecentOccurred(DEFAULT_RECENT_EVENT_COUNT.toLong())
                .executeAsList().map { it.summary },
            checklistEvents = database.eventsQueries
                .selectEventsByStatus(EventStatus.IN_LIST.code)
                .executeAsList()
                .map { ChecklistEvent(eventId = it.id, summary = it.summary) },
            // 在流程内（待确认 + 进行中）：只进查重靶集——它们会回到 / 仍在清单，复读要挡
            inFlightSummaries = (
                database.eventsQueries.selectEventsByStatus(EventStatus.PENDING_CONFIRMATION.code).executeAsList() +
                    database.eventsQueries.selectEventsByStatus(EventStatus.IN_PROGRESS.code).executeAsList()
                ).map { it.summary },
        )
        return config.assemble(now, utcOffsetSeconds)
    }

    private fun spec(characterId: Long) =
        database.character_specsQueries.selectCharacterSpecByCharacterId(characterId).executeAsOneOrNull()

    /** 角色硬规则（判定用行）——生成侧只借它的码投影（06 §4 [0] 预防层）。 */
    private fun judgeRules(characterId: Long): List<JudgeHardRule> =
        database.character_hard_rulesQueries.selectHardRulesByCharacter(characterId)
            .executeAsList().map { it.toJudgeHardRule() }

    /** 参考素材：voice_ref（说话样本）+ activity_ref（活动素材）各取前几条。 */
    private fun documents(packCode: String): List<GrowDocumentRef> {
        val limit = DEFAULT_DOC_SNIPPETS_PER_TYPE.toLong()
        val voices = database.document_snippetsQueries
            .selectDocumentSnippetsByPackAndType(packCode, "voice_ref", limit).executeAsList()
        val activities = database.document_snippetsQueries
            .selectDocumentSnippetsByPackAndType(packCode, "activity_ref", limit).executeAsList()
        return (voices + activities).map { it.toGrowDocumentRef() }
    }
}
