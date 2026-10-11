package com.pairseedkmp.domain.seed

import com.pairseedkmp.domain.testing.FakeSeedRepository
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 造种子：只关心挑选相关的字段（其余给稳定默认）。 */
private fun seed(id: Long, keyword: String = "种子$id", pickedCount: Int = 0) = IdeaSeed(
    id = id,
    keyword = keyword,
    theme = "主题",
    sceneHintTags = emptyList(),
    suggestedTimeWindow = null,
    relationshipPushTarget = null,
    noveltyTarget = null,
    sourceType = SeedSourceType.MANUAL,
    qualityScore = null,
    pickedCount = pickedCount,
    lastPickedAt = null,
    isDeleted = false,
    createdAt = 0L,
    updatedAt = 0L,
)

/** 固定随机源下反复抽取，返回每颗被抽中次数。 */
private fun drawCounts(seeds: List<IdeaSeed>, draws: Int, random: Random): Map<Long, Int> {
    val counts = mutableMapOf<Long, Int>()
    repeat(draws) {
        val picked = weightedPick(seeds, random)!!
        counts[picked.id] = (counts[picked.id] ?: 0) + 1
    }
    return counts
}

class SeedPickerTest {

    @Test
    fun 空池不挑也不记账() {
        val repo = FakeSeedRepository()
        assertNull(SeedPicker(repo, Random(1)).pick(now = 100L))
        assertTrue(repo.stored().isEmpty())
    }

    @Test
    fun 挑中即记账_单颗池必中() {
        val repo = FakeSeedRepository(listOf(seed(1)))
        val picked = SeedPicker(repo, Random(7)).pick(now = 1234L)!!

        assertEquals(1L, picked.id)
        assertEquals(1, picked.pickedCount, "返回值带新计数")
        assertEquals(1234L, picked.lastPickedAt)

        val stored = repo.stored().single()
        assertEquals(1, stored.pickedCount, "库里也写计数")
        assertEquals(1234L, stored.lastPickedAt)
        assertEquals(1234L, stored.updatedAt)
    }

    @Test
    fun 反比权重_次数少的更容易被挑中() {
        // 权重 = 1/(1+pickedCount)：1 : 1/2 : 1/4 = 4 : 2 : 1
        val seeds = listOf(seed(1, pickedCount = 0), seed(2, pickedCount = 1), seed(3, pickedCount = 3))
        val counts = drawCounts(seeds, draws = 7000, random = Random(42))

        val total = 7000.0
        assertTrue(abs(counts.getValue(1) / total - 4.0 / 7) < 0.03, "0 次应按 4/7 被抽中：$counts")
        assertTrue(abs(counts.getValue(2) / total - 2.0 / 7) < 0.03, "1 次应按 2/7 被抽中：$counts")
        assertTrue(abs(counts.getValue(3) / total - 1.0 / 7) < 0.03, "3 次应按 1/7 被抽中：$counts")
        assertTrue(counts.getValue(1) > counts.getValue(2), "次数少的总量更多")
        assertTrue(counts.getValue(2) > counts.getValue(3))
    }

    @Test
    fun 老种子不断线_高次数仍有正权重() {
        val seeds = listOf(seed(1, pickedCount = 100), seed(2, pickedCount = 0))
        val counts = drawCounts(seeds, draws = 10100, random = Random(3))
        assertTrue(counts.getValue(1) > 0, "被挑过 100 次仍会被抽中（1/101 权重）：$counts")
        assertTrue(counts.getValue(2) > counts.getValue(1))
    }

    @Test
    fun exclude_不重挑同颗() {
        val repo = FakeSeedRepository(listOf(seed(1), seed(2)))
        val picked = SeedPicker(repo, Random(7)).pick(now = 100L, exclude = setOf(1L))

        assertEquals(2L, picked!!.id, "被排除的 1 不参与（05 换种子）")
        val byId = repo.stored().associateBy { it.id }
        assertEquals(0, byId.getValue(1).pickedCount, "排除的不记账")
        assertEquals(1, byId.getValue(2).pickedCount)
    }

    @Test
    fun 全被排除_不挑不记账() {
        val repo = FakeSeedRepository(listOf(seed(1)))
        assertNull(SeedPicker(repo, Random(1)).pick(now = 100L, exclude = setOf(1L)))
        assertEquals(0, repo.stored().single().pickedCount)
    }
}
