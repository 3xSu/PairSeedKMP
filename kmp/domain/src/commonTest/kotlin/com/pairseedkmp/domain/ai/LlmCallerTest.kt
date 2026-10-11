package com.pairseedkmp.domain.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 假 client：按"第几次调用"（0 起）回放结果；记录总调用次数。 */
private class FakeLlmClient(
    private val onComplete: suspend (Int) -> LlmResponse,
) : LlmClient {
    var calls = 0
        private set

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val n = calls
        calls++
        return onComplete(n)
    }
}

class LlmCallerTest {

    private val request = LlmRequest(
        system = "角色设定",
        user = "生成一件日常小事",
        temperature = 0.9,
        expectJson = true,
        task = LlmTask.EVENT_GROW,
    )

    @Test
    fun 成功直接返回_不重试() = runTest {
        val client = FakeLlmClient { LlmResponse("ok") }
        assertEquals("ok", LlmCaller(client).complete(request).text)
        assertEquals(1, client.calls)
    }

    @Test
    fun 瞬时错误重试后成功() = runTest {
        val client = FakeLlmClient { n ->
            if (n < 2) throw LlmException(LlmErrorKind.TRANSIENT, "断连") else LlmResponse("ok")
        }
        assertEquals("ok", LlmCaller(client).complete(request).text)
        assertEquals(3, client.calls) // 失败 2 次 + 成功 1 次
    }

    @Test
    fun 瞬时错误重试上限用尽后抛出() = runTest {
        val client = FakeLlmClient { throw LlmException(LlmErrorKind.TRANSIENT, "断连") }
        val e = assertFailsWith<LlmException> { LlmCaller(client).complete(request) }
        assertEquals(LlmErrorKind.TRANSIENT, e.kind)
        assertEquals(3, client.calls) // 初次 + 重试 ≤2
    }

    @Test
    fun 持续错误直接上抛_不重试() = runTest {
        val client = FakeLlmClient { throw LlmException(LlmErrorKind.PERSISTENT, "Key 无效") }
        val e = assertFailsWith<LlmException> { LlmCaller(client).complete(request) }
        assertEquals(LlmErrorKind.PERSISTENT, e.kind)
        assertEquals(1, client.calls)
    }

    @Test
    fun 超时按瞬时处理_重试用尽后抛出() = runTest {
        val client = FakeLlmClient {
            delay(10 * 60_000) // 远超超时（虚拟时间，瞬推）
            LlmResponse("永不到达")
        }
        val caller = LlmCaller(client, timeoutMillis = 120_000)
        val e = assertFailsWith<LlmException> { caller.complete(request) }
        assertEquals(LlmErrorKind.TRANSIENT, e.kind)
        assertEquals(3, client.calls)
    }
}
