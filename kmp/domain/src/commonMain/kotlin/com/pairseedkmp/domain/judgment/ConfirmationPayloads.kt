package com.pairseedkmp.domain.judgment

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** payload 来源：系统三支 / AI 冷读。 */
const val CONF_SOURCE_SYSTEM: String = "SYSTEM"
const val CONF_SOURCE_AI: String = "AI"

/**
 * 系统裁决的待确认 payload（`events.confirmation_payload` JSON v1；第 4 层出口「卡住的点」）：
 * `{ "source": "SYSTEM", "reason": "MISSING" | "RISK", "missing": ["…"]?, "risk": {…}? }`
 * - missing 项 = 机器码串：`step_code_not_in_table:<code>` / `soft_axis_missing:<角色 id>:<维度键>` /
 *   `scene_visibility_missing` / `axis_level_out_of_range:<维度>:<档>`（显示话术由 UI 组，不入库）；
 * - risk = `{ "value": R, "deviations": [ { "dim", "event_level", "tolerance", "delta", "score" } ] }`。
 * 判定物本身仍不落库（同 1.8）；payload 只为确认弹框与库内排查。
 */
fun systemConfirmationPayload(judgment: SystemJudgment): String {
    val uncertain = judgment.outcome as? JudgmentOutcome.Uncertain
        ?: throw IllegalArgumentException("只有待确认的结果才有 payload")
    return buildJsonObject {
        put("source", CONF_SOURCE_SYSTEM)
        when (val reason = uncertain.reason) {
            is UncertainReason.Missing -> {
                put("reason", "MISSING")
                putJsonArray("missing") { reason.items.forEach { add(JsonPrimitive(encodeMissing(it))) } }
            }

            is UncertainReason.Risk -> {
                put("reason", "RISK")
                putJsonObject("risk") {
                    put("value", reason.risk.value)
                    putJsonArray("deviations") {
                        reason.risk.deviations.forEach { deviation ->
                            add(
                                buildJsonObject {
                                    put("dim", deviation.dim.name)
                                    put("event_level", deviation.eventLevel)
                                    put("tolerance", deviation.tolerance)
                                    put("delta", deviation.delta)
                                    put("score", deviation.score)
                                },
                            )
                        }
                    }
                }
            }

            // 防御：AI 的疑问走 aiConfirmationPayload（系统三支不产这个变体）
            is UncertainReason.Ai -> throw IllegalArgumentException("AI 的疑问走 aiConfirmationPayload")
        }
    }.toString()
}

/**
 * AI 冷读的待确认 payload（形状 v1，06 步骤 4）：
 * `{ "source": "AI", "reason": "<AI 的疑问原话>" }`——弹框显示原话；
 * 解析失败两次的 fail-safe 也用这个形状（reason = 固定话术）。
 */
fun aiConfirmationPayload(reason: String): String = buildJsonObject {
    put("source", CONF_SOURCE_AI)
    put("reason", reason)
}.toString()

private fun encodeMissing(item: MissingMaterial): String = when (item) {
    is MissingMaterial.StepCodeNotInTable -> "step_code_not_in_table:${item.code}"
    is MissingMaterial.SoftAxisMissing -> "soft_axis_missing:${item.characterId}:${item.dim.axisKey}"
    MissingMaterial.SceneVisibilityMissing -> "scene_visibility_missing"
    is MissingMaterial.AxisLevelOutOfRange -> "axis_level_out_of_range:${item.dim.name}:${item.level}"
}
