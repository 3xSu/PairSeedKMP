package com.pairseedkmp.data.ai_impl

import com.pairseedkmp.domain.ai.LlmJson
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.common.Period
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun request(task: LlmTask) = LlmRequest(
    system = "设定",
    user = "任务输入",
    temperature = 0.9,
    expectJson = true,
    task = task,
)

private fun parse(text: String): JsonObject =
    LlmJson.parse(text) ?: error("预设输出解析失败：$text")

class PresetLlmClientTest {

    @Test
    fun 所有任务的预设都能被解析骨架解析() = runTest {
        val client = PresetLlmClient()
        for (task in LlmTask.entries) {
            repeat(4) { round -> // 覆盖轮转一整圈
                val text = client.complete(request(task)).text
                assertNotNull(LlmJson.parse(text), "task=$task 第 ${round + 1} 条解析失败：$text")
            }
        }
    }

    @Test
    fun 事件生成预设_动作码都在调试动作库闭集内() = runTest {
        val client = PresetLlmClient()
        val allowed = setOf("walk", "sit", "say", "lie_down", "eat") // debug 种子动作库
        val summaries = mutableSetOf<String>()
        repeat(3) {
            val root = parse(client.complete(request(LlmTask.EVENT_GROW)).text)
            summaries += root.getValue("summary").jsonPrimitive.content

            val steps = root.getValue("steps").jsonArray
            assertTrue(steps.isNotEmpty(), "steps 不应为空")
            for (step in steps) {
                val code = step.jsonObject.getValue("action").jsonPrimitive.content
                assertTrue(code in allowed, "动作码不在闭集：$code")
            }

            // 三维：physical_intimacy / emotion_visibility 必给 0-100；verbal_directness 可空
            val axes = root.getValue("comparison_axes").jsonObject
            for (key in listOf("physical_intimacy", "emotion_visibility")) {
                assertTrue(axes.getValue(key).jsonPrimitive.int in 0..100, "$key 越界")
            }
            val directness = axes["verbal_directness"]
            if (directness != null && directness != JsonNull) {
                assertTrue(directness.jsonPrimitive.int in 0..100, "verbal_directness 越界")
            }

            // 时段码 ∈ 四时段
            val periods = root.getValue("suitable_time").jsonArray
            assertTrue(periods.isNotEmpty(), "suitable_time 不应为空（预设样例都给得出时段）")
            for (p in periods) Period.fromCode(p.jsonPrimitive.content)
        }
        assertEquals(3, summaries.size, "轮转应产出 3 条不同内容")
    }

    @Test
    fun 说步骤只给speaker与text_时长留给链路程序算() = runTest {
        val client = PresetLlmClient()
        var found = false
        repeat(3) {
            val root = parse(client.complete(request(LlmTask.EVENT_GROW)).text)
            for (step in root.getValue("steps").jsonArray) {
                val obj = step.jsonObject
                if (obj.getValue("action").jsonPrimitive.content == "say") {
                    found = true
                    val params = obj.getValue("params").jsonObject
                    assertTrue(
                        params.getValue("speaker").jsonPrimitive.content in setOf("actor", "target"),
                        "speaker 只能取 actor / target",
                    )
                    assertTrue(params.getValue("text").jsonPrimitive.content.isNotBlank())
                    assertTrue("duration_sec" !in params, "duration_sec 由链路程序算，预设不该给")
                }
            }
        }
        assertTrue(found, "预设中应有一条带「说」的样例")
    }

    @Test
    fun 事件生成预设含接续指认样例() = runTest {
        val client = PresetLlmClient()
        val afters = List(3) {
            parse(client.complete(request(LlmTask.EVENT_GROW)).text)["after"]
        }
        assertTrue(afters.any { it != null && it != JsonNull }, "应有一条带接续指认的样例")
        assertTrue(afters.any { it == null }, "应有无接续的样例（清单空时也能过校验）")
    }

    @Test
    fun 预设按列表轮转_用尽回到第一条() = runTest {
        val client = PresetLlmClient()
        val texts = List(4) { client.complete(request(LlmTask.EVENT_GROW)).text }
        assertEquals(texts[0], texts[3], "第 4 次应回到第 1 条")
        assertNotEquals(texts[0], texts[1])
    }

    @Test
    fun 判定预设三档齐且都带理由() = runTest {
        val client = PresetLlmClient()
        val verdicts = List(4) {
            val root = parse(client.complete(request(LlmTask.JUDGE)).text)
            assertTrue(root.getValue("reason").jsonPrimitive.content.isNotBlank(), "每条都要带理由")
            root.getValue("verdict").jsonPrimitive.content
        }
        assertEquals(setOf("PASS", "UNCERTAIN", "REJECT"), verdicts.toSet())
    }

    @Test
    fun 种子生长与查重预设形状() = runTest {
        val client = PresetLlmClient()
        repeat(3) {
            val seed = parse(client.complete(request(LlmTask.SEED_GROW)).text)
            assertTrue(seed.getValue("keyword").jsonPrimitive.content.isNotBlank())
            assertTrue(seed.getValue("theme").jsonPrimitive.content.isNotBlank())
        }
        var hit = false
        repeat(3) {
            val dedup = parse(client.complete(request(LlmTask.DEDUP)).text)
            if (dedup.getValue("same").jsonPrimitive.boolean) {
                hit = true
                assertTrue(dedup.getValue("index").jsonPrimitive.int >= 1, "同一件事必须指认第几条（1 起）")
            }
        }
        assertTrue(hit, "应有一条命中样例（同一条件事 + 指认）")
    }
}
