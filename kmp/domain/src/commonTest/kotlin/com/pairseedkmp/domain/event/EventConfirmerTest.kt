package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.common.MILLIS_PER_DAY
import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.testing.FakeEventRepository
import com.pairseedkmp.domain.testing.FakeUserConfirmationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 步骤 5：一级确认——答是 / 答否 × 单 / 多时段、改答回滚、可答门（《工作链路·确认链路 1.4–1.6》）。 */
class EventConfirmerTest {

    private val day = 10L * MILLIS_PER_DAY // 任一天（以下都按 UTC 偏移 0 算时段）
    private val sevenDays = 7L * MILLIS_PER_DAY

    private fun at(hour: Int, minute: Int = 0): Long = day + (hour * 60 + minute) * MILLIS_PER_MINUTE

    private val steps = EventStepsJson.parse("""[{"action":"walk"}]""")

    private fun pending(
        id: Long = 1,
        suitable: List<Period>,
        expiresAt: Long = day + sevenDays,
    ) = Event(
        id = id,
        packId = 7,
        actorId = 3,
        sceneId = 5,
        summary = "打游戏",
        steps = steps,
        suitableTime = suitable,
        status = EventStatus.PENDING_CONFIRMATION,
        origin = EventOrigin.SEED,
        durationSec = 10,
        expiresAt = expiresAt,
        createdAt = day,
        updatedAt = day,
    )

    private class Fixture(vararg initial: Event) {
        val events = FakeEventRepository(initial.toList())
        val confirmations = FakeUserConfirmationRepository()
        val confirmer = EventConfirmer(events, confirmations)
    }

    // ===== 可答门 =====

    @Test
    fun 可答门_待确认_当前时段在时段里才可答() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING)))
        assertTrue(f.confirmer.canAnswer(1, at(8), 0))
        assertFalse(f.confirmer.canAnswer(1, at(14), 0)) // 下午不在"早上"里
    }

    @Test
    fun 可答门_多时段_每个时段都能答_别的时段不能() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING, Period.EVENING)))
        assertTrue(f.confirmer.canAnswer(1, at(8), 0))
        assertTrue(f.confirmer.canAnswer(1, at(20), 0))
        assertFalse(f.confirmer.canAnswer(1, at(14), 0))
    }

    @Test
    fun 可答门_进行中与已发生_不可答() {
        val running = pending(suitable = listOf(Period.MORNING)).copy(status = EventStatus.IN_PROGRESS)
        val occurred = pending(suitable = listOf(Period.MORNING)).copy(status = EventStatus.OCCURRED)
        val f = Fixture(running, occurred)
        assertFalse(f.confirmer.canAnswer(1, at(8), 0))
        assertFalse(f.confirmer.canAnswer(2, at(8), 0))
    }

    @Test
    fun 可答门_过期后不可答() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING), expiresAt = at(9)))
        assertFalse(f.confirmer.canAnswer(1, at(10), 0)) // 已过过期点（还没被结算滚出的空窗里也不给答）
    }

    @Test
    fun 可答门_空时段_不给答() {
        // "空 = 不限"只用于触发挑选；确认链路本阶段的事件都带时段，不带时段的不给答
        val f = Fixture(pending(suitable = emptyList()))
        assertFalse(f.confirmer.canAnswer(1, at(8), 0))
        assertNull(f.confirmer.answer(1, ConfirmationAnswer.YES, at(8), 0))
        assertTrue(f.confirmations.all().isEmpty())
    }

    @Test
    fun 可答门_已拒的_只在被拒那个时段可改答() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING)))
        f.confirmer.answer(1, ConfirmationAnswer.NO, at(8), 0)!! // 早上否 → 单时段摘空 → 已拒
        assertEquals(EventStatus.REJECTED, f.events.byId(1)!!.status)

        assertTrue(f.confirmer.canAnswer(1, at(8), 0), "被拒的那个时段 = 改答入口")
        assertFalse(f.confirmer.canAnswer(1, at(20), 0), "别的时段不可答")
    }

    // ===== 答是 / 答否 × 单 / 多时段 =====

    @Test
    fun 答是_单时段_进清单_时段不动() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING)))
        val now = at(8)

        val result = f.confirmer.answer(1, ConfirmationAnswer.YES, now, 0)!!

        assertEquals(EventStatus.IN_LIST, result.event.status)
        assertEquals(listOf(Period.MORNING), result.event.suitableTime)
        assertFalse(result.rejected)
        assertEquals(Period.MORNING, result.answeredPeriod)
        assertEquals(now, result.event.updatedAt)
        assertEquals(EventStatus.IN_LIST, f.events.byId(1)!!.status) // 落了库
        val row = f.confirmations.all().single()
        assertEquals(1L, row.eventId)
        assertEquals(Period.MORNING, row.answeredPeriod)
        assertEquals(ConfirmationAnswer.YES, row.answer)
        assertEquals(now, row.answeredAt)
    }

    @Test
    fun 答是_多时段_时段全保留() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING, Period.EVENING)))
        val result = f.confirmer.answer(1, ConfirmationAnswer.YES, at(8), 0)!!
        assertEquals(EventStatus.IN_LIST, result.event.status)
        assertEquals(listOf(Period.MORNING, Period.EVENING), result.event.suitableTime)
    }

    @Test
    fun 答否_单时段_摘空即已拒() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING)))
        val result = f.confirmer.answer(1, ConfirmationAnswer.NO, at(8), 0)!!

        assertTrue(result.rejected)
        assertEquals(EventStatus.REJECTED, result.event.status)
        assertTrue(result.event.suitableTime.isEmpty()) // 摘空
        assertEquals(Period.MORNING, result.answeredPeriod) // 记着"是在哪个时段被拒的"（1.6）
        assertEquals(EventStatus.REJECTED, f.events.byId(1)!!.status)
    }

    @Test
    fun 答否_多时段_摘当前留其余_进清单() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING, Period.EVENING)))
        val result = f.confirmer.answer(1, ConfirmationAnswer.NO, at(8), 0)!!

        assertFalse(result.rejected)
        assertEquals(EventStatus.IN_LIST, result.event.status)
        assertEquals(listOf(Period.EVENING), result.event.suitableTime) // 还有别的时段能留 = 时间问题
    }

    @Test
    fun 答否_摘的是当下这个时段() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING, Period.EVENING)))
        val result = f.confirmer.answer(1, ConfirmationAnswer.NO, at(20), 0)!!
        assertEquals(listOf(Period.MORNING), result.event.suitableTime) // 摘的是晚上，早上留着
        assertEquals(Period.EVENING, result.answeredPeriod)
    }

    // ===== 改答（倒着回滚 + 落新答，一次完成）=====

    @Test
    fun 改答_摘过的加回_改答是_时段复原() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING, Period.EVENING)))
        f.confirmer.answer(1, ConfirmationAnswer.NO, at(8), 0)!! // 早上否：摘 M → 剩 E
        assertEquals(listOf(Period.EVENING), f.events.byId(1)!!.suitableTime)

        val result = f.confirmer.answer(1, ConfirmationAnswer.YES, at(20), 0)!! // 晚上改答是

        assertEquals(EventStatus.IN_LIST, result.event.status)
        assertEquals(listOf(Period.MORNING, Period.EVENING), result.event.suitableTime) // 摘过的加回来
        assertEquals(2, f.confirmations.all().size) // 只插不删：两条都在
        assertEquals(ConfirmationAnswer.YES, f.confirmations.latestFor(1)!!.answer) // 最新生效
    }

    @Test
    fun 改答_拒过的改答是_离开已拒() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING)))
        f.confirmer.answer(1, ConfirmationAnswer.NO, at(8), 0)!! // → 已拒
        assertEquals(EventStatus.REJECTED, f.events.byId(1)!!.status)

        val result = f.confirmer.answer(1, ConfirmationAnswer.YES, at(8), 0)!!

        assertEquals(EventStatus.IN_LIST, result.event.status) // 拒过的恢复
        assertEquals(listOf(Period.MORNING), result.event.suitableTime)
    }

    @Test
    fun 改答_摘过的再摘另一个_倒着回滚() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING, Period.EVENING)))
        f.confirmer.answer(1, ConfirmationAnswer.NO, at(8), 0)!! // 摘 M → [E]

        val result = f.confirmer.answer(1, ConfirmationAnswer.NO, at(20), 0)!! // 改答：M 加回、摘 E

        assertEquals(EventStatus.IN_LIST, result.event.status)
        assertEquals(listOf(Period.MORNING), result.event.suitableTime)
        assertEquals(Period.EVENING, result.answeredPeriod)
        assertEquals(2, f.confirmations.all().size)
    }

    @Test
    fun 改答_拒过的再答否_依旧已拒() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING)))
        f.confirmer.answer(1, ConfirmationAnswer.NO, at(8), 0)!!
        val result = f.confirmer.answer(1, ConfirmationAnswer.NO, at(8), 0)!!
        assertTrue(result.rejected)
        assertEquals(EventStatus.REJECTED, result.event.status)
        assertEquals(2, f.confirmations.all().size)
    }

    // ===== 门不过就不落 =====

    @Test
    fun 不可答时不给答_不落记录() {
        val f = Fixture(pending(suitable = listOf(Period.MORNING)))
        val result = f.confirmer.answer(1, ConfirmationAnswer.YES, at(14), 0) // 下午不在时段里

        assertNull(result)
        assertTrue(f.confirmations.all().isEmpty()) // 没落记录
        val stored = f.events.byId(1)!!
        assertEquals(EventStatus.PENDING_CONFIRMATION, stored.status) // 事件也没动
        assertEquals(listOf(Period.MORNING), stored.suitableTime)
    }
}
