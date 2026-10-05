package com.pairseedkmp.domain.common

/**
 * 四时段（边界 2026-10-04 拍板，左闭右开 [start, end)）：
 * MORNING 06:00–12:00 / AFTERNOON 12:00–18:00 / EVENING 18:00–23:00 / NIGHT 23:00–06:00（跨天）。
 * NIGHT 的分钟区间 = [23:00, 24:00) ∪ [00:00, 06:00)，四段无缺口、不重叠。
 */
enum class Period(val code: String, private val startMinute: Int, private val endMinuteExclusive: Int) {
    MORNING("MORNING", 6 * 60, 12 * 60),
    AFTERNOON("AFTERNOON", 12 * 60, 18 * 60),
    EVENING("EVENING", 18 * 60, 23 * 60),
    NIGHT("NIGHT", 23 * 60, 6 * 60);

    /** 是否含"当天 00:00 起第 minute 分钟"（0..1439）。 */
    fun contains(minuteOfDay: Int): Boolean {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay 越界：$minuteOfDay" }
        return if (startMinute < endMinuteExclusive) {
            minuteOfDay >= startMinute && minuteOfDay < endMinuteExclusive
        } else {
            // 跨天（NIGHT）
            minuteOfDay >= startMinute || minuteOfDay < endMinuteExclusive
        }
    }

    companion object {
        fun fromCode(code: String): Period =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("未知时段 code：$code")

        /** 某分钟（0..1439）落在哪个时段（四段必有且仅有一段）。 */
        fun forMinuteOfDay(minuteOfDay: Int): Period =
            entries.first { it.contains(minuteOfDay) }
    }
}

/** events.suitable_time：半角逗号分隔多值（如 "MORNING,EVENING"）；NULL / 空 → 空表 = 不限。 */
fun parsePeriods(raw: String?): List<Period> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { Period.fromCode(it) }
}

/** 反向序列化：空表 → null（不限）。 */
fun List<Period>.toRawString(): String? =
    if (isEmpty()) null else joinToString(",") { it.code }
