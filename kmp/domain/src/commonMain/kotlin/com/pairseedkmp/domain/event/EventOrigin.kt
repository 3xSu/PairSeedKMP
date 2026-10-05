package com.pairseedkmp.domain.event

/**
 * 事件来源（events.origin）：
 * SEED 种子生长 / SCHEDULED 半固定模板（发生记录）/ PRESET 预设（用户直接建的）。
 */
enum class EventOrigin(val code: String) {
    SEED("SEED"),
    SCHEDULED("SCHEDULED"),
    PRESET("PRESET");

    companion object {
        fun fromCode(code: String): EventOrigin =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("未知事件来源 code：$code")
    }
}
