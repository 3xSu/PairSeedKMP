package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun character(id: Long, name: String) = GrowCharacter(id = id, name = name, summary = "$name 的摘要")

private fun behavior(code: String) = GrowBehavior(code = code, name = code.uppercase(), description = "d-$code")

private fun config(
    behaviors: List<GrowBehavior> = listOf(behavior("walk"), behavior("say"), behavior("sit")),
    forbidden: Set<String> = emptySet(),
    checklist: List<ChecklistEvent> = emptyList(),
) = GrowPackConfig(
    packId = 7,
    packName = "A 与 B",
    actor = character(1, "A"),
    target = character(2, "B"),
    scene = GrowScene(id = 9, name = "家", locationType = "PRIVATE", description = null),
    behaviors = behaviors,
    forbiddenActionCodes = forbidden,
    checklistEvents = checklist,
)

/** 某天（UTC 偏移 0 下测试）hour 点整的毫秒。 */
private fun at(hour: Int, minute: Int = 0) = (hour * 60 + minute) * MILLIS_PER_MINUTE

class EventGrowContextTest {

    @Test
    fun 组装_时段由当前时刻定() {
        assertEquals(Period.MORNING, config().assemble(now = at(8), utcOffsetSeconds = 0).period)
        assertEquals(Period.EVENING, config().assemble(now = at(20), utcOffsetSeconds = 0).period)
        // 时区偏移参与：UTC 20 点、偏移 +4 小时 → 本地 24 点 = 夜里
        assertEquals(Period.NIGHT, config().assemble(now = at(20), utcOffsetSeconds = 4 * 3600).period)
    }

    @Test
    fun 组装_禁码过滤_没禁的不动() {
        val context = config(forbidden = setOf("say", "不存在的码")).assemble(now = at(8), utcOffsetSeconds = 0)
        assertEquals(listOf("walk", "sit"), context.allowedBehaviors.map { it.code })
    }

    @Test
    fun 组装_清单编号从1起_指回事件id() {
        val context = config(
            checklist = listOf(
                ChecklistEvent(eventId = 11, summary = "想收拾房间"),
                ChecklistEvent(eventId = 12, summary = "去拿快递"),
            ),
        ).assemble(now = at(8), utcOffsetSeconds = 0)

        assertEquals(listOf(1, 2), context.checklist.map { it.number })
        assertEquals(listOf(11L, 12L), context.checklist.map { it.eventId })
        assertEquals("去拿快递", context.checklist[1].summary)
    }

    @Test
    fun parseJsonStringList_容错与去空() {
        assertEquals(listOf("a", "b"), parseJsonStringList("""["a", " b "]"""))
        assertTrue(parseJsonStringList(null).isEmpty())
        assertTrue(parseJsonStringList("").isEmpty())
        assertTrue(parseJsonStringList("  ").isEmpty())
        assertTrue(parseJsonStringList("不是 JSON").isEmpty())
        assertTrue(parseJsonStringList("""{"a":1}""").isEmpty(), "对象不是数组")
        assertEquals(listOf("a"), parseJsonStringList("""["a", null, " "]"""), "null 与空白元素丢弃")
    }
}
