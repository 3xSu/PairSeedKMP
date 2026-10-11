package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.Period
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 事件客观属性（05 生成 → 06 判定读）：语言 / 情感两维档位（1–4）。
 * 物理维不在这里——由步骤动作码的码表推（2026-10-07 修订）。
 * `verbalDirectness` null = 不适用：整件事没有人说话（可空性由程序按「说」步判定并固化，事实固化）。
 */
data class ComparisonAxes(val verbalDirectness: Int?, val emotionVisibility: Int) {
    /** 落库 JSON（events.comparison_axes）：只放有值的维（verbal 为 null 时省略该键）。 */
    fun encodeJson(): String = buildJsonObject {
        verbalDirectness?.let { put("verbal_directness", it) }
        put("emotion_visibility", emotionVisibility)
    }.toString()
}

/**
 * 候选事件（05 产出 → 交 06 判定再落库；05 自己不落库）。
 * - [steps]：「说」步的 `duration_sec` 已由程序算好（字数 ÷ 语速）；
 * - [afterEventId] 已在复检时按清单编号解析成事件 id（接续指认）；
 * - [suitableTime] 空 = 不限。
 */
data class EventCandidate(
    val packId: Long,
    val actorId: Long,
    val targetId: Long?,
    val sceneId: Long,
    val summary: String,
    val steps: List<EventStep>,
    val axes: ComparisonAxes,
    val suitableTime: List<Period>,
    val afterEventId: Long? = null,
    val sourceSeedId: Long? = null,
) {
    /** 总时长（步骤加总；「说」步已带程序算的 duration_sec，其余查动作库）。 */
    fun durationSeconds(durations: BehaviorDurationSource): Int = steps.totalDurationSeconds(durations)
}
