package com.pairseedkmp.domain.seed

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.dedup.DedupType
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.testing.FakeSeedRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 假 client：人为补充（短词查重）不该碰 AI——被调用即失败。 */
private class NoAiLlmClient : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse = error("短词查重不应调用 AI")
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

private fun supplement(repo: FakeSeedRepository) = SeedSupplement(repo, Deduplicator(LlmCaller(NoAiLlmClient())))

class SeedSupplementTest {

    @Test
    fun 无重复_入库_MANUAL口径() {
        val repo = FakeSeedRepository(listOf(seed(1, "旧毛衣")))
        val result = supplement(repo).add(keyword = "深夜便利店", theme = "城市里的安静角落", now = 555L)

        val added = result as SupplementResult.Added
        assertEquals("深夜便利店", added.seed.keyword)
        val stored = repo.stored().single { it.id == added.seed.id }
        assertEquals("城市里的安静角落", stored.theme)
        assertEquals(SeedSourceType.MANUAL, stored.sourceType)
        assertEquals(0, stored.pickedCount)
        assertEquals(555L, stored.createdAt)
        assertEquals(555L, stored.updatedAt)
        assertTrue(added.seed.id > 0)
    }

    @Test
    fun 完全重复_不入库_含归一() {
        val repo = FakeSeedRepository(listOf(seed(1, "旧毛衣")))
        // 全角标点 + 繁简差异，规范化后精确命中
        val result = supplement(repo).add(keyword = "舊毛衣！", theme = "x", now = 1L)

        assertEquals(SupplementResult.Duplicate(DedupType.EXACT, "旧毛衣"), result)
        assertEquals(1, repo.stored().size, "重复不入库")
    }

    @Test
    fun 字形近似_不入库_提示相似词() {
        val repo = FakeSeedRepository(listOf(seed(1, "旧毛衣")))
        val result = supplement(repo).add(keyword = "旧毛线衣", theme = "x", now = 1L)

        assertEquals(SupplementResult.Duplicate(DedupType.CHAR_SIMILAR, "旧毛衣"), result)
        assertEquals(1, repo.stored().size)
    }

    @Test
    fun 空白与纯标点_不入库() {
        val repo = FakeSeedRepository()
        val supplement = supplement(repo)
        assertEquals(SupplementResult.Blank, supplement.add(keyword = "  ", theme = "", now = 1L))
        assertEquals(SupplementResult.Blank, supplement.add(keyword = "，。！？", theme = "", now = 1L))
        assertTrue(repo.stored().isEmpty())
    }

    @Test
    fun 搜索_前缀与同字都能查到() {
        val repo = FakeSeedRepository(listOf(seed(1, "深夜便利店"), seed(2, "旧毛衣"), seed(3, "深夜食堂")))
        val supplement = supplement(repo)

        assertEquals(listOf(1L, 3L), supplement.search("深夜").map { it.id })
        assertEquals(listOf(2L), supplement.search("衣").map { it.id })
        assertTrue(supplement.search("没有的").isEmpty())
    }
}
