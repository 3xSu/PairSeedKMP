package com.pairseedkmp.data.event_impl

import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.event.toDomain
import com.pairseedkmp.domain.event.EventStepsJson
import com.pairseedkmp.domain.event.SemiFixedEventTemplate
import com.pairseedkmp.domain.repository.SemiFixedTemplateRepository

/** [SemiFixedTemplateRepository] 的 SQLDelight 实现。 */
class SemiFixedTemplateRepositoryImpl(private val database: PairSeedDatabase) : SemiFixedTemplateRepository {

    private val queries get() = database.semi_fixed_event_templatesQueries

    override fun byPack(packId: Long): List<SemiFixedEventTemplate> =
        queries.selectSemiFixedTemplatesByPack(packId).executeAsList().map { it.toDomain() }

    override fun insert(template: SemiFixedEventTemplate): Long {
        queries.insertSemiFixedTemplate(
            pack_id = template.packId,
            template_code = template.templateCode,
            name_cn = template.nameCn,
            summary = template.summary,
            steps = EventStepsJson.encode(template.steps),
            window_earliest = template.windowEarliest.toLong(),
            window_latest = template.windowLatest.toLong(),
            created_at = template.createdAt,
            updated_at = template.updatedAt,
        )
        return queries.lastInsertedSemiFixedTemplateId().executeAsOne()
    }
}
