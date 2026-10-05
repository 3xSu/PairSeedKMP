package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.Period

/** 确认回答（user_confirmations.answer）。 */
enum class ConfirmationAnswer(val code: String) {
    YES("YES"),
    NO("NO");

    companion object {
        fun fromCode(code: String): ConfirmationAnswer =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("未知确认回答 code：$code")
    }
}

/**
 * 确认记录（user_confirmations 表）：用户在历史页答过的每一次。
 * 只插不删；改答 = 再插一条，按 answered_at 最新生效。
 */
data class UserConfirmation(
    val id: Long,
    val eventId: Long,
    /** 这次回答针对的时段。 */
    val answeredPeriod: Period,
    val answer: ConfirmationAnswer,
    /** 答的时刻（Unix 毫秒）；兼作 created_at。 */
    val answeredAt: Long,
)
