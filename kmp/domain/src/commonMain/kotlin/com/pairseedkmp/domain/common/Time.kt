package com.pairseedkmp.domain.common

// 时间：全项目统一用 Unix 毫秒表示（见《数据设计》）。

const val MILLIS_PER_SECOND: Long = 1_000L
const val MILLIS_PER_MINUTE: Long = 60 * MILLIS_PER_SECOND
const val MILLIS_PER_DAY: Long = 24 * 60 * MILLIS_PER_MINUTE

/** 一天的分钟数。 */
const val MINUTES_PER_DAY: Int = 24 * 60

/**
 * 某时刻在本地时区里的"当天 00:00 起分钟数"（0..1439）。
 *
 * @param utcOffsetSeconds 本地时区相对 UTC 的偏移（东八区 = 8 * 3600；东正西负），
 *   由调用方从平台取（域层不认识时区库）；负数时间戳也安全。
 */
fun minuteOfDay(epochMillis: Long, utcOffsetSeconds: Int): Int {
    val localMillis = epochMillis + utcOffsetSeconds * MILLIS_PER_SECOND
    val rough = localMillis % MILLIS_PER_DAY
    val normalized = (rough + MILLIS_PER_DAY) % MILLIS_PER_DAY
    return (normalized / MILLIS_PER_MINUTE).toInt()
}

/**
 * 某时刻所在本地日的 00:00（Unix 毫秒）——半固定轮窗口按"天"定位实例用。
 *
 * @param utcOffsetSeconds 同 [minuteOfDay]；负数时间戳也安全。
 */
fun startOfLocalDay(epochMillis: Long, utcOffsetSeconds: Int): Long {
    val localMillis = epochMillis + utcOffsetSeconds * MILLIS_PER_SECOND
    return localMillis.floorDiv(MILLIS_PER_DAY) * MILLIS_PER_DAY - utcOffsetSeconds * MILLIS_PER_SECOND
}
