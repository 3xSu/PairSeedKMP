package com.pairseedkmp.data.ai_impl

import com.pairseedkmp.domain.ai.LlmJson
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.event.tagStageSchema
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

private fun request(task: LlmTask, responseSchema: String? = null) = LlmRequest(
    system = "设定",
    user = "任务输入",
    temperature = 0.9,
    expectJson = true,
    task = task,
    responseSchema = responseSchema,
)

private fun parse(text: String): JsonObject =
    LlmJson.parse(text) ?: error("预设输出解析失败：$text")

class PresetLlmClientTest {

    @Test
    fun 所有任务的预设都能被解析骨架解析() = runTest {
        val client = PresetLlmClient()
        for (task in LlmTask.entries) {
            repeat(6) { round -> // 覆盖最长轮转一整圈（EVENT_GROW 6 条）
                val schema = if (task == LlmTask.EVENT_TAG) tagStageSchema(round % 2 == 0) else null
                val text = client.complete(request(task, schema)).text
                assertNotNull(LlmJson.parse(text), "task=$task 第 ${round + 1} 条解析失败：$text")
            }
        }
    }

    @Test
    fun 事件生成预设_动作码都在调试动作库闭集内() = runTest {
        val client = PresetLlmClient()
        val allowed = setOf("walk", "sit", "say", "lie_down", "eat") // debug 种子动作库
        val summaries = mutableSetOf<String>()
        repeat(6) {
            val root = parse(client.complete(request(LlmTask.EVENT_GROW)).text)
            summaries += root.getValue("summary").jsonPrimitive.content

            val steps = root.getValue("steps").jsonArray
            assertTrue(steps.isNotEmpty(), "steps 不应为空")
            for (step in steps) {
                val code = step.jsonObject.getValue("action").jsonPrimitive.content
                assertTrue(code in allowed, "动作码不在闭集：$code")
            }
        }
        assertEquals(6, summaries.size, "轮转应产出 6 条不同内容")
    }

    @Test
    fun 说步骤只给speaker与text_时长留给链路程序算() = runTest {
        val client = PresetLlmClient()
        var found = false
        repeat(6) {
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
    fun 打标段预设_按固化schema给verbal_含台词档位_无台词null() = runTest {
        val client = PresetLlmClient()
        val speechVerbal = mutableSetOf<Int>()
        repeat(4) { round ->
            val root = parse(client.complete(request(LlmTask.EVENT_TAG, tagStageSchema(hasSpeech = true))).text)
            val axes = root.getValue("comparison_axes").jsonObject
            val verbal = axes.getValue("verbal_directness").jsonPrimitive.int
            assertTrue(verbal in 1..4, "有台词时 verbal 应在 1-4：$verbal（第 ${round + 1} 条）")
            speechVerbal += verbal
            val emotion = axes.getValue("emotion_visibility").jsonPrimitive.int
            assertTrue(emotion in 1..4, "emotion 应在 1-4：$emotion")
            for (p in root.getValue("suitable_time").jsonArray) {
                Period.fromCode(p.jsonPrimitive.content) // 时段码合法（含空数组）
            }
        }
        assertTrue(speechVerbal.size > 1, "verbal 应随轮转变化：$speechVerbal")

        val silent = parse(client.complete(request(LlmTask.EVENT_TAG, tagStageSchema(hasSpeech = false))).text)
        assertEquals(
            JsonNull,
            silent.getValue("comparison_axes").jsonObject.getValue("verbal_directness"),
            "无台词时固化 schema 只放行 null",
        )
    }

    @Test
    fun 预设按列表轮转_用尽回到第一条() = runTest {
        val client = PresetLlmClient()
        val texts = List(7) { client.complete(request(LlmTask.EVENT_GROW)).text }
        assertEquals(texts[0], texts[6], "第 7 次应回到第 1 条")
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
