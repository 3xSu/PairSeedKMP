package com.pairseedkmp.domain.testing

import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventOrigin
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.SemiFixedEventTemplate
import com.pairseedkmp.domain.event.UserConfirmation
import com.pairseedkmp.domain.repository.EventRepository
import com.pairseedkmp.domain.repository.RuntimeProfileRepository
import com.pairseedkmp.domain.repository.SemiFixedTemplateRepository
import com.pairseedkmp.domain.repository.UserConfirmationRepository
import com.pairseedkmp.domain.runtime.RuntimeProfile

/** 内存版事件仓储（commonTest 用；行为对齐 [EventRepository] 契约，同 data 层的 SQLDelight 实现）。 */
class FakeEventRepository(initial: List<Event> = emptyList()) : EventRepository {

    private val stored = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    /** 读一眼当前存储（测试断言用）。 */
    fun all(): List<Event> = stored.toList()

    override fun insert(event: Event): Long {
        val id = nextId++
        stored += event.copy(id = id)
        return id
    }

    override fun byId(id: Long): Event? = stored.firstOrNull { it.id == id }

    override fun activeEvents(): List<Event> =
        stored.filter { !it.isDeleted && it.status in ACTIVE_STATUSES }

    override fun markInProgress(id: Long, occurredAt: Long, updatedAt: Long) {
        replace(id) { it.copy(status = EventStatus.IN_PROGRESS, occurredAt = occurredAt, updatedAt = updatedAt) }
    }

    override fun updateStatus(id: Long, status: EventStatus, updatedAt: Long) {
        replace(id) { it.copy(status = status, updatedAt = updatedAt) }
    }

    override fun updateStatusAndSuitableTime(
        id: Long,
        status: EventStatus,
        suitableTime: List<Period>,
        updatedAt: Long,
    ) {
        replace(id) { it.copy(status = status, suitableTime = suitableTime, updatedAt = updatedAt) }
    }

    override fun completedPastExpiry(now: Long): List<Event> =
        stored.filter {
            !it.isDeleted && it.status in COMPLETED_STATUSES && now >= it.expiresAt
        }.sortedBy { it.id }

    override fun markExpired(id: Long, updatedAt: Long) {
        replace(id) { it.copy(isDeleted = true, updatedAt = updatedAt) }
    }

    override fun scheduledOccurredBetween(templateCode: String, fromInclusive: Long, toExclusive: Long): Boolean =
        stored.any {
            it.origin == EventOrigin.SCHEDULED && it.semiFixedTemplateCode == templateCode &&
                it.occurredAt != null && it.occurredAt >= fromInclusive && it.occurredAt < toExclusive
        }

    override fun lastOccurredEndMillis(): Long? =
        stored.filter { it.occurredAt != null }.maxOfOrNull { it.endsAt!! }

    override fun visibleEvents(): List<Event> =
        stored.filter { !it.isDeleted }.sortedByDescending { it.id }

    private fun replace(id: Long, transform: (Event) -> Event) {
        val index = stored.indexOfFirst { it.id == id }
        if (index >= 0) stored[index] = transform(stored[index])
    }

    private companion object {
        val ACTIVE_STATUSES =
            setOf(EventStatus.PENDING_CONFIRMATION, EventStatus.IN_LIST, EventStatus.IN_PROGRESS)
        val COMPLETED_STATUSES = setOf(EventStatus.OCCURRED, EventStatus.REJECTED)
    }
}

/** 内存版确认记录仓储（只插不删；[latestFor] 排序对齐库内 SQL：answered_at 优先、同刻比 id）。 */
class FakeUserConfirmationRepository(
    initial: List<UserConfirmation> = emptyList(),
) : UserConfirmationRepository {

    private val stored = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    /** 读一眼当前存储（测试断言用）。 */
    fun all(): List<UserConfirmation> = stored.toList()

    override fun insert(confirmation: UserConfirmation): Long {
        val id = nextId++
        stored += confirmation.copy(id = id)
        return id
    }

    override fun latestFor(eventId: Long): UserConfirmation? =
        stored.filter { it.eventId == eventId }
            .maxWithOrNull(compareBy({ it.answeredAt }, { it.id }))
}

/** 内存版运行配置仓储（单行）。 */
class FakeRuntimeProfileRepository(initial: RuntimeProfile? = null) : RuntimeProfileRepository {

    private var stored: RuntimeProfile? = initial
    private var nextId = 1L

    /** 读一眼当前行（测试断言用）。 */
    fun stored(): RuntimeProfile? = stored

    override fun current(): RuntimeProfile? = stored

    override fun insert(profile: RuntimeProfile): Long {
        val id = nextId++
        stored = profile.copy(id = id)
        return id
    }

    override fun heartbeat(id: Long, now: Long) {
        stored = stored?.takeIf { it.id == id }?.copy(lastSeenAt = now, updatedAt = now)
    }
}

/** 内存版半固定模板仓储。 */
class FakeSemiFixedTemplateRepository(
    initial: List<SemiFixedEventTemplate> = emptyList(),
) : SemiFixedTemplateRepository {

    private val stored = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    /** 读一眼当前存储（测试断言用）。 */
    fun all(): List<SemiFixedEventTemplate> = stored.toList()

    override fun byPack(packId: Long): List<SemiFixedEventTemplate> =
        stored.filter { it.packId == packId && !it.isDeleted }

    override fun insert(template: SemiFixedEventTemplate): Long {
        val id = nextId++
        stored += template.copy(id = id)
        return id
    }
}
