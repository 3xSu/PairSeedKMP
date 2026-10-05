package com.pairseedkmp.domain.repository

import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventStatus

/** 事件读写端口（纯接口；SQLDelight 实现落在 :kmp:data）。 */
interface EventRepository {
    /** 插入一条事件，返回库分配的 id（传入的 `Event.id` 忽略）。 */
    fun insert(event: Event): Long

    fun byId(id: Long): Event?

    /** 未完的事件：待确认 / 在清单 / 进行中（已过期逻辑删除的不算）。 */
    fun activeEvents(): List<Event>

    /** 状态推进到"进行中"，同时记 `occurredAt`（半固定发生 / 清单启动都走它）。 */
    fun markInProgress(id: Long, occurredAt: Long, updatedAt: Long)

    /** 其余状态推进（已发生等单改状态的动作；确认链路的改动走 [updateStatusAndSuitableTime]）。 */
    fun updateStatus(id: Long, status: EventStatus, updatedAt: Long)

    /** 过期到点 → 逻辑删除（数据留、不再显示）。 */
    fun markExpired(id: Long, updatedAt: Long)

    /** 轮窗口 [fromInclusive, toExclusive) 内该半固定模板是否已发生过（按 `occurred_at` 判）。 */
    fun scheduledOccurredBetween(templateCode: String, fromInclusive: Long, toExclusive: Long): Boolean

    /** 上次事件结束时刻（最晚的 发生+时长；结算算空闲起点用）；一条没发生过 → null。 */
    fun lastOccurredEndMillis(): Long?

    /** 确认答复落库：状态与可答时段一起改（答是 / 答否摘时段 / 改答回滚后落新答）。 */
    fun updateStatusAndSuitableTime(id: Long, status: EventStatus, suitableTime: List<Period>, updatedAt: Long)

    /** 已发生 / 已拒的过期件（结算"滚出历史页"用；按 id 升序）。 */
    fun completedPastExpiry(now: Long): List<Event>

    /** 未逻辑删除的全部事件，新的在前（调试壳事件列表用）。 */
    fun visibleEvents(): List<Event>
}
