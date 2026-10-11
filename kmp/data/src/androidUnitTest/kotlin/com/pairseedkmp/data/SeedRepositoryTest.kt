package com.pairseedkmp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.pairseedkmp.data.seed_impl.SeedRepositoryImpl
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.seed.IdeaSeed
import com.pairseedkmp.domain.seed.SeedSourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 04 步骤 2：种子池数据访问——真 SQLite（JVM 驱动、内存库）跑插入读出 / 列表与搜索 / 挑选记账。 */
class SeedRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: PairSeedDatabase

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PairSeedDatabase.Schema.create(driver)
        db = PairSeedDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun sampleSeed(
        keyword: String = "雨后的泥土味",
        theme: String = "气味与记忆",
        sceneHintTags: List<String> = listOf("kitchen", "rainy_day"),
        suggestedTimeWindow: Period? = Period.EVENING,
        relationshipPushTarget: Int? = 2,
        noveltyTarget: Int? = null,
        sourceType: SeedSourceType = SeedSourceType.MANUAL,
    ) = IdeaSeed(
        id = 0,
        keyword = keyword,
        theme = theme,
        sceneHintTags = sceneHintTags,
        suggestedTimeWindow = suggestedTimeWindow,
        relationshipPushTarget = relationshipPushTarget,
        noveltyTarget = noveltyTarget,
        sourceType = sourceType,
        qualityScore = null,
        pickedCount = 0,
        lastPickedAt = null,
        isDeleted = false,
        createdAt = 1_791_072_000_000L,
        updatedAt = 1_791_072_000_000L,
    )

    @Test
    fun 插入读出_字段往返() {
        val repo = SeedRepositoryImpl(db)
        val id = repo.insert(sampleSeed())
        assertTrue(id > 0)

        val seed = repo.all().single()
        assertEquals(id, seed.id)
        assertEquals("雨后的泥土味", seed.keyword)
        assertEquals("气味与记忆", seed.theme)
        assertEquals(listOf("kitchen", "rainy_day"), seed.sceneHintTags)
        assertEquals(Period.EVENING, seed.suggestedTimeWindow)
        assertEquals(2, seed.relationshipPushTarget)
        assertNull(seed.noveltyTarget)
        assertEquals(SeedSourceType.MANUAL, seed.sourceType)
        assertNull(seed.qualityScore)
        assertEquals(0, seed.pickedCount)
        assertNull(seed.lastPickedAt)
        assertEquals(1_791_072_000_000L, seed.createdAt)
        assertEquals(1_791_072_000_000L, seed.updatedAt)
    }

    @Test
    fun 空标签与不限时段_来回仍为空() {
        val repo = SeedRepositoryImpl(db)
        repo.insert(sampleSeed(sceneHintTags = emptyList(), suggestedTimeWindow = null))

        val seed = repo.all().single()
        assertTrue(seed.sceneHintTags.isEmpty())
        assertNull(seed.suggestedTimeWindow)
    }

    @Test
    fun 列表_按id升序_逻辑删除的不出现() {
        val repo = SeedRepositoryImpl(db)
        val first = repo.insert(sampleSeed(keyword = "深夜便利店"))
        repo.insert(sampleSeed(keyword = "旧毛衣"))
        driver.execute(
            identifier = null,
            sql = "UPDATE idea_seeds SET is_deleted = 1 WHERE id = ?",
            parameters = 1,
        ) {
            bindLong(0, first)
        }

        assertEquals(listOf("旧毛衣"), repo.all().map { it.keyword })
    }

    @Test
    fun 搜索_前缀与同字命中_通配符按字面() {
        val repo = SeedRepositoryImpl(db)
        repo.insert(sampleSeed(keyword = "深夜便利店"))
        repo.insert(sampleSeed(keyword = "旧毛衣"))
        repo.insert(sampleSeed(keyword = "深夜食堂"))
        repo.insert(sampleSeed(keyword = "100%纯棉"))

        assertEquals(listOf("深夜便利店", "深夜食堂"), repo.search("深夜").map { it.keyword })
        assertEquals(listOf("旧毛衣"), repo.search("衣").map { it.keyword })
        // instr 包含匹配：% 不做通配符解释（LIKE 会误命中全部）
        assertEquals(listOf("100%纯棉"), repo.search("100%").map { it.keyword })
        assertTrue(repo.search("没有的").isEmpty())
    }

    @Test
    fun 挑选记账_计数累加_时间写入() {
        val repo = SeedRepositoryImpl(db)
        val id = repo.insert(sampleSeed())

        repo.recordPick(id, pickedAt = 100L)
        repo.recordPick(id, pickedAt = 200L)

        val seed = repo.all().single()
        assertEquals(2, seed.pickedCount)
        assertEquals(200L, seed.lastPickedAt)
        assertEquals(200L, seed.updatedAt)
    }
}
