package com.pairseedkmp.ui.debug.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pairseedkmp.domain.ai.AiMode
import com.pairseedkmp.domain.ai.AiSettings
import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmSystemPrompts
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventCandidate
import com.pairseedkmp.domain.event.EventGrowResult
import com.pairseedkmp.domain.event.EventGrower
import com.pairseedkmp.domain.event.EventJudge
import com.pairseedkmp.domain.event.EventJudgeResult
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.judgment.AiJudge
import com.pairseedkmp.domain.judgment.JudgeDecision
import com.pairseedkmp.domain.judgment.JudgmentOutcome
import com.pairseedkmp.domain.judgment.MissingMaterial
import com.pairseedkmp.domain.judgment.RISK_PASS_THRESHOLD
import com.pairseedkmp.domain.judgment.RISK_REJECT_THRESHOLD
import com.pairseedkmp.domain.judgment.RejectReason
import com.pairseedkmp.domain.judgment.RiskDim
import com.pairseedkmp.domain.judgment.SystemJudge
import com.pairseedkmp.domain.judgment.UncertainReason
import com.pairseedkmp.domain.judgment.samples.JudgeSampleSet
import com.pairseedkmp.domain.judgment.systemDecider
import com.pairseedkmp.domain.seed.GrowResult
import com.pairseedkmp.domain.seed.IdeaSeed
import com.pairseedkmp.domain.seed.SeedGrowMode
import com.pairseedkmp.domain.seed.SeedGrower
import com.pairseedkmp.domain.seed.SeedPicker
import com.pairseedkmp.ui.debug.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 面板里一次事件连跑的阶段报告（跑链区块展示：输入 → 提示词见调用记录 → 解析 → 落点）。 */
data class EventRunReport(
    val growLine: String,
    val candidateLines: List<String> = emptyList(),
    val judgeLines: List<String> = emptyList(),
    val landLine: String,
)

/** 样例集一行结果（校准表）。 */
data class SampleRow(
    val id: String,
    val group: String,
    val title: String,
    val expected: String,
    val actual: String,
    val riskText: String,
    val cause: String,
    val ok: Boolean,
)

/**
 * 调试面板状态（07）：现状 / 跑链路 / 提示词与输出 / Provider 配置 / 样例五个区块的状态与动作。
 * 链路调用一律走「当前模式 client → [PanelLlmClient]（覆盖 + 记录）→ [LlmCaller]」；
 * 保存的提示词覆盖即时生效（每次调用现读）。动作禁重入（[busy]）；协程跑在传入的 [scope]（Compose 主线程）。
 */
class DebugPanelState(
    private val deps: DebugPanelDeps,
    private val now: () -> Long,
    private val utcOffsetSeconds: () -> Int,
    private val scope: CoroutineScope,
) {

    // —— 通用 ——

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("就绪")
        private set
    val invocations = mutableStateListOf<LlmInvocation>()

    // —— 现状 ——

    var seeds by mutableStateOf<List<IdeaSeed>>(emptyList())
        private set
    var events by mutableStateOf<List<Event>>(emptyList())
        private set

    // —— 跑链 ——

    var seedRunNote by mutableStateOf<String?>(null)
        private set
    var seedAskContinue by mutableStateOf<GrowResult.AskContinue?>(null)
        private set
    var eventReport by mutableStateOf<EventRunReport?>(null)
        private set

    // —— 提示词与输出 ——

    var selectedTask by mutableStateOf(LlmTask.EVENT_GROW)
    var promptDrafts by mutableStateOf<Map<LlmTask, String>>(builtinPrompts())
        private set
    var overriddenTasks by mutableStateOf<Set<LlmTask>>(emptySet())
        private set
    private var overrides: MutableMap<LlmTask, String> = mutableMapOf()

    // —— Provider 配置 ——

    var modeDraft by mutableStateOf(AiMode.PRESET)
        private set
    var baseUrlDraft by mutableStateOf("")
        private set
    var modelDraft by mutableStateOf("")
        private set
    var keyDraft by mutableStateOf("")
        private set
    var keyConfigured by mutableStateOf(false)
        private set
    var connectionNote by mutableStateOf<String?>(null)
        private set

    // —— 样例 ——

    var sampleRows by mutableStateOf<List<SampleRow>?>(null)
        private set

    /** 覆盖存储是否持久（面板提示「本地文件 / 内存」）。 */
    val promptStoreAvailable: Boolean get() = deps.promptStore.persistent

    init {
        refresh()
        scope.launch {
            loadPrompts()
            loadSettings()
        }
    }

    // —— 现状 ——

    fun refresh() {
        seeds = deps.seedRepository.all()
        events = deps.eventRepository.visibleEvents()
    }

    val inListCount: Int get() = events.count { it.status == EventStatus.IN_LIST }
    val pendingEvents: List<Event> get() = events.filter { it.status == EventStatus.PENDING_CONFIRMATION }

    // —— 跑链：种子 ——

    /** 长一颗种子（手动模式：单工具用尽 → [seedAskContinue]，面板问「继续 / 打住」）。 */
    fun growSeed(continueFrom: Int? = null) {
        runExclusive("长种子中…") { client ->
            val caller = newCaller(client)
            val grower = SeedGrower(deps.seedRepository, Deduplicator(caller), caller, deps.materialSources)
            when (val result = grower.grow(now(), SeedGrowMode.MANUAL, continueFrom ?: 0)) {
                is GrowResult.Grown -> {
                    seedRunNote = "长出一颗：${result.seed.keyword}（互动主题：${result.seed.theme}；第 ${result.attempts} 次尝试）"
                    seedAskContinue = null
                }
                is GrowResult.AskContinue -> {
                    seedRunNote = "「${result.toolLabel}」工具用满 5 次都没成——重复词 ${result.duplicates.size} 个"
                    seedAskContinue = result
                }
                is GrowResult.Exhausted -> { seedRunNote = "一轮轮空（共 ${result.attempts} 次尝试）"; seedAskContinue = null }
                is GrowResult.NoMaterial -> {
                    val errors = result.errors.joinToString("；").ifEmpty { "没有可用工具" }
                    seedRunNote = "素材全取不到：$errors"
                    seedAskContinue = null
                }
            }
        }
    }

    /** 手动模式「继续」：从下一工具接着长。 */
    fun continueSeed() {
        val pending = seedAskContinue ?: return
        growSeed(continueFrom = pending.nextSourceIndex)
    }

    fun dropSeedContinue() {
        seedAskContinue = null
        status = "种子生长：就此打住"
    }

    // —— 跑链：事件（生成 → 查重 → 判定连跑） ——

    fun growEvent() {
        runExclusive("长事件中（生成 → 查重 → 判定）…") { client ->
            val caller = newCaller(client)
            val grower = EventGrower(
                picker = SeedPicker(deps.seedRepository),
                contextSource = deps.contextSource,
                caller = caller,
                deduplicator = Deduplicator(caller),
            )
            when (val grown = grower.grow(now(), utcOffsetSeconds())) {
                is EventGrowResult.Grown -> {
                    val settings = deps.settingsStore.current()
                    val decider = if (settings.mode == AiMode.API_KEY) AiJudge(caller) else systemDecider()
                    val judge = EventJudge(deps.snapshotSource, deps.durations, deps.eventRepository, decider)
                    val delivered = judge.deliver(grown.candidate, now())
                    eventReport = EventRunReport(
                        growLine = "产出候选（种子 #${grown.seedId}；第 ${grown.attempts} 次尝试）",
                        candidateLines = describeCandidate(grown.candidate),
                        judgeLines = describeJudgement(delivered),
                        landLine = describeLanding(delivered),
                    )
                }
                else -> eventReport = EventRunReport(growLine = describeGrowEmpty(grown), landLine = "未落库")
            }
        }
    }

    private fun describeCandidate(candidate: EventCandidate): List<String> {
        val steps = candidate.steps.joinToString("，") { step ->
            val say = step.params["text"]?.jsonPrimitive?.contentOrNull
            step.action + (say?.let { "「$it」" } ?: "")
        }
        return listOf(
            "概要：${candidate.summary}",
            "步骤：$steps",
            "两维：语言 ${candidate.axes.verbalDirectness ?: "无（没台词）"} ・ 情感 ${candidate.axes.emotionVisibility}",
            "适合时段：${candidate.suitableTime.joinToString("、") { it.label() }.ifEmpty { "不限" }}",
            candidate.afterEventId?.let { "接续：清单事件 #$it" } ?: "接续：无",
        )
    }

    private fun describeGrowEmpty(result: EventGrowResult): String = when (result) {
        is EventGrowResult.Exhausted -> "种子用尽（换过 ${result.seedsTried} 颗、共 ${result.attempts} 次尝试）——本轮空手"
        EventGrowResult.NoSeed -> "种子池空——先长一颗种子"
        EventGrowResult.NoContext -> "组不出生成上下文（没运行配置 / 角色不足两人 / 没场景）"
        is EventGrowResult.EnvFailed -> "环境失败（${result.error.kind}）：${result.error.message}"
        is EventGrowResult.Grown -> "产出候选" // 不会走到
    }

    private fun describeJudgement(delivered: EventJudgeResult): List<String> = when (delivered) {
        is EventJudgeResult.NoSnapshot -> listOf("组不出判定快照（角色 / 场景读不到）——不收下")
        else -> {
            val decision = when (delivered) {
                is EventJudgeResult.InList -> delivered.decision
                is EventJudgeResult.Pending -> delivered.decision
                is EventJudgeResult.Rejected -> delivered.decision
                is EventJudgeResult.NoSnapshot -> error("已排除")
            }
            describeDecision(decision)
        }
    }

    private fun describeDecision(decision: JudgeDecision): List<String> = buildList {
        val outcome = decision.outcome
        val verdict = when (outcome) {
            JudgmentOutcome.Pass -> "过"
            is JudgmentOutcome.Uncertain -> "拿不准"
            is JudgmentOutcome.Reject -> "拒"
        }
        val channel = when {
            outcome is JudgmentOutcome.Reject && outcome.reason is RejectReason.HardRule -> "硬规则码命中（直通）"
            outcome is JudgmentOutcome.Uncertain && outcome.reason is UncertainReason.Missing -> "缺料 fail-safe"
            else -> "风险分"
        }
        add("结论：$verdict ・ 通道：$channel")
        decision.risk?.let { risk ->
            add("R = ${risk.value}（${bandLabel(risk.value)}；过 ≤$RISK_PASS_THRESHOLD / 拒 ≥$RISK_REJECT_THRESHOLD）")
            risk.deviations.forEach { d ->
                add("偏离：${d.dim} 事件 ${d.eventLevel} 档 − 容忍 ${d.tolerance} 档 = +${d.delta} → 分 ${d.score}")
            }
        }
        when (outcome) {
            is JudgmentOutcome.Reject -> when (val reason = outcome.reason) {
                is RejectReason.HardRule -> add("命中：${reason.hit.ruleKey}（码 ${reason.hit.hitCodes.joinToString("、")}）")
                is RejectReason.Risk -> Unit // R 已列
                is RejectReason.Ai -> add("AI 拒：${reason.reason}（违反：${reason.violatedRule}）")
            }
            is JudgmentOutcome.Uncertain -> when (val reason = outcome.reason) {
                is UncertainReason.Missing -> add("缺料：${reason.items.joinToString("、") { it.label() }}")
                is UncertainReason.Risk -> Unit
                is UncertainReason.Ai -> add("AI 拿不准：${reason.reason}")
            }
            JudgmentOutcome.Pass -> Unit
        }
        decision.degradedReason?.let { add("降级：$it（已回落系统三支）") }
    }

    private fun describeLanding(delivered: EventJudgeResult): String = when (delivered) {
        is EventJudgeResult.InList -> "进清单：#${delivered.event.id} ${delivered.event.summary}"
        is EventJudgeResult.Pending -> "待确认：#${delivered.event.id} ${delivered.event.summary}（确认弹框内容已落 payload）"
        is EventJudgeResult.Rejected -> "拒绝：不落任何行（理由只进端上记录）"
        EventJudgeResult.NoSnapshot -> "未收下：不落库"
    }

    // —— 提示词与输出 ——

    fun editPrompt(task: LlmTask, text: String) {
        promptDrafts = promptDrafts + (task to text)
    }

    fun savePrompt(task: LlmTask) {
        val text = promptDrafts[task]?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            deps.promptStore.save(task, text)
            overrides = (overrides + (task to text)).toMutableMap()
            overriddenTasks = overrides.keys.toSet()
            status = "已保存覆盖：${task.name}（下次调用生效）"
        }
    }

    /** 恢复内置：清覆盖 + 草稿回内置原文。 */
    fun resetPrompt(task: LlmTask) {
        scope.launch {
            deps.promptStore.clear(task)
            overrides = (overrides - task).toMutableMap()
            overriddenTasks = overrides.keys.toSet()
            promptDrafts = promptDrafts + (task to LlmSystemPrompts.defaultFor(task))
            status = "已恢复内置：${task.name}"
        }
    }

    fun invocationFor(task: LlmTask): List<LlmInvocation> = invocations.filter { it.task == task }

    // —— Provider 配置 ——

    fun editMode(mode: AiMode) {
        modeDraft = mode
    }

    fun editBaseUrl(value: String) {
        baseUrlDraft = value
    }

    fun editModel(value: String) {
        modelDraft = value
    }

    fun editKey(value: String) {
        keyDraft = value
    }

    fun saveSettings() {
        scope.launch {
            deps.settingsStore.update(AiSettings(mode = modeDraft, baseUrl = baseUrlDraft.trim(), model = modelDraft.trim()))
            status = "运行配置已保存：${modeDraft.label()}"
        }
    }

    /** 保存 Key：只进 Keystore（不回显、不进日志）+ 库侧镜像位；随后清空输入框。 */
    fun saveKey() {
        val key = keyDraft.trim()
        if (key.isEmpty()) {
            status = "Key 为空——没保存"
            return
        }
        scope.launch {
            deps.secretStore.set(key)
            keyDraft = ""
            keyConfigured = true
            deps.profileRepository.current()?.let { deps.profileRepository.setApiKeyConfigured(it.id, true, now()) }
            status = "Key 已存进 Keystore（不回显）"
        }
    }

    fun clearKey() {
        scope.launch {
            deps.secretStore.clear()
            keyConfigured = false
            deps.profileRepository.current()?.let { deps.profileRepository.setApiKeyConfigured(it.id, false, now()) }
            status = "Key 已清除"
        }
    }

    /** 连接测试：按已保存配置发一条最小请求（短超时、不重试）；预设模式 = 本地回放，同样走一遍链路。 */
    fun testConnection() {
        if (busy) return
        busy = true
        connectionNote = null
        status = "连接测试中…"
        scope.launch {
            try {
                val settings = deps.settingsStore.current()
                val client = deps.llmClientFor(settings)
                if (client == null) {
                    connectionNote = "当前模式（${settings.mode.label()}）没有可用实现——端侧未接入"
                } else {
                    val caller = LlmCaller(client, timeoutMillis = CONNECTION_TEST_TIMEOUT_MILLIS, maxRetries = 0)
                    val started = now()
                    val response = caller.complete(
                        LlmRequest(
                            system = "你是连接测试。",
                            user = "只回复两个字：收到。",
                            temperature = 0.0,
                            expectJson = false,
                            task = LlmTask.JUDGE,
                        ),
                    )
                    connectionNote = "成功（${now() - started}ms）：${response.text.trim().take(60)}"
                }
                status = "连接测试完成"
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                connectionNote = "失败（${e.kind}）：${e.message}"
                status = "连接测试失败"
            } catch (e: Exception) {
                connectionNote = "失败：${e.message}"
                status = "连接测试失败"
            } finally {
                busy = false
            }
        }
    }

    // —— 样例 ——

    /** 样例集一键跑（系统三支；门槛 15 / 65）——校准表：期望 / 实际 / R / 一致。 */
    fun runSamples() {
        val judge = SystemJudge()
        val rows = JudgeSampleSet.ALL.map { sample ->
            val judgment = judge.judge(sample.snapshot)
            val actual = verdictOf(judgment.outcome)
            val cause = causeOf(judgment.outcome)
            val expectedCause = sample.expectedCause // 跨模块属性不能智能转换，先取局部值
            SampleRow(
                id = sample.id,
                group = sample.group.label,
                title = sample.title,
                expected = sample.expected.name,
                actual = actual,
                riskText = judgment.risk?.let { "${it.value}（${bandLabel(it.value)}）" } ?: "—",
                cause = cause ?: "—",
                ok = actual == sample.expected.name && (expectedCause == null || cause == expectedCause.name),
            )
        }
        sampleRows = rows
        status = "样例集：${rows.count { it.ok }}/${rows.size} 一致"
    }

    private fun verdictOf(outcome: JudgmentOutcome): String = when (outcome) {
        JudgmentOutcome.Pass -> "PASS"
        is JudgmentOutcome.Uncertain -> "UNCERTAIN"
        is JudgmentOutcome.Reject -> "REJECT"
    }

    private fun causeOf(outcome: JudgmentOutcome): String? = when (outcome) {
        JudgmentOutcome.Pass -> "RISK"
        is JudgmentOutcome.Uncertain -> when (outcome.reason) {
            is UncertainReason.Missing -> "MISSING"
            is UncertainReason.Risk -> "RISK"
            is UncertainReason.Ai -> null
        }
        is JudgmentOutcome.Reject -> when (outcome.reason) {
            is RejectReason.HardRule -> "HARD_RULE"
            is RejectReason.Risk -> "RISK"
            is RejectReason.Ai -> null
        }
    }

    // —— 内部 ——

    /** 通用「禁重入 + 取 client + 收尾刷新」骨架。 */
    private fun runExclusive(progress: String, body: suspend (client: LlmClient) -> Unit) {
        if (busy) return
        busy = true
        status = progress
        scope.launch {
            try {
                val settings = deps.settingsStore.current()
                val client = deps.llmClientFor(settings)
                if (client == null) {
                    status = "当前模式（${settings.mode.label()}）没有可用实现——端侧未接入"
                } else {
                    body(client)
                    status = "完成"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                status = "环境失败（${e.kind}）：${e.message}"
            } catch (e: Exception) {
                status = "异常：${e.message}"
            } finally {
                refresh()
                busy = false
            }
        }
    }

    private fun newCaller(client: LlmClient): LlmCaller =
        LlmCaller(
            PanelLlmClient(
                delegate = client,
                overrideFor = { task -> overrides[task] },
                nowMillis = now,
                log = invocations,
            ),
        )

    private suspend fun loadPrompts() {
        val stored = deps.promptStore.load()
        val restored = stored.mapNotNull { (name, text) ->
            LlmTask.entries.firstOrNull { it.name == name }?.let { it to text }
        }.toMap()
        overrides = restored.toMutableMap()
        overriddenTasks = restored.keys
        promptDrafts = LlmTask.entries.associateWith { restored[it] ?: LlmSystemPrompts.defaultFor(it) }
    }

    private suspend fun loadSettings() {
        val settings = deps.settingsStore.current()
        modeDraft = settings.mode
        baseUrlDraft = settings.baseUrl
        modelDraft = settings.model
        keyConfigured = deps.secretStore.get()?.isNotBlank() == true
    }

    private companion object {
        const val CONNECTION_TEST_TIMEOUT_MILLIS = 30_000L
    }
}

/** 内置默认（面板草稿初值；恢复内置同源）。 */
private fun builtinPrompts(): Map<LlmTask, String> =
    LlmTask.entries.associateWith { LlmSystemPrompts.defaultFor(it) }

/** 三档带（面板显示用；与门槛常量同源）。 */
internal fun bandLabel(value: Int): String = when {
    value <= RISK_PASS_THRESHOLD -> "过带"
    value >= RISK_REJECT_THRESHOLD -> "拒带"
    else -> "拿不准带"
}

/** 模式中文名。 */
internal fun AiMode.label(): String = when (this) {
    AiMode.PRESET -> "预设"
    AiMode.ON_DEVICE -> "端侧"
    AiMode.API_KEY -> "API Key"
}

/** 缺料条目的面板话术。 */
internal fun MissingMaterial.label(): String = when (this) {
    is MissingMaterial.StepCodeNotInTable -> "动作码「$code」不在码表"
    is MissingMaterial.SoftAxisMissing -> "角色 #$characterId 的${dim.label()}软档没填"
    MissingMaterial.SceneVisibilityMissing -> "场景没标公开/私密"
    is MissingMaterial.AxisLevelOutOfRange -> "${dim.label()}档位越界（$level）"
}

internal fun RiskDim.label(): String = when (this) {
    RiskDim.PHYSICAL -> "物理"
    RiskDim.VERBAL -> "语言"
    RiskDim.EMOTION -> "情感"
}
