package com.pairseedkmp.domain.seed

import com.pairseedkmp.domain.dedup.DedupType
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.dedup.normalize
import com.pairseedkmp.domain.repository.SeedRepository

/** 人为补充的结果。 */
sealed interface SupplementResult {
    /** 无重复：已入库（`source_type` = MANUAL）。 */
    data class Added(val seed: IdeaSeed) : SupplementResult

    /** 重复：不入库，提示含已存在的相似词（工作链路 3.2）。 */
    data class Duplicate(val type: DedupType, val matchedKeyword: String) : SupplementResult

    /** 空白 / 纯标点（规范化后为空）：不入库。 */
    data object Blank : SupplementResult
}

/**
 * 人为补充种子（《工作链路·种子池生长链路》3）：开局手填一批的入口，不用 AI。
 * 查重（短词：规范化 → 精确 → 编辑距离）→ 重复不入库并提示相似词；无重复入库。
 * 人为补充不影响生长节奏（不记生长时间）。
 */
class SeedSupplement(
    private val repository: SeedRepository,
    private val deduplicator: Deduplicator,
) {
    /**
     * 补充一颗：不重复则入库（[theme] 由调用方给，可空串）。
     * 空白 / 纯标点 → [SupplementResult.Blank]；重复 → [SupplementResult.Duplicate]（含相似词与类型）。
     */
    fun add(keyword: String, theme: String, now: Long): SupplementResult {
        if (normalize(keyword).isEmpty()) return SupplementResult.Blank

        val hit = deduplicator.shortWord(keyword, repository.all().map { it.keyword })
        if (hit.isDuplicate) {
            return SupplementResult.Duplicate(hit.type!!, hit.matchedText!!)
        }

        val seed = IdeaSeed(
            id = 0,
            keyword = keyword,
            theme = theme,
            sceneHintTags = emptyList(),
            suggestedTimeWindow = null,
            relationshipPushTarget = null,
            noveltyTarget = null,
            sourceType = SeedSourceType.MANUAL,
            qualityScore = null,
            pickedCount = 0,
            lastPickedAt = null,
            isDeleted = false,
            createdAt = now,
            updatedAt = now,
        )
        return SupplementResult.Added(seed.copy(id = repository.insert(seed)))
    }

    /** 前缀 / 同字搜索（边输边查、防重复输入用）。 */
    fun search(fragment: String): List<IdeaSeed> = repository.search(fragment)
}
