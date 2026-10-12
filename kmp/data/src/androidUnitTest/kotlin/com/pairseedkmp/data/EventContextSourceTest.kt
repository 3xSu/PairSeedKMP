package com.pairseedkmp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.pairseedkmp.data.grow_impl.EventContextSourceImpl
import com.pairseedkmp.domain.common.MILLIS_PER_DAY
import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.event.EventStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 节点 05：生成上下文读侧——真 SQLite（JVM 驱动、内存库）跑上下文组装。
 * 节点 08 起 character_specs / character_target_relations / document_snippets 有插入命名查询，
 * 种数据全走命名查询（逻辑删除的样本插完用一条 UPDATE 翻旗标）。
 */
class EventContextSourceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: PairSeedDatabase

    private val day = 10L * MILLIS_PER_DAY // 任意一天
    private val now = day + 8 * 60 * MILLIS_PER_MINUTE // 08:00 → MORNING（UTC 偏移 0）

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

    // ===== 种数据（能走命名查询的走命名查询；其余 raw SQL）=====

    private fun insertPack(code: String = "cp_ab", name: String = "A 与 B"): Long {
        db.character_packsQueries.insertCharacterPack(code, name, null, day, day)
        return db.character_packsQueries.lastInsertedCharacterPackId().executeAsOne()
    }

    private fun insertCharacter(packId: Long, code: String, name: String): Long {
        db.charactersQueries.insertCharacter(packId, code, name, "$name 的摘要", day, day)
        return db.charactersQueries.lastInsertedCharacterId().executeAsOne()
    }

    private fun insertScene(packId: Long): Long {
        db.scenesQueries.insertScene(
            pack_id = packId,
            scene_code = "home",
            scene_name = "家",
            location_type = "PRIVATE",
            description = "有一张沙发和一张书桌",
            scene_image = "",
            grid_size = 32,
            grid_cols = 60,
            grid_rows = 34,
            walk_mask = "",
            place_mask = "",
            created_at = day,
            updated_at = day,
        )
        return db.scenesQueries.lastInsertedSceneId().executeAsOne()
    }

    private fun insertProfile(packId: Long?, sceneId: Long?) {
        db.runtime_profilesQueries.insertRuntimeProfile(packId, sceneId, null, 0, null, day, day)
    }

    /** 角色设定卡（节点 08 插入查询）。 */
    private fun insertSpec(
        characterId: Long,
        aliases: String? = null,
        softAxes: String = "{}",
        speechStyle: String = "——",
        preferred: String? = null,
        disliked: String? = null,
    ) {
        db.character_specsQueries.insertCharacterSpec(
            character_id = characterId,
            aliases = aliases,
            soft_axes = softAxes,
            speech_style_summary = speechStyle,
            preferred_affection_modes = preferred,
            disliked_affection_modes = disliked,
            body_spec = "{}",
            wardrobe = "{}",
            created_at = day,
            updated_at = day,
        )
    }

    /** 角色关系一行（节点 08 插入查询）。 */
    private fun insertRelation(from: Long, to: Long, summary: String, distance: String, modes: String? = null) {
        db.character_target_relationsQueries.insertCharacterTargetRelation(
            character_id = from,
            target_character_id = to,
            toward_target_summary = summary,
            default_distance = distance,
            likely_initiation_modes = modes,
            sensitive_progression_tags = null,
            safe_comfort_actions = null,
            direct_addressing_summary = null,
            reference_addressing_summary = null,
            created_at = day,
            updated_at = day,
        )
    }

    /** 文档片段（节点 08 插入查询；逻辑删除的样本插完翻旗标）。 */
    private fun insertDocument(
        code: String,
        type: String,
        packCode: String,
        title: String,
        body: String,
        deleted: Boolean = false,
    ) {
        db.document_snippetsQueries.insertDocumentSnippet(
            doc_code = code,
            doc_type = type,
            pack_code = packCode,
            scope = "global",
            character_code = null,
            title = title,
            summary = null,
            body = body,
            tags = null,
            source_ref = null,
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

    /** 事件一行（raw SQL：只给测试关心的列，id 显式给）。 */
    private fun insertEvent(
        id: Long,
        packId: Long,
        actorId: Long,
        sceneId: Long,
        summary: String,
        status: EventStatus,
        occurredAt: Long? = null,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO events (id, pack_id, actor_id, target_id, scene_id, summary, steps, comparison_axes,
                    confirmation_payload, suitable_time, status, origin, source_seed_id, after_event_id,
                    semi_fixed_template_code, occurred_at, duration_sec, expires_at, is_deleted, created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?, '[{"action":"walk"}]', NULL, NULL, NULL, ?, 'SEED',
                    NULL, NULL, NULL, ?, 10, ?, 0, 0, 0)
            """.trimIndent(),
            parameters = 8,
        ) {
            bindLong(0, id)
            bindLong(1, packId)
            bindLong(2, actorId)
            bindLong(3, sceneId)
            bindString(4, summary)
            bindString(5, status.code)
            bindLong(6, occurredAt) // null 即绑 NULL（2.0.2 的 binder 只认可空装箱参数）
            bindLong(7, day + 7 * MILLIS_PER_DAY)
        }
    }

    /** 种一整套最小配置（成功路径用）：角色组 / 两人 / 场景 / 运行配置 / 共享设定 / 动作库。 */
    private fun seedBase(): Triple<Long, Long, Long> {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val targetId = insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId)
        insertProfile(packId, sceneId)
        db.pair_shared_specsQueries.insertPairSharedSpec(packId, "同居室友", "轻松日常", """["冲突","冷战"]""", day, day)
        db.behavior_templatesQueries.insertBehaviorTemplate(
            "walk", "WALK", "Walk somewhere", "走路", "single", "none", 1, 9, "{}", 2, day, day,
        )
        db.behavior_templatesQueries.insertBehaviorTemplate(
            "say", "SAY", "Say something", "说话", "single", "none", 1, 5, "{}", 1, day, day,
        )
        return Triple(packId, actorId, targetId)
    }

    // ===== 成功路径 =====

    @Test
    fun 组装_全配置齐_快照读全() {
        val (packId, actorId, targetId) = seedBase()
        val sceneId = db.scenesQueries.selectScenesByPack(packId).executeAsList().single().id

        insertSpec(
            characterId = actorId,
            aliases = """["小 A", null]""",
            softAxes = """{"proactivity":{"level":2,"text":"不先开口"}}""",
            speechStyle = "话少，短句",
            preferred = """["递吃的"]""",
        )
        // 关系：A→B、B→A 留下；A→C（对别人）要被剔除
        val otherId = insertCharacter(packId, "c", "C")
        insertRelation(actorId, targetId, "熟络的室友", "亲近", """["默默做事"]""")
        insertRelation(targetId, actorId, "依赖又不好意思说", "亲近")
        insertRelation(actorId, otherId, "同事", "疏远")

        // 文档：voice_ref 在前、activity_ref 在后；已删 / 别的 pack / 别的类型都不出
        insertDocument("v1", "voice_ref", "cp_ab", "口头禅", "先说再想")
        insertDocument("v2", "voice_ref", "cp_ab", "删掉的样本", "不该出现", deleted = true)
        insertDocument("v3", "voice_ref", "cp_other", "别人组的样本", "不该出现")
        insertDocument("a1", "activity_ref", "cp_ab", "活动素材", "楼下散步")

        // 事件：一条在清单 + 两条已发生（新的在前）+ 一条待确认 + 一条进行中（后两类只进查重靶集、不进清单）
        insertEvent(31, packId, actorId, sceneId, "想收拾房间", EventStatus.IN_LIST)
        insertEvent(32, packId, actorId, sceneId, "一起看了会儿书", EventStatus.OCCURRED, occurredAt = now - 3_600_000L)
        insertEvent(33, packId, actorId, sceneId, "各自忙了一下午", EventStatus.OCCURRED, occurredAt = now - 7_200_000L)
        insertEvent(34, packId, actorId, sceneId, "要不要去散步", EventStatus.PENDING_CONFIRMATION)
        insertEvent(35, packId, actorId, sceneId, "正走在路上", EventStatus.IN_PROGRESS)

        val context = EventContextSourceImpl(db).snapshot(now = now, utcOffsetSeconds = 0)!!

        // 角色（设定卡接上）
        assertEquals(packId, context.config.packId)
        assertEquals("A 与 B", context.config.packName)
        assertEquals("A", context.config.actor.name)
        assertEquals(listOf("小 A"), context.config.actor.aliases, "null 元素丢弃")
        assertEquals("""{"proactivity":{"level":2,"text":"不先开口"}}""", context.config.actor.softAxesRaw)
        assertEquals("话少，短句", context.config.actor.speechStyleSummary)
        assertEquals(listOf("递吃的"), context.config.actor.preferredAffectionModes)
        assertTrue(context.config.actor.dislikedAffectionModes.isEmpty())
        assertEquals("B", context.config.target.name)
        assertEquals("B 的摘要", context.config.target.summary)

        // 关系：只留 A↔B 两条（A→C 剔除）
        assertEquals(
            listOf(actorId to targetId, targetId to actorId),
            context.config.relations.map { it.fromCharacterId to it.toCharacterId },
        )
        assertEquals("熟络的室友", context.config.relations[0].towardTargetSummary)
        assertEquals(listOf("默默做事"), context.config.relations[0].likelyInitiationModes)
        assertEquals("亲近", context.config.relations[1].defaultDistance)

        // 共享设定 / 场景
        assertEquals("同居室友", context.config.pairSummary)
        assertEquals("轻松日常", context.config.relationToneSummary)
        assertEquals(listOf("冲突", "冷战"), context.config.sharedForbiddenTags)
        assertEquals("家", context.config.scene.name)
        assertEquals("PRIVATE", context.config.scene.locationType)
        assertEquals("有一张沙发和一张书桌", context.config.scene.description)

        // 动作库（sort_order 排；逻辑删除的不出）
        assertEquals(listOf("say", "walk"), context.allowedBehaviors.map { it.code })
        assertEquals("Walk somewhere", context.allowedBehaviors[1].description, "读的是喂 AI 的 description")

        // 文档（voice_ref 在前、activity_ref 在后）
        assertEquals(listOf("voice_ref", "activity_ref"), context.config.documents.map { it.docType })
        assertEquals("口头禅", context.config.documents[0].title)
        assertEquals("楼下散步", context.config.documents[1].body)

        // 时段 / 近期 / 清单
        assertEquals(Period.MORNING, context.period)
        assertEquals(listOf("一起看了会儿书", "各自忙了一下午"), context.config.recentSummaries)
        assertEquals(listOf(1), context.checklist.map { it.number })
        assertEquals(31L, context.checklist.single().eventId)
        assertEquals("想收拾房间", context.checklist.single().summary)

        // 在流程内（查重靶集用）：待确认在前、进行中在后；都只在此列、不进清单
        assertEquals(listOf("要不要去散步", "正走在路上"), context.config.inFlightSummaries)
    }

    @Test
    fun 组装_动作库逻辑删除的不出() {
        val (packId, _, _) = seedBase()
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO behavior_templates (behavior_code, behavior_name, scope, duration_seconds, limbs,
                    sort_order, is_deleted, created_at, updated_at)
                VALUES ('ghost', 'GHOST', 'single', 5, '{}', 0, 1, 0, 0)
            """.trimIndent(),
            parameters = 0,
        )

        val context = EventContextSourceImpl(db).snapshot(now = now, utcOffsetSeconds = 0)!!
        assertEquals(listOf("say", "walk"), context.allowedBehaviors.map { it.code })
        assertEquals(packId, context.config.packId)
    }

    @Test
    fun 组装_禁码投影_按场景匹配_双方并集() {
        val (_, actorId, targetId) = seedBase()
        db.behavior_templatesQueries.insertBehaviorTemplate(
            "hug", "HUG", "Hug", "拥抱", "pair", "none", 4, 6, "{}", 3, day, day,
        )
        db.behavior_templatesQueries.insertBehaviorTemplate(
            "lean", "LEAN", "Lean", "靠", "pair", "none", 3, 6, "{}", 4, day, day,
        )
        // A：恒生效禁 say；限私密禁 hug；限公开禁 lean
        db.character_hard_rulesQueries.insertHardRule(actorId, "no_say", "always", "不主动说话", """["say"]""", null, 0, day, day)
        db.character_hard_rulesQueries.insertHardRule(actorId, "no_hug_private", "private", "私下不拥抱", """["hug"]""", null, 1, day, day)
        db.character_hard_rulesQueries.insertHardRule(actorId, "no_lean_public", "public", "公开场合不靠着", """["lean"]""", null, 2, day, day)
        // B：限公开禁 walk；无码投影的规则（null）不进投影
        db.character_hard_rulesQueries.insertHardRule(targetId, "no_walk_public", "public", "公开场合不一起走", """["walk"]""", null, 0, day, day)
        db.character_hard_rulesQueries.insertHardRule(targetId, "no_longtext", "always", "不会读长文", null, null, 1, day, day)

        // 场景私密（seedBase 的"家"）：减 always 的 say + private 的 hug；public 的 walk / lean 保留
        val privateContext = EventContextSourceImpl(db).snapshot(now, 0)!!
        assertEquals(listOf("walk", "lean"), privateContext.allowedBehaviors.map { it.code })

        // 切公开：减 always 的 say + public 的 walk / lean；private 的 hug 放回
        driver.execute(identifier = null, sql = "UPDATE scenes SET location_type = 'PUBLIC'", parameters = 0)
        val publicContext = EventContextSourceImpl(db).snapshot(now, 0)!!
        assertEquals(listOf("hug"), publicContext.allowedBehaviors.map { it.code })
    }

    @Test
    fun 没指场景_取组里第一个() {
        val packId = insertPack()
        insertCharacter(packId, "a", "A")
        insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId)
        insertProfile(packId, sceneId = null)

        val context = EventContextSourceImpl(db).snapshot(now = now, utcOffsetSeconds = 0)!!
        assertEquals(sceneId, context.config.scene.id)
    }

    // ===== 组装不出 =====

    @Test
    fun 没有运行配置_或没指角色组_组装不出() {
        val source = EventContextSourceImpl(db)
        assertNull(source.snapshot(now, 0), "空库：连运行配置都没有")

        insertProfile(packId = null, sceneId = null)
        assertNull(source.snapshot(now, 0), "有配置但没指角色组")
    }

    @Test
    fun 角色不足两人_组装不出() {
        val packId = insertPack()
        insertCharacter(packId, "a", "A") // 只有一人
        val sceneId = insertScene(packId) // 场景齐全，null 只能来自角色不足
        insertProfile(packId, sceneId)

        assertNull(EventContextSourceImpl(db).snapshot(now, 0))
    }

    @Test
    fun 没有场景_组装不出() {
        val packId = insertPack()
        insertCharacter(packId, "a", "A")
        insertCharacter(packId, "b", "B")
        insertProfile(packId, sceneId = 999L) // 指的场景不存在，组里也没有别的

        assertNull(EventContextSourceImpl(db).snapshot(now, 0))
    }
}
