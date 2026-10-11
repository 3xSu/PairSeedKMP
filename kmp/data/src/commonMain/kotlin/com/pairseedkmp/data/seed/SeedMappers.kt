package com.pairseedkmp.data.seed

import com.pairseedkmp.data.Idea_seeds
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.seed.IdeaSeed
import com.pairseedkmp.domain.seed.SeedSourceType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// 生成行 ↔ 领域模型的映射（场景标签按 JSON 数组存取、枚举按 code、时间 Unix 毫秒）。

internal fun Idea_seeds.toDomain(): IdeaSeed = IdeaSeed(
    id = id,
    keyword = keyword,
    theme = theme,
    sceneHintTags = decodeSceneHintTags(scene_hint_tags),
    suggestedTimeWindow = suggested_time_window?.let { Period.fromCode(it) },
    relationshipPushTarget = relationship_push_target?.toInt(),
    noveltyTarget = novelty_target?.toInt(),
    sourceType = SeedSourceType.fromCode(source_type),
    qualityScore = quality_score?.toInt(),
    pickedCount = picked_count.toInt(),
    lastPickedAt = last_picked_at,
    isDeleted = is_deleted != 0L,
    createdAt = created_at,
    updatedAt = updated_at,
)

private val tagsJson = Json

/** 场景提示标签：空表 → NULL；否则 JSON 字符串数组（如 `["kitchen"]`）。 */
internal fun encodeSceneHintTags(tags: List<String>): String? =
    if (tags.isEmpty()) null else tagsJson.encodeToString(ListSerializer(String.serializer()), tags)

/** 读侧容错：坏 JSON → 空表（不炸整池读取）。 */
internal fun decodeSceneHintTags(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        tagsJson.decodeFromString(ListSerializer(String.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }
}
