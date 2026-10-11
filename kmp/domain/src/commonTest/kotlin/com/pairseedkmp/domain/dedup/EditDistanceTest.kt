package com.pairseedkmp.domain.dedup

import kotlin.test.Test
import kotlin.test.assertEquals

/** 编辑距离与相似度（《工作链路·查重链路》1.2 短词 L2）。 */
class EditDistanceTest {

    @Test
    fun 相同串距离为零() {
        assertEquals(0, editDistance("深夜便利店", "深夜便利店"))
        assertEquals(0, editDistance("", ""))
    }

    @Test
    fun 单字符替换() {
        assertEquals(1, editDistance("早上跑步", "早上散步"))
        assertEquals(1, editDistance("花瓶", "花盆"))
    }

    @Test
    fun 插入与删除() {
        assertEquals(1, editDistance("深夜便利店", "深夜的便利店")) // 插入「的」
        assertEquals(1, editDistance("折叠雨伞", "折叠伞")) // 删除「雨」
        assertEquals(3, editDistance("", "abc"))
        assertEquals(3, editDistance("abc", ""))
    }

    @Test
    fun 完全不同串() {
        assertEquals(3, editDistance("abc", "xyz"))
    }

    @Test
    fun 相似度_完全相同为一() {
        assertEquals(1.0, similarity("深夜便利店", "深夜便利店"))
    }

    @Test
    fun 相似度_按最长串归一() {
        assertEquals(1.0 - 1.0 / 6, similarity("深夜便利店", "深夜的便利店"), 1e-9) // ≈0.833
        assertEquals(0.5, similarity("花瓶", "花盆"))
        assertEquals(0.0, similarity("abc", "xyz"))
        assertEquals(0.0, similarity("", "abc"))
        assertEquals(1.0, similarity("", ""))
    }
}
