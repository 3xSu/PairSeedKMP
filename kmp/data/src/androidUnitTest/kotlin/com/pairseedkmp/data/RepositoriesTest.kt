package com.pairseedkmp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.pairseedkmp.data.event_impl.EventRepositoryImpl
import com.pairseedkmp.data.event_impl.SemiFixedTemplateRepositoryImpl
import com.pairseedkmp.data.event_impl.UserConfirmationRepositoryImpl
import com.pairseedkmp.data.runtime_impl.RuntimeProfileRepositoryImpl
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.event.BehaviorDurationSource
import com.pairseedkmp.domain.event.ConfirmationAnswer
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventOrigin
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.EventStepsJson
import com.pairseedkmp.domain.event.SemiFixedEventTemplate
import com.pairseedkmp.domain.event.UserConfirmation
import com.pairseedkmp.domain.runtime.RuntimeProfile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/** 步骤 2：数据访问——真 SQLite（JVM 驱动、内存库）跑事件读写 / 轮窗口查询 / 确认最新一条 / 心跳
 *（步骤 5 增：确认落库、过期滚出查询；步骤 6 增：可见事件列表查询）。 */
class RepositoriesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: PairSeedDatabase

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PairSeedDatabase.Schema.create(driver)
        db = PairSeedDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun sampleEvent(
        status: EventStatus = EventStatus.IN_LIST,
        origin: EventOrigin = EventOrigin.SEED,
        semiFixedCode: String? = null,
        occurredAt: Long? = null,
        suitableTime: List<Period> = listOf(Period.MORNING),
        expiresAt: Long = 1_791_072_000_000L + 7 * 86_400_000L,
    ) = Event(
        id = 0,
        packId = 7,
        actorId = 1,
        targetId = 2,
        sceneId = 1,
        summary = "一起做早饭",
        steps = EventStepsJson.parse(
            """[{"action":"walk"},{"action":"say","params":{"speaker":"A","text":"早","duration_sec":6}}]"""
        ),
        comparisonAxes = """{"physical_intimacy":10}""",
        confirmationPayload = null,
        suitableTime = suitableTime,
        status = status,
        origin = origin,
        sourceSeedId = null,
        semiFixedTemplateCode = semiFixedCode,
        occurredAt = occurredAt,
        durationSec = 15,
        expiresAt = expiresAt,
        isDeleted = false,
        createdAt = 1_791_072_000_000L,
        updatedAt = 1_791_072_000_000L,
    )

    @Test
    fun 插入读出_字段往返() {
        val repo = EventRepositoryImpl(db)
        val id = repo.insert(sampleEvent())
        assertTrue(id > 0, "应返回库分配的 id")

        val e = repo.byId(id)!!
        assertEquals("一起做早饭", e.summary)
        assertEquals(listOf(Period.MORNING), e.suitableTime)
        assertEquals(EventStatus.IN_LIST, e.status)
        assertEquals(EventOrigin.SEED, e.origin)
        assertEquals(2, e.steps.size)
        assertEquals("早", e.steps[1].params["text"]!!.jsonPrimitive.content)
        assertEquals("""{"physical_intimacy":10}""", e.comparisonAxes)
        // steps 时长加总 ↔ duration_sec（walk 9 + 说 6 = 15）
        assertTrue(e.durationMatchesSteps(BehaviorDurationSource { code -> if (code == "walk") 9 else null }))
    }

    @Test
    fun 状态推进_进行中到已发生_过期逻辑删除() {
        val repo = EventRepositoryImpl(db)
        val id = repo.insert(sampleEvent())

        repo.markInProgress(id, occurredAt = 1_800_000_000_000L, updatedAt = 1_800_000_000_000L)
        var e = repo.byId(id)!!
        assertEquals(EventStatus.IN_PROGRESS, e.status)
        assertEquals(1_800_000_000_000L, e.occurredAt)
        assertEquals(listOf(id), repo.activeEvents().map { it.id })

        repo.updateStatus(id, EventStatus.OCCURRED, updatedAt = 1_800_000_010_000L)
        e = repo.byId(id)!!
        assertEquals(EventStatus.OCCURRED, e.status)
        assertTrue(repo.activeEvents().isEmpty(), "已发生不在主动事件里")

        repo.markExpired(id, updatedAt = 1_800_000_020_000L)
        e = repo.byId(id)!!
        assertTrue(e.isDeleted, "过期 → 逻辑删除（数据留）")
        assertTrue(repo.activeEvents().isEmpty())
        assertEquals(EventStatus.OCCURRED, e.status, "逻辑删除不改状态")
    }

    @Test
    fun 确认落库_状态与时段一起改() {
        val repo = EventRepositoryImpl(db)
        val id = repo.insert(
            sampleEvent(status = EventStatus.PENDING_CONFIRMATION, suitableTime = listOf(Period.MORNING, Period.EVENING))
        )

        repo.updateStatusAndSuitableTime(id, EventStatus.IN_LIST, listOf(Period.EVENING), updatedAt = 555L)
        var e = repo.byId(id)!!
        assertEquals(EventStatus.IN_LIST, e.status)
        assertEquals(listOf(Period.EVENING), e.suitableTime)
        assertEquals(555L, e.updatedAt)

        repo.updateStatusAndSuitableTime(id, EventStatus.REJECTED, emptyList(), updatedAt = 666L)
        e = repo.byId(id)!!
        assertEquals(EventStatus.REJECTED, e.status)
        assertTrue(e.suitableTime.isEmpty(), "摘空 → 存 NULL，读回来空表")
        assertEquals(666L, e.updatedAt)
    }

    @Test
    fun 已发生与已拒的过期件_按到点查出() {
        val repo = EventRepositoryImpl(db)
        val expiredOccurred = repo.insert(
            sampleEvent(status = EventStatus.OCCURRED, occurredAt = 1_800_000_000_000L, expiresAt = 1_800_000_001_000L)
        )
        val expiredRejected = repo.insert(
            sampleEvent(status = EventStatus.REJECTED, suitableTime = emptyList(), expiresAt = 1_800_000_001_000L)
        )
        repo.insert(
            sampleEvent(status = EventStatus.OCCURRED, occurredAt = 1_800_000_000_000L, expiresAt = 1_800_000_999_000L)
        )

        assertTrue(repo.completedPastExpiry(1_800_000_000_999L).isEmpty(), "差 1 毫秒没到点 → 不滚")
        val due = repo.completedPastExpiry(1_800_000_001_000L)
        assertEquals(listOf(expiredOccurred, expiredRejected), due.map { it.id }, "now ≥ expires_at 即到点（按 id 升序）")
    }

    @Test
    fun 可见事件_逻辑删除的不出_新的在前() {
        val repo = EventRepositoryImpl(db)
        val first = repo.insert(sampleEvent())
        val second = repo.insert(sampleEvent())
        assertEquals(listOf(second, first), repo.visibleEvents().map { it.id }, "新的在前")

        repo.markExpired(second, updatedAt = 1_800_000_000_000L)
        assertEquals(listOf(first), repo.visibleEvents().map { it.id }, "逻辑删除的不显示（数据留、byId 仍查得到）")
        assertTrue(repo.byId(second)!!.isDeleted)
    }

    @Test
    fun 轮窗口内是否发生过() {
        val repo = EventRepositoryImpl(db)
        val windowStart = 1_800_000_000_000L
        val windowEnd = windowStart + 8 * 3_600_000L

        assertFalse(repo.scheduledOccurredBetween("sleep", windowStart, windowEnd))

        repo.insert(
            sampleEvent(
                status = EventStatus.IN_PROGRESS,
                origin = EventOrigin.SCHEDULED,
                semiFixedCode = "sleep",
                occurredAt = windowStart + 1_000L,
                suitableTime = emptyList(),
            )
        )
        assertTrue(repo.scheduledOccurredBetween("sleep", windowStart, windowEnd))

        // 窗口外 / 别的模板 都不算
        assertFalse(repo.scheduledOccurredBetween("sleep", windowEnd, windowEnd + 1_000L))
        assertFalse(repo.scheduledOccurredBetween("meal", windowStart, windowEnd))
    }

    @Test
    fun 半固定模板_按pack读与幂等插入() {
        val repo = SemiFixedTemplateRepositoryImpl(db)
        val template = SemiFixedEventTemplate(
            id = 0,
            packId = 7,
            templateCode = "sleep",
            nameCn = "睡觉",
            summary = "去睡了",
            steps = EventStepsJson.parse("""[{"action":"walk"}]"""),
            windowEarliest = 23 * 60,
            windowLatest = 7 * 60,
            isDeleted = false,
            createdAt = 100L,
            updatedAt = 100L,
        )

        val id = repo.insert(template)
        assertTrue(id > 0)
        repo.insert(template) // 幂等：UNIQUE(pack_id, template_code) + OR IGNORE

        val list = repo.byPack(7)
        assertEquals(1, list.size)
        assertEquals("sleep", list.single().templateCode)
        assertEquals(23 * 60, list.single().windowEarliest)
        assertEquals(7 * 60, list.single().windowLatest)
        assertTrue(repo.byPack(8).isEmpty(), "别的 pack 读不到")
    }

    @Test
    fun 确认_最新一条生效() {
        val eventRepo = EventRepositoryImpl(db)
        val repo = UserConfirmationRepositoryImpl(db)
        val eventId = eventRepo.insert(sampleEvent(status = EventStatus.PENDING_CONFIRMATION))

        assertNull(repo.latestFor(eventId))

        repo.insert(UserConfirmation(0, eventId, Period.MORNING, ConfirmationAnswer.NO, answeredAt = 1_000L))
        repo.insert(UserConfirmation(0, eventId, Period.MORNING, ConfirmationAnswer.YES, answeredAt = 2_000L))

        val latest = repo.latestFor(eventId)!!
        assertEquals(ConfirmationAnswer.YES, latest.answer, "改答 = 再插一条，最新生效")
        assertEquals(Period.MORNING, latest.answeredPeriod)
        assertEquals(2_000L, latest.answeredAt)
    }

    @Test
    fun 运行配置_首启建行与心跳() {
        val repo = RuntimeProfileRepositoryImpl(db)
        assertNull(repo.current(), "首启还没有配置行")

        val id = repo.insert(RuntimeProfile(id = 0, createdAt = 100L, updatedAt = 100L))
        assertTrue(id > 0)
        assertNull(repo.current()!!.lastSeenAt, "首启未记心跳 → null")

        repo.heartbeat(id, now = 999L)
        val p = repo.current()!!
        assertEquals(999L, p.lastSeenAt)
        assertEquals(999L, p.updatedAt)
    }
}
