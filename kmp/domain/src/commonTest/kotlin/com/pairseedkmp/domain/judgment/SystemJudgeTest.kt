package com.pairseedkmp.domain.judgment

import com.pairseedkmp.domain.event.EventCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemJudgeTest {

    private fun judge(
        candidate: EventCandidate,
        actor: JudgeCharacter,
        target: JudgeCharacter? = null,
        systemJudge: SystemJudge = SystemJudge(),
    ): SystemJudgment = systemJudge.judge(snapshot(candidate, actor, target))

    @Test
    fun 三支_零偏离_放行() {
        val result = judge(
            candidate("walk", "say", verbal = 1, emotion = 1),
            actor = char(1, softAxes(phys(1), verb(1), emo(1))),
            target = char(2, softAxes(phys(1), verb(1), emo(1))),
        )
        assertIs<JudgmentOutcome.Pass>(result.outcome)
        assertEquals(0, result.risk?.value)
        assertEquals(1, result.facts.physicalLevel)
    }

    @Test
    fun 三支_情感一档_恰好门槛内放行() {
        val result = judge(
            candidate("say", emotion = 2),
            actor = char(1, softAxes(emo(4))),
            target = char(2, softAxes(emo(4))),
        )
        assertIs<JudgmentOutcome.Pass>(result.outcome)
        assertEquals(13, result.risk?.value)
    }

    @Test
    fun 三支_语言一档_拿不准() {
        val result = judge(
            candidate("say", verbal = 3),
            actor = char(1, softAxes(verb(3))),
            target = char(2, softAxes(verb(3))),
        )
        val uncertain = assertIs<JudgmentOutcome.Uncertain>(result.outcome)
        val reason = assertIs<UncertainReason.Risk>(uncertain.reason)
        assertEquals(20, reason.risk.value)
        assertEquals(20, result.risk?.value)
    }

    @Test
    fun 三支_物理一档_拿不准() {
        val result = judge(
            candidate("hug"),
            actor = char(1, softAxes(phys(2), emo(1))),
            target = char(2, softAxes(phys(2), emo(1))),
        )
        val uncertain = assertIs<JudgmentOutcome.Uncertain>(result.outcome)
        val reason = assertIs<UncertainReason.Risk>(uncertain.reason)
        assertEquals(33, reason.risk.value)
    }

    @Test
    fun 三支_物理两档_拒() {
        val result = judge(
            candidate("hug"),
            actor = char(1, softAxes(phys(3), emo(1))),
            target = char(2, softAxes(phys(3), emo(1))),
        )
        val reject = assertIs<JudgmentOutcome.Reject>(result.outcome)
        val reason = assertIs<RejectReason.Risk>(reject.reason)
        assertEquals(67, reason.risk.value)
    }

    @Test
    fun 三支_物理三档_满偏离拒() {
        val result = judge(
            candidate("hug"),
            actor = char(1, softAxes(phys(4), emo(1))),
            target = char(2, softAxes(phys(4), emo(1))),
        )
        val reject = assertIs<JudgmentOutcome.Reject>(result.outcome)
        val reason = assertIs<RejectReason.Risk>(reject.reason)
        assertEquals(100, reason.risk.value)
    }

    @Test
    fun 硬规则直通_先于缺料且不算风险分() {
        val result = judge(
            // 软档全缺（本会强制待确认），但恒生效禁码命中 → 直通拒。
            candidate("hug"),
            actor = char(1, rules = listOf(rule(1, HardRuleScope.ALWAYS, "hug"))),
            target = char(2),
        )
        val reject = assertIs<JudgmentOutcome.Reject>(result.outcome)
        val reason = assertIs<RejectReason.HardRule>(reject.reason)
        assertEquals(HardRuleHit(1, "r", HardRuleScope.ALWAYS, setOf("hug")), reason.hit)
        assertNull(result.risk)
        assertTrue(result.facts.missing.isNotEmpty())
    }

    @Test
    fun 缺料_强制待确认() {
        val unknownCode = judge(
            candidate("walk", "fly"),
            actor = char(1, softAxes(phys(1))),
            target = char(2, softAxes(phys(1))),
        )
        val uncertain = assertIs<JudgmentOutcome.Uncertain>(unknownCode.outcome)
        val reason = assertIs<UncertainReason.Missing>(uncertain.reason)
        assertEquals(listOf(MissingMaterial.StepCodeNotInTable("fly")), reason.items)
        assertNull(unknownCode.risk)

        val softMissing = judge(
            candidate("hug"),
            actor = char(1),
            target = char(2),
        )
        val softReason = assertIs<UncertainReason.Missing>(
            assertIs<JudgmentOutcome.Uncertain>(softMissing.outcome).reason,
        )
        assertEquals(2, softReason.items.size)
        assertNull(softMissing.risk)
    }

    @Test
    fun 门槛可换_校准用() {
        // 同一条 33 分的候选：默认门槛拿不准；放宽放行门槛 → 过；收紧拦截门槛 → 拒。
        val permissive = SystemJudge(passThreshold = 40)
        assertIs<JudgmentOutcome.Pass>(
            judge(
                candidate("hug"),
                actor = char(1, softAxes(phys(2), emo(1))),
                target = char(2, softAxes(phys(2), emo(1))),
                systemJudge = permissive,
            ).outcome,
        )
        val strict = SystemJudge(rejectThreshold = 30)
        val reject = assertIs<JudgmentOutcome.Reject>(
            judge(
                candidate("hug"),
                actor = char(1, softAxes(phys(2), emo(1))),
                target = char(2, softAxes(phys(2), emo(1))),
                systemJudge = strict,
            ).outcome,
        )
        assertIs<RejectReason.Risk>(reject.reason)
    }
}
