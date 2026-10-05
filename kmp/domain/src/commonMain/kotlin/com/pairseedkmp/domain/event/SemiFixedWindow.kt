package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.MILLIS_PER_DAY
import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.MINUTES_PER_DAY
import com.pairseedkmp.domain.common.startOfLocalDay

/**
 * 半固定模板的"一轮"：由 now 推出来的绝对时间区间 [startMillis, endMillis)。
 * 一轮 = 模板窗口在某天的实例——固定时刻（早 == 晚）= 零长；跨天（早 > 晚）终点落次日。
 * 轮按天推进；due 判定区间 = [start, nextStart)（见 [EventTrigger] 的注释）。
 */
data class SemiFixedRound(
    val templateCode: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    /** 下一轮起点：轮按天推进。 */
    val nextStartMillis: Long
        get() = startMillis + MILLIS_PER_DAY

    /** [epochMillis] 是否在窗口内（左闭右开；固定时刻恒 false）。 */
    fun contains(epochMillis: Long): Boolean = epochMillis >= startMillis && epochMillis < endMillis
}

/** 模板在 [dayStart]（本地日 00:00）那天的窗口实例。 */
private fun SemiFixedEventTemplate.instanceOn(dayStart: Long): SemiFixedRound {
    val start = dayStart + windowEarliest * MILLIS_PER_MINUTE
    val lengthMinutes =
        if (windowEarliest > windowLatest) MINUTES_PER_DAY - windowEarliest + windowLatest
        else windowLatest - windowEarliest
    return SemiFixedRound(templateCode, start, start + lengthMinutes * MILLIS_PER_MINUTE)
}

/**
 * now 对应的"最近一轮"（只认最近一轮，不追更早历史）：
 * 窗口开着就是本轮（跨天窗口的清早，落在昨天开始的那一轮里）；已关取最近一轮；今天还没到点也取昨天的（已关）。
 */
fun SemiFixedEventTemplate.roundAt(now: Long, utcOffsetSeconds: Int): SemiFixedRound {
    val todayStart = startOfLocalDay(now, utcOffsetSeconds)
    val today = instanceOn(todayStart)
    val yesterday = instanceOn(todayStart - MILLIS_PER_DAY)
    return when {
        yesterday.contains(now) -> yesterday // 跨天窗口：清早还在昨天开的那轮里
        today.contains(now) -> today
        today.endMillis <= now -> today // 今天这轮已关
        else -> yesterday // 今天还没到最早点：最近一轮是昨天的
    }
}
