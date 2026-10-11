package com.pairseedkmp.data.seed_impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/** 建一个接 MockEngine 的知乎日报素材源；[requests] 非空时记录全部请求。 */
private fun zhihuSource(
    limit: Int = 10,
    requests: MutableList<HttpRequestData>? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): ZhihuDailyMaterialSource {
    val engine = MockEngine { request ->
        requests?.add(request)
        handler(request)
    }
    return ZhihuDailyMaterialSource(HttpClient(engine), limit = limit)
}

class ZhihuDailyMaterialSourceTest {

    private fun storiesJson(titles: List<String>): String =
        """{"date":"20261011","stories":[${titles.joinToString(",") { """{"title":"$it"}""" }}]}"""

    @Test
    fun 正常_解析标题_按上限截取() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val source = zhihuSource(requests = requests) {
            respond(storiesJson((1..12).map { "标题$it" }), HttpStatusCode.OK, jsonHeaders)
        }

        val lines = source.fetch()

        assertEquals((1..10).map { "标题$it" }, lines)
        assertEquals(1, requests.size)
        assertEquals("https://news-at.zhihu.com/api/4/news/latest", requests.single().url.toString())
    }

    @Test
    fun 跳过缺失与空标题() = runTest {
        val source = zhihuSource {
            respond(
                """{"stories":[{"title":"有效"},{},"bad",{"title":"  "},{"title":"也有效"}]}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        assertEquals(listOf("有效", "也有效"), source.fetch())
    }

    @Test
    fun 无stories字段_空表返回() = runTest {
        val source = zhihuSource { respond("""{"date":"20261011"}""", HttpStatusCode.OK, jsonHeaders) }

        assertTrue(source.fetch().isEmpty())
    }

    @Test
    fun 非2xx_抛异常() = runTest {
        val source = zhihuSource { respond("boom", HttpStatusCode.TooManyRequests) }

        val e = assertFailsWith<IllegalStateException> { source.fetch() }
        assertTrue(e.message!!.contains("知乎日报"))
    }

    @Test
    fun 坏响应体_抛异常() = runTest {
        val source = zhihuSource { respond("不是 JSON", HttpStatusCode.OK, jsonHeaders) }

        assertFailsWith<IllegalStateException> { source.fetch() }
    }
}
