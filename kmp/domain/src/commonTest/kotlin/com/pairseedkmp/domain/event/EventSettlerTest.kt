package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.runtime.RuntimeProfile
import com.pairseedkmp.domain.testing.FakeEventRepository
import com.pairseedkmp.domain.testing.FakeRuntimeProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventSettlerTest {

    private val t0 = 1_791_072_000_000L // 生成时刻（对齐 EventTest 的样例）
    private val sevenDays = 7L * 86_400_000L

    private val durations = BehaviorDurationSource { code ->
        when (code) {
            "walk" -> 10
            "sit" -> 3
            else -> null
        }
    }

    /** walk 10s + sit 3s = 13s（与 durationSec 默认值对齐）。 */
    private val steps = EventStepsJson.parse("""[{"action":"walk"},{"action":"sit"}]""")

    private fun event(
        id: Long = 1,
        status: EventStatus,
        occurredAt: Long? = null,
        expiresAt: Long = t0 + sevenDays,
        durationSec: Int = 13,
    ) = Event(
        id = id,
        packId = 1,
        actorId = 1,
        sceneId = 1,
        summary = "散步",
        steps = steps,
        status = status,
        origin = EventOrigin.SEED,
        occurredAt = occurredAt,
        durationSec = durationSec,
        expiresAt = expiresAt,
        createdAt = t0,
        updatedAt = t0,
    )

    // ===== 纯决策：结束边界 / 过期 / 空闲 / 步骤位置 =====

    @Test
    fun 进行中_提前还在跑并给出步骤位置() {
        val e = event(status = EventStatus.IN_PROGRESS, occurredAt = t0) // 结束 = t0 + 13_000
        val plan = planSettlement(listOf(e), lastOccurredEnd = t0, sessionAnchor = t0, now = t0 + 12_999, durations = durations)
        assertTrue(plan.finished.isEmpty())
        assertEquals(1, plan.running.size)
        val position = plan.running[0].position
        assertEquals(1, position.index) // walk 10 秒已走完，在第 2 步（sit）
        assertEquals(2_999L, position.elapsedInStepMillis)
        assertNull(plan.idleSince) // 进行中不算空闲
    }

    @Test
    fun 进行中_恰好结束即已发生() {
        val e = event(status = EventStatus.IN_PROGRESS, occurredAt = t0)
        val plan = planSettlement(listOf(e), lastOccurredEnd = null, sessionAnchor = t0, now = t0 + 13_000, durations = durations)
        assertEquals(listOf(e), plan.finished)
        assertTrue(plan.running.isEmpty())
        assertEquals(t0 + 13_000, plan.idleSince) // 空闲从事件结束起算
    }

    @Test
    fun 进行中_超时也结算为已发生() {
        val e = event(status = EventStatus.IN_PROGRESS, occurredAt = t0)
        val plan = planSettlement(listOf(e), lastOccurredEnd = null, sessionAnchor = t0, now = t0 + 3_600_000, durations = durations)
        assertEquals(1, plan.finished.size)
        assertEquals(t0 + 13_000, plan.idleSince)
    }

    @Test
    fun 过期_待确认与在清单到点放弃() {
        val pending = event(id = 1, status = EventStatus.PENDING_CONFIRMATION)
        val inList = event(id = 2, status = EventStatus.IN_LIST)

        val before = planSettlement(listOf(pending, inList), null, sessionAnchor = t0, now = t0 + sevenDays - 1, durations = durations)
        assertTrue(before.expired.isEmpty()) // 差 1 毫秒：还没到点

        val atPoint = planSettlement(listOf(pending, inList), null, sessionAnchor = t0, now = t0 + sevenDays, durations = durations)
        assertEquals(listOf(1L, 2L), atPoint.expired.map { it.id }) // now ≥ expires_at 即到点
    }

    @Test
    fun 空闲_从上次事件结束起算_锚点更晚则从锚点算() {
        // 无在跑：空闲起点 = max(上次事件结束, 会话锚点)
        val fromEnd = planSettlement(emptyList(), t0 + 13_000, sessionAnchor = t0, now = t0 + 5 * 60_000, durations)
        assertEquals(t0 + 13_000, fromEnd.idleSince)

        val fromAnchor = planSettlement(emptyList(), t0 + 13_000, sessionAnchor = t0 + 60_000, now = t0 + 120_000, durations)
        assertEquals(t0 + 60_000, fromAnchor.idleSince)

        // 从未发生过任何事件：从会话锚点算
        val neverHappened = planSettlement(emptyList(), null, sessionAnchor = t0, now = t0 + 1_000, durations)
        assertEquals(t0, neverHappened.idleSince)
    }

    // ===== 结算器：落库 + 心跳 + 会话锚点 =====

    @Test
    fun 结算_落库写心跳并报出上次在场时刻() {
        val e = event(status = EventStatus.IN_PROGRESS, occurredAt = t0)
        val events = FakeEventRepository(listOf(e))
        val profiles = FakeRuntimeProfileRepository(
            RuntimeProfile(id = 1, lastSeenAt = t0 - 5_000, createdAt = t0 - 100_000, updatedAt = t0 - 100_000)
        )
        val settler = EventSettler(events, profiles, durations)

        val result = settler.settle(t0 + 13_000)

        assertEquals(EventStatus.OCCURRED, events.byId(1)!!.status)
        assertEquals(t0 + 13_000, profiles.stored()!!.lastSeenAt) // 心跳写了
        assertEquals(t0 - 5_000, result.previousLastSeenAt) // 步骤 4 的"整段离线翻篇"判据
    }

    @Test
    fun 结算_首启建运行配置行() {
        val profiles = FakeRuntimeProfileRepository(null)
        val settler = EventSettler(FakeEventRepository(), profiles, durations)

        val result = settler.settle(t0)

        assertEquals(t0, profiles.stored()!!.createdAt)
        assertEquals(t0, profiles.stored()!!.lastSeenAt)
        assertNull(result.previousLastSeenAt) // 还没有过在场记录
    }

    @Test
    fun 结算_进行中过期_先完成再滚出历史() {
        val e = event(status = EventStatus.IN_PROGRESS, occurredAt = t0 + 10_000, expiresAt = t0 + 20_000) // 结束 = t0 + 23_000
        val events = FakeEventRepository(listOf(e))
        val settler = EventSettler(events, FakeRuntimeProfileRepository(), durations)

        val result = settler.settle(t0 + 25_000) // 既过了结束点、也过了过期点

        assertEquals(1, result.finished.size)
        assertEquals(1, result.expired.size)
        val stored = events.byId(1)!!
        assertEquals(EventStatus.OCCURRED, stored.status) // 先结算成已发生
        assertTrue(stored.isDeleted) // 再按"已发生也过期"滚出历史页（逻辑删除，数据留）
    }

    @Test
    fun 结算_已发生与已拒的过期件_滚出历史() {
        val oldOccurred = event(id = 1, status = EventStatus.OCCURRED, occurredAt = t0, expiresAt = t0 + 1_000)
        val oldRejected = event(id = 2, status = EventStatus.REJECTED, expiresAt = t0 + 1_000)
        val freshOccurred = event(id = 3, status = EventStatus.OCCURRED, occurredAt = t0, expiresAt = t0 + 60_000)
        val events = FakeEventRepository(listOf(oldOccurred, oldRejected, freshOccurred))
        val settler = EventSettler(events, FakeRuntimeProfileRepository(), durations)

        val result = settler.settle(t0 + 2_000)

        assertEquals(listOf(1L, 2L), result.expired.map { it.id }) // 到点的滚出（不推进状态，只逻辑删除）
        assertTrue(events.byId(1)!!.isDeleted)
        assertTrue(events.byId(2)!!.isDeleted)
        assertFalse(events.byId(3)!!.isDeleted) // 没到点的已发生件留着
    }

    @Test
    fun 空闲_打开重计时_前台连续累积_回前台再重计时() {
        val ended = event(status = EventStatus.OCCURRED, occurredAt = t0) // 结束于 t0 + 13_000，很久以前
        val events = FakeEventRepository(listOf(ended))
        val profiles = FakeRuntimeProfileRepository(RuntimeProfile(id = 1, createdAt = t0, updatedAt = t0))
        val settler = EventSettler(events, profiles, durations)

        val open = t0 + 3_600_000 // 事件结束 1 小时后才打开 app
        assertEquals(0L, settler.settle(open).idleMillis) // 打开：从这一刻重计时（离线空白不累积）
        assertEquals(15 * 60_000L, settler.settle(open + 15 * 60_000).idleMillis) // 前台连续：一直累积

        settler.onSessionStart(open + 16 * 60_000) // 回前台
        assertEquals(0L, settler.settle(open + 16 * 60_000).idleMillis)
    }
}
