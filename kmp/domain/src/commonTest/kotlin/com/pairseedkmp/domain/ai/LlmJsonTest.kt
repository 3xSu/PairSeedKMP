package com.pairseedkmp.domain.ai

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmJsonTest {

    @Test
    fun 解析纯JSON对象() {
        val obj = LlmJson.parse("""{"summary":"一起做饭","n":3}""")
        assertEquals("一起做饭", obj?.get("summary")?.jsonPrimitive?.content)
        assertEquals(3, obj?.get("n")?.jsonPrimitive?.int)
    }

    @Test
    fun 剥json围栏() {
        assertEquals(1, LlmJson.parse("```json\n{\"a\": 1}\n```")?.get("a")?.jsonPrimitive?.int)
    }

    @Test
    fun 无语言标记的围栏也能剥() {
        assertEquals(1, LlmJson.parse("```\n{\"a\": 1}\n```")?.get("a")?.jsonPrimitive?.int)
    }

    @Test
    fun 前后夹带文字也能取出() {
        val raw = "好的，这是结果：\n{\"a\": 1}\n希望有用！"
        assertEquals(1, LlmJson.parse(raw)?.get("a")?.jsonPrimitive?.int)
    }

    @Test
    fun 嵌套花括号取到末个右括号() {
        val obj = LlmJson.parse("""{"steps":[{"action":"cook"}],"tail":{"x":2}}""")
        assertEquals(2, obj?.get("tail")?.jsonObject?.get("x")?.jsonPrimitive?.int)
    }

    @Test
    fun 非JSON返回null() {
        assertNull(LlmJson.parse("抱歉，我不能这么做。"))
        assertNull(LlmJson.parse(""))
        assertNull(LlmJson.parse("{"))
        assertNull(LlmJson.parse("{\"a\": "))
    }

    @Test
    fun 数组而不是对象返回null() {
        assertNull(LlmJson.parse("[1, 2, 3]"))
    }
}
