package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.judgment.HardRuleScope
import com.pairseedkmp.domain.judgment.JudgmentOutcome
import com.pairseedkmp.domain.judgment.JudgmentSnapshot
import com.pairseedkmp.domain.judgment.JudgmentSnapshotSource
import com.pairseedkmp.domain.judgment.RejectReason
import com.pairseedkmp.domain.judgment.candidate
import com.pairseedkmp.domain.judgment.char
import com.pairseedkmp.domain.judgment.phys
import com.pairseedkmp.domain.judgment.rule
import com.pairseedkmp.domain.judgment.snapshot
import com.pairseedkmp.domain.judgment.softAxes
import com.pairseedkmp.domain.testing.FakeEventRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 06 步骤 3：判定出口落库（三档 → status；REJECT 不落行）。
 * 假源 / 假时长 + [FakeEventRepository]；真库出口链在 data 侧 JudgmentSnapshotSourceTest。
 */
class EventJudgeTest {

    private val now = 1_000_000_000_000L

    /** 一个候选一把手的假快照源。 */
    private class LambdaSnapshotSource(private val f: (EventCandidate) -> JudgmentSnapshot?) :
        JudgmentSnapshotSource {
        override fun snapshot(candidate: EventCandidate): JudgmentSnapshot? = f(candidate)
    }

    private fun newJudge(
        repository: FakeEventRepository,
        snapshotSource: (EventCandidate) -> JudgmentSnapshot?,
        durations: BehaviorDurationSource = BehaviorDurationSource { null },
    ): EventJudge = EventJudge(LambdaSnapshotSource(snapshotSource), durations, repository)

    // ===== 三档出口 =====

    @Test
    fun 过_落库进清单_全字段透传() = runTest {
        val repo = FakeEventRepository()
        val actor = char(1, axes = softAxes(phys(1)))
        val target = char(2, axes = softAxes(phys(1)))
        val cand = candidate("walk", "say", verbal = 1, emotion = 1).copy(
            sourceSeedId = 42L,
            afterEventId = 8L,
            suitableTime = listOf(Period.EVENING, Period.NIGHT),
        )
        val judge = newJudge(repo, { snapshot(it, actor, target) }, BehaviorDurationSource { if (it == "walk") 10 else null })

        val result = judge.deliver(cand, now)

        val inList = assertIs<EventJudgeResult.InList>(result)
        assertEquals(JudgmentOutcome.Pass, inList.decision.outcome)
        val event = inList.event
        assertEquals(EventStatus.IN_LIST, event.status)
        assertEquals(EventOrigin.SEED, event.origin)
        assertEquals(7L, event.packId)
        assertEquals(1L, event.actorId)
        assertEquals(2L, event.targetId)
        assertEquals(9L, event.sceneId)
        assertEquals("概要", event.summary)
        assertEquals(cand.steps, event.steps)
        assertEquals(cand.axes.encodeJson(), event.comparisonAxes)
        assertNull(event.confirmationPayload)
        assertEquals(listOf(Period.EVENING, Period.NIGHT), event.suitableTime)
        assertEquals(42L, event.sourceSeedId)
        assertEquals(8L, event.afterEventId)
        assertNull(event.occurredAt)
        assertNull(event.semiFixedTemplateCode)
        assertEquals(15, event.durationSec) // walk 10 + say 兜底 5
        assertEquals(now + EVENT_EXPIRY_MILLIS, event.expiresAt)
        assertEquals(now, event.createdAt)
        assertEquals(now, event.updatedAt)
        assertFalse(event.isDeleted)
        assertEquals(listOf(event), repo.all()) // 库 id 已带回、行就是这么存的
    }

    @Test
    fun 缺码_待确认_落行带缺料payload() = runTest {
        val repo = FakeEventRepository()
        val actor = char(1, axes = softAxes(phys(1)))
        val judge = newJudge(repo, { snapshot(it, actor, target = null) })

        val result = judge.deliver(candidate("fly", targetId = null), now)

        val pending = assertIs<EventJudgeResult.Pending>(result)
        assertEquals(EventStatus.PENDING_CONFIRMATION, pending.event.status)
        assertNull(pending.event.occurredAt)
        val payload = Json.parseToJsonElement(pending.event.confirmationPayload!!).jsonObject
        assertEquals("SYSTEM", payload["source"]!!.jsonPrimitive.content)
        assertEquals("MISSING", payload["reason"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("step_code_not_in_table:fly"),
            payload["missing"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(listOf(pending.event), repo.all())
    }

    @Test
    fun 软档缺料_待确认_payload带角色与维_其余字段照传() = runTest {
        val repo = FakeEventRepository()
        val actor = char(1, axes = softAxes(phys(2)))
        val target = char(2, axes = null) // 没设定卡
        val cand = candidate("hug").copy(suitableTime = listOf(Period.MORNING), sourceSeedId = 42L)
        val judge = newJudge(repo, { snapshot(it, actor, target) })

        val result = judge.deliver(cand, now)

        val pending = assertIs<EventJudgeResult.Pending>(result)
        assertEquals(listOf(Period.MORNING), pending.event.suitableTime)
        assertEquals(42L, pending.event.sourceSeedId)
        val payload = Json.parseToJsonElement(pending.event.confirmationPayload!!).jsonObject
        assertEquals("MISSING", payload["reason"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("soft_axis_missing:2:physical_intimacy"),
            payload["missing"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun 灰带_待确认_payload带R与偏离事实() = runTest {
        val repo = FakeEventRepository()
        val actor = char(1, axes = softAxes(phys(2)))
        val target = char(2, axes = softAxes(phys(2)))
        val judge = newJudge(repo, { snapshot(it, actor, target) })

        // 拥抱 e=4、双方档 2 → t=3、δ=1 → 33，落 15<33<65 灰带
        val result = judge.deliver(candidate("hug"), now)

        val pending = assertIs<EventJudgeResult.Pending>(result)
        val payload = Json.parseToJsonElement(pending.event.confirmationPayload!!).jsonObject
        assertEquals("RISK", payload["reason"]!!.jsonPrimitive.content)
        val risk = payload["risk"]!!.jsonObject
        assertEquals(33, risk["value"]!!.jsonPrimitive.int)
        val deviations = risk["deviations"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("PHYSICAL", "EMOTION"), deviations.map { it["dim"]!!.jsonPrimitive.content })
        assertEquals(4, deviations[0]["event_level"]!!.jsonPrimitive.int)
        assertEquals(3, deviations[0]["tolerance"]!!.jsonPrimitive.int)
        assertEquals(1, deviations[0]["delta"]!!.jsonPrimitive.int)
        assertEquals(33, deviations[0]["score"]!!.jsonPrimitive.int)
        assertEquals(0, deviations[1]["score"]!!.jsonPrimitive.int)
    }

    @Test
    fun 硬规则拒_不留痕() = runTest {
        val repo = FakeEventRepository()
        val actor = char(1, axes = softAxes(phys(2)), rules = listOf(rule(1, HardRuleScope.ALWAYS, "hug", key = "no_hug")))
        val target = char(2, axes = softAxes(phys(2)))
        val judge = newJudge(repo, { snapshot(it, actor, target) })

        val result = judge.deliver(candidate("hug"), now)

        val rejected = assertIs<EventJudgeResult.Rejected>(result)
        val reject = assertIs<JudgmentOutcome.Reject>(rejected.decision.outcome)
        assertIs<RejectReason.HardRule>(reject.reason)
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun 风险拒_不留痕() = runTest {
        val repo = FakeEventRepository()
        val actor = char(1, axes = softAxes(phys(4)))
        val target = char(2, axes = softAxes(phys(4)))
        val judge = newJudge(repo, { snapshot(it, actor, target) })

        // 拥抱 e=4、双方档 4 → t=1、δ=3 → 100 ≥ 65
        val result = judge.deliver(candidate("hug"), now)

        val rejected = assertIs<EventJudgeResult.Rejected>(result)
        val reject = assertIs<JudgmentOutcome.Reject>(rejected.decision.outcome)
        val reason = assertIs<RejectReason.Risk>(reject.reason)
        assertEquals(100, reason.risk.value)
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun 快照组不出_不收下_不落行() = runTest {
        val repo = FakeEventRepository()
        val judge = newJudge(repo, { null })

        val result = judge.deliver(candidate("walk"), now)

        assertIs<EventJudgeResult.NoSnapshot>(result)
        assertTrue(repo.all().isEmpty())
    }

    // ===== 补货口适配 =====

    @Test
    fun sink适配_过用拒都继续_快照缺停本轮() = runTest {
        val repo = FakeEventRepository()
        val actor = char(1, axes = softAxes(phys(1)), rules = listOf(rule(1, HardRuleScope.ALWAYS, "hug", key = "no_hug")))
        val target = char(2, axes = softAxes(phys(1)))
        val judge = newJudge(repo, { c -> if (c.summary == "组不出") null else snapshot(c, actor, target) })
        val sink = judge.asCandidateSink(now)

        assertTrue(sink.deliver(candidate("walk")), "过 → 继续补别条")
        assertTrue(sink.deliver(candidate("hug")), "拒（不落行）→ 仍算处理过，继续")
        assertTrue(sink.deliver(candidate("fly")), "缺料 → 待确认落行，继续")
        assertFalse(sink.deliver(candidate("walk").copy(summary = "组不出")), "快照组不出 → 停本轮")

        assertEquals(
            listOf(EventStatus.IN_LIST, EventStatus.PENDING_CONFIRMATION),
            repo.all().map { it.status },
        )
    }
}
