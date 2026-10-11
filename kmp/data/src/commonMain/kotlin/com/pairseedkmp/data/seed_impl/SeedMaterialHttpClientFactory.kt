package com.pairseedkmp.data.seed_impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

/**
 * 素材采集用的 HttpClient 工厂（04）：与 [com.pairseedkmp.data.ai_impl.LlmHttpClientFactory] 同款 CIO 引擎。
 *
 * 与 LLM 侧不同，素材采集没有 LlmCaller 那样的外层取消，**超时在这层配**：
 * 请求 10s / 连接 5s——超时抛出的是 Ktor 的 HTTP 超时异常（非 CancellationException），
 * 生长回环按「采集失败」记错误、跳过该工具（不误伤协程取消口径）。
 * 测试传 `HttpClient(MockEngine)`，不走本工厂。
 */
object SeedMaterialHttpClientFactory {
    fun create(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    }
}
