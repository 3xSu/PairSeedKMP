package com.pairseedkmp.data.smoke

import com.pairseedkmp.data.ai_impl.ApiKeyLlmClient
import com.pairseedkmp.data.ai_impl.LlmHttpClientFactory
import com.pairseedkmp.domain.ai.AiMode
import com.pairseedkmp.domain.ai.AiSettings
import com.pairseedkmp.domain.ai.AiSettingsStore
import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmJson
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.ai.SecretStore
import com.pairseedkmp.domain.event.ComparisonAxes
import com.pairseedkmp.domain.event.EventCandidate
import com.pairseedkmp.domain.event.EventStep
import com.pairseedkmp.domain.event.GrowCharacter
import com.pairseedkmp.domain.judgment.AiJudge
import com.pairseedkmp.domain.judgment.HardRuleScope
import com.pairseedkmp.domain.judgment.JudgeCharacter
import com.pairseedkmp.domain.judgment.JudgeDecision
import com.pairseedkmp.domain.judgment.JudgeHardRule
import com.pairseedkmp.domain.judgment.JudgmentOutcome
import com.pairseedkmp.domain.judgment.JudgmentSnapshot
import com.pairseedkmp.domain.judgment.RejectReason
import com.pairseedkmp.domain.judgment.UncertainReason
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assume

/**
 * 节点 06 步骤 5：API 冒烟——10 组对抗样本（与端侧 spike 同一样本）走真实 API provider。
 *
 * 两条路线、同一批样本，全部走产品同款链路（LlmCaller 超时/重试 → ApiKeyLlmClient 真 HTTP）：
 * ① 产品路线：AiJudge 三档冷读（快照由样本构造——硬规则 = spike 原文、动作码 = 示意映射、R = 0）；
 * ② 对照路线：spike 原版二元提示词与 schema（与 0.6B 2/10、1.7B 4/10 端侧结果直接可比）。
 *
 * 开关：默认不跑（防全量测试误触真实网络/计费）——显式 `-Ppairseed.smoke=true` 才放行。
 * 本地配置（dev 侧通用：本冒烟 / 将来设备调试共用同一形状）：仓库根 `.pairseed.local.json`
 * （已 `.gitignore`，只在本地）优先，`~/.pairseed.local.json`（仓库外）备选；
 * 形状 `{"baseUrl":…, "model":…, "apiKey":…, "jsonFormat"?:true, "logFile"?:绝对路径}`。
 * 放行后：文件不在 → 整测跳过（Assume）；文件在但缺字段 / Key 空 → 直接失败（不吞错）。Key 只进内存与 Authorization 头，
 * 不进任何输出（本文件不打印 apiKey；请求日志只打 system/user 文本）。
 *
 * 复现：`./gradlew :kmp:data:testDebugUnitTest --tests "com.pairseedkmp.data.smoke.ApiJudgeSmokeTest" -Ppairseed.smoke=true`
 */
class ApiJudgeSmokeTest {

    @Test
    fun 十组对抗样本_产品三档路线与spike二元路线() {
        if (System.getProperty(SMOKE_FLAG) != "true") {
            Assume.assumeTrue("未显式开启（$SMOKE_FLAG=true）→ 跳过 API 冒烟（防全量测试误触真实网络）", false)
            return
        }

        val configFile = resolveConfigFile()
        if (configFile == null) {
            Assume.assumeTrue("未放置 $LOCAL_CONFIG_NAME（仓库根或用户主目录）→ 跳过 API 冒烟", false)
            return
        }

        val config = loadSmokeConfig(configFile)
        val writer = config.logFile?.let { path ->
            File(path).also { it.parentFile?.mkdirs() }
                .let { PrintWriter(OutputStreamWriter(FileOutputStream(it), Charsets.UTF_8), true) }
        }
        SmokeSink(writer).use { sink ->
            sink.line("== API 冒烟（节点 06 步骤 5）==")
            sink.line("base = ${config.baseUrl}　model = ${config.model}　jsonFormat = ${config.jsonFormat}")
            sink.line("开始：${timestamp()}　主机编码：file.encoding = ${System.getProperty("file.encoding")}")
            sink.line("")
            printFixture(sink)

            runBlocking {
                val http = LlmHttpClientFactory.create()
                try {
                    val recording = RecordingClient(
                        ApiKeyLlmClient(
                            http,
                            settingsStoreOf(config),
                            secretStoreOf(config),
                            useJsonFormat = config.jsonFormat,
                        ),
                    )
                    val caller = LlmCaller(recording)

                    sink.line("===== ① 产品三档路线（AiJudge 冷读）=====")
                    sink.line("")
                    val cellsA = runRouteA(caller, recording, sink)

                    sink.line("===== ② 对照：spike 原版二元提示词 =====")
                    sink.line("")
                    val cellsB = runRouteB(caller, sink)

                    sink.line("")
                    sink.line("===== 汇总 =====")
                    printSummaryA(sink, cellsA)
                    sink.line("")
                    printSummaryB(sink, cellsB)
                    sink.line("")
                    sink.line("结束：${timestamp()}")

                    assertTrue(cellsA.all { it.verdict != null }, "① 有样本发生未兜住的异常，数据不完整")
                    assertTrue(cellsA.none { it.degraded }, "① 有样本降级到系统裁决（API 调用失败），数据不完整")
                    assertTrue(cellsB.all { it.callSucceeded }, "② 有样本 API 调用失败，数据不完整")
                } finally {
                    http.close()
                }
            }
        }
    }

    // ===== ① 产品三档路线 =====

    private suspend fun runRouteA(caller: LlmCaller, recording: RecordingClient, sink: SmokeSink): List<ACell> {
        val judge = AiJudge(caller)
        var systemPrinted = false
        val cells = mutableListOf<ACell>()
        for (sample in ADVERSARIAL_SAMPLES) {
            sink.line("—— #${sample.index} ${sample.text}")
            sink.line("   构造：发起方 = ${nameOf(sample.actorId)}；步骤 = ${sample.steps.joinToString("、")}；标注 = ${sample.note}")
            sink.line("   期望：${if (sample.compatible) "相容 → 不拒（理想 PASS）" else "违反 → REJECT"}")
            val from = recording.attempts.size
            val started = System.currentTimeMillis()
            val decision = try {
                judge.decide(snapshotOf(sample))
            } catch (t: Throwable) {
                sink.line("   ✗ 未兜住异常：${t::class.simpleName}：${t.message}")
                null
            }
            val ms = System.currentTimeMillis() - started
            recording.attempts.drop(from).forEachIndexed { i, (request, text) ->
                if (!systemPrinted) {
                    sink.line("   [system（全部样本相同）]")
                    sink.lines("     | ", request.system)
                    systemPrinted = true
                }
                sink.line("   [user｜第 ${i + 1} 次请求]")
                sink.lines("     | ", request.user)
                sink.line("   [响应｜第 ${i + 1} 次尝试]")
                sink.lines("     < ", text)
            }
            val cell: ACell
            if (decision == null) {
                cell = ACell(sample, verdict = null, risk = null, degraded = false, detail = "（调用异常）", ms = ms)
            } else {
                val verdict = when (decision.outcome) {
                    is JudgmentOutcome.Pass -> "PASS"
                    is JudgmentOutcome.Uncertain -> "UNCERTAIN"
                    is JudgmentOutcome.Reject -> "REJECT"
                }
                cell = ACell(
                    sample = sample,
                    verdict = verdict,
                    risk = decision.risk?.value,
                    degraded = decision.degradedReason != null,
                    detail = describe(decision),
                    ms = ms,
                )
                sink.line("   裁决：${describe(decision)}")
                sink.line("   出口：$verdict　R = ${decision.risk?.value}　降级 = ${decision.degradedReason ?: "无"}　耗时 ${ms}ms")
            }
            cells += cell
            sink.line("")
        }
        return cells
    }

    // ===== ② 对照：spike 原版二元提示词 =====

    private suspend fun runRouteB(caller: LlmCaller, sink: SmokeSink): List<BCell> {
        sink.line("（提示词/规则/样本 = spike `OnDeviceSpikeActivity` 判定段同源；温度 0.1；约束解码由 API 侧 json_object 近似）")
        sink.line("[system]")
        sink.lines("  | ", SPIKE_JUDGE_SYSTEM)
        sink.line("[规则块]")
        sink.lines("  | ", SPIKE_JUDGE_RULES)
        sink.line("")
        val cells = mutableListOf<BCell>()
        for (sample in ADVERSARIAL_SAMPLES) {
            val user = SPIKE_JUDGE_RULES + "\n## 待判定事件\n" + sample.text + "\n请判定该事件是否 OOC，输出严格的 JSON。"
            val request = LlmRequest(
                system = SPIKE_JUDGE_SYSTEM,
                user = user,
                temperature = 0.1,
                expectJson = true,
                task = LlmTask.JUDGE,
                responseSchema = SPIKE_JUDGE_SCHEMA,
            )
            var text: String? = null
            var failure: String? = null
            try {
                text = caller.complete(request).text
            } catch (e: LlmException) {
                failure = "${e.kind} ${e.message}"
            } catch (t: Throwable) {
                failure = "${t::class.simpleName} ${t.message}"
            }
            val parsed = text?.let { LlmJson.parse(it) }
            val got = (parsed?.get("consistent") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            sink.line("—— #${sample.index} ${sample.text}")
            sink.line("   期望 consistent = ${sample.compatible}")
            if (text != null) {
                sink.lines("   响应 < ", text)
            } else {
                sink.line("   调用失败：$failure")
            }
            sink.line("   jsonOk = ${parsed != null}　consistent = $got　${hitLabel(got, sample.compatible)}")
            sink.line("")
            cells += BCell(sample, callSucceeded = failure == null, jsonOk = parsed != null, got = got)
        }
        return cells
    }

    private fun hitLabel(got: Boolean?, expected: Boolean): String = when {
        got == null -> "—"
        got == expected -> "命中"
        else -> "未中"
    }

    // ===== 汇总 =====

    private fun printSummaryA(sink: SmokeSink, cells: List<ACell>) {
        sink.line("== ① 汇总（产品三档路线）==")
        sink.line(" # | 期望 | 出口 | 判定（严格） | R | 耗时")
        for (cell in cells) {
            val strict = when {
                cell.verdict == null -> "—"
                cell.sample.compatible && cell.verdict == "PASS" -> "✓"
                !cell.sample.compatible && cell.verdict == "REJECT" -> "✓"
                cell.verdict == "UNCERTAIN" -> "○（弃权）"
                cell.sample.compatible -> "✗（误杀）"
                else -> "✗（漏放）"
            }
            sink.line(
                " ${cell.sample.index} | ${if (cell.sample.compatible) "相容" else "违反"} | " +
                    "${cell.verdict ?: "崩溃"} | $strict | ${cell.risk} | ${cell.ms}ms",
            )
        }
        val n = cells.size
        sink.line("严格一致 ${cells.count { it.strictHit() }}/$n（相容 = PASS、违反 = REJECT；UNCERTAIN 不算一致）")
        sink.line("安全一致 ${cells.count { it.safeHit() }}/$n（相容不误杀、违反不漏放；UNCERTAIN 计安全）")
        sink.line(
            "误杀 ${cells.count { it.sample.compatible && it.verdict == "REJECT" }}　" +
                "漏放 ${cells.count { !it.sample.compatible && it.verdict == "PASS" }}　" +
                "弃权（UNCERTAIN）${cells.count { it.verdict == "UNCERTAIN" }}　" +
                "降级 ${cells.count { it.degraded }}",
        )
    }

    private fun printSummaryB(sink: SmokeSink, cells: List<BCell>) {
        sink.line("== ② 汇总（spike 二元口径；端侧对照：0.6B jsonOk 5/10·准确 2/10，1.7B jsonOk 8/10·准确 4/10）==")
        for (cell in cells) {
            sink.line(
                " #${cell.sample.index} 期望 = ${cell.sample.compatible}　输出 = ${cell.got ?: "（无）"}　" +
                    "jsonOk = ${cell.jsonOk}　${hitLabel(cell.got, cell.sample.compatible)}",
            )
        }
        val n = cells.size
        sink.line("jsonOk ${cells.count { it.jsonOk }}/$n")
        sink.line("准确 ${cells.count { it.got != null && it.got == it.sample.compatible }}/$n")
    }

    private fun ACell.strictHit(): Boolean = verdict != null && (
        (sample.compatible && verdict == "PASS") || (!sample.compatible && verdict == "REJECT")
        )

    private fun ACell.safeHit(): Boolean = verdict != null && (
        (sample.compatible && verdict != "REJECT") || (!sample.compatible && verdict != "PASS")
        )

    // ===== 快照构造（① 用）=====

    private fun snapshotOf(sample: AdversarialSample): JudgmentSnapshot {
        val actor = if (sample.actorId == PROMETHEUS_ID) PROMETHEUS else HERACLES
        val target = if (sample.actorId == PROMETHEUS_ID) HERACLES else PROMETHEUS
        return JudgmentSnapshot(
            candidate = EventCandidate(
                packId = 1L,
                actorId = actor.id,
                targetId = target.id,
                sceneId = 1L,
                summary = sample.text,
                steps = sample.steps.map { EventStep(it) },
                axes = ComparisonAxes(verbalDirectness = null, emotionVisibility = 1),
                suitableTime = emptyList(),
            ),
            actor = actor,
            target = target,
            behaviorLevels = CODE_LEVELS,
            behaviorNames = CODE_NAMES,
        )
    }

    private fun printFixture(sink: SmokeSink) {
        sink.line("== 快照构造（① 产品路线；spike 样本是自然语言句子，动作码/轴为示意映射）==")
        sink.line("角色：${PROMETHEUS.name}（id=1，软轴三维全 1 档）　硬规则：mobility（恒生效）+ no_public_intimacy（仅公开场合）")
        sink.line("角色：${HERACLES.name}（id=2，软轴三维全 1 档）　硬规则：no_longtext（恒生效）")
        sink.line("规则文字 = spike 规则块原文（主语由角色块给出：「两人不会在他人面前…」挂在普罗米修斯名下）")
        sink.line("码表：17 码 physical_level（单人 13 码 = 1 / hold_hands·lean = 3 / hug·hold = 4）")
        sink.line("每样本：axes = verbal null + emotion 1；场景 / 关系 / 共享设定 / 最近已发生 = 空 → R = 0")
        sink.line("理由：这些违反（跑 / 蹲 / 读长文 / 公开亲密）都无对应码——码表通道看不见，正是留给 AI 通道的残余")
        sink.line("")
    }

    // ===== 配置与工具 =====

    /**
     * 配置发现：从测试工作目录（Gradle 默认 = 模块目录 kmp/data）逐级向上，至仓库根（含 .git）为止，
     * 找到 `.pairseed.local.json` 即用；都没有 → 退回用户主目录（仓库外备选位）。找不到返回 null。
     */
    private fun resolveConfigFile(): File? {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, LOCAL_CONFIG_NAME)
            if (candidate.isFile) return candidate
            if (File(dir, ".git").exists()) break
            dir = dir.parentFile
        }
        return File(System.getProperty("user.home"), LOCAL_CONFIG_NAME).takeIf { it.isFile }
    }

    private fun loadSmokeConfig(file: File): SmokeConfig {
        val root = try {
            Json { isLenient = true }.parseToJsonElement(file.readText()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("$LOCAL_CONFIG_NAME 不是合法 JSON 对象")
        return SmokeConfig(
            baseUrl = root.str("baseUrl") ?: throw IllegalStateException("$LOCAL_CONFIG_NAME 缺 baseUrl"),
            model = root.str("model") ?: throw IllegalStateException("$LOCAL_CONFIG_NAME 缺 model"),
            apiKey = root.str("apiKey")
                ?: throw IllegalStateException("$LOCAL_CONFIG_NAME 的 apiKey 缺失或为空——请先把 Key 填进配置文件（${file.path}）"),
            jsonFormat = root.str("jsonFormat") != "false",
            logFile = root.str("logFile"),
        )
    }

    private fun JsonObject.str(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun settingsStoreOf(config: SmokeConfig) = object : AiSettingsStore {
        override suspend fun current() = AiSettings(mode = AiMode.API_KEY, baseUrl = config.baseUrl, model = config.model)
        override suspend fun update(settings: AiSettings) = Unit
    }

    /** Key 只经这里进内存；本类任何输出都不碰它。 */
    private fun secretStoreOf(config: SmokeConfig) = object : SecretStore {
        override suspend fun get(): String = config.apiKey
        override suspend fun set(value: String) = Unit
        override suspend fun clear() = Unit
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

    /** 输出双通道：控制台 + UTF-8 文件（防控制台编码问题；文件路径来自配置 logFile）。 */
    private class SmokeSink(private val file: PrintWriter?) : Closeable {
        fun line(text: String) {
            println(text)
            file?.println(text)
        }

        fun lines(prefix: String, text: String) {
            text.lineSequence().forEach { line("$prefix$it") }
        }

        override fun close() {
            file?.close()
        }
    }

    /** 记录每次真实尝试（LlmCaller 重试时能看到多次）；不记录任何 header / 密钥。 */
    private class RecordingClient(private val inner: LlmClient) : LlmClient {
        val attempts = mutableListOf<Pair<LlmRequest, String>>()

        override suspend fun complete(request: LlmRequest): LlmResponse = try {
            inner.complete(request).also { attempts += request to it.text }
        } catch (e: LlmException) {
            attempts += request to "<调用失败 ${e.kind}：${e.message}>"
            throw e
        }
    }

    private data class SmokeConfig(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val jsonFormat: Boolean,
        val logFile: String?,
    )

    private data class AdversarialSample(
        val index: Int,
        val text: String,
        /** spike 期望「与硬规则相容、不 OOC」；false = 期望拦截。 */
        val compatible: Boolean,
        val actorId: Long,
        val steps: List<String>,
        val note: String,
    )

    private data class ACell(
        val sample: AdversarialSample,
        /** PASS / UNCERTAIN / REJECT；null = 未兜住异常。 */
        val verdict: String?,
        val risk: Int?,
        val degraded: Boolean,
        val detail: String,
        val ms: Long,
    )

    private data class BCell(
        val sample: AdversarialSample,
        val callSucceeded: Boolean,
        val jsonOk: Boolean,
        val got: Boolean?,
    )

    private fun describe(decision: JudgeDecision): String = when (val outcome = decision.outcome) {
        is JudgmentOutcome.Pass -> "会（PASS）"
        is JudgmentOutcome.Uncertain -> "拿不准（UNCERTAIN）——${describe(outcome.reason)}"
        is JudgmentOutcome.Reject -> "不会（REJECT）——${describe(outcome.reason)}"
    }

    private fun describe(reason: RejectReason): String = when (reason) {
        is RejectReason.HardRule -> "硬规则码命中：${reason.hit.ruleKey}（角色 ${reason.hit.characterId}，码 ${reason.hit.hitCodes}）"
        is RejectReason.Risk -> "R = ${reason.risk.value} 达拦截带"
        is RejectReason.Ai -> "AI：${reason.reason}｜违反条：${reason.violatedRule}"
    }

    private fun describe(reason: UncertainReason): String = when (reason) {
        is UncertainReason.Missing -> "缺料：${reason.items}"
        is UncertainReason.Risk -> "R = ${reason.risk.value} 落两门槛之间"
        is UncertainReason.Ai -> "AI：${reason.reason}"
    }

    private fun nameOf(id: Long): String = if (id == PROMETHEUS_ID) "普罗米修斯" else "赫拉克勒斯"

    private companion object {
        const val LOCAL_CONFIG_NAME = ".pairseed.local.json"
        const val SMOKE_FLAG = "pairseed.smoke"
        const val PROMETHEUS_ID = 1L
        const val HERACLES_ID = 2L

        /** 软轴三维全 1 档（= 最主动端）：容忍档 t = 5 − 1 = 4 → 任何码的事件档都不超，R = 0。 */
        const val SOFT_AXES_ALL_ONE =
            """{"physical_intimacy":{"level":1},"verbal_directness":{"level":1},"emotion_visibility":{"level":1}}"""

        val PROMETHEUS = JudgeCharacter(
            card = GrowCharacter(
                id = PROMETHEUS_ID,
                name = "普罗米修斯",
                summary = "静默多思的学者。",
                softAxesRaw = SOFT_AXES_ALL_ONE,
            ),
            hardRules = listOf(
                ruleOf(PROMETHEUS_ID, "mobility", HardRuleScope.ALWAYS, "双腿行动不便，不能跑、跳、蹲、上下楼梯；出行依赖轮椅。"),
                ruleOf(PROMETHEUS_ID, "no_public_intimacy", HardRuleScope.PUBLIC, "两人不会在他人面前进行亲密身体接触。"),
            ),
        )

        val HERACLES = JudgeCharacter(
            card = GrowCharacter(
                id = HERACLES_ID,
                name = "赫拉克勒斯",
                summary = "体格健壮、为人热忱。",
                softAxesRaw = SOFT_AXES_ALL_ONE,
            ),
            hardRules = listOf(
                ruleOf(HERACLES_ID, "no_longtext", HardRuleScope.ALWAYS, "不会读写长文，看不懂复杂的学术文本。"),
            ),
        )

        fun ruleOf(characterId: Long, key: String, scope: HardRuleScope, description: String): JudgeHardRule =
            JudgeHardRule(characterId, key, scope, forbiddenCodes = emptySet(), description = description)

        /** 码表（值 = 调试种子 `DebugSeedProvider` 17 码定档）。 */
        val CODE_LEVELS: Map<String, Int> = mapOf(
            "stand" to 1, "walk" to 1, "sit" to 1, "lie_down" to 1, "pick_up" to 1, "put_down" to 1,
            "hand_item" to 1, "wave" to 1, "push_pull" to 1, "eat" to 1, "drink" to 1, "say" to 1, "turn" to 1,
            "hold_hands" to 3, "hug" to 4, "lean" to 3, "hold" to 4,
        )

        /** 动作名（同调试种子 behavior_name 列）。 */
        val CODE_NAMES: Map<String, String> = mapOf(
            "stand" to "Stand", "walk" to "Walk", "sit" to "Sit", "lie_down" to "Lie Down",
            "pick_up" to "Pick Up", "put_down" to "Put Down", "hand_item" to "Hand Item", "wave" to "Wave",
            "push_pull" to "Push or Pull", "eat" to "Eat", "drink" to "Drink", "say" to "Say",
            "turn" to "Turn", "hold_hands" to "Hold Hands", "hug" to "Hug", "lean" to "Lean On",
            "hold" to "Hold Up",
        )

        /**
         * 10 组对抗样本（text ↔ 期望「相容」；与 spike `JUDGE_SAMPLES` 逐字同源，
         * 步骤码 = 示意映射：句子里的违反点（跑 / 蹲 / 读长文 / 公开亲密）全都没有对应码）。
         */
        val ADVERSARIAL_SAMPLES: List<AdversarialSample> = listOf(
            AdversarialSample(
                index = 1, text = "赫拉克勒斯把温水递到普罗米修斯手边，顺手把散落的稿纸码整齐。",
                compatible = true, actorId = HERACLES_ID, steps = listOf("hand_item"),
                note = "日常照料，无规则涉及",
            ),
            AdversarialSample(
                index = 2, text = "普罗米修斯快步跑上楼梯，去追已经出门的赫拉克勒斯。",
                compatible = false, actorId = PROMETHEUS_ID, steps = listOf("walk"),
                note = "「跑」无码，文字面违反 mobility",
            ),
            AdversarialSample(
                index = 3, text = "赫拉克勒斯凑近看了眼稿子上的公式，然后和普罗米修斯讨论起推导过程。",
                compatible = false, actorId = HERACLES_ID, steps = listOf("walk"),
                note = "「读写长文」无码，文字面违反 no_longtext",
            ),
            AdversarialSample(
                index = 4, text = "在集市人多处，两人停下脚步，赫拉克勒斯俯身亲吻了普罗米修斯的脸颊。",
                compatible = false, actorId = HERACLES_ID, steps = listOf("hug"),
                note = "公开场合亲密，文字面违反 no_public_intimacy",
            ),
            AdversarialSample(
                index = 5, text = "深夜，普罗米修斯伏案写字，赫拉克勒斯把一条薄毯盖在他肩上。",
                compatible = true, actorId = HERACLES_ID, steps = listOf("put_down"),
                note = "日常照料，私密无误",
            ),
            AdversarialSample(
                index = 6, text = "普罗米修斯推着轮椅到窗边，示意赫拉克勒斯一起看外面下雨。",
                compatible = true, actorId = PROMETHEUS_ID, steps = listOf("push_pull"),
                note = "轮椅出行与设定一致",
            ),
            AdversarialSample(
                index = 7, text = "赫拉克勒斯一口气跑完三层楼梯，把药送到了普罗米修斯房间。",
                compatible = true, actorId = HERACLES_ID, steps = listOf("walk"),
                note = "「跑」的主语是赫拉克勒斯——mobility 只管普罗米修斯（对照 #2）",
            ),
            AdversarialSample(
                index = 8, text = "普罗米修斯蹲下来，仔细检查书架最下层的旧手稿。",
                compatible = false, actorId = PROMETHEUS_ID, steps = listOf("pick_up"),
                note = "「蹲」无码，文字面违反 mobility",
            ),
            AdversarialSample(
                index = 9, text = "赫拉克勒斯靠在轮椅旁，小声把今天街上的见闻讲给普罗米修斯听。",
                compatible = true, actorId = HERACLES_ID, steps = listOf("lean"),
                note = "日常照料，无规则涉及",
            ),
            AdversarialSample(
                index = 10, text = "客厅里只有他们两人时，普罗米修斯轻轻握住赫拉克勒斯的手。",
                compatible = true, actorId = PROMETHEUS_ID, steps = listOf("hold_hands"),
                note = "亲密接触但仅私密场合——no_public_intimacy 场合不符（对照 #4）",
            ),
        )

        // —— ② 对照：spike `OnDeviceSpikeActivity` 判定段逐字复制（脚本校验见 .agent-docs/logs/）——

        val SPIKE_JUDGE_SYSTEM = """
            你是角色一致性判定器。给你角色硬规则与一个事件描述，判断该事件是否与角色设定冲突（OOC）。
            要求：
            1. 只要事件与任一硬规则冲突，即判 consistent=false，并在 violation 中引用被违反的规则。
            2. 若事件与所有硬规则相容，consistent=true，violation 留空字符串。
            3. 输出必须是严格的 JSON 格式，不要包含任何其他文本。
        """.trimIndent()

        val SPIKE_JUDGE_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"consistent":{"type":"boolean"},"violation":{"type":"string"},"reason":{"type":"string"}},"required":["consistent","violation","reason"]}
        """.trimIndent()

        val SPIKE_JUDGE_RULES = """
            ## 角色硬规则
            1. 普罗米修斯双腿行动不便，不能跑、跳、蹲、上下楼梯；出行依赖轮椅。
            2. 赫拉克勒斯不会读写长文，看不懂复杂的学术文本。
            3. 两人不会在他人面前进行亲密身体接触。
        """.trimIndent()
    }
}
