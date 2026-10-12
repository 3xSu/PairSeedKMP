package com.pairseedkmp.domain.judgment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FactExtractorTest {

    @Test
    fun 物理档_多步取码表最大档() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("walk", "hug", "say"),
                actor = char(1, softAxes(phys(1))),
                target = char(2, softAxes(phys(1))),
            ),
        )
        assertEquals(4, facts.physicalLevel)
        assertTrue(facts.missing.isEmpty())
    }

    @Test
    fun 物理档_未知码记缺料_已知码照常取max() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("hug", "fly", "fly"),
                actor = char(1, softAxes(phys(1))),
                target = char(2, softAxes(phys(1))),
            ),
        )
        assertEquals(4, facts.physicalLevel)
        // 去重后只记一条未知码。
        assertEquals(listOf(MissingMaterial.StepCodeNotInTable("fly")), facts.missing)
    }

    @Test
    fun 软档缺料_事件档足够时双方各记一条() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("hug"),
                actor = char(1, softAxes(emo(1))),
                target = char(2, softAxes(emo(1))),
            ),
        )
        assertEquals(
            listOf(
                MissingMaterial.SoftAxisMissing(1, RiskDim.PHYSICAL),
                MissingMaterial.SoftAxisMissing(2, RiskDim.PHYSICAL),
            ),
            facts.missing,
        )
    }

    @Test
    fun 软档缺料_事件档一档时不查该维() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("walk", "say"),
                actor = char(1),
                target = char(2),
            ),
        )
        assertEquals(1, facts.physicalLevel)
        assertTrue(facts.missing.isEmpty())
    }

    @Test
    fun 单人事件_只查本人软档() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("say", emotion = 4, targetId = null),
                actor = char(1),
                target = null,
            ),
        )
        assertEquals(4, facts.levelOf(RiskDim.EMOTION))
        assertEquals(listOf(MissingMaterial.SoftAxisMissing(1, RiskDim.EMOTION)), facts.missing)
    }

    @Test
    fun 硬规则_恒生效_命中记录角色与命中码() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("hug", "walk"),
                actor = char(1, softAxes(phys(1)), rules = listOf(rule(1, HardRuleScope.ALWAYS, "hug", "lean"))),
                target = char(2, softAxes(phys(1))),
            ),
        )
        assertEquals(HardRuleHit(1, "r", HardRuleScope.ALWAYS, setOf("hug")), facts.hardRuleHit)
        assertTrue(facts.missing.isEmpty())
    }

    @Test
    fun 硬规则_按场景_公开命中_私密不命中() {
        val rules = listOf(rule(1, HardRuleScope.PUBLIC, "hug"))
        val public = FactExtractor.extract(
            snapshot(
                candidate("hug"),
                actor = char(1, softAxes(phys(1)), rules = rules),
                target = char(2, softAxes(phys(1))),
                visibility = SceneVisibility.PUBLIC,
            ),
        )
        assertEquals(HardRuleHit(1, "r", HardRuleScope.PUBLIC, setOf("hug")), public.hardRuleHit)

        val private = FactExtractor.extract(
            snapshot(
                candidate("hug"),
                actor = char(1, softAxes(phys(1)), rules = rules),
                target = char(2, softAxes(phys(1))),
                visibility = SceneVisibility.PRIVATE,
            ),
        )
        assertEquals(null, private.hardRuleHit)
        assertTrue(private.missing.isEmpty())
    }

    @Test
    fun 硬规则_旗标缺_不算命中且记缺料() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("hug"),
                actor = char(1, softAxes(phys(1)), rules = listOf(rule(1, HardRuleScope.PUBLIC, "hug"))),
                target = char(2, softAxes(phys(1))),
                visibility = null,
            ),
        )
        assertEquals(null, facts.hardRuleHit)
        assertEquals(listOf(MissingMaterial.SceneVisibilityMissing), facts.missing)
    }

    @Test
    fun 硬规则_旗标缺_但恒生效规则照常命中() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("hug"),
                actor = char(
                    1,
                    softAxes(phys(1)),
                    rules = listOf(
                        rule(1, HardRuleScope.PUBLIC, "hug", key = "pub"),
                        rule(1, HardRuleScope.ALWAYS, "hug", key = "always"),
                    ),
                ),
                target = char(2, softAxes(phys(1))),
                visibility = null,
            ),
        )
        assertEquals("always", facts.hardRuleHit?.ruleKey)
        // 旗标缺照记（按场景的规则仍在局中）。
        assertEquals(listOf(MissingMaterial.SceneVisibilityMissing), facts.missing)
    }

    @Test
    fun 硬规则_目标角色的规则也命中() {
        val facts = FactExtractor.extract(
            snapshot(
                candidate("hug"),
                actor = char(1, softAxes(phys(1))),
                target = char(2, softAxes(phys(1)), rules = listOf(rule(2, HardRuleScope.ALWAYS, "hug", key = "t"))),
            ),
        )
        assertEquals(2L, facts.hardRuleHit?.characterId)
        assertEquals("t", facts.hardRuleHit?.ruleKey)
    }

    @Test
    fun 未知作用域_按恒生效() {
        assertEquals(HardRuleScope.ALWAYS, HardRuleScope.fromCode("weird"))
        assertEquals(HardRuleScope.ALWAYS, HardRuleScope.fromCode(null))
        assertEquals(HardRuleScope.PUBLIC, HardRuleScope.fromCode("public"))
        val facts = FactExtractor.extract(
            snapshot(
                candidate("hug"),
                actor = char(
                    1,
                    softAxes(phys(1)),
                    rules = listOf(rule(1, HardRuleScope.fromCode("weird"), "hug")),
                ),
                target = char(2, softAxes(phys(1))),
                visibility = null,
            ),
        )
        assertEquals(HardRuleScope.ALWAYS, facts.hardRuleHit?.scope)
    }

    @Test
    fun 档位越界_防御缺料() {
        val verbal = FactExtractor.extract(
            snapshot(
                candidate("walk", verbal = 7),
                actor = char(1),
                target = char(2),
            ),
        )
        assertTrue(verbal.missing.contains(MissingMaterial.AxisLevelOutOfRange(RiskDim.VERBAL, 7)))

        val emotion = FactExtractor.extract(
            snapshot(
                candidate("walk", emotion = 5),
                actor = char(1),
                target = char(2),
            ),
        )
        assertTrue(emotion.missing.contains(MissingMaterial.AxisLevelOutOfRange(RiskDim.EMOTION, 5)))
    }
}
