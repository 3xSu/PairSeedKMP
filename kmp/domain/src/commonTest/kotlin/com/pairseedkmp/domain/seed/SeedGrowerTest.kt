package com.pairseedkmp.domain.seed

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmErrorKind
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.dedup.DedupType
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.repository.SeedRepository
import com.pairseedkmp.domain.testing.FakeSeedRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 脚本化假 client：按调用次序返回预置文本；多调一次即越界兜「不该调」。 */
private class ScriptedLlmClient(private val responses: suspend (Int) -> LlmResponse) : LlmClient {
    val requests = mutableListOf<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return responses(requests.size - 1)
    }
}

private fun scripted(vararg texts: String) = ScriptedLlmClient { n -> LlmResponse(texts[n]) }

/** 假采集工具：可预置素材行或失败；记取素材次数。 */
private class FakeSource(
    override val label: String,
    var lines: List<String> = listOf("素材一", "素材二"),
    var failure: Exception? = null,
) : SeedMaterialSource {
    var fetches = 0
        private set

    override suspend fun fetch(): List<String> {
        fetches++
        failure?.let { throw it }
        return lines
    }
}

private fun seed(id: Long, keyword: String) = IdeaSeed(
    id = id,
    keyword = keyword,
    theme = "主题",
    sceneHintTags = emptyList(),
    suggestedTimeWindow = null,
    relationshipPushTarget = null,
    noveltyTarget = null,
    sourceType = SeedSourceType.MANUAL,
    qualityScore = null,
    pickedCount = 0,
    lastPickedAt = null,
    isDeleted = false,
    createdAt = 0L,
    updatedAt = 0L,
)

private fun grower(
    client: LlmClient,
    repository: SeedRepository,
    vararg sources: SeedMaterialSource,
): SeedGrower = SeedGrower(
    repository = repository,
    deduplicator = Deduplicator(LlmCaller(client)),
    caller = LlmCaller(client),
    sources = sources.toList(),
)

/** 重复提案：池里的「旧毛衣」会被 L1 精确命中。 */
private const val DUP_PROPOSAL = """{"keyword": "旧毛衣", "theme": "衣物"}"""

class SeedGrowerTest {

    @Test
    fun 成功_入库为AI_VARIANT_请求形状对() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1, "旧毛衣")))
        val source = FakeSource("新闻RSS", lines = listOf("今晨小雨", "泥土的清香"))
        val client = scripted("""{"keyword": "雨后的泥土味", "theme": "气味与记忆"}""")

        val result = grower(client, repo, source).grow(now = 900L)

        val grown = assertIs<GrowResult.Grown>(result)
        assertEquals(1, grown.attempts)
        val stored = repo.stored().single { it.id == grown.seed.id }
        assertEquals("雨后的泥土味", stored.keyword)
        assertEquals("气味与记忆", stored.theme)
        assertEquals(SeedSourceType.AI_VARIANT, stored.sourceType)
        assertEquals(900L, stored.createdAt)
        assertEquals(900L, stored.updatedAt)
        assertEquals(0, stored.pickedCount)
        assertTrue(stored.id > 0)

        val request = client.requests.single()
        assertEquals(LlmTask.SEED_GROW, request.task)
        assertTrue(request.expectJson)
        assertEquals(0.9, request.temperature)
        assertTrue(request.user.contains("（来源：新闻RSS）"))
        assertTrue(request.user.contains("1. 今晨小雨"))
        assertTrue(request.user.contains("2. 泥土的清香"))
        assertTrue(request.user.contains("- 旧毛衣"), "已有种子进提示词：${request.user}")
        assertTrue(request.system.contains("只输出 JSON"))
        assertEquals(1, source.fetches)
    }

    @Test
    fun 重复_词与类型反馈进下一轮() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1, "旧毛衣")))
        val client = scripted(
            DUP_PROPOSAL,                                     // 完全重复
            """{"keyword": "旧毛线衣", "theme": "衣物"}""",  // 字形近似（0.75）
            """{"keyword": "新词", "theme": "新主题"}""",
        )

        val result = grower(client, repo, FakeSource("RSS")).grow(now = 1L)

        assertEquals(3, assertIs<GrowResult.Grown>(result).attempts)
        assertTrue(!client.requests[0].user.contains("【上一轮判断为重复】"), "首轮无反馈")
        val third = client.requests[2].user
        assertTrue(third.contains("【上一轮判断为重复】"))
        assertTrue(third.contains("「旧毛衣」——完全重复"), "反馈含重复词与类型：$third")
        assertTrue(third.contains("「旧毛线衣」——字形近似"), "反馈逐次累计：$third")
        assertEquals(listOf("旧毛衣", "新词"), repo.stored().map { it.keyword }, "两个重复词不入库，只多了新词")
    }

    @Test
    fun 一个工具用满上限_自动换下一个工具() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1, "旧毛衣")))
        val client = scripted(DUP_PROPOSAL, DUP_PROPOSAL, DUP_PROPOSAL, DUP_PROPOSAL, DUP_PROPOSAL,
            """{"keyword": "新词", "theme": "新主题"}""")
        val a = FakeSource("A")
        val b = FakeSource("B")

        val result = grower(client, repo, a, b).grow(now = 1L)

        assertEquals(6, assertIs<GrowResult.Grown>(result).attempts)
        assertEquals(1, a.fetches)
        assertEquals(1, b.fetches, "换工具时取该工具的素材")
    }

    @Test
    fun 自动模式_全轮用尽_Exhausted且不入库() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1, "旧毛衣")))
        val client = scripted(*Array(10) { DUP_PROPOSAL })

        val result = grower(client, repo, FakeSource("A"), FakeSource("B")).grow(now = 1L)

        assertEquals(10, assertIs<GrowResult.Exhausted>(result).attempts)
        assertEquals(1, repo.stored().size, "只有原有那颗，没长新的")
    }

    @Test
    fun 手动模式_单工具用尽_询问_同意续跑() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1, "旧毛衣")))
        val client = scripted(DUP_PROPOSAL, DUP_PROPOSAL, DUP_PROPOSAL, DUP_PROPOSAL, DUP_PROPOSAL,
            """{"keyword": "新词", "theme": "新主题"}""")
        val grower = grower(client, repo, FakeSource("A"), FakeSource("B"))

        val asked = assertIs<GrowResult.AskContinue>(
            grower.grow(now = 1L, mode = SeedGrowMode.MANUAL),
        )
        assertEquals("A", asked.toolLabel)
        assertEquals(1, asked.nextSourceIndex)
        assertEquals(5, asked.duplicates.size)
        assertEquals(SeedDuplicate("旧毛衣", DedupType.EXACT), asked.duplicates.first())

        val result = grower.grow(now = 2L, mode = SeedGrowMode.MANUAL, startSourceIndex = asked.nextSourceIndex)
        assertIs<GrowResult.Grown>(result)
        assertEquals(2, repo.stored().size)
    }

    @Test
    fun 无效输出_计次但不进反馈() = runTest {
        val repo = FakeSeedRepository()
        val client = scripted(
            "这不是 JSON",
            """{"keyword": "", "theme": "x"}""",
            """{"keyword": "新词", "theme": "新主题"}""",
        )

        val result = grower(client, repo, FakeSource("A")).grow(now = 1L)

        assertEquals(3, assertIs<GrowResult.Grown>(result).attempts)
        assertTrue(!client.requests[2].user.contains("【上一轮判断为重复】"), "技术失败不进重复反馈")
        assertTrue(client.requests[1].user.contains("（池子还是空的"), "空池提示：${client.requests[1].user}")
    }

    @Test
    fun 采集失败_换下一个工具_记错误() = runTest {
        val client = scripted("""{"keyword": "新词", "theme": "新主题"}""")
        val bad = FakeSource("坏源", failure = RuntimeException("请求超时"))
        val good = FakeSource("好源")

        val result = grower(client, FakeSeedRepository(), bad, good).grow(now = 1L)

        assertIs<GrowResult.Grown>(result)
        assertEquals(1, bad.fetches)
        assertEquals(1, good.fetches)
    }

    @Test
    fun 全采集失败或素材空_NoMaterial_不调AI() = runTest {
        val client = scripted()
        val bad = FakeSource("坏源", failure = RuntimeException("断网"))
        val empty = FakeSource("空源", lines = listOf(" ", ""))

        val result = grower(client, FakeSeedRepository(), bad, empty).grow(now = 1L)

        val noMaterial = assertIs<GrowResult.NoMaterial>(result)
        assertEquals(1, noMaterial.errors.size)
        assertTrue(noMaterial.errors.single().contains("坏源"), "错误带来源：${noMaterial.errors}")
        assertTrue(noMaterial.errors.single().contains("断网"))
        assertTrue(client.requests.isEmpty(), "没素材不调 AI")
    }

    @Test
    fun 没有工具_NoMaterial() = runTest {
        val result = grower(scripted(), FakeSeedRepository()).grow(now = 1L)
        val noMaterial = assertIs<GrowResult.NoMaterial>(result)
        assertTrue(noMaterial.errors.isEmpty())
    }

    @Test
    fun 环境失败原样穿出_不重试不吞() = runTest {
        val client = ScriptedLlmClient { throw LlmException(LlmErrorKind.PERSISTENT, "Key 无效") }

        val e = assertFailsWith<LlmException> {
            grower(client, FakeSeedRepository(), FakeSource("A")).grow(now = 1L)
        }
        assertEquals(LlmErrorKind.PERSISTENT, e.kind)
        assertEquals(1, client.requests.size, "持续失败不重试")
    }
}
