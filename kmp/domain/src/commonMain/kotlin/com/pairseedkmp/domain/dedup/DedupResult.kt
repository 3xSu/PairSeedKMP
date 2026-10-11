package com.pairseedkmp.domain.dedup

/**
 * 重复类型（《工作链路·查重链路》1.2 词表）。
 * 短词 L3「语义近义」（embedding）后置留位——实现时在这里加一支。
 */
enum class DedupType {
    /** 完全重复：规范化后精确命中（第一闸，短词 / 长文共用）。 */
    EXACT,

    /** 近义重复（字形）：短词 L2 编辑距离相似度命中。 */
    CHAR_SIMILAR,

    /** 同一件事：长文分支（AI 冷读目标列表）。 */
    SAME_THING,
}

/**
 * 查重出口（链路共用契约）：是否重复 + 重复类型 + 命中的对象。
 * 不变式：`isDuplicate = true` ⇔ `type` / `matchedIndex` / `matchedText` 非空。
 */
data class DedupResult(
    val isDuplicate: Boolean,
    val type: DedupType?,
    /** 命中目标在调用方列表里的下标（0 起）。 */
    val matchedIndex: Int?,
    /** 命中目标原文（**未规范化**；反馈进提示词 / 提示用户用）。 */
    val matchedText: String?,
) {
    companion object {
        /** 无重复（放行）。 */
        val NONE = DedupResult(false, null, null, null)

        /** 命中一条。 */
        fun hit(type: DedupType, index: Int, text: String): DedupResult =
            DedupResult(isDuplicate = true, type = type, matchedIndex = index, matchedText = text)
    }
}
