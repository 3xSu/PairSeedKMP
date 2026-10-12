package com.pairseedkmp.data.judgment_impl

import com.pairseedkmp.data.Characters
import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.grow.toGrowCharacter
import com.pairseedkmp.data.grow.toGrowRelation
import com.pairseedkmp.data.grow.toGrowScene
import com.pairseedkmp.data.judgment.toJudgeHardRule
import com.pairseedkmp.domain.event.DEFAULT_RECENT_EVENT_COUNT
import com.pairseedkmp.domain.event.EventCandidate
import com.pairseedkmp.domain.event.parseJsonStringList
import com.pairseedkmp.domain.judgment.JudgeCharacter
import com.pairseedkmp.domain.judgment.JudgmentSnapshot
import com.pairseedkmp.domain.judgment.JudgmentSnapshotSource
import com.pairseedkmp.domain.judgment.SceneVisibility

/**
 * [JudgmentSnapshotSource] 的 SQLDelight 实现（06 步骤 2 + 冷读块步骤 4）：
 * 候选的 actor / target / scene → 角色行（已删 / 不存在 → null）→ 设定卡（与 05 同一份映射）+ 硬规则行 → 判定用角色；
 * 场景旗标（location_type，未知 / 空 → null）+ 码表档位投影（behavior_code → physical_level）+ 动作名；
 * 冷读块（AI 路线用：场景 / 双向关系 / 共享设定 / 最近已发生）一并在快照里读出——系统三支忽略它们，
 * 两条路线共用一份快照组装。
 * 缺料不管（软档没填、码不在表里都照常进快照），由判定层 fail-safe 兜。
 */
class JudgmentSnapshotSourceImpl(private val database: PairSeedDatabase) : JudgmentSnapshotSource {

    override fun snapshot(candidate: EventCandidate): JudgmentSnapshot? {
        val actor = database.charactersQueries.selectCharacterById(candidate.actorId).executeAsOneOrNull()
            ?: return null
        val target = candidate.targetId?.let {
            database.charactersQueries.selectCharacterById(it).executeAsOneOrNull() ?: return null
        }
        val scene = database.scenesQueries.selectSceneById(candidate.sceneId).executeAsOneOrNull()
            ?: return null
        val pair = database.pair_shared_specsQueries.selectPairSharedSpecByPackId(actor.pack_id).executeAsOneOrNull()
        val behaviors = database.behavior_templatesQueries.selectBehaviorLevels().executeAsList()
        return JudgmentSnapshot(
            candidate = candidate,
            actor = actor.toJudgeCharacter(),
            target = target?.toJudgeCharacter(),
            sceneVisibility = SceneVisibility.fromCode(scene.location_type),
            behaviorLevels = behaviors.associate { it.behavior_code to it.physical_level.toInt() },
            scene = scene.toGrowScene(),
            relations = if (target == null) {
                emptyList()
            } else {
                database.character_target_relationsQueries
                    .selectRelationsBetween(actor.id, target.id)
                    .executeAsList()
                    .filter {
                        (it.character_id == actor.id && it.target_character_id == target.id) ||
                            (it.character_id == target.id && it.target_character_id == actor.id)
                    }
                    .map { it.toGrowRelation() }
            },
            pairSummary = pair?.pair_summary,
            relationToneSummary = pair?.relation_tone_summary,
            sharedForbiddenTags = parseJsonStringList(pair?.shared_forbidden_tags),
            recentSummaries = database.eventsQueries.selectRecentOccurred(DEFAULT_RECENT_EVENT_COUNT.toLong())
                .executeAsList().map { it.summary },
            behaviorNames = behaviors.associate { it.behavior_code to it.behavior_name },
        )
    }

    /** 角色行 + 设定卡（可能没有）+ 硬规则 → 判定用角色（设定卡走 05 同一份映射，别名 / 说话风格 / 喜恶不重复搬）。 */
    private fun Characters.toJudgeCharacter(): JudgeCharacter = JudgeCharacter(
        card = toGrowCharacter(
            database.character_specsQueries.selectCharacterSpecByCharacterId(id).executeAsOneOrNull(),
        ),
        hardRules = database.character_hard_rulesQueries.selectHardRulesByCharacter(id)
            .executeAsList().map { it.toJudgeHardRule() },
    )
}
