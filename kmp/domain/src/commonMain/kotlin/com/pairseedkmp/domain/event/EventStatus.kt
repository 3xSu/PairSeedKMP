package com.pairseedkmp.domain.event

/**
 * 事件五状态（events.status）：
 * 待确认 → 在清单 → 进行中 → 已发生 / 已拒。
 * 半固定发生记录不走前两步，落库即"进行中"。
 */
enum class EventStatus(val code: String) {
    PENDING_CONFIRMATION("PENDING_CONFIRMATION"),
    IN_LIST("IN_LIST"),
    IN_PROGRESS("IN_PROGRESS"),
    OCCURRED("OCCURRED"),
    REJECTED("REJECTED");

    companion object {
        fun fromCode(code: String): EventStatus =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("未知事件状态 code：$code")
    }
}
