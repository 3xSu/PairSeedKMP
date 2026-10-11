package com.pairseedkmp.data.ai_impl

import com.pairseedkmp.data.ai.LlmApiMappers
import com.pairseedkmp.domain.ai.AiSettingsStore
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmErrorKind
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * API Key provider（01）：OpenAI 兼容 `POST {base}/v1/chat/completions`（base 不含 /v1）。
 *
 * - 配置与 Key **每次调用现读**（面板改完即时生效）；Key 只进 Authorization 头，不进日志
 * - 错误映射：401 / 403 / 404 → PERSISTENT；429 / 5xx / 超时 → TRANSIENT；其余 4xx → PERSISTENT；
 *   网络层异常 → TRANSIENT；信封不可解析 → TRANSIENT；content 空串 → 照常返回（业务按技术失败处理）
 * - 超时不在这层：LlmCaller 的 `withTimeout` 取消请求即断（取消异常原样上抛，不吞）
 */
class ApiKeyLlmClient(
    private val http: HttpClient,
    private val settingsStore: AiSettingsStore,
    private val secretStore: SecretStore,
    private val useJsonFormat: Boolean = true,
) : LlmClient {

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val settings = settingsStore.current()
        val baseUrl = settings.baseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) throw LlmException(LlmErrorKind.PERSISTENT, "API 基址未配置")
        val model = settings.model.trim()
        if (model.isEmpty()) throw LlmException(LlmErrorKind.PERSISTENT, "模型名未配置")
        val key = secretStore.get()?.takeIf { it.isNotBlank() }
            ?: throw LlmException(LlmErrorKind.PERSISTENT, "API Key 未配置")

        val body = LlmApiMappers.buildChatRequestBody(model, request, useJsonFormat)
        val response = try {
            http.post("$baseUrl/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e // LlmCaller 的 withTimeout 取消：原样上抛
        } catch (e: Throwable) {
            throw LlmException(LlmErrorKind.TRANSIENT, "网络异常：${e.message}", e)
        }

        if (!response.status.isSuccess()) {
            throw LlmException(mapStatusKind(response.status), "HTTP ${response.status.value}")
        }

        val content = LlmApiMappers.parseChatContent(response.bodyAsText())
            ?: throw LlmException(LlmErrorKind.TRANSIENT, "响应信封不可解析")
        return LlmResponse(content)
    }

    private fun mapStatusKind(status: HttpStatusCode): LlmErrorKind = when {
        status == HttpStatusCode.Unauthorized ||
            status == HttpStatusCode.Forbidden ||
            status == HttpStatusCode.NotFound -> LlmErrorKind.PERSISTENT

        status == HttpStatusCode.TooManyRequests ||
            status == HttpStatusCode.RequestTimeout ||
            status.value >= 500 -> LlmErrorKind.TRANSIENT

        status.value in 400..499 -> LlmErrorKind.PERSISTENT

        else -> LlmErrorKind.TRANSIENT
    }
}
