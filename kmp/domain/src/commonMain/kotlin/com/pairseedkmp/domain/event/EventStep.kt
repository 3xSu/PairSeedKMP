package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.MILLIS_PER_SECOND
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 事件步骤（events.steps / semi_fixed_event_templates.steps 的元素）。
 * JSON 定稿信封：[{"action": "<基础动作 code>", "params": {…}}]
 * params 随动作定：「说」= speaker / text / duration_sec（时长按字数 ÷ 语速算）。
 */
@Serializable
data class EventStep(
    val action: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

/** steps JSON 解析 / 序列化（读库、写库共用一份配置）。 */
object EventStepsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): List<EventStep> = json.decodeFromString(raw)

    fun encode(steps: List<EventStep>): String = json.encodeToString(steps)
}

/** behavior_templates.duration_seconds 的查询口（域内只定端口，查库在数据层）。 */
fun interface BehaviorDurationSource {
    /** 查不到返回 null。 */
    fun durationSecondsFor(behaviorCode: String): Int?
}

/** 动作时长查不到时的兜底（与 behavior_templates.duration_seconds 建表默认值一致）。 */
const val DEFAULT_STEP_DURATION_SECONDS: Int = 5

/**
 * 单步时长（秒）：params.duration_sec 优先（定稿里只有「说」带），
 * 否则按动作 code 查 behavior_templates，查不到兜底 [DEFAULT_STEP_DURATION_SECONDS]。
 */
fun EventStep.durationSeconds(durations: BehaviorDurationSource): Int =
    params["duration_sec"]?.jsonPrimitive?.intOrNull
        ?: durations.durationSecondsFor(action)
        ?: DEFAULT_STEP_DURATION_SECONDS

/** 事件总时长 = 各步骤时长加总（与 events.duration_sec 对齐校验用）。 */
fun List<EventStep>.totalDurationSeconds(durations: BehaviorDurationSource): Int =
    sumOf { it.durationSeconds(durations) }

/** 步骤位置：第几步（0 起）+ 当步内已走毫秒（结算算"此刻跑到哪一步"，播放直接用）。 */
data class StepPosition(val index: Int, val elapsedInStepMillis: Long)

/**
 * 已走过 [elapsedMillis] 时落在第几步（结算"步骤位置"用）。
 * 时间轴 = 各步骤时长首尾相接；超过总时长（如 duration_sec 与步骤对不上）落在最后一步。
 * steps 为空（不该发生）→ 第 0 步、0 毫秒。
 */
fun List<EventStep>.stepPositionAt(elapsedMillis: Long, durations: BehaviorDurationSource): StepPosition {
    if (isEmpty()) return StepPosition(0, 0L)
    val elapsed = elapsedMillis.coerceAtLeast(0L)
    var start = 0L
    forEachIndexed { index, step ->
        val end = start + step.durationSeconds(durations) * MILLIS_PER_SECOND
        if (elapsed < end) return StepPosition(index, elapsed - start)
        start = end
    }
    val lastLength = last().durationSeconds(durations) * MILLIS_PER_SECOND
    val lastStart = start - lastLength
    // 超出总时长（步骤加总 < duration_sec 的尾巴）：停在最后一步末尾，当步已走夹在步长内
    return StepPosition(lastIndex, (elapsed - lastStart).coerceAtMost(lastLength))
}
