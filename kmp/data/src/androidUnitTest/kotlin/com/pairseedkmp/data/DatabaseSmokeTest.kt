package com.pairseedkmp.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 冒烟（步骤 5）：开库（建表）→ 插入 → 读出 → 重开还在。JVM 版驱动跑，不用设备。 */
class DatabaseSmokeTest {

    private val dbFile = File(
        System.getProperty("java.io.tmpdir"),
        "pairseedkmp_smoke_${System.nanoTime()}.db",
    )

    private fun openDriver() =
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath.replace('\\', '/')}")

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    @Test
    fun 开库建表_17张() {
        val driver = openDriver()
        try {
            PairSeedDatabase.Schema.create(driver)
            assertTrue(dbFile.exists(), "开库后库文件应生成")

            val count = driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0)!!)
                },
                parameters = 0,
            ).value
            assertEquals(17L, count, "17 张表应全部建出")
        } finally {
            driver.close()
        }
    }

    @Test
    fun 插入读出() {
        val driver = openDriver()
        try {
            PairSeedDatabase.Schema.create(driver)
            insertOne(driver)

            val row = readOne(driver)
            assertEquals("雨夜", row.keyword)
            assertEquals("care", row.theme)
            assertEquals("""["kitchen"]""", row.sceneHintTags)
            assertEquals(0L, row.pickedCount, "未写 picked_count → NOT NULL DEFAULT 0 生效")
        } finally {
            driver.close()
        }
    }

    @Test
    fun 重开库_数据还在() {
        val driver = openDriver()
        try {
            PairSeedDatabase.Schema.create(driver)
            insertOne(driver)
        } finally {
            driver.close()
        }

        // 第二次打开：不再 create（模拟第二次启动）
        val reopened = openDriver()
        try {
            assertEquals("雨夜", readOne(reopened).keyword)
        } finally {
            reopened.close()
        }
    }

    private fun insertOne(driver: JdbcSqliteDriver) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO idea_seeds (keyword, theme, scene_hint_tags, source_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6,
        ) {
            bindString(0, "雨夜")
            bindString(1, "care")
            bindString(2, """["kitchen"]""")
            bindString(3, "MANUAL")
            bindLong(4, 1_700_000_000_000)
            bindLong(5, 1_700_000_000_000)
        }
    }

    private data class SeedRow(
        val keyword: String,
        val theme: String,
        val sceneHintTags: String?,
        val pickedCount: Long,
    )

    private fun readOne(driver: JdbcSqliteDriver): SeedRow = driver.executeQuery(
        identifier = null,
        sql = "SELECT keyword, theme, scene_hint_tags, picked_count FROM idea_seeds",
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(
                SeedRow(
                    keyword = cursor.getString(0)!!,
                    theme = cursor.getString(1)!!,
                    sceneHintTags = cursor.getString(2),
                    pickedCount = cursor.getLong(3)!!,
                ),
            )
        },
        parameters = 0,
    ).value
}
