package com.pairseedkmp.ui.debug.panel

import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmErrorKind
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 面板调用装饰器测试（07）：覆盖替换 / 记录 / 失败上抛 / 容量。
 * 口径：记录的是「实际送出」的系统词；覆盖按任务键控；失败留痕后原样上抛（链路重试不受影响）。
 */
class PanelLlmClientTest {

    @Test
    fun `无覆盖时原样透传并记录`() = runTest {
        val delegate = FakeClient()
        val log = mutableListOf<LlmInvocation>()
        val client = PanelLlmClient(delegate, { null }, FakeClock()::now, log)

        val request = request(system = "内置系统词")
        val response = client.complete(request)

        assertEquals("ok", response.text)
        assertEquals(request, delegate.received.single()) // 原样透传（未覆盖不改请求）
        val entry = log.single()
        assertEquals(LlmTask.EVENT_GROW, entry.task)
        assertEquals("内置系统词", entry.system)
        assertEquals(false, entry.overridden)
        assertEquals("ok", entry.responseText)
        assertNull(entry.error)
        assertEquals(50L, entry.elapsedMillis)
    }

    @Test
    fun `空白覆盖按未覆盖算`() = runTest {
        val delegate = FakeClient()
        val log = mutableListOf<LlmInvocation>()
        val client = PanelLlmClient(delegate, { "   " }, FakeClock()::now, log)

        client.complete(request(system = "内置系统词"))

        assertEquals("内置系统词", delegate.received.single().system)
        assertEquals(false, log.single().overridden)
    }

    @Test
    fun `覆盖按任务键控：命中替换实际送出、未命中不动`() = runTest {
        val delegate = FakeClient()
        val log = mutableListOf<LlmInvocation>()
        val client = PanelLlmClient(
            delegate,
            overrideFor = { task -> if (task == LlmTask.JUDGE) "面板改过的词" else null },
            nowMillis = FakeClock()::now,
            log = log,
        )

        client.complete(request(system = "内置判定词", user = "待判内容", temperature = 0.2, task = LlmTask.JUDGE))
        client.complete(request(system = "内置生长词", task = LlmTask.EVENT_GROW))

        val hit = delegate.received[0]
        assertEquals("面板改过的词", hit.system)
        assertEquals("待判内容", hit.user) // 其余字段不动
        assertEquals(0.2, hit.temperature)

        assertEquals("内置生长词", delegate.received[1].system)
        // 记录表新的在前：judge 那笔在 log[1]（先发生），grow 那笔在 log[0]
        assertEquals("面板改过的词", log[1].system) // 记录的是实际送出
        assertTrue(log[1].overridden)
        assertEquals(false, log[0].overridden)
    }

    @Test
    fun `失败记录后原样上抛`() = runTest {
        val boom = LlmException(LlmErrorKind.PERSISTENT, "401 没配 Key")
        val delegate = FakeClient(result = { throw boom })
        val log = mutableListOf<LlmInvocation>()
        val client = PanelLlmClient(delegate, { null }, FakeClock()::now, log)

        val thrown = assertFailsWith<LlmException> { client.complete(request()) }

        assertTrue(thrown === boom) // 同一个实例：链路失败分类不换
        val entry = log.single()
        assertNull(entry.responseText)
        assertEquals("PERSISTENT：401 没配 Key", entry.error)
    }

    @Test
    fun `超容量只留最近 N 条且新的在前`() = runTest {
        val log = mutableListOf<LlmInvocation>()
        val client = PanelLlmClient(FakeClient(), { null }, FakeClock()::now, log, capacity = 3)

        repeat(5) { index -> client.complete(request(user = "第 $index 次")) }

        assertEquals(3, log.size)
        assertEquals(listOf(4, 3, 2), log.map { it.seq }) // seq 大的在前；旧的被挤出
        assertEquals("第 4 次", log.first().user)
    }

    @Test
    fun `跨实例序号接着排不重头`() = runTest {
        // 每次跑链路都会新建一个装饰器实例、共享同一张记录表——序号要接着排，
        // 否则新一笔又显示 #0（装机演练发现的显示缺陷）
        val log = mutableListOf<LlmInvocation>()
        PanelLlmClient(FakeClient(), { null }, FakeClock()::now, log).complete(request(user = "上一轮"))
        PanelLlmClient(FakeClient(), { null }, FakeClock()::now, log).complete(request(user = "这一轮"))

        assertEquals(listOf(1, 0), log.map { it.seq })
        assertEquals("这一轮", log.first().user)
    }
}

private fun request(
    system: String = "内置系统词",
    user: String = "输入",
    temperature: Double = 0.9,
    task: LlmTask = LlmTask.EVENT_GROW,
): LlmRequest = LlmRequest(
    system = system,
    user = user,
    temperature = temperature,
    expectJson = true,
    task = task,
)

private class FakeClient(
    private val result: (LlmRequest) -> LlmResponse = { LlmResponse("ok") },
) : LlmClient {
    val received = mutableListOf<LlmRequest>()
    override suspend fun complete(request: LlmRequest): LlmResponse {
        received += request
        return result(request)
    }
}

/** 每次读表 +50ms：一次调用（起 / 止两读）耗时固定 50ms，断言可写死。 */
private class FakeClock(private var value: Long = 0L) {
    fun now(): Long {
        value += 50
        return value
    }
}
