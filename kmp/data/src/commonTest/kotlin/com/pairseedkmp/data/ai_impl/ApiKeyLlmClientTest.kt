package com.pairseedkmp.data.ai_impl

import com.pairseedkmp.domain.ai.AiMode
import com.pairseedkmp.domain.ai.AiSettings
import com.pairseedkmp.domain.ai.AiSettingsStore
import com.pairseedkmp.domain.ai.LlmErrorKind
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.ai.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

private class FakeSettingsStore(
    private var settings: AiSettings = AiSettings(
        mode = AiMode.API_KEY,
        baseUrl = "https://api.test.local",
        model = "test-model",
    ),
) : AiSettingsStore {
    override suspend fun current(): AiSettings = settings
    override suspend fun update(settings: AiSettings) {
        this.settings = settings
    }
}

private class FakeSecretStore(private var value: String? = "sk-test") : SecretStore {
    override suspend fun get(): String? = value
    override suspend fun set(value: String) {
        this.value = value
    }

    override suspend fun clear() {
        value = null
    }
}

private val genRequest = LlmRequest(
    system = "设定",
    user = "生成一件日常小事",
    temperature = 0.9,
    expectJson = true,
    task = LlmTask.EVENT_GROW,
)

private const val OK_BODY =
    """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"{\"summary\":\"一起做饭\"}"}}]}"""

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/**
 * 建一个接 MockEngine 的 client；[captured] 非空时记录最后一次请求的 url / method / Authorization / body。
 */
private fun mockClient(
    settings: AiSettings = AiSettings(mode = AiMode.API_KEY, baseUrl = "https://api.test.local", model = "test-model"),
    key: String? = "sk-test",
    useJsonFormat: Boolean = true,
    captured: MutableMap<String, String>? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): ApiKeyLlmClient {
    val engine = MockEngine { request ->
        captured?.let {
            it["url"] = request.url.toString()
            it["method"] = request.method.value
            it["auth"] = request.headers[HttpHeaders.Authorization].orEmpty()
            it["body"] = request.body.toByteArray().decodeToString()
        }
        handler(request)
    }
    return ApiKeyLlmClient(HttpClient(engine), FakeSettingsStore(settings), FakeSecretStore(key), useJsonFormat)
}

class ApiKeyLlmClientTest {

    @Test
    fun 成功_返回content文本() = runTest {
        val client = mockClient { respond(content = OK_BODY, headers = jsonHeaders) }
        assertEquals("""{"summary":"一起做饭"}""", client.complete(genRequest).text)
    }

    @Test
    fun 请求形状_地址方法头与载荷() = runTest {
        val captured = mutableMapOf<String, String>()
        val client = mockClient(captured = captured) { respond(content = OK_BODY, headers = jsonHeaders) }
        client.complete(genRequest)

        assertEquals("https://api.test.local/v1/chat/completions", captured["url"])
        assertEquals(HttpMethod.Post.value, captured["method"])
        assertEquals("Bearer sk-test", captured["auth"])

        val body = Json.parseToJsonElement(captured.getValue("body")).jsonObject
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray
        assertEquals(
            listOf("system", "user"),
            messages.map { it.jsonObject.getValue("role").jsonPrimitive.content },
        )
        assertEquals("设定", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("生成一件日常小事", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(0.9, body.getValue("temperature").jsonPrimitive.double)
        assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun 基址带尾斜杠_也能拼出正确地址() = runTest {
        val captured = mutableMapOf<String, String>()
        val settings = AiSettings(mode = AiMode.API_KEY, baseUrl = "https://api.test.local/", model = "test-model")
        val client = mockClient(settings = settings, captured = captured) {
            respond(content = OK_BODY, headers = jsonHeaders)
        }
        client.complete(genRequest)
        assertEquals("https://api.test.local/v1/chat/completions", captured["url"])
    }

    @Test
    fun response_format能力开关_关掉则不带() = runTest {
        val captured = mutableMapOf<String, String>()
        val client = mockClient(useJsonFormat = false, captured = captured) {
            respond(content = OK_BODY, headers = jsonHeaders)
        }
        client.complete(genRequest)
        val body = Json.parseToJsonElement(captured.getValue("body")).jsonObject
        assertTrue("response_format" !in body, "开关关掉时不应带 response_format")
    }

    @Test
    fun 不期望JSON时_不带response_format() = runTest {
        val captured = mutableMapOf<String, String>()
        val client = mockClient(captured = captured) { respond(content = OK_BODY, headers = jsonHeaders) }
        client.complete(genRequest.copy(expectJson = false))
        val body = Json.parseToJsonElement(captured.getValue("body")).jsonObject
        assertTrue("response_format" !in body)
    }

    @Test
    fun 错误映射_持续类与瞬时类() = runTest {
        val cases = listOf(
            HttpStatusCode.Unauthorized to LlmErrorKind.PERSISTENT,
            HttpStatusCode.Forbidden to LlmErrorKind.PERSISTENT,
            HttpStatusCode.NotFound to LlmErrorKind.PERSISTENT,
            HttpStatusCode.BadRequest to LlmErrorKind.PERSISTENT, // 其余 4xx 按持续
            HttpStatusCode.TooManyRequests to LlmErrorKind.TRANSIENT,
            HttpStatusCode.InternalServerError to LlmErrorKind.TRANSIENT,
            HttpStatusCode.BadGateway to LlmErrorKind.TRANSIENT,
        )
        for ((status, kind) in cases) {
            val client = mockClient { respond(content = "{}", status = status, headers = jsonHeaders) }
            val e = assertFailsWith<LlmException> { client.complete(genRequest) }
            assertEquals(kind, e.kind, "status=$status 映射错")
        }
    }

    @Test
    fun Key未配置_持续错误() = runTest {
        val client = mockClient(key = null) { respond(content = OK_BODY, headers = jsonHeaders) }
        val e = assertFailsWith<LlmException> { client.complete(genRequest) }
        assertEquals(LlmErrorKind.PERSISTENT, e.kind)
    }

    @Test
    fun 基址未配置_持续错误() = runTest {
        val settings = AiSettings(mode = AiMode.API_KEY, baseUrl = "  ", model = "test-model")
        val client = mockClient(settings = settings) { respond(content = OK_BODY, headers = jsonHeaders) }
        val e = assertFailsWith<LlmException> { client.complete(genRequest) }
        assertEquals(LlmErrorKind.PERSISTENT, e.kind)
    }

    @Test
    fun content空串_照常返回空串() = runTest {
        val body = """{"choices":[{"message":{"role":"assistant","content":""}}]}"""
        val client = mockClient { respond(content = body, headers = jsonHeaders) }
        assertEquals("", client.complete(genRequest).text)
    }

    @Test
    fun 信封不可解析_瞬时错误() = runTest {
        val client = mockClient { respond(content = "这不是 JSON", headers = jsonHeaders) }
        val e = assertFailsWith<LlmException> { client.complete(genRequest) }
        assertEquals(LlmErrorKind.TRANSIENT, e.kind)
    }

    @Test
    fun 网络层异常_瞬时错误() = runTest {
        val client = mockClient { throw IllegalStateException("连接被重置") }
        val e = assertFailsWith<LlmException> { client.complete(genRequest) }
        assertEquals(LlmErrorKind.TRANSIENT, e.kind)
    }
}
