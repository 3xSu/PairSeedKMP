package com.pairseedkmp.data.ai

import com.pairseedkmp.domain.ai.LlmRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// 领域 LlmRequest ↔ OpenAI 兼容 chat completions 载荷的映射（手搓 JSON：不引 ktor-serialization / content-negotiation，避开版本耦合）。

internal object LlmApiMappers {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 组请求体：`{model, messages[system, user], temperature, response_format?}`。
     * `response_format`（json_object）仅在「期望 JSON 且开关开」时带——部分兼容实现不认，作能力开关（01）。
     */
    fun buildChatRequestBody(model: String, request: LlmRequest, useJsonFormat: Boolean): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", request.system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", request.user)
                }
            }
            put("temperature", request.temperature)
            if (request.expectJson && useJsonFormat) {
                putJsonObject("response_format") {
                    put("type", "json_object")
                }
            }
        }
        return body.toString()
    }

    /**
     * 取 `choices[0].message.content`。
     * 信封不是 JSON / 缺段 / content 为 null → null（调用方按瞬时异常处理，重试有意义）；
     * content 是空串 → 返回空串（信封合法、模型没给内容——业务按「空返回」技术失败处理，见 05 失败矩阵）。
     */
    fun parseChatContent(responseBody: String): String? = try {
        val root = json.parseToJsonElement(responseBody) as? JsonObject ?: return null
        root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }
}
