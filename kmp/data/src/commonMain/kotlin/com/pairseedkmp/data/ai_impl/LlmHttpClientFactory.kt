package com.pairseedkmp.data.ai_impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * ApiKeyLlmClient 的 HttpClient 工厂（01）：**CIO 引擎**——commonMain 直接建，跨平台、iOS 就绪（OkHttp 仅 JVM / Android）。
 * 超时不在这层配（统一由 LlmCaller.withTimeout 取消请求）；非 2xx 默认不抛异常（expectSuccess = false），状态码由 client 自己映射。
 * 测试传 `HttpClient(MockEngine)`，不走本工厂。
 */
object LlmHttpClientFactory {
    fun create(): HttpClient = HttpClient(CIO)
}
