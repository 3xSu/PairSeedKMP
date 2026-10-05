package com.pairseedkmp.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventTest {

    private val durations = BehaviorDurationSource { code -> if (code == "walk") 10 else null }

    private fun event(steps: List<EventStep>, durationSec: Int, occurredAt: Long?) = Event(
        id = 1,
        packId = 1,
        actorId = 1,
        sceneId = 1,
        summary = "散步",
        steps = steps,
        status = EventStatus.IN_LIST,
        origin = EventOrigin.SEED,
        occurredAt = occurredAt,
        durationSec = durationSec,
        expiresAt = 1_791_072_000_000L + 7 * 86_400_000L, // 生成 + 7 天（《数据设计》）
        createdAt = 1_791_072_000_000L,
        updatedAt = 1_791_072_000_000L,
    )

    @Test
    fun 时长加总与duration_sec校验() {
        val e = event(EventStepsJson.parse("""[{"action":"walk"},{"action":"wave"}]"""), durationSec = 15, occurredAt = null)
        assertTrue(e.durationMatchesSteps(durations)) // 10 + 兜底 5
        assertFalse(e.copy(durationSec = 12).durationMatchesSteps(durations))
    }

    @Test
    fun 结束时刻由occurredAt加时长算出() {
        val e = event(EventStepsJson.parse("""[{"action":"walk"}]"""), durationSec = 10, occurredAt = 1_000L)
        assertEquals(11_000L, e.endsAt)
        assertNull(e.copy(occurredAt = null).endsAt)
    }

    @Test
    fun 状态与来源code互转() {
        assertEquals(EventStatus.OCCURRED, EventStatus.fromCode("OCCURRED"))
        assertEquals(EventOrigin.SCHEDULED, EventOrigin.fromCode("SCHEDULED"))
        assertFailsWith<IllegalArgumentException> { EventStatus.fromCode("NOPE") }
        assertFailsWith<IllegalArgumentException> { EventOrigin.fromCode("NOPE") }
    }
}
