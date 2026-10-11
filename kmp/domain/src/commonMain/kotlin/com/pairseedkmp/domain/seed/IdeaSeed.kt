package com.pairseedkmp.domain.seed

import com.pairseedkmp.domain.common.Period

/**
 * 灵感种子（《数据设计·种子记录》）：事件的发散起点。池子全局一份、不按角色组隔离、不设上限。
 * `keyword` 是种子唯一的业务字段（随种子进提示词）；其余字段是提示 / 挑选辅助。
 */
data class IdeaSeed(
    val id: Long,
    val keyword: String,
    val theme: String,
    /** 场景提示标签（库里是 JSON 数组；空表 ↔ NULL）。 */
    val sceneHintTags: List<String>,
    /** 建议触发时段；null = 不限。 */
    val suggestedTimeWindow: Period?,
    val relationshipPushTarget: Int?,
    val noveltyTarget: Int?,
    val sourceType: SeedSourceType,
    val qualityScore: Int?,
    /** 被挑中次数（挑选按次数反比加权）。 */
    val pickedCount: Int,
    /** 最近被挑中时间（Unix 毫秒）；没被挑过 null。 */
    val lastPickedAt: Long?,
    val isDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 种子来源（《数据设计》枚举）：人工补充 / AI 变体 / 预设。 */
enum class SeedSourceType(val code: String) {
    MANUAL("MANUAL"),
    AI_VARIANT("AI_VARIANT"),
    PRESET("PRESET");

    companion object {
        fun fromCode(code: String): SeedSourceType =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("未知种子来源 code：$code")
    }
}
