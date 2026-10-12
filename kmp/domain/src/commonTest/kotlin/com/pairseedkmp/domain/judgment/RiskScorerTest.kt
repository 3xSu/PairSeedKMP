package com.pairseedkmp.domain.judgment

import com.pairseedkmp.domain.event.EventCandidate
import kotlin.test.Test
import kotlin.test.assertEquals

class RiskScorerTest {

    private fun risk(
        candidate: EventCandidate,
        actor: JudgeCharacter,
        target: JudgeCharacter? = null,
    ): RiskScore {
        val snap = snapshot(candidate, actor, target)
        return RiskScorer().score(snap, FactExtractor.extract(snap))
    }

    private fun deviationOf(score: RiskScore, dim: RiskDim): DimDeviation =
        score.deviations.first { it.dim == dim }

    @Test
    fun 方向校正_双方都最主动_拥抱零偏离() {
        val score = risk(
            candidate("hug"),
            actor = char(1, softAxes(phys(1), emo(1))),
            target = char(2, softAxes(phys(1), emo(1))),
        )
        assertEquals(0, score.value)
        val physical = deviationOf(score, RiskDim.PHYSICAL)
        assertEquals(4, physical.eventLevel)
        assertEquals(4, physical.tolerance)
        assertEquals(0, physical.delta)
    }

    @Test
    fun 方向校正_双方都保持距离_拥抱满偏离() {
        val score = risk(
            candidate("hug"),
            actor = char(1, softAxes(phys(4), emo(1))),
            target = char(2, softAxes(phys(4), emo(1))),
        )
        assertEquals(100, score.value)
        val physical = deviationOf(score, RiskDim.PHYSICAL)
        assertEquals(1, physical.tolerance)
        assertEquals(3, physical.delta)
    }

    @Test
    fun 方向校正_一方保守即按保守那位() {
        val score = risk(
            candidate("hug"),
            actor = char(1, softAxes(phys(1), emo(1))),
            target = char(2, softAxes(phys(4), emo(1))),
        )
        assertEquals(100, score.value)
    }

    @Test
    fun 物理维_牵手偏克制_33() {
        val score = risk(
            candidate("hold_hands"),
            actor = char(1, softAxes(phys(3), emo(1))),
            target = char(2, softAxes(phys(3), emo(1))),
        )
        assertEquals(33, score.value)
        val physical = deviationOf(score, RiskDim.PHYSICAL)
        assertEquals(3, physical.eventLevel)
        assertEquals(2, physical.tolerance)
        assertEquals(1, physical.delta)
    }

    @Test
    fun 物理维_牵手双方都保持距离_67() {
        val score = risk(
            candidate("hold_hands"),
            actor = char(1, softAxes(phys(4), emo(1))),
            target = char(2, softAxes(phys(4), emo(1))),
        )
        assertEquals(67, score.value)
        assertEquals(2, deviationOf(score, RiskDim.PHYSICAL).delta)
    }

    @Test
    fun 物理维_混合档金值_按保守那位() {
        // (2,2) 拥抱：t=3 δ=1 → 33。
        assertEquals(
            33,
            risk(
                candidate("hug"),
                actor = char(1, softAxes(phys(2), emo(1))),
                target = char(2, softAxes(phys(2), emo(1))),
            ).value,
        )
        // (2,3) 拥抱：t=2 δ=2 → 67。
        assertEquals(
            67,
            risk(
                candidate("hug"),
                actor = char(1, softAxes(phys(2), emo(1))),
                target = char(2, softAxes(phys(3), emo(1))),
            ).value,
        )
        // (1,2) 牵手：t=3 δ=0 → 0（偏主动组合够得着牵手级）。
        assertEquals(
            0,
            risk(
                candidate("hold_hands"),
                actor = char(1, softAxes(phys(1), emo(1))),
                target = char(2, softAxes(phys(2), emo(1))),
            ).value,
        )
    }

    @Test
    fun 单人_情感维按本人档_40() {
        val score = risk(
            candidate("say", emotion = 4, targetId = null),
            actor = char(1, softAxes(emo(4))),
        )
        assertEquals(40, score.value)
        val emotion = deviationOf(score, RiskDim.EMOTION)
        assertEquals(1, emotion.tolerance)
        assertEquals(3, emotion.delta)
        assertEquals(40, emotion.score)
    }

    @Test
    fun 语言维_离散表() {
        // 一档偏离：e=3 (3,3)：t=2 δ=1 → 20。
        assertEquals(
            20,
            risk(
                candidate("say", verbal = 3),
                actor = char(1, softAxes(verb(3))),
                target = char(2, softAxes(verb(3))),
            ).value,
        )
        // 两档偏离：e=4 (3,3)：t=2 δ=2 → 40。
        assertEquals(
            40,
            risk(
                candidate("say", verbal = 4),
                actor = char(1, softAxes(verb(3))),
                target = char(2, softAxes(verb(3))),
            ).value,
        )
        // 三档偏离：e=4 (4,4)：t=1 δ=3 → 60。
        assertEquals(
            60,
            risk(
                candidate("say", verbal = 4),
                actor = char(1, softAxes(verb(4))),
                target = char(2, softAxes(verb(4))),
            ).value,
        )
        // 无偏离：e=4 (1,1)：t=4 δ=0 → 0。
        assertEquals(
            0,
            risk(
                candidate("say", verbal = 4),
                actor = char(1, softAxes(verb(1))),
                target = char(2, softAxes(verb(1))),
            ).value,
        )
    }

    @Test
    fun 情感维_离散表() {
        // 一档：e=2 (4,4)：t=1 δ=1 → 13。
        assertEquals(
            13,
            risk(
                candidate("say", emotion = 2),
                actor = char(1, softAxes(emo(4))),
                target = char(2, softAxes(emo(4))),
            ).value,
        )
        // 两档：e=3 → 27。
        assertEquals(
            27,
            risk(
                candidate("say", emotion = 3),
                actor = char(1, softAxes(emo(4))),
                target = char(2, softAxes(emo(4))),
            ).value,
        )
        // 三档：e=4 → 40。
        assertEquals(
            40,
            risk(
                candidate("say", emotion = 4),
                actor = char(1, softAxes(emo(4))),
                target = char(2, softAxes(emo(4))),
            ).value,
        )
        // 无偏离：e=4 (1,1)：t=4 δ=0 → 0。
        assertEquals(
            0,
            risk(
                candidate("say", emotion = 4),
                actor = char(1, softAxes(emo(1))),
                target = char(2, softAxes(emo(1))),
            ).value,
        )
    }

    @Test
    fun 聚合_取最大维不稀释() {
        val score = risk(
            candidate("hug", verbal = 4),
            actor = char(1, softAxes(phys(3), verb(4), emo(1))),
            target = char(2, softAxes(phys(3), verb(4), emo(1))),
        )
        // 物理 e=4 (3,3)：t=2 δ=2 → 67；语言 e=4 (4,4)：t=1 δ=3 → 60；取最大 67。
        assertEquals(67, score.value)
        assertEquals(67, deviationOf(score, RiskDim.PHYSICAL).score)
        assertEquals(60, deviationOf(score, RiskDim.VERBAL).score)
        assertEquals(0, deviationOf(score, RiskDim.EMOTION).score)
    }

    @Test
    fun 没人说话_跳过语言维() {
        val score = risk(
            candidate("walk"),
            actor = char(1, softAxes(phys(1), emo(1))),
            target = char(2, softAxes(phys(1), emo(1))),
        )
        assertEquals(listOf(RiskDim.PHYSICAL, RiskDim.EMOTION), score.deviations.map { it.dim })
        assertEquals(0, score.value)
    }
}
