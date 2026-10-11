package com.pairseedkmp.data.seed_impl

import com.pairseedkmp.domain.seed.SeedMaterialSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 素材源：一言（hitokoto）——文艺句子（文学 / 诗词 / 网易云热评 / 影视台词）。
 *
 * 接口一次只回一句，取 [count] 句 = [count] 次顺序 GET；单次失败不弃整批（保留已取到的句子），
 * 全败才抛异常（生长回环按「采集失败」跳过该工具）。超时由工厂（[SeedMaterialHttpClientFactory]）统一配。
 */
class HitokotoMaterialSource(
    private val http: HttpClient,
    private val count: Int = DEFAULT_COUNT,
    private val categories: List<String> = DEFAULT_CATEGORIES,
) : SeedMaterialSource {

    override val label: String = "一言"

    override suspend fun fetch(): List<String> {
        val lines = mutableListOf<String>()
        var lastError: Throwable? = null
        repeat(count) {
            try {
                lines += fetchOne()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        if (lines.isEmpty() && lastError != null) {
            throw IllegalStateException("$label 素材获取失败：${lastError!!.message}", lastError)
        }
        return lines
    }

    private suspend fun fetchOne(): String {
        val response = http.get(BASE_URL) {
            url {
                categories.forEach { parameters.append("c", it) }
            }
        }
        if (!response.status.isSuccess()) throw IllegalStateException("$label HTTP ${response.status.value}")
        val text = (Json.parseToJsonElement(response.bodyAsText()).jsonObject["hitokoto"]
            ?: throw IllegalStateException("$label 响应缺少 hitokoto 字段"))
            .jsonPrimitive.content.trim()
        if (text.isEmpty()) throw IllegalStateException("$label 响应为空句子")
        return text
    }

    companion object {
        private const val BASE_URL = "https://v1.hitokoto.cn/"
        private const val DEFAULT_COUNT = 5

        /** 分类：d 文学 / i 诗词 / j 网易云热评 / h 影视（避开动画 / 游戏 / 抖机灵类）。 */
        private val DEFAULT_CATEGORIES = listOf("d", "i", "j", "h")
    }
}
