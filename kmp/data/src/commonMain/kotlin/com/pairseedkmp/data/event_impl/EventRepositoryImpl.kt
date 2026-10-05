package com.pairseedkmp.data.event_impl

import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.event.toDomain
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.common.toRawString
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.EventStepsJson
import com.pairseedkmp.domain.repository.EventRepository

/** [EventRepository] 的 SQLDelight 实现（读写对的是 events 表）。 */
class EventRepositoryImpl(private val database: PairSeedDatabase) : EventRepository {

    private val queries get() = database.eventsQueries

    override fun insert(event: Event): Long {
        queries.insertEvent(
            pack_id = event.packId,
            actor_id = event.actorId,
            target_id = event.targetId,
            scene_id = event.sceneId,
            summary = event.summary,
            steps = EventStepsJson.encode(event.steps),
            comparison_axes = event.comparisonAxes,
            confirmation_payload = event.confirmationPayload,
            suitable_time = event.suitableTime.toRawString(),
            status = event.status.code,
            origin = event.origin.code,
            source_seed_id = event.sourceSeedId,
            semi_fixed_template_code = event.semiFixedTemplateCode,
            occurred_at = event.occurredAt,
            duration_sec = event.durationSec.toLong(),
            expires_at = event.expiresAt,
            is_deleted = if (event.isDeleted) 1L else 0L,
            created_at = event.createdAt,
            updated_at = event.updatedAt,
        )
        return queries.lastInsertedEventId().executeAsOne()
    }

    override fun byId(id: Long): Event? =
        queries.selectEventById(id).executeAsOneOrNull()?.toDomain()

    override fun activeEvents(): List<Event> =
        queries.selectActiveEvents().executeAsList().map { it.toDomain() }

    override fun markInProgress(id: Long, occurredAt: Long, updatedAt: Long) {
        queries.markEventInProgress(occurred_at = occurredAt, updated_at = updatedAt, id = id)
    }

    override fun updateStatus(id: Long, status: EventStatus, updatedAt: Long) {
        queries.updateEventStatus(status = status.code, updated_at = updatedAt, id = id)
    }

    override fun updateStatusAndSuitableTime(
        id: Long,
        status: EventStatus,
        suitableTime: List<Period>,
        updatedAt: Long,
    ) {
        queries.updateEventStatusAndSuitableTime(
            status = status.code,
            suitable_time = suitableTime.toRawString(),
            updated_at = updatedAt,
            id = id,
        )
    }

    override fun completedPastExpiry(now: Long): List<Event> =
        queries.selectCompletedPastExpiry(now).executeAsList().map { it.toDomain() }

    override fun visibleEvents(): List<Event> =
        queries.selectVisibleEvents().executeAsList().map { it.toDomain() }

    override fun markExpired(id: Long, updatedAt: Long) {
        queries.markEventExpired(updated_at = updatedAt, id = id)
    }

    override fun scheduledOccurredBetween(templateCode: String, fromInclusive: Long, toExclusive: Long): Boolean =
        // 位置传参：两个 occurred_at 重名（生成名 occurred_at / occurred_at_）
        queries.countScheduledOccurrencesBetween(templateCode, fromInclusive, toExclusive).executeAsOne() > 0

    override fun lastOccurredEndMillis(): Long? =
        // MAX(...) 恒有结果行；一条都没发生过 → 值为 NULL（生成类属性名 MAX）
        queries.selectLastOccurredEnd().executeAsOneOrNull()?.MAX
}
