package com.pairseedkmp.domain.dedup

import kotlin.test.Test
import kotlin.test.assertEquals

/** 规范化（《工作链路·查重链路》1.1）：全角 / 大小写 / 空白 / 标点 / 简繁归一。 */
class NormalizeTest {

    @Test
    fun 全角字母数字转半角() {
        assertEquals("abc123", normalize("ＡＢＣ１２３"))
    }

    @Test
    fun 英文转小写() {
        assertEquals("helloworld", normalize("Hello World"))
    }

    @Test
    fun 空白去除_半角全角与不间断空格() {
        assertEquals("你好世界", normalize("你好 世界"))
        assertEquals("你好世界", normalize("你好" + Char(0x3000) + "世界")) // 全角空格 U+3000
        assertEquals("你好世界", normalize("你好" + Char(0xA0) + "世界")) // 不间断空格 U+00A0
    }

    @Test
    fun 标点去除_中英() {
        assertEquals("早上好", normalize("早上好！"))
        assertEquals("雨天散步", normalize("雨天，散步。"))
        assertEquals("旧毛衣", normalize("「旧毛衣」"))
        assertEquals("abc", normalize("a.b,c"))
        assertEquals("coops", normalize("co-op's"))
    }

    @Test
    fun 简繁归一_常用字() {
        assertEquals("说这里的后方很复杂", normalize("說這裡的後方很複雜"))
    }

    @Test
    fun 简繁归一_常见词对() {
        assertEquals(normalize("旧毛衣"), normalize("舊毛衣"))
        assertEquals("周末", normalize("週末"))
        assertEquals("时间", normalize("時間"))
        assertEquals("眼镜", normalize("眼鏡"))
    }

    @Test
    fun 未收录多义字保持原样() {
        // 乾 一繁对多简（乾 / 干），宁缺勿错不收录；淨 收 → 结果「乾净」不命中「干净」
        assertEquals("乾净", normalize("乾淨"))
        // 著 / 瞭 同理不收录
        assertEquals("著作", normalize("著作"))
        assertEquals("瞭望", normalize("瞭望"))
    }

    @Test
    fun 纯标点规范化后为空() {
        assertEquals("", normalize("，。！？…"))
        assertEquals("", normalize("  " + Char(0x3000) + Char(0xA0) + "  "))
    }

    @Test
    fun 组合_全角简繁标点混用() {
        assertEquals("abc说再见", normalize("ＡＢＣ，說" + Char(0x3000) + "再見！"))
    }
}
