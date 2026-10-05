package com.pairseedkmp.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

class EventStepsTest {

    private val durations = BehaviorDurationSource { code ->
        when (code) {
            "walk" -> 10
            "sit" -> 3
            else -> null
        }
    }

    @Test
    fun 解析定稿信封() {
        val raw = """[{"action":"walk","params":{}},{"action":"say","params":{"speaker":"A","text":"早安","duration_sec":6}}]"""
        val steps = EventStepsJson.parse(raw)
        assertEquals(2, steps.size)
        assertEquals("walk", steps[0].action)
        assertTrue(steps[0].params.isEmpty())
        assertEquals("早安", steps[1].params["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun 解析容错_未知键与缺省params() {
        val steps = EventStepsJson.parse("""[{"action":"sit","params":{"foo":1},"extra":"x"},{"action":"walk"}]""")
        assertEquals(2, steps.size)
        assertEquals("sit", steps[0].action)
        assertTrue(steps[1].params.isEmpty()) // params 缺省 → 空对象
    }

    @Test
    fun 序列化往返() {
        val steps = EventStepsJson.parse("""[{"action":"sit","params":{"foo":1}}]""")
        assertEquals(steps, EventStepsJson.parse(EventStepsJson.encode(steps)))
    }

    @Test
    fun 步骤时长_说用params其余查库_查不到兜底() {
        val steps = EventStepsJson.parse(
            """[{"action":"walk"},{"action":"say","params":{"duration_sec":6}},{"action":"wave"}]"""
        )
        assertEquals(10, steps[0].durationSeconds(durations))
        assertEquals(6, steps[1].durationSeconds(durations))
        assertEquals(DEFAULT_STEP_DURATION_SECONDS, steps[2].durationSeconds(durations))
        assertEquals(10 + 6 + DEFAULT_STEP_DURATION_SECONDS, steps.totalDurationSeconds(durations))
    }

    @Test
    fun 步骤位置_切步与超时() {
        val steps = EventStepsJson.parse("""[{"action":"walk"},{"action":"sit"}]""") // 10 + 3 秒

        assertEquals(StepPosition(0, 0), steps.stepPositionAt(0, durations))
        assertEquals(StepPosition(0, 0), steps.stepPositionAt(-5, durations)) // 负数夹到 0
        assertEquals(StepPosition(0, 9_999), steps.stepPositionAt(9_999, durations))
        assertEquals(StepPosition(1, 0), steps.stepPositionAt(10_000, durations)) // 恰好切步
        assertEquals(StepPosition(1, 2_999), steps.stepPositionAt(12_999, durations))
        assertEquals(StepPosition(1, 3_000), steps.stepPositionAt(13_000, durations)) // 走完 = 停在最后一步末尾
        assertEquals(StepPosition(1, 3_000), steps.stepPositionAt(99_999, durations)) // 超时（时长对不上时）也兜在最后一步

        assertEquals(StepPosition(0, 0), emptyList<EventStep>().stepPositionAt(500, durations)) // steps 为空：不该发生，兜底
    }
}
