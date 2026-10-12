package com.pairseedkmp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.pairseedkmp.data.event_impl.BehaviorDurationSourceImpl
import com.pairseedkmp.data.event_impl.EventRepositoryImpl
import com.pairseedkmp.data.judgment_impl.JudgmentSnapshotSourceImpl
import com.pairseedkmp.domain.common.MILLIS_PER_DAY
import com.pairseedkmp.domain.event.ComparisonAxes
import com.pairseedkmp.domain.event.EVENT_EXPIRY_MILLIS
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventCandidate
import com.pairseedkmp.domain.event.EventJudge
import com.pairseedkmp.domain.event.EventJudgeResult
import com.pairseedkmp.domain.event.EventOrigin
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.EventStep
import com.pairseedkmp.domain.judgment.HardRuleHit
import com.pairseedkmp.domain.judgment.HardRuleScope
import com.pairseedkmp.domain.judgment.JudgeHardRule
import com.pairseedkmp.domain.judgment.JudgmentOutcome
import com.pairseedkmp.domain.judgment.MissingMaterial
import com.pairseedkmp.domain.judgment.RejectReason
import com.pairseedkmp.domain.judgment.SceneVisibility
import com.pairseedkmp.domain.judgment.SystemJudge
import com.pairseedkmp.domain.judgment.UncertainReason
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 节点 06：判定快照读侧（含步骤 4 冷读块：场景 / 关系 / 共享设定 / 最近已发生 / 动作名）+ 出口落库——真 SQLite
 * （JVM 驱动、内存库）跑组装，含走真库的系统三支端到端与 EventJudge 三档出口（步骤 3）。
 * character_specs / character_target_relations 没有插入命名查询（与 05 测试同口径），raw SQL 种。
 */
class JudgmentSnapshotSourceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: PairSeedDatabase

    private val day = 20L * MILLIS_PER_DAY // 任意一天

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

    // ===== 种数据 =====

    private fun insertPack(): Long {
        db.character_packsQueries.insertCharacterPack("cp_ab", "A 与 B", null, day, day)
        return db.character_packsQueries.lastInsertedCharacterPackId().executeAsOne()
    }

    private fun insertCharacter(packId: Long, code: String, name: String): Long {
        db.charactersQueries.insertCharacter(packId, code, name, "$name 的摘要", day, day)
        return db.charactersQueries.lastInsertedCharacterId().executeAsOne()
    }

    private fun insertScene(packId: Long, locationType: String?): Long {
        db.scenesQueries.insertScene(
            pack_id = packId,
            scene_code = "home",
            scene_name = "家",
            location_type = locationType,
            description = null,
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

    /** 角色设定卡（raw SQL：无插入命名查询）。 */
    private fun insertSpec(characterId: Long, softAxes: String = "{}") {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO character_specs (character_id, aliases, soft_axes, speech_style_summary,
                    preferred_affection_modes, disliked_affection_modes, body_spec, wardrobe, created_at, updated_at)
                VALUES (?, NULL, ?, '——', NULL, NULL, '{}', '{}', 0, 0)
            """.trimIndent(),
            parameters = 2,
        ) {
            bindLong(0, characterId)
            bindString(1, softAxes)
        }
    }

    private fun insertBehavior(code: String, level: Int, sortOrder: Long = 1) {
        db.behavior_templatesQueries.insertBehaviorTemplate(
            behavior_code = code,
            behavior_name = code.uppercase(),
            description = "$code something",
            description_cn = code,
            scope = "single",
            position_change = "none",
            physical_level = level.toLong(),
            duration_seconds = 5,
            limbs = "{}",
            sort_order = sortOrder,
            created_at = day,
            updated_at = day,
        )
    }

    private fun insertRule(characterId: Long, key: String, scope: String, forbiddenCodes: String?, sortOrder: Long = 0) {
        db.character_hard_rulesQueries.insertHardRule(
            character_id = characterId,
            rule_key = key,
            scope = scope,
            description = "$key 的说明",
            forbidden_codes = forbiddenCodes,
            notes = null,
            sort_order = sortOrder,
            created_at = day,
            updated_at = day,
        )
    }

    /** 角色关系（raw SQL：无插入命名查询）。 */
    private fun insertRelation(from: Long, to: Long, summary: String) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO character_target_relations (character_id, target_character_id, toward_target_summary,
                    default_distance, likely_initiation_modes, sensitive_progression_tags, safe_comfort_actions,
                    direct_addressing_summary, reference_addressing_summary, created_at, updated_at)
                VALUES (?, ?, ?, '亲近', NULL, NULL, NULL, NULL, NULL, 0, 0)
            """.trimIndent(),
            parameters = 3,
        ) {
            bindLong(0, from)
            bindLong(1, to)
            bindString(2, summary)
        }
    }

    /** 已发生事件（冷读块「最近已发生」用）：走真仓库插入。 */
    private fun insertOccurred(packId: Long, actorId: Long, sceneId: Long, summary: String, occurredAt: Long) {
        EventRepositoryImpl(db).insert(
            Event(
                id = 0L,
                packId = packId,
                actorId = actorId,
                sceneId = sceneId,
                summary = summary,
                steps = listOf(EventStep("walk")),
                status = EventStatus.OCCURRED,
                origin = EventOrigin.SEED,
                occurredAt = occurredAt,
                durationSec = 5,
                expiresAt = day + EVENT_EXPIRY_MILLIS,
                createdAt = day,
                updatedAt = day,
            ),
        )
    }

    private fun candidate(
        actorId: Long,
        targetId: Long?,
        sceneId: Long,
        vararg codes: String,
        verbal: Int? = null,
        emotion: Int = 1,
    ): EventCandidate = EventCandidate(
        packId = 1L,
        actorId = actorId,
        targetId = targetId,
        sceneId = sceneId,
        summary = "概要",
        steps = codes.map { EventStep(it) },
        axes = ComparisonAxes(verbal, emotion),
        suitableTime = emptyList(),
    )

    // ===== 快照读侧 =====

    @Test
    fun 快照读全_软轴_规则_旗标_码表() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val targetId = insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        val axes = """{"physical_intimacy":{"level":1,"text":"总是主动靠近"}}"""
        insertSpec(actorId, softAxes = axes)
        insertSpec(targetId, softAxes = "{}")
        insertRule(actorId, "no_say", "always", """["say"]""")
        insertRule(targetId, "no_hug_public", "public", """["hug","lean"]""", sortOrder = 1)
        insertBehavior("walk", 1, sortOrder = 1)
        insertBehavior("say", 1, sortOrder = 2)
        insertBehavior("hug", 4, sortOrder = 3)

        val snapshot = JudgmentSnapshotSourceImpl(db).snapshot(candidate(actorId, targetId, sceneId, "hug"))!!

        assertEquals(actorId, snapshot.actor.id)
        assertEquals("A", snapshot.actor.name)
        assertEquals(axes, snapshot.actor.softAxesRaw)
        assertEquals("A 的摘要", snapshot.actor.card.summary)
        assertEquals(
            listOf(JudgeHardRule(actorId, "no_say", HardRuleScope.ALWAYS, setOf("say"), "no_say 的说明")),
            snapshot.actor.hardRules,
        )
        assertEquals(targetId, snapshot.target?.id)
        assertEquals(
            listOf(JudgeHardRule(targetId, "no_hug_public", HardRuleScope.PUBLIC, setOf("hug", "lean"), "no_hug_public 的说明")),
            snapshot.target?.hardRules,
        )
        assertEquals(SceneVisibility.PRIVATE, snapshot.sceneVisibility)
        assertEquals(mapOf("walk" to 1, "say" to 1, "hug" to 4), snapshot.behaviorLevels)
        assertEquals("家", snapshot.scene?.name) // 冷读块：场景行与旗标同源
        assertEquals(mapOf("walk" to "WALK", "say" to "SAY", "hug" to "HUG"), snapshot.behaviorNames)
        assertTrue(snapshot.recentSummaries.isEmpty())
    }

    @Test
    fun 冷读块_关系_共享设定_最近已发生() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val targetId = insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        insertSpec(actorId)
        db.pair_shared_specsQueries.insertPairSharedSpec(
            pack_id = packId,
            pair_summary = "姐弟，彼此照应",
            relation_tone_summary = "温和克制",
            shared_forbidden_tags = """["不提过去"]""",
            created_at = day,
            updated_at = day,
        )
        insertRelation(actorId, targetId, "A 照顾 B")
        insertRelation(targetId, actorId, "B 依赖 A")
        insertOccurred(packId, actorId, sceneId, "早上一起吃了饭", occurredAt = day + 60_000)

        val snapshot = JudgmentSnapshotSourceImpl(db).snapshot(candidate(actorId, targetId, sceneId, "walk"))!!

        assertEquals(listOf("A 照顾 B", "B 依赖 A"), snapshot.relations.map { it.towardTargetSummary })
        assertEquals(listOf(actorId, targetId), snapshot.relations.map { it.fromCharacterId })
        assertEquals("姐弟，彼此照应", snapshot.pairSummary)
        assertEquals("温和克制", snapshot.relationToneSummary)
        assertEquals(listOf("不提过去"), snapshot.sharedForbiddenTags)
        assertEquals(listOf("早上一起吃了饭"), snapshot.recentSummaries)
    }

    @Test
    fun 场景没标公开私密_旗标为null() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val sceneId = insertScene(packId, locationType = null)

        val snapshot = JudgmentSnapshotSourceImpl(db).snapshot(candidate(actorId, null, sceneId, "walk"))!!
        assertNull(snapshot.sceneVisibility)
    }

    @Test
    fun 规则无码投影_空集不炸() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        insertRule(actorId, "no_longtext", "always", forbiddenCodes = null)

        val snapshot = JudgmentSnapshotSourceImpl(db).snapshot(candidate(actorId, null, sceneId, "walk"))!!
        assertEquals(
            listOf(JudgeHardRule(actorId, "no_longtext", HardRuleScope.ALWAYS, emptySet(), "no_longtext 的说明")),
            snapshot.actor.hardRules,
        )
    }

    @Test
    fun 单人候选_target为空() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val sceneId = insertScene(packId, locationType = "PRIVATE")

        val snapshot = JudgmentSnapshotSourceImpl(db).snapshot(candidate(actorId, null, sceneId, "walk"))!!
        assertNull(snapshot.target)
        assertEquals(listOf(actorId), snapshot.participants.map { it.id })
    }

    @Test
    fun 组不出快照_角色或场景读不到() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val targetId = insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        val source = JudgmentSnapshotSourceImpl(db)

        assertNull(source.snapshot(candidate(999, targetId, sceneId, "walk")), "actor 不存在")
        assertNull(source.snapshot(candidate(actorId, 999, sceneId, "walk")), "target 不存在")
        assertNull(source.snapshot(candidate(actorId, targetId, 999, "walk")), "场景不存在")

        driver.execute(identifier = null, sql = "UPDATE characters SET is_deleted = 1 WHERE id = $actorId", parameters = 0)
        assertNull(source.snapshot(candidate(actorId, targetId, sceneId, "walk")), "actor 已删")
    }

    // ===== 端到端（真库 → 系统三支）=====

    @Test
    fun 端到端_拥抱对双方保守档_风险拒() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val targetId = insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        val axes3 = """{"physical_intimacy":{"level":3,"text":"x"}}"""
        insertSpec(actorId, softAxes = axes3)
        insertSpec(targetId, softAxes = axes3)
        insertBehavior("hug", 4)

        val snapshot = JudgmentSnapshotSourceImpl(db).snapshot(candidate(actorId, targetId, sceneId, "hug"))!!
        val judgment = SystemJudge().judge(snapshot)

        val reject = assertIs<JudgmentOutcome.Reject>(judgment.outcome)
        val reason = assertIs<RejectReason.Risk>(reject.reason)
        assertEquals(67, reason.risk.value)
    }

    @Test
    fun 端到端_硬规则直通_先于缺料() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val targetId = insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        insertRule(actorId, "no_hug", "always", """["hug"]""")
        insertBehavior("hug", 4)
        // 双方都没设定卡（软档全缺，本会待确认）——硬规则命中照样直通拒
        val snapshot = JudgmentSnapshotSourceImpl(db).snapshot(candidate(actorId, targetId, sceneId, "hug"))!!
        val judgment = SystemJudge().judge(snapshot)

        val reject = assertIs<JudgmentOutcome.Reject>(judgment.outcome)
        val reason = assertIs<RejectReason.HardRule>(reject.reason)
        assertEquals(HardRuleHit(actorId, "no_hug", HardRuleScope.ALWAYS, setOf("hug")), reason.hit)
        assertNull(judgment.risk)
        assertTrue(judgment.facts.missing.isNotEmpty())
    }

    @Test
    fun 端到端_码不在表_强制待确认() {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        insertBehavior("walk", 1)

        val snapshot = JudgmentSnapshotSourceImpl(db).snapshot(candidate(actorId, null, sceneId, "fly"))!!
        val judgment = SystemJudge().judge(snapshot)

        val uncertain = assertIs<JudgmentOutcome.Uncertain>(judgment.outcome)
        val reason = assertIs<UncertainReason.Missing>(uncertain.reason)
        assertEquals(listOf(MissingMaterial.StepCodeNotInTable("fly")), reason.items)
        assertNull(judgment.risk)
    }

    // ===== 出口落库（真库 → EventJudge → events 行；06 步骤 3）=====

    private fun exitJudge(repo: EventRepositoryImpl): EventJudge =
        EventJudge(JudgmentSnapshotSourceImpl(db), BehaviorDurationSourceImpl(db), repo)

    @Test
    fun 出口_过_真库落行进清单() = runTest {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        insertSpec(actorId, softAxes = """{"physical_intimacy":{"level":1,"text":"x"}}""")
        insertBehavior("walk", 1)
        val repo = EventRepositoryImpl(db)
        val cand = candidate(actorId, null, sceneId, "walk")

        val result = exitJudge(repo).deliver(cand, day)

        val inList = assertIs<EventJudgeResult.InList>(result)
        val row = repo.byId(inList.event.id)!!
        assertEquals(EventStatus.IN_LIST, row.status)
        assertEquals(EventOrigin.SEED, row.origin)
        assertEquals(cand.axes.encodeJson(), row.comparisonAxes)
        assertNull(row.confirmationPayload)
        assertNull(row.occurredAt)
        assertEquals(5, row.durationSec) // walk 的 duration_seconds = 5
        assertEquals(day + EVENT_EXPIRY_MILLIS, row.expiresAt)
        assertEquals(1, repo.visibleEvents().size)
    }

    @Test
    fun 出口_灰带_真库落行带payload可读回() = runTest {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val targetId = insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        val axes2 = """{"physical_intimacy":{"level":2,"text":"x"}}"""
        insertSpec(actorId, softAxes = axes2)
        insertSpec(targetId, softAxes = axes2)
        insertBehavior("hug", 4)
        val repo = EventRepositoryImpl(db)

        // 拥抱 e=4、双方档 2 → t=3、δ=1 → 33，落灰带
        val result = exitJudge(repo).deliver(candidate(actorId, targetId, sceneId, "hug"), day)

        val pending = assertIs<EventJudgeResult.Pending>(result)
        val row = repo.byId(pending.event.id)!!
        assertEquals(EventStatus.PENDING_CONFIRMATION, row.status)
        assertNull(row.occurredAt)
        assertEquals(1, repo.activeEvents().size) // 待确认算未完事件
        val payload = Json.parseToJsonElement(row.confirmationPayload!!).jsonObject
        assertEquals("SYSTEM", payload["source"]!!.jsonPrimitive.content)
        assertEquals("RISK", payload["reason"]!!.jsonPrimitive.content)
        assertEquals(33, payload["risk"]!!.jsonObject["value"]!!.jsonPrimitive.int)
    }

    @Test
    fun 出口_硬规则拒_真库无行() = runTest {
        val packId = insertPack()
        val actorId = insertCharacter(packId, "a", "A")
        val targetId = insertCharacter(packId, "b", "B")
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        insertRule(actorId, "no_hug", "always", """["hug"]""")
        insertBehavior("hug", 4)
        val repo = EventRepositoryImpl(db)

        val result = exitJudge(repo).deliver(candidate(actorId, targetId, sceneId, "hug"), day)

        assertIs<EventJudgeResult.Rejected>(result)
        assertTrue(repo.visibleEvents().isEmpty())
        assertNull(repo.byId(1L))
    }

    @Test
    fun 出口_快照组不出_真库无行() = runTest {
        val packId = insertPack()
        val sceneId = insertScene(packId, locationType = "PRIVATE")
        val repo = EventRepositoryImpl(db)

        val result = exitJudge(repo).deliver(candidate(999, null, sceneId, "walk"), day)

        assertIs<EventJudgeResult.NoSnapshot>(result)
        assertTrue(repo.visibleEvents().isEmpty())
    }
}
