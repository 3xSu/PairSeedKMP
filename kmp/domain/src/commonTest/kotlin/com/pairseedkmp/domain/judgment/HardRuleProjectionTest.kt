package com.pairseedkmp.domain.judgment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardRuleProjectionTest {

    private val always = rule(1, HardRuleScope.ALWAYS, "say", key = "a")
    private val publicRule = rule(1, HardRuleScope.PUBLIC, "lean", key = "p")
    private val privateRule = rule(2, HardRuleScope.PRIVATE, "hug", key = "r")

    @Test
    fun 恒生效_旗标缺也减() {
        assertEquals(setOf("say"), listOf(always).projectForbiddenCodes(null))
        assertEquals(setOf("say"), listOf(always).projectForbiddenCodes(SceneVisibility.PUBLIC))
    }

    @Test
    fun 限场景_旗标匹配才减() {
        val rules = listOf(publicRule, privateRule)
        assertEquals(setOf("lean"), rules.projectForbiddenCodes(SceneVisibility.PUBLIC))
        assertEquals(setOf("hug"), rules.projectForbiddenCodes(SceneVisibility.PRIVATE))
        assertTrue(rules.projectForbiddenCodes(null).isEmpty(), "旗标缺只减恒生效")
    }

    @Test
    fun 多规则多码_并集去重() {
        val rules = listOf(
            rule(1, HardRuleScope.ALWAYS, "say", "walk", key = "a"),
            rule(2, HardRuleScope.ALWAYS, "walk", "hug", key = "b"),
        )
        assertEquals(setOf("say", "walk", "hug"), rules.projectForbiddenCodes(null))
    }

    @Test
    fun 未知作用域_按恒生效减() {
        val odd = rule(1, HardRuleScope.fromCode("weird"), "say", key = "w")
        assertEquals(setOf("say"), listOf(odd).projectForbiddenCodes(SceneVisibility.PRIVATE))
    }
}
