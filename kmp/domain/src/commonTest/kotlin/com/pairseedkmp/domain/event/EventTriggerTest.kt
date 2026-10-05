package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.MILLIS_PER_DAY
import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.runtime.RuntimeProfile
import com.pairseedkmp.domain.testing.FakeEventRepository
import com.pairseedkmp.domain.testing.FakeRuntimeProfileRepository
import com.pairseedkmp.domain.testing.FakeSemiFixedTemplateRepository
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventTriggerTest {

    private val day = 10L * MILLIS_PER_DAY // 任意一天（UTC 偏移 0 下测试）

    private fun at(dayOffset: Int = 0, hour: Int, minute: Int = 0) =
        day + dayOffset * MILLIS_PER_DAY + (hour * 60 + minute) * MILLIS_PER_MINUTE

    private val durations = BehaviorDurationSource { code -> if (code == "walk") 10 else null }
    private val steps = EventStepsJson.parse("""[{"action":"walk"}]""") // 10 秒

    private fun template(code: String, earliest: Int, latest: Int) = SemiFixedEventTemplate(
        id = 0,
        packId = 7,
        templateCode = code,
        nameCn = code,
        summary = "去$code",
        steps = steps,
        windowEarliest = earliest,
        windowLatest = latest,
        createdAt = day,
        updatedAt = day,
    )

    private fun scheduledEvent(code: String, occurredAt: Long, status: EventStatus = EventStatus.OCCURRED) = Event(
        id = 0,
        packId = 7,
        actorId = 3,
        sceneId = 5,
        summary = "去$code",
        steps = steps,
        status = status,
        origin = EventOrigin.SCHEDULED,
        semiFixedTemplateCode = code,
        occurredAt = occurredAt,
        durationSec = 10,
        expiresAt = occurredAt + EVENT_EXPIRY_MILLIS,
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )

    private fun listEvent(id: Long, suitableTime: List<Period>, summary: String) = Event(
        id = id,
        packId = 7,
        actorId = 3,
        sceneId = 5,
        summary = summary,
        steps = steps,
        suitableTime = suitableTime,
        status = EventStatus.IN_LIST,
        origin = EventOrigin.SEED,
        durationSec = 10,
        expiresAt = at(dayOffset = 3, hour = 0),
        createdAt = day,
        updatedAt = day,
    )

    private class Fixture(
        val events: FakeEventRepository,
        val trigger: EventTrigger,
    )

    private fun fixture(
        templates: List<SemiFixedEventTemplate> = emptyList(),
        events: List<Event> = emptyList(),
        thresholdMillis: Long = DEFAULT_IDLE_TRIGGER_THRESHOLD_MILLIS,
        random: Random = Random(1),
    ): Fixture {
        val eventsRepo = FakeEventRepository(events)
        val profilesRepo = FakeRuntimeProfileRepository(
            RuntimeProfile(id = 1, activePackId = 7, activeSceneId = 5, createdAt = day, updatedAt = day)
        )
        val trigger = EventTrigger(
            eventRepository = eventsRepo,
            templateRepository = FakeSemiFixedTemplateRepository(templates),
            profileRepository = profilesRepo,
            durations = durations,
            idleThresholdMillis = thresholdMillis,
            random = random,
        )
        return Fixture(eventsRepo, trigger)
    }

    private fun context() = SemiFixedFireContext(actorId = 3, sceneId = 5)

    /** 空闲中的结算结果：无在跑；[idleSince] 定空闲时长，[previousSeen] = 本次心跳前的 last_seen_at。 */
    private fun idleSettle(now: Long, idleSince: Long, previousSeen: Long?) = SettleResult(
        now = now,
        finished = emptyList(),
        expired = emptyList(),
        running = emptyList(),
        idleSince = idleSince,
        previousLastSeenAt = previousSeen,
    )

    // ===== ① 过最晚 =====

    @Test
    fun 过最晚优先于窗内_并落库为进行中() {
        val f = fixture(
            templates = listOf(
                template("meal", 12 * 60, 12 * 60), // 固定时刻 12:00，已过最晚
                template("nap", 13 * 60, 15 * 60), // 现在 13:30 窗内
            )
        )
        val now = at(hour = 13, minute = 30)
        val result = f.trigger.check(now, idleSettle(now, idleSince = now, previousSeen = at(hour = 13, minute = 29)), 0, context())

        assertEquals(TriggerDecision.FIRED_SEMI_FIXED_OVERDUE, result.decision)
        val fired = result.fired!!
        assertEquals("meal", fired.semiFixedTemplateCode)
        assertEquals(EventStatus.IN_PROGRESS, fired.status) // 不过判定 / 确认，直接发生
        assertEquals(EventOrigin.SCHEDULED, fired.origin)
        assertEquals(now, fired.occurredAt)
        assertEquals(now + EVENT_EXPIRY_MILLIS, fired.expiresAt) // 生成时刻 = 发生时刻
        assertEquals(10, fired.durationSec) // 步骤时长加总
        assertEquals(EventStatus.IN_PROGRESS, f.events.all().single().status) // 落库了
    }

    @Test
    fun 过最晚_多个due按最晚点早者先() {
        val f = fixture(
            templates = listOf(
                template("late", 10 * 60, 11 * 60), // 11:00 关
                template("early", 8 * 60, 9 * 60), // 9:00 关，先补
            )
        )
        val now = at(hour = 14)
        val result = f.trigger.check(now, idleSettle(now, idleSince = now, previousSeen = at(hour = 13, minute = 59)), 0, context())
        assertEquals("early", result.fired!!.semiFixedTemplateCode)
    }

    @Test
    fun 翻篇_整段离线的窗口不补_且翻篇有粘性() {
        // 睡觉 23:00–07:00：昨晚 22:00 之后关着 app，早上 08:00 打开
        val f = fixture(templates = listOf(template("sleep", 23 * 60, 7 * 60)))
        val now = at(hour = 8)
        val first = f.trigger.check(
            now, idleSettle(now, idleSince = now, previousSeen = at(dayOffset = -1, hour = 22)), 0, context(),
        )
        assertNull(first.fired)
        assertEquals(listOf("sleep"), first.swallowedOffline) // 翻篇，不早上补睡

        // 下一秒：心跳已刷新（previousSeen = now - 1 秒）——翻篇的轮不许"翻回来"补发
        val second = f.trigger.check(
            now + 1_000, idleSettle(now + 1_000, idleSince = now, previousSeen = now), 0, context(),
        )
        assertNull(second.fired)
        assertTrue(second.swallowedOffline.isEmpty())
    }

    @Test
    fun 在线被挡住跨过最晚_事件结束后的检查点立即补() {
        // 睡觉 23:00–07:00：凌晨 01:00 还在线（前一晚在线被事件挡着），08:00 检查点 → 补
        val f = fixture(templates = listOf(template("sleep", 23 * 60, 7 * 60)))
        val now = at(hour = 8)
        val result = f.trigger.check(now, idleSettle(now, idleSince = now, previousSeen = at(hour = 1)), 0, context())
        assertEquals(TriggerDecision.FIRED_SEMI_FIXED_OVERDUE, result.decision)
        assertEquals("sleep", result.fired!!.semiFixedTemplateCode)
    }

    // ===== ③ 窗内 / due 判定 =====

    @Test
    fun 窗内且没发生过_发生它_并压过清单() {
        val f = fixture(
            templates = listOf(template("nap", 13 * 60, 15 * 60)),
            events = listOf(listEvent(id = 1, suitableTime = listOf(Period.AFTERNOON), summary = "想收拾房间")),
        )
        val now = at(hour = 14)
        val result = f.trigger.check(now, idleSettle(now, idleSince = now - 11 * MILLIS_PER_MINUTE, previousSeen = now - 1_000), 0, context())
        assertEquals(TriggerDecision.FIRED_SEMI_FIXED_IN_WINDOW, result.decision) // 空闲早达标了，仍先半固定
        assertEquals("nap", result.fired!!.semiFixedTemplateCode)
    }

    @Test
    fun 这轮已发生过_不重复发生() {
        // nap 13:00–15:00，今天 13:10 已发生过一条记录
        val f = fixture(
            templates = listOf(template("nap", 13 * 60, 15 * 60)),
            events = listOf(scheduledEvent("nap", occurredAt = at(hour = 13, minute = 10))),
        )
        val now = at(hour = 14)
        val result = f.trigger.check(now, idleSettle(now, idleSince = now, previousSeen = now - 1_000), 0, context())
        assertNull(result.fired)
        assertEquals(TriggerDecision.IDLE_BELOW_THRESHOLD, result.decision)
    }

    @Test
    fun due_过最晚补发的记录也算这轮发生过_不会反复补() {
        // meal 固定时刻 12:00，13:10 补发过（occurred_at 在窗口终点之后）→ 同一轮不再补
        val f = fixture(
            templates = listOf(template("meal", 12 * 60, 12 * 60)),
            events = listOf(scheduledEvent("meal", occurredAt = at(hour = 13, minute = 10))),
        )
        val now = at(hour = 14)
        val result = f.trigger.check(now, idleSettle(now, idleSince = now, previousSeen = at(hour = 13, minute = 59)), 0, context())
        assertNull(result.fired)
    }

    // ===== ④ 空闲挑清单 =====

    @Test
    fun 空闲到阈值_按时段挑一件并落库() {
        val f = fixture(
            events = listOf(
                listEvent(id = 1, suitableTime = listOf(Period.MORNING), summary = "晨跑"),
                listEvent(id = 2, suitableTime = listOf(Period.EVENING), summary = "夜读"),
                listEvent(id = 3, suitableTime = emptyList(), summary = "发呆"), // 空 = 不限
            ),
            random = Random(7),
        )
        val now = at(hour = 8) // MORNING
        val result = f.trigger.check(now, idleSettle(now, idleSince = now - 10 * MILLIS_PER_MINUTE, previousSeen = now - 1_000), 0, context())

        assertEquals(TriggerDecision.FIRED_FROM_LIST, result.decision)
        val fired = result.fired!!
        assertTrue(fired.summary in listOf("晨跑", "发呆")) // 只从含当前时段的里挑（均匀随机）
        assertEquals(EventStatus.IN_PROGRESS, fired.status)
        assertEquals(now, fired.occurredAt)
        assertEquals(EventStatus.IN_PROGRESS, f.events.byId(fired.id)!!.status) // 清单件已推进
    }

    @Test
    fun 空闲_阈值边界_恰好等于才算到() {
        val f = fixture(events = listOf(listEvent(id = 1, suitableTime = listOf(Period.MORNING), summary = "晨跑")))
        val now = at(hour = 8)

        val below = f.trigger.check(now, idleSettle(now, idleSince = now - 10 * MILLIS_PER_MINUTE + 1, previousSeen = now - 1_000), 0, context())
        assertEquals(TriggerDecision.IDLE_BELOW_THRESHOLD, below.decision)

        val atPoint = f.trigger.check(now, idleSettle(now, idleSince = now - 10 * MILLIS_PER_MINUTE, previousSeen = now - 1_000), 0, context())
        assertEquals(TriggerDecision.FIRED_FROM_LIST, atPoint.decision)
    }

    @Test
    fun 空闲达标_没有含当前时段的清单件_继续空闲() {
        val f = fixture(events = listOf(listEvent(id = 2, suitableTime = listOf(Period.EVENING), summary = "夜读")))
        val now = at(hour = 8) // MORNING，夜读不可挑
        val result = f.trigger.check(now, idleSettle(now, idleSince = now - 11 * MILLIS_PER_MINUTE, previousSeen = now - 1_000), 0, context())
        assertNull(result.fired)
        assertEquals(TriggerDecision.IDLE_NO_ELIGIBLE_EVENT, result.decision)
    }

    // ===== 不打断 =====

    @Test
    fun 有事件在跑_整个触发链不打断() {
        val f = fixture(
            templates = listOf(template("nap", 13 * 60, 15 * 60)),
            events = listOf(listEvent(id = 1, suitableTime = listOf(Period.AFTERNOON), summary = "想收拾房间")),
        )
        val now = at(hour = 14)
        val running = scheduledEvent("walk", occurredAt = now - 1_000, status = EventStatus.IN_PROGRESS)
        val settle = SettleResult(
            now = now,
            finished = emptyList(),
            expired = emptyList(),
            running = listOf(RunningEvent(running, StepPosition(0, 1_000))),
            idleSince = null,
            previousLastSeenAt = now - 1_000,
        )
        val result = f.trigger.check(now, settle, 0, context())
        assertEquals(TriggerDecision.BLOCKED_BY_RUNNING, result.decision)
        assertNull(result.fired)
        assertTrue(f.events.all().none { it.origin == EventOrigin.SCHEDULED }) // 没有半固定发生
        assertEquals(EventStatus.IN_LIST, f.events.byId(1)!!.status) // 清单件也没被启动
    }

    // ===== 没有角色组 =====

    @Test
    fun 还没有角色组_半固定不参与_照常走清单() {
        val eventsRepo = FakeEventRepository(listOf(listEvent(id = 1, suitableTime = listOf(Period.MORNING), summary = "晨跑")))
        val profilesRepo = FakeRuntimeProfileRepository(RuntimeProfile(id = 1, createdAt = day, updatedAt = day)) // activePackId = null
        val trigger = EventTrigger(eventsRepo, FakeSemiFixedTemplateRepository(), profilesRepo, durations)
        val now = at(hour = 8)
        val result = trigger.check(now, idleSettle(now, idleSince = now - 11 * MILLIS_PER_MINUTE, previousSeen = now - 1_000), 0, context())
        assertEquals(TriggerDecision.FIRED_FROM_LIST, result.decision)
    }
}
