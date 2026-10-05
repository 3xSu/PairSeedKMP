package com.pairseedkmp.domain.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PeriodTest {

    @Test
    fun 时段边界归属() {
        // 每个边界的"起点前一分钟 / 起点"（左闭右开）
        assertEquals(Period.NIGHT, Period.forMinuteOfDay(0))        // 00:00
        assertEquals(Period.NIGHT, Period.forMinuteOfDay(359))      // 05:59
        assertEquals(Period.MORNING, Period.forMinuteOfDay(360))    // 06:00
        assertEquals(Period.MORNING, Period.forMinuteOfDay(719))    // 11:59
        assertEquals(Period.AFTERNOON, Period.forMinuteOfDay(720))  // 12:00
        assertEquals(Period.AFTERNOON, Period.forMinuteOfDay(1079)) // 17:59
        assertEquals(Period.EVENING, Period.forMinuteOfDay(1080))   // 18:00
        assertEquals(Period.EVENING, Period.forMinuteOfDay(1379))   // 22:59
        assertEquals(Period.NIGHT, Period.forMinuteOfDay(1380))     // 23:00
        assertEquals(Period.NIGHT, Period.forMinuteOfDay(1439))     // 23:59
    }

    @Test
    fun 全天无缺口_每分钟后恰好一个时段() {
        for (minute in 0 until MINUTES_PER_DAY) {
            assertEquals(1, Period.entries.count { it.contains(minute) }, "第 $minute 分钟")
        }
    }

    @Test
    fun 时刻换算_按时区偏移() {
        // 2026-10-04 00:00:00 UTC
        val epoch = 1_791_072_000_000L
        assertEquals(0, minuteOfDay(epoch, utcOffsetSeconds = 0))
        assertEquals(480, minuteOfDay(epoch, utcOffsetSeconds = 8 * 3600))   // 东八区 08:00
        assertEquals(1140, minuteOfDay(epoch, utcOffsetSeconds = -5 * 3600)) // 西五区 前一天 19:00
        // 负数时间戳安全
        assertEquals(1439, minuteOfDay(-1L, utcOffsetSeconds = 0))
    }

    @Test
    fun suitable_time解析() {
        assertEquals(emptyList(), parsePeriods(null))
        assertEquals(emptyList(), parsePeriods(""))
        assertEquals(listOf(Period.MORNING, Period.EVENING), parsePeriods("MORNING,EVENING"))
        assertEquals(listOf(Period.NIGHT), parsePeriods(" NIGHT "))
    }

    @Test
    fun suitable_time序列化() {
        assertEquals("MORNING,EVENING", listOf(Period.MORNING, Period.EVENING).toRawString())
        assertNull(emptyList<Period>().toRawString())
    }

    @Test
    fun 未知时段code报错() {
        assertFailsWith<IllegalArgumentException> { Period.fromCode("NOON") }
    }
}
