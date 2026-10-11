package com.pairseedkmp.data.seed_impl

import com.pairseedkmp.domain.seed.SeedMaterialSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 素材源：知乎日报——当日约 10 条「好奇心 / 生活百态」标题，一次 GET 就够一批素材。
 *
 * 注意：该接口无官方文档承诺（长期存活但无契约）；失败口径同其他采集源——抛异常由生长回环跳过该工具。
 * 超时由工厂（[SeedMaterialHttpClientFactory]）统一配。
 */
class ZhihuDailyMaterialSource(
    private val http: HttpClient,
    private val limit: Int = DEFAULT_LIMIT,
) : SeedMaterialSource {

    override val label: String = "知乎日报"

    override suspend fun fetch(): List<String> {
        val response = try {
            http.get(URL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("$label 素材获取失败：${e.message}", e)
        }
        if (!response.status.isSuccess()) throw IllegalStateException("$label HTTP ${response.status.value}")

        val root = try {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("$label 响应不可解析", e)
        }
        val stories = (root["stories"] as? JsonArray)?.jsonArray ?: return emptyList()
        return stories
            .mapNotNull { story ->
                ((story as? JsonObject)?.get("title") as? JsonPrimitive)
                    ?.content?.trim()?.takeIf { it.isNotEmpty() }
            }
            .take(limit)
    }

    companion object {
        private const val URL = "https://news-at.zhihu.com/api/4/news/latest"
        private const val DEFAULT_LIMIT = 10
    }
}
