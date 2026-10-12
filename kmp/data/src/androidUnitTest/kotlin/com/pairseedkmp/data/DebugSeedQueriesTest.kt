package com.pairseedkmp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.pairseedkmp.domain.event.parseJsonStringList
import com.pairseedkmp.domain.judgment.AXIS_EMOTION_VISIBILITY
import com.pairseedkmp.domain.judgment.AXIS_PHYSICAL_INTIMACY
import com.pairseedkmp.domain.judgment.AXIS_VERBAL_DIRECTNESS
import com.pairseedkmp.domain.judgment.parseSoftAxisLevels
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 节点 08：调试种子三张表的插入命名查询（character_specs / character_target_relations /
 * document_snippets）——真 SQLite（JVM 驱动、内存库）往返：插入 → 读回，逐列核对 +
 * JSON 列按 domain 解析器验形（soft_axes 五维四档 / 字符串数组列）。
 * 数据为合成测试夹具（与本地调试种子文件解耦）。
 */
class DebugSeedQueriesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: PairSeedDatabase

    private val day = 10L * 86_400_000L

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

    private fun insertPack(code: String = "test_pack"): Long {
        db.character_packsQueries.insertCharacterPack(code, "测试角色组", null, day, day)
        return db.character_packsQueries.lastInsertedCharacterPackId().executeAsOne()
    }

    private fun insertCharacter(packId: Long, code: String): Long {
        db.charactersQueries.insertCharacter(packId, code, code, "$code 的摘要", day, day)
        return db.charactersQueries.lastInsertedCharacterId().executeAsOne()
    }

    @Test
    fun 设定卡插入往返_五维四档_判定三维读得出() {
        val packId = insertPack()
        val characterId = insertCharacter(packId, "A")

        db.character_specsQueries.insertCharacterSpec(
            character_id = characterId,
            aliases = """["测试别名"]""",
            soft_axes = """
                {"proactivity":{"level":2,"text":"大多时候不先开口"},
                 "emotion_visibility":{"level":3,"text":"心里的事不太说"},
                 "expression_mode":{"level":2,"text":"话少"},
                 "physical_intimacy":{"level":2,"text":"不排斥亲近的接触"},
                 "verbal_directness":{"level":1,"text":"句子短"}}
            """.trimIndent(),
            speech_style_summary = "简短直接，偶尔冒出一句温柔的话。",
            preferred_affection_modes = """["安静地陪着","递东西式的照顾"]""",
            disliked_affection_modes = """["当众表达"]""",
            body_spec = """{"head":{"diameter":26}}""",
            wardrobe = """{"current_hair":0}""",
            created_at = day,
            updated_at = day,
        )

        val spec = db.character_specsQueries.selectCharacterSpecByCharacterId(characterId).executeAsOne()
        assertEquals(characterId, spec.character_id)
        assertEquals(listOf("测试别名"), parseJsonStringList(spec.aliases))
        assertEquals("简短直接，偶尔冒出一句温柔的话。", spec.speech_style_summary)
        assertEquals(listOf("安静地陪着", "递东西式的照顾"), parseJsonStringList(spec.preferred_affection_modes))
        assertEquals(listOf("当众表达"), parseJsonStringList(spec.disliked_affection_modes))
        assertEquals("""{"head":{"diameter":26}}""", spec.body_spec)
        assertEquals("""{"current_hair":0}""", spec.wardrobe)

        // 判定三维只读得出口径内的键（五维里 expression_mode / proactivity 不进判定表）
        assertEquals(
            mapOf(
                AXIS_PHYSICAL_INTIMACY to 2,
                AXIS_VERBAL_DIRECTNESS to 1,
                AXIS_EMOTION_VISIBILITY to 3,
            ),
            parseSoftAxisLevels(spec.soft_axes),
        )

        assertNull(
            db.character_specsQueries.selectCharacterSpecByCharacterId(999).executeAsOneOrNull(),
            "没种过设定的角色 → 无行",
        )
    }

    @Test
    fun 关系插入往返_双向两条各读各的() {
        val packId = insertPack()
        val a = insertCharacter(packId, "A")
        val b = insertCharacter(packId, "B")

        db.character_target_relationsQueries.insertCharacterTargetRelation(
            character_id = a,
            target_character_id = b,
            toward_target_summary = "把 B 当最放松的室友",
            default_distance = "亲近稳定",
            likely_initiation_modes = """["安静的陪伴"]""",
            sensitive_progression_tags = """["开口表白"]""",
            safe_comfort_actions = """["一起吃饭"]""",
            direct_addressing_summary = "很少叫名字",
            reference_addressing_summary = "只说「室友」",
            created_at = day,
            updated_at = day,
        )
        db.character_target_relationsQueries.insertCharacterTargetRelation(
            character_id = b,
            target_character_id = a,
            toward_target_summary = "想靠近又怕推太快",
            default_distance = "亲近但有点小心",
            likely_initiation_modes = null,
            sensitive_progression_tags = null,
            safe_comfort_actions = null,
            direct_addressing_summary = null,
            reference_addressing_summary = null,
            created_at = day,
            updated_at = day,
        )

        val rows = db.character_target_relationsQueries.selectRelationsBetween(a, b).executeAsList()
        assertEquals(listOf(a to b, b to a), rows.map { it.character_id to it.target_character_id })
        assertEquals("把 B 当最放松的室友", rows[0].toward_target_summary)
        assertEquals("亲近稳定", rows[0].default_distance)
        assertEquals(listOf("安静的陪伴"), parseJsonStringList(rows[0].likely_initiation_modes))
        assertEquals(listOf("开口表白"), parseJsonStringList(rows[0].sensitive_progression_tags))
        assertEquals(listOf("一起吃饭"), parseJsonStringList(rows[0].safe_comfort_actions))
        assertEquals("很少叫名字", rows[0].direct_addressing_summary)
        assertEquals("只说「室友」", rows[0].reference_addressing_summary)
        assertEquals("亲近但有点小心", rows[1].default_distance)
        assertEquals(emptyList(), parseJsonStringList(rows[1].likely_initiation_modes), "空列 → 空表")
    }

    @Test
    fun 文档片段插入往返_按组按类取_逻辑删除不出() {
        val packCode = "test_pack"

        fun insert(
            code: String,
            type: String,
            character: String?,
            title: String,
            deleted: Boolean = false,
        ) {
            db.document_snippetsQueries.insertDocumentSnippet(
                doc_code = code,
                doc_type = type,
                pack_code = packCode,
                scope = if (character != null) "character" else "pair",
                character_code = character,
                title = title,
                summary = "$title 的摘要",
                body = "$title 的正文",
                tags = """["demo"]""",
                source_ref = "demo_ab",
                created_at = day,
                updated_at = day,
            )
            if (deleted) {
                driver.execute(
                    identifier = null,
                    sql = "UPDATE document_snippets SET is_deleted = 1 WHERE doc_code = ?",
                    parameters = 1,
                ) { bindString(0, code) }
            }
        }

        insert("voice_a_01", "voice_ref", "A", "A·靠窗")
        insert("voice_a_02", "voice_ref", "A", "A·递水")
        insert("voice_a_03", "voice_ref", "A", "A·删掉的样本", deleted = true)
        insert("activity_01", "activity_ref", null, "一起晒被子")

        val voices = db.document_snippetsQueries
            .selectDocumentSnippetsByPackAndType(packCode, "voice_ref", 10).executeAsList()
        assertEquals(listOf("voice_a_01", "voice_a_02"), voices.map { it.doc_code }, "逻辑删除的不出")
        val first = voices.first()
        assertEquals("character", first.scope)
        assertEquals("A", first.character_code)
        assertEquals("A·靠窗 的摘要", first.summary)
        assertEquals("A·靠窗 的正文", first.body)
        assertEquals(listOf("demo"), parseJsonStringList(first.tags))
        assertEquals("demo_ab", first.source_ref)

        assertEquals(
            listOf("activity_01"),
            db.document_snippetsQueries
                .selectDocumentSnippetsByPackAndType(packCode, "activity_ref", 10)
                .executeAsList().map { it.doc_code },
            "按类型分流",
        )
        assertEquals(
            listOf("voice_a_01"),
            db.document_snippetsQueries
                .selectDocumentSnippetsByPackAndType(packCode, "voice_ref", 1)
                .executeAsList().map { it.doc_code },
            "limit 截断（按 id 升序取前 N）",
        )
        assertEquals(
            emptyList(),
            db.document_snippetsQueries
                .selectDocumentSnippetsByPackAndType("别的组", "voice_ref", 10)
                .executeAsList(),
            "按组过滤",
        )
    }
}
