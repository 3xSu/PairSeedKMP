package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.repository.EventRepository

/** 清单上限起步值 6（05 计划 §1；《工作链路·清单》「做掉一件补一件，始终保持满仓」）。 */
const val DEFAULT_EVENT_LIST_CAPACITY: Int = 6

/** 补货出口：候选交下游（06 判定 → 落库 → 进清单）；返回 false = 没收下（本轮停止补货）。 */
fun interface GrowCandidateSink {
    suspend fun deliver(candidate: EventCandidate): Boolean
}

/** 一轮补货结果：[delivered] = 交下游的条数；[reentered] = 上一轮还在跑（本轮直接跳过）。 */
data class ReplenishResult(val delivered: Int, val reentered: Boolean = false)

/**
 * 自动补货（05 计划 §4）：onResume 结算后清单 < 上限 → 在线串行补至满仓。
 * - 同一时刻只跑一件（重入守卫：上一轮未完 → 本轮跳过）；
 * - 一件跑完再看还缺就继续；
 * - 失败静默：生长空手 / 环境失败 / 下游没收 → 停本轮（日志由调用方记），下一轮再试。
 */
class EventReplenisher(
    private val grower: EventGrower,
    private val repository: EventRepository,
    private val capacity: Int = DEFAULT_EVENT_LIST_CAPACITY,
) {
    private var running = false

    /**
     * 补到满仓。[now] / [utcOffsetSeconds] 透传给生长（同一轮里多次生长共用同一时刻）。
     * [delivered] 上限与 [capacity] 相同——若下游「收下但没进清单」也不会空转。
     */
    suspend fun replenish(now: Long, utcOffsetSeconds: Int, sink: GrowCandidateSink): ReplenishResult {
        if (running) return ReplenishResult(delivered = 0, reentered = true)
        running = true
        try {
            var delivered = 0
            while (delivered < capacity && inListCount() < capacity) {
                val outcome = grower.grow(now, utcOffsetSeconds)
                if (outcome !is EventGrowResult.Grown) break
                if (!sink.deliver(outcome.candidate)) break
                delivered++
            }
            return ReplenishResult(delivered)
        } finally {
            running = false
        }
    }

    /** 清单内条数（IN_LIST；待确认不算——还没进清单）。 */
    private fun inListCount(): Int =
        repository.activeEvents().count { it.status == EventStatus.IN_LIST }
}
