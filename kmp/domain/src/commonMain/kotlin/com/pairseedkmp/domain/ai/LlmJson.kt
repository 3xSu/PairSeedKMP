package com.pairseedkmp.domain.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * JSON 结果解析骨架（01）：模型原始文本 → JSON 对象；取不到 → null。
 * 各链路自己决定"解析失败算不算技术失败、要不要重试"（04 / 05 / 06）。
 *
 * 走法：剥 ```json / ``` 围栏 → 取首 `{` 到末 `}` → 宽松解析（多字段 / 瑕疵容忍）。
 */
object LlmJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 提取并解析 JSON 对象；不是合法 JSON 对象（或压根没有花括号）→ null。 */
    fun parse(text: String): JsonObject? {
        val trimmed = stripFences(text.trim())
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return try {
            json.parseToJsonElement(trimmed.substring(start, end + 1)) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** 剥围栏（```json / ```；剥不掉也无妨——花括号截取兜底）。 */
    private fun stripFences(text: String): String =
        text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
