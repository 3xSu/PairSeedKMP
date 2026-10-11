package com.pairseedkmp.domain.dedup

import com.pairseedkmp.domain.ai.LlmCaller
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 假 client：第 n 次调用（0 起）回第 n 条脚本文本；记录请求与调用次数。 */
private class FakeLlmClient(
    private val onComplete: suspend (Int) -> LlmResponse,
) : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    val calls: Int get() = requests.size

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return onComplete(calls - 1)
    }
}

/** 组装：脚本化 client + 指定阈值的 [Deduplicator]。（脚本未覆盖的调用会下标越界——顺带兜"不调用"断言） */
private fun dedupWith(
    vararg responses: String,
    threshold: Double = DEFAULT_SHORT_WORD_THRESHOLD,
): Pair<Deduplicator, FakeLlmClient> {
    val client = FakeLlmClient { n -> LlmResponse(responses[n]) }
    return Deduplicator(LlmCaller(client), threshold) to client
}

class DeduplicatorTest {

    // ---------- 短词：L1 精确 ----------

    @Test
    fun 短词_完全重复_类型与下标正确() {
        val (dedup, _) = dedupWith()
        assertEquals(
            DedupResult.hit(DedupType.EXACT, 1, "深夜便利店"),
            dedup.shortWord("深夜便利店", listOf("雨后的泥土味", "深夜便利店", "深夜便利店")),
        )
    }

    @Test
    fun 短词_规范化后精确命中_全角标点与简繁() {
        val (dedup, _) = dedupWith()
        val hit = dedup.shortWord("舊毛衣！", listOf("旧毛衣"))
        assertEquals(DedupType.EXACT, hit.type)
        assertEquals(0, hit.matchedIndex)
        assertEquals("旧毛衣", hit.matchedText) // 命中对象回原文（未规范化）
    }

    // ---------- 短词：L2 编辑距离 ----------

    @Test
    fun 短词_字形近义命中() {
        val (dedup, _) = dedupWith()
        assertEquals(
            DedupResult.hit(DedupType.CHAR_SIMILAR, 0, "折叠伞"),
            dedup.shortWord("折叠雨伞", listOf("折叠伞")),
        )
    }

    @Test
    fun 短词_命中多条取相似度最高() {
        val (dedup, _) = dedupWith()
        // 折叠伞 0.75 < 折叠雨伞啊 0.8 → 取后者
        val hit = dedup.shortWord("折叠雨伞", listOf("折叠伞", "折叠雨伞啊"))
        assertEquals(DedupType.CHAR_SIMILAR, hit.type)
        assertEquals(1, hit.matchedIndex)
    }

    @Test
    fun 短词_并列取靠前() {
        val (dedup, _) = dedupWith()
        val hit = dedup.shortWord("小雨散步", listOf("大雨散步", "小雨漫步"))
        assertEquals(0, hit.matchedIndex) // 两条同为 0.75，取靠前
    }

    @Test
    fun 短词_相似度不足放行() {
        val (dedup, _) = dedupWith()
        assertFalse(dedup.shortWord("雨伞", listOf("伞")).isDuplicate)
        assertFalse(dedup.shortWord("花瓶", listOf("花盆")).isDuplicate)
        assertFalse(dedup.shortWord("做饭", listOf("做菜")).isDuplicate)
        assertFalse(dedup.shortWord("便当", listOf("便当盒")).isDuplicate)
    }

    @Test
    fun 短词_阈值可调() {
        val (strict, _) = dedupWith(threshold = 0.9)
        assertFalse(strict.shortWord("折叠雨伞", listOf("折叠伞")).isDuplicate)

        val (loose, _) = dedupWith(threshold = 0.5)
        assertEquals(DedupType.CHAR_SIMILAR, loose.shortWord("花瓶", listOf("花盆")).type)
    }

    @Test
    fun 短词_空目标与纯标点候选放行() {
        val (dedup, _) = dedupWith()
        assertEquals(DedupResult.NONE, dedup.shortWord("深夜便利店", emptyList()))
        assertEquals(DedupResult.NONE, dedup.shortWord("，。！？", listOf("。", "雨伞")))
    }

    @Test
    fun 阈值抽查样例表() {
        val (dedup, _) = dedupWith()
        // 命中侧（阈值 0.75 的依据；表见 .agent-docs/.tests/查重链路.md）
        val hits = listOf(
            "深夜便利店" to "深夜的便利店",
            "雨后的泥土味" to "雨后泥土味",
            "旧毛衣" to "旧毛线衣",
            "折叠雨伞" to "折叠伞",
            "厨房一起做饭" to "厨房里一起做饭",
            "早上跑步" to "早上散步", // 已知代价：0.75 下也会并（04 人为补充对重复是硬拦）
        )
        for ((a, b) in hits) {
            assertTrue(dedup.shortWord(a, listOf(b)).isDuplicate, "应命中：$a / $b")
        }
        // 放行侧（本度量不可达的 1–3 字包含 / 形近关系）
        val misses = listOf(
            "便当" to "便当盒",
            "雨伞" to "伞",
            "花瓶" to "花盆",
            "做饭" to "做菜",
        )
        for ((a, b) in misses) {
            assertFalse(dedup.shortWord(a, listOf(b)).isDuplicate, "不应命中：$a / $b")
        }
    }

    // ---------- 长文：第一闸 + AI「同一件事」 ----------

    @Test
    fun 长文_同一件事命中_指认一条() = runTest {
        val (dedup, client) = dedupWith("""{"same": true, "index": 2}""")
        val hit = dedup.longText("和对方一起把厨房收拾干净", listOf("收拾厨房", "周末一起大扫除"))
        assertEquals(DedupResult.hit(DedupType.SAME_THING, 1, "周末一起大扫除"), hit)
        assertEquals(1, client.calls)
    }

    @Test
    fun 长文_判定无重复() = runTest {
        val (dedup, client) = dedupWith("""{"same": false}""")
        assertEquals(DedupResult.NONE, dedup.longText("一起看星星", listOf("一起看星星啊")))
        assertEquals(1, client.calls)
    }

    @Test
    fun 长文_same为false时忽略index() = runTest {
        val (dedup, _) = dedupWith("""{"same": false, "index": 1}""")
        assertEquals(DedupResult.NONE, dedup.longText("一起看星星", listOf("一起看日落")))
    }

    @Test
    fun 长文_完全重复走第一闸不进AI() = runTest {
        val (dedup, client) = dedupWith() // 不提供脚本：一旦调用即下标越界
        val hit = dedup.longText("周末，一起大扫除。", listOf("周末一起大扫除"))
        assertEquals(DedupType.EXACT, hit.type)
        assertEquals(0, hit.matchedIndex)
        assertEquals(0, client.calls)
    }

    @Test
    fun 长文_第一闸简繁归一() = runTest {
        val (dedup, client) = dedupWith()
        val hit = dedup.longText("週末一起大掃除", listOf("周末一起大扫除"))
        assertEquals(DedupType.EXACT, hit.type)
        assertEquals(0, client.calls)
    }

    @Test
    fun 长文_空目标不调用() = runTest {
        val (dedup, client) = dedupWith()
        assertEquals(DedupResult.NONE, dedup.longText("随便什么", emptyList()))
        assertEquals(0, client.calls)
    }

    @Test
    fun 长文_解析失败重试一次成功() = runTest {
        val (dedup, client) = dedupWith("这不是 JSON", """{"same": true, "index": 1}""")
        val hit = dedup.longText("和对方一起做晚饭", listOf("一起做晚饭"))
        assertEquals(DedupType.SAME_THING, hit.type)
        assertEquals(0, hit.matchedIndex)
        assertEquals(2, client.calls)
    }

    @Test
    fun 长文_重试用尽抛技术失败() = runTest {
        val (dedup, client) = dedupWith("{}", """{"same": "也许是"}""")
        assertFailsWith<DedupParseException> {
            dedup.longText("和对方一起做晚饭", listOf("一起做晚饭"))
        }
        assertEquals(2, client.calls)
    }

    @Test
    fun 长文_index越界重试可救() = runTest {
        val (dedup, client) = dedupWith("""{"same": true, "index": 5}""", """{"same": true, "index": 1}""")
        val hit = dedup.longText("和对方一起做晚饭", listOf("一起做晚饭"))
        assertEquals(0, hit.matchedIndex)
        assertEquals(2, client.calls)
    }

    @Test
    fun 长文_请求形状_任务与编号() = runTest {
        val (dedup, client) = dedupWith("""{"same": false}""")
        dedup.longText("一起看星星", listOf("一起看日落", "一起看星星啊"))
        val request = client.requests.single()
        assertEquals(LlmTask.DEDUP, request.task)
        assertTrue(request.expectJson)
        assertEquals(0.2, request.temperature)
        assertTrue(request.user.contains("1. 一起看日落"), request.user)
        assertTrue(request.user.contains("2. 一起看星星啊"), request.user)
        assertTrue(request.user.contains("一起看星星"), request.user)
        assertTrue(request.system.isNotBlank())
    }

    @Test
    fun 长文_环境失败原样穿过不重试() = runTest {
        val client = FakeLlmClient { throw LlmException(LlmErrorKind.PERSISTENT, "Key 无效") }
        val dedup = Deduplicator(LlmCaller(client))
        val e = assertFailsWith<LlmException> {
            dedup.longText("一起看星星", listOf("一起看日落"))
        }
        assertEquals(LlmErrorKind.PERSISTENT, e.kind)
        assertEquals(1, client.calls)
    }
}
