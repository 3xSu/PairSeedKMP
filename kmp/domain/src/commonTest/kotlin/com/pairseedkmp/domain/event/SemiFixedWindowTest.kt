package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.MILLIS_PER_DAY
import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.startOfLocalDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemiFixedWindowTest {

    private val day = 10L * MILLIS_PER_DAY // 任意一天（UTC 偏移 0 下测试）

    private fun at(dayOffset: Int = 0, hour: Int, minute: Int = 0) =
        day + dayOffset * MILLIS_PER_DAY + (hour * 60 + minute) * MILLIS_PER_MINUTE

    private fun template(earliest: Int, latest: Int) = SemiFixedEventTemplate(
        id = 1,
        packId = 1,
        templateCode = "t",
        nameCn = "测试",
        summary = "去测试",
        steps = EventStepsJson.parse("""[{"action":"walk"}]"""),
        windowEarliest = earliest,
        windowLatest = latest,
        createdAt = day,
        updatedAt = day,
    )

    private val sleep = template(23 * 60, 7 * 60) // 跨天 23:00–07:00
    private val meal = template(12 * 60, 13 * 60) // 普通 12:00–13:00
    private val lunchFixed = template(12 * 60, 12 * 60) // 固定时刻 12:00

    @Test
    fun 本地日起点() {
        assertEquals(day, startOfLocalDay(at(hour = 0), 0))
        assertEquals(day, startOfLocalDay(at(hour = 23, minute = 59), 0))
        // 东八区：day 00:00 UTC 是当地 08:00，其本地日起点 = day - 8 小时（前一天 16:00 UTC）
        assertEquals(day - 8 * 60 * MILLIS_PER_MINUTE, startOfLocalDay(at(hour = 0), 8 * 3600))
    }

    @Test
    fun 跨天窗口_清早落在昨天开的那一轮() {
        val now = at(hour = 1)
        val round = sleep.roundAt(now, 0)
        assertEquals(at(dayOffset = -1, hour = 23), round.startMillis)
        assertEquals(at(hour = 7), round.endMillis)
        assertTrue(round.contains(now))
        assertEquals(round.startMillis + MILLIS_PER_DAY, round.nextStartMillis)
    }

    @Test
    fun 跨天窗口_深夜是本天那一轮() {
        val now = at(hour = 23, minute = 30)
        val round = sleep.roundAt(now, 0)
        assertEquals(at(hour = 23), round.startMillis)
        assertEquals(at(dayOffset = 1, hour = 7), round.endMillis)
        assertTrue(round.contains(now))
    }

    @Test
    fun 跨天窗口_白天取最近一轮_已关() {
        val round = sleep.roundAt(at(hour = 15), 0)
        assertEquals(at(dayOffset = -1, hour = 23), round.startMillis) // 昨晚那轮
        assertFalse(round.contains(at(hour = 15)))
        assertTrue(round.endMillis <= at(hour = 15)) // 已关
    }

    @Test
    fun 普通窗_窗内与窗外() {
        assertTrue(meal.roundAt(at(hour = 12, minute = 30), 0).contains(at(hour = 12, minute = 30)))

        val after = meal.roundAt(at(hour = 14), 0) // 窗后：今天这轮，已关
        assertEquals(at(hour = 12), after.startMillis)
        assertTrue(after.endMillis <= at(hour = 14))

        val before = meal.roundAt(at(hour = 11), 0) // 今天没到点：昨天那轮
        assertEquals(at(dayOffset = -1, hour = 12), before.startMillis)
    }

    @Test
    fun 固定时刻_零长窗_到点即已关() {
        val atNoon = lunchFixed.roundAt(at(hour = 12), 0)
        assertEquals(at(hour = 12), atNoon.startMillis)
        assertEquals(atNoon.startMillis, atNoon.endMillis) // 零长
        assertFalse(atNoon.contains(at(hour = 12)))
        assertTrue(atNoon.endMillis <= at(hour = 12)) // now ≥ 最晚 = 已关

        assertEquals(at(dayOffset = -1, hour = 12), lunchFixed.roundAt(at(hour = 11), 0).startMillis) // 上午：昨天的固定时刻
    }
}
