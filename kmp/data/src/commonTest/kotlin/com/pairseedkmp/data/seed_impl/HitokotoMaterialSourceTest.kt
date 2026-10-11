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

/** 建一个接 MockEngine 的一言素材源；[requests] 非空时记录全部请求。 */
private fun hitokotoSource(
    count: Int = 5,
    requests: MutableList<HttpRequestData>? = null,
    handler: suspend MockRequestHandleScope.(callIndex: Int) -> HttpResponseData,
): HitokotoMaterialSource {
    var calls = 0
    val engine = MockEngine { request ->
        requests?.add(request)
        handler(calls++)
    }
    return HitokotoMaterialSource(HttpClient(engine), count = count)
}

class HitokotoMaterialSourceTest {

    @Test
    fun 正常_取满句数_分类参数正确() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val source = hitokotoSource(count = 5, requests = requests) { index ->
            respond("""{"hitokoto": "第${index + 1}句"}""", HttpStatusCode.OK, jsonHeaders)
        }

        val lines = source.fetch()

        assertEquals(listOf("第1句", "第2句", "第3句", "第4句", "第5句"), lines)
        assertEquals(5, requests.size)
        requests.forEach {
            assertEquals(listOf("d", "i", "j", "h"), it.url.parameters.getAll("c"))
            assertEquals("v1.hitokoto.cn", it.url.host)
        }
    }

    @Test
    fun 部分失败_保留已取到的句子() = runTest {
        val source = hitokotoSource(count = 3) { index ->
            if (index == 1) respond("boom", HttpStatusCode.InternalServerError)
            else respond("""{"hitokoto": "句子$index"}""", HttpStatusCode.OK, jsonHeaders)
        }

        assertEquals(listOf("句子0", "句子2"), source.fetch())
    }

    @Test
    fun 全部失败_抛异常() = runTest {
        val source = hitokotoSource(count = 2) { respond("boom", HttpStatusCode.InternalServerError) }

        val e = assertFailsWith<IllegalStateException> { source.fetch() }
        assertTrue(e.message!!.contains("一言"))
    }

    @Test
    fun 响应缺字段或空句_视为失败() = runTest {
        val missing = hitokotoSource(count = 1) { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        assertFailsWith<IllegalStateException> { missing.fetch() }

        val blank = hitokotoSource(count = 1) { respond("""{"hitokoto": "  "}""", HttpStatusCode.OK, jsonHeaders) }
        assertFailsWith<IllegalStateException> { blank.fetch() }
    }

    @Test
    fun 零句配置_不发请求_空表返回() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val source = hitokotoSource(count = 0, requests = requests) { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        assertTrue(source.fetch().isEmpty())
        assertEquals(0, requests.size)
    }
}
