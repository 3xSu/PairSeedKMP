package com.pairseedkmp.ui.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.event.BehaviorDurationSource
import com.pairseedkmp.domain.event.ConfirmationAnswer
import com.pairseedkmp.domain.event.EVENT_EXPIRY_MILLIS
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventOrigin
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.EventStep
import com.pairseedkmp.domain.event.SemiFixedFireContext
import com.pairseedkmp.domain.event.SettleResult
import com.pairseedkmp.domain.event.TriggerResult
import com.pairseedkmp.domain.event.totalDurationSeconds
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 调试壳的状态与动作：前台每秒 [tick]（结算 + 触发检查 + 刷新列表，兼作存活心跳）；
 * 操作区按钮走 [manualSettle] / [insertSample] / [answer]；"清空数据重来"由壳层换一整条新栈
 * （[com.pairseedkmp.ui.App] 会用新依赖重建本对象）。
 */
class DebugShellState(private val deps: DebugShellDeps) {

    var now by mutableStateOf(deps.now())
        private set
    var context by mutableStateOf(deps.context())
        private set
    var settle by mutableStateOf<SettleResult?>(null)
        private set
    var trigger by mutableStateOf<TriggerResult?>(null)
        private set
    /** 最近一次翻篇（粘性）：翻篇只出现在那一秒的判断里，留一句让"判断与理由"看得见。 */
    var swallowedNote by mutableStateOf<String?>(null)
        private set
    /** 最近一次发生（粘性）：同理，发生的那一秒判断就会被"不打断"覆盖。 */
    var lastFiredNote by mutableStateOf<String?>(null)
        private set
    var events by mutableStateOf<List<Event>>(emptyList())
        private set
    var feedback by mutableStateOf("就绪：前台每秒自动结算 + 触发检查")
        private set

    val debugEnabled: Boolean get() = deps.debugEnabled
    val canReset: Boolean get() = deps.clearAndReseed != null
    val durations: BehaviorDurationSource get() = deps.durations

    fun clock(millis: Long): String = deps.formatClock(millis)
    fun stamp(millis: Long): String = deps.formatTime(millis)

    /** 前台循环：先立即跑一次，然后每秒一次。 */
    suspend fun run() {
        while (true) {
            tick()
            delay(1_000)
        }
    }

    /** 一次检查点：结算 → 触发 → 读回事件列表。 */
    fun tick() {
        val t = deps.now()
        now = t
        val ctx = deps.context()
        context = ctx
        val settled = deps.settler.settle(t)
        settle = settled
        val trig = ctx?.let {
            deps.trigger.check(t, settled, deps.utcOffsetSeconds(), SemiFixedFireContext(it.actorId, it.targetId, it.sceneId))
        }
        trigger = trig
        trig?.fired?.let { fired ->
            lastFiredNote = "${trig.decision.label()} → #${fired.id} ${fired.summary}"
        }
        trig?.swallowedOffline?.takeIf { it.isNotEmpty() }?.let {
            swallowedNote = "吞了 ${it.size} 条：${it.joinToString("、")}"
        }
        events = deps.eventRepository.visibleEvents()
    }

    fun manualSettle() {
        tick()
        feedback = "手动结算了一次（前台本来每秒都结算）"
    }

    /** 可答门（步骤 5）：状态可答 × 没过期 × 现在落在一个可答时段里。 */
    fun canAnswer(eventId: Long): Boolean =
        deps.confirmer.canAnswer(eventId, now, deps.utcOffsetSeconds())

    fun answer(eventId: Long, answer: ConfirmationAnswer) {
        val result = deps.confirmer.answer(eventId, answer, deps.now(), deps.utcOffsetSeconds())
        feedback = when {
            result == null -> "门没过（已过期 / 不在当前时段）：什么都没落"
            result.answer == ConfirmationAnswer.YES -> "已答是 → 在清单"
            result.rejected -> "已答否：摘掉「${result.answeredPeriod.label()}」后没有别的时段了 → 已拒绝"
            else -> "已答否：摘掉「${result.answeredPeriod.label()}」，其余时段还留着"
        }
        tick()
    }

    fun insertSample(sample: SampleEvent) {
        val ctx = context
        if (ctx == null) {
            feedback = "没有角色组上下文（空库 / release 不种）：插不了"
            return
        }
        val t = deps.now()
        val steps = sample.buildSteps()
        val id = deps.eventRepository.insert(
            Event(
                id = 0,
                packId = ctx.packId,
                actorId = ctx.actorId,
                targetId = ctx.targetId,
                sceneId = ctx.sceneId,
                summary = sample.summary,
                steps = steps,
                suitableTime = sample.suitableTime,
                status = EventStatus.PENDING_CONFIRMATION,
                origin = EventOrigin.SEED,
                durationSec = steps.totalDurationSeconds(deps.durations),
                expiresAt = t + EVENT_EXPIRY_MILLIS,
                createdAt = t,
                updatedAt = t,
            )
        )
        feedback = "插入 #$id：${sample.summary}（待确认 ・ ${sample.suitableTime.joinToString("、") { it.label() }}）"
        tick()
    }
}

/** 操作区的假事件样例（照真信封写：单 / 多时段 / 深夜，含「说」步骤）。 */
enum class SampleEvent(val buttonLabel: String, val summary: String, val suitableTime: List<Period>) {
    SINGLE_MORNING("插·单时段", "在窗边聊两句", listOf(Period.MORNING)),
    MULTI_AFTERNOON_EVENING("插·多时段", "一起吃点东西", listOf(Period.AFTERNOON, Period.EVENING)),
    NIGHT("插·深夜", "一起看会儿书", listOf(Period.NIGHT)),
    ;

    fun buildSteps(): List<EventStep> = when (this) {
        SINGLE_MORNING -> listOf(
            EventStep("walk"),
            EventStep("say", sayParams("今天阳光不错。")),
        )
        MULTI_AFTERNOON_EVENING -> listOf(
            EventStep("walk"),
            EventStep("sit"),
            EventStep("eat"),
        )
        NIGHT -> listOf(
            EventStep("sit"),
            EventStep("say", sayParams("这段还挺有意思。")),
        )
    }
}

/** 「说」步骤的 params（定稿信封：speaker / text / duration_sec）。 */
private fun sayParams(text: String): JsonObject = buildJsonObject {
    put("speaker", "A")
    put("text", text)
    put("duration_sec", 6)
}
