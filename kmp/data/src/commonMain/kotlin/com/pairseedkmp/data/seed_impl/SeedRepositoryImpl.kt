package com.pairseedkmp.data.seed_impl

import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.seed.encodeSceneHintTags
import com.pairseedkmp.data.seed.toDomain
import com.pairseedkmp.domain.repository.SeedRepository
import com.pairseedkmp.domain.seed.IdeaSeed

/** [SeedRepository] 的 SQLDelight 实现。 */
class SeedRepositoryImpl(private val database: PairSeedDatabase) : SeedRepository {

    private val queries get() = database.idea_seedsQueries

    override fun all(): List<IdeaSeed> = queries.selectSeeds().executeAsList().map { it.toDomain() }

    override fun search(fragment: String): List<IdeaSeed> =
        queries.searchSeedsByKeyword(fragment).executeAsList().map { it.toDomain() }

    override fun insert(seed: IdeaSeed): Long {
        queries.insertSeed(
            keyword = seed.keyword,
            theme = seed.theme,
            scene_hint_tags = encodeSceneHintTags(seed.sceneHintTags),
            suggested_time_window = seed.suggestedTimeWindow?.code,
            relationship_push_target = seed.relationshipPushTarget?.toLong(),
            novelty_target = seed.noveltyTarget?.toLong(),
            source_type = seed.sourceType.code,
            quality_score = seed.qualityScore?.toLong(),
            created_at = seed.createdAt,
            updated_at = seed.updatedAt,
        )
        return queries.lastInsertedSeedId().executeAsOne()
    }

    override fun recordPick(id: Long, pickedAt: Long) {
        queries.recordSeedPick(pickedAt = pickedAt, id = id)
    }
}
