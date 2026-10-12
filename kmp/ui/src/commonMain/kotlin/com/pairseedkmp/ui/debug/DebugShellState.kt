package com.pairseedkmp.ui.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pairseedkmp.domain.ai.AiMode
import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.event.BehaviorDurationSource
import com.pairseedkmp.domain.event.ConfirmationAnswer
import com.pairseedkmp.domain.event.DEFAULT_EVENT_LIST_CAPACITY
import com.pairseedkmp.domain.event.EVENT_EXPIRY_MILLIS
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventGrower
import com.pairseedkmp.domain.event.EventJudge
import com.pairseedkmp.domain.event.EventOrigin
import com.pairseedkmp.domain.event.EventReplenisher
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.EventStep
import com.pairseedkmp.domain.event.SemiFixedFireContext
import com.pairseedkmp.domain.event.SettleResult
import com.pairseedkmp.domain.event.TriggerResult
import com.pairseedkmp.domain.event.totalDurationSeconds
import com.pairseedkmp.domain.judgment.AiJudge
import com.pairseedkmp.domain.judgment.systemDecider
import com.pairseedkmp.domain.seed.GrowResult
import com.pairseedkmp.domain.seed.SeedGrower
import com.pairseedkmp.domain.seed.SeedPicker
import com.pairseedkmp.ui.debug.panel.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 自动链一轮空手后的重试冷却：下一轮检查点至少要等这么久（进度则立即接着跑）。 */
private const val AUTO_RETRY_COOLDOWN_MILLIS: Long = 60_000L

/**
 * 调试壳的状态与动作：前台每秒 [tick]（结算 + 触发检查 + 刷新列表，兼作存活心跳）；
 * 操作区按钮走 [manualSettle] / [insertSample] / [answer]；"清空数据重来"由壳层换一整条新栈
 * （[com.pairseedkmp.ui.App] 会用新依赖重建本对象）。
 *
 * 自动链（08）：前台对账 [DebugShellDeps.foreground]（涨了 = 新一轮前台）→ 清单 < 上限自动补货 /
 * 清单已满长种子；后台协程跑在 [scope]、[autoBusy] 不重入；空手后 [AUTO_RETRY_COOLDOWN_MILLIS] 再试。
 */
class DebugShellState(
    private val deps: DebugShellDeps,
    private val scope: CoroutineScope,
) {

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
    /** 自动链最近一轮的结果（08；没跑过 → null）。 */
    var autoStatus by mutableStateOf<String?>(null)
        private set
    var autoBusy by mutableStateOf(false)
        private set

    val debugEnabled: Boolean get() = deps.debugEnabled
    val canReset: Boolean get() = deps.clearAndReseed != null
    val durations: BehaviorDurationSource get() = deps.durations

    private var lastForegroundCount = -1
    private var autoRetryAfter = 0L

    fun clock(millis: Long): String = deps.formatClock(millis)
    fun stamp(millis: Long): String = deps.formatTime(millis)

    /** 前台循环：先立即跑一次，然后每秒一次（含自动链对账）。 */
    suspend fun run() {
        while (true) {
            tick()
            autoCheckpoint()
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

    // —— 自动链（08：前台信号对账 → 补货 / 长种子）——

    /** 操作区按钮：不等前台信号，立刻跑一轮（补货或长种子按清单现状分叉）。 */
    fun autoGrowNow() {
        requestAutoPass(force = true)
    }

    /** 每秒对账：前台信号涨了 → 跑一轮；没涨时清单缺货且过了冷却 → 也跑（空手后不空转）。 */
    private fun autoCheckpoint() {
        if (deps.autoGrow == null || context == null) return
        val resumed = deps.foreground.count != lastForegroundCount
        if (resumed) {
            lastForegroundCount = deps.foreground.count
            autoRetryAfter = 0L
        }
        if (autoBusy) return
        val inList = events.count { it.status == EventStatus.IN_LIST }
        val need = inList < DEFAULT_EVENT_LIST_CAPACITY
        if (resumed || (need && now >= autoRetryAfter)) requestAutoPass(force = false)
    }

    private fun requestAutoPass(force: Boolean) {
        val auto = deps.autoGrow ?: return
        if (autoBusy) return
        if (!force && !autoNeeded()) return
        autoBusy = true
        autoStatus = "自动链：跑一轮…"
        scope.launch {
            try {
                runAutoPass(auto)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                autoStatus = "自动链：环境失败（${e.kind}）——${e.message}"
                autoRetryAfter = deps.now() + AUTO_RETRY_COOLDOWN_MILLIS
            } catch (e: Exception) {
                autoStatus = "自动链：异常——${e.message}"
                autoRetryAfter = deps.now() + AUTO_RETRY_COOLDOWN_MILLIS
            } finally {
                autoBusy = false
                // 跑的过程里可能撞上「清空数据重来」（旧栈已关库）——这一下刷新失败就算了，
                // 旧 state 反正已经不在屏上
                runCatching { tick() }
            }
        }
    }

    private fun autoNeeded(): Boolean =
        events.count { it.status == EventStatus.IN_LIST } < DEFAULT_EVENT_LIST_CAPACITY

    /**
     * 一轮自动链：清单 < 上限 → 补货（挑种子 → 生成 → 查重 → 判定 → 落库，串行补）；
     * 清单已满 → 长一颗种子（AUTO：工具轮转，全轮无果算空手）。
     * 判定通道同面板口径：API Key 模式走 AI 冷读，其余系统三支。
     */
    private suspend fun runAutoPass(auto: AutoGrowDeps) {
        val settings = auto.settingsStore.current()
        val client = auto.llmClientFor(settings)
        if (client == null) {
            autoStatus = "自动链：当前模式（${settings.mode.label()}）没有可用实现——跳过"
            return
        }
        val caller = LlmCaller(client)
        val startedAt = deps.now()
        val inList = auto.eventRepository.activeEvents().count { it.status == EventStatus.IN_LIST }
        if (inList < DEFAULT_EVENT_LIST_CAPACITY) {
            val grower = EventGrower(
                picker = SeedPicker(auto.seedRepository),
                contextSource = auto.contextSource,
                caller = caller,
                deduplicator = Deduplicator(caller),
            )
            val decider = if (settings.mode == AiMode.API_KEY) AiJudge(caller) else systemDecider()
            val judge = EventJudge(auto.snapshotSource, auto.durations, auto.eventRepository, decider)
            val result = EventReplenisher(grower, auto.eventRepository)
                .replenish(startedAt, deps.utcOffsetSeconds(), judge.asCandidateSink(startedAt))
            if (result.reentered) {
                autoStatus = "自动补货：上一轮还在跑——跳过"
                return
            }
            val after = auto.eventRepository.activeEvents().count { it.status == EventStatus.IN_LIST }
            autoStatus = "自动补货：交下游 ${result.delivered} 条（清单 $after/$DEFAULT_EVENT_LIST_CAPACITY ・ 拿不准的进待确认）"
            // 清单没涨（空手 / 只落待确认与拒绝）→ 进冷却，不每秒空转；涨了就接着补到下轮检查点
            if (after <= inList) autoRetryAfter = deps.now() + AUTO_RETRY_COOLDOWN_MILLIS
        } else {
            val grower = SeedGrower(auto.seedRepository, Deduplicator(caller), caller, auto.materialSources)
            when (val result = grower.grow(startedAt)) {
                is GrowResult.Grown ->
                    autoStatus = "清单已满 → 补种子：长出「${result.seed.keyword}」（第 ${result.attempts} 次尝试）"
                is GrowResult.Exhausted -> {
                    autoStatus = "清单已满 → 补种子：一轮轮空（共 ${result.attempts} 次尝试）"
                    autoRetryAfter = deps.now() + AUTO_RETRY_COOLDOWN_MILLIS
                }
                is GrowResult.NoMaterial -> {
                    autoStatus = "清单已满 → 补种子：素材全取不到"
                    autoRetryAfter = deps.now() + AUTO_RETRY_COOLDOWN_MILLIS
                }
                is GrowResult.AskContinue -> Unit // AUTO 模式不会出
            }
        }
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
