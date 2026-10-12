package com.pairseedkmp.domain.judgment

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmErrorKind
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.event.EventStep
import com.pairseedkmp.domain.event.GrowScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 06 步骤 4：API 路线裁决（AiJudge）——三档 × 解析失败重试一次 × 「不会」指不出 → 拿不准 ×
 * 降级矩阵（硬规则直通 / 缺料先于 AI / API 失败回落系统三支）× 提示词冷读块。
 * 假 client 队列式给响应，`LlmCaller` 真跑（瞬时重试用虚拟时间）。
 */
class AiJudgeTest {

    /** 队列式假 client：按调用次序给文本；[failWith] 非空则每次调用先抛；记录全部请求。 */
    private class QueueClient(vararg texts: String) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        private val queue = ArrayDeque(texts.toList())
        var failWith: LlmException? = null

        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            failWith?.let { throw it }
            return LlmResponse(queue.removeFirstOrNull() ?: error("脚本用完了"))
        }
    }

    private fun payloadOf(decision: JudgeDecision): JsonObject =
        Json.parseToJsonElement(decision.confirmationPayload!!).jsonObject

    // ===== 三档 =====

    @Test
    fun 会_直通_请求形状对上() = runTest {
        val client = QueueClient("""{"verdict":"PASS","reason":"他没有意见","violated_rule":null}""")
        val judge = AiJudge(LlmCaller(client))
        val snap = snapshot(candidate("walk", targetId = null), char(1, axes = softAxes(phys(1))), target = null)

        val decision = judge.decide(snap)

        assertEquals(JudgmentOutcome.Pass, decision.outcome)
        assertEquals(0, decision.risk?.value)
        assertNull(decision.confirmationPayload, "过的没有 payload")
        assertNull(decision.degradedReason)
        val request = client.requests.single()
        assertEquals(LlmTask.JUDGE, request.task)
        assertEquals(0.2, request.temperature)
        assertTrue(request.expectJson)
        assertEquals(judgeStageSchema(), request.responseSchema)
    }

    @Test
    fun 拿不准_疑问原话进payload_系统事实照留() = runTest {
        val client = QueueClient("""{"verdict":"UNCERTAIN","reason":"拿不准是不是太亲了"}""")
        val judge = AiJudge(LlmCaller(client))
        // 拥抱 e=4、双方档 2 → 系统算 33 灰带；AI 独立冷读同样拿不准
        val snap = snapshot(candidate("hug"), char(1, axes = softAxes(phys(2))), char(2, axes = softAxes(phys(2))))

        val decision = judge.decide(snap)

        val uncertain = assertIs<JudgmentOutcome.Uncertain>(decision.outcome)
        assertEquals(UncertainReason.Ai("拿不准是不是太亲了"), uncertain.reason)
        assertEquals(33, decision.risk?.value, "R 仍在决定里（程序事实）")
        val payload = payloadOf(decision)
        assertEquals("AI", payload["source"]!!.jsonPrimitive.content)
        assertEquals("拿不准是不是太亲了", payload["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun 不会_指不出违反条_降为拿不准_带条才拒() = runTest {
        val client = QueueClient(
            """{"verdict":"REJECT","reason":"太主动了，不像他"}""",
            """{"verdict":"REJECT","reason":"太主动了，不像他","violated_rule":"   "}""",
            """{"verdict":"REJECT","reason":"他不接长文的活","violated_rule":"no_longtext"}""",
        )
        val judge = AiJudge(LlmCaller(client))
        val snap = snapshot(candidate("hug"), char(1, axes = softAxes(phys(2))), char(2, axes = softAxes(phys(2))))

        // 缺 violated_rule
        val absent = judge.decide(snap)
        val first = assertIs<JudgmentOutcome.Uncertain>(absent.outcome)
        assertEquals(UncertainReason.Ai("太主动了，不像他"), first.reason)
        assertEquals("AI", payloadOf(absent)["source"]!!.jsonPrimitive.content)

        // violated_rule 是空白
        val blank = judge.decide(snap)
        assertIs<JudgmentOutcome.Uncertain>(blank.outcome)

        // 带条 → 拒（理由 = AI 原话 + 违反条）
        val decision = judge.decide(snap)
        val reject = assertIs<JudgmentOutcome.Reject>(decision.outcome)
        assertEquals(RejectReason.Ai("他不接长文的活", "no_longtext"), reject.reason)
        assertNull(decision.confirmationPayload, "拒不留痕、无 payload")
    }

    // ===== 解析失败与降级 =====

    @Test
    fun 解析失败_重试一次_第二次成功() = runTest {
        val client = QueueClient("这不是 JSON", """{"verdict":"PASS","reason":"可以"}""")
        val judge = AiJudge(LlmCaller(client))
        val snap = snapshot(candidate("walk", targetId = null), char(1, axes = softAxes(phys(1))), target = null)

        val decision = judge.decide(snap)

        assertEquals(JudgmentOutcome.Pass, decision.outcome)
        assertEquals(2, client.requests.size)
        assertEquals(client.requests[0], client.requests[1], "重试 = 同一请求原样再要一次")
    }

    @Test
    fun 解析失败两次_failSafe待确认_固定话术() = runTest {
        val client = QueueClient("{}", "还是不 JSON")
        val judge = AiJudge(LlmCaller(client))
        val snap = snapshot(candidate("walk", targetId = null), char(1, axes = softAxes(phys(1))), target = null)

        val decision = judge.decide(snap)

        val uncertain = assertIs<JudgmentOutcome.Uncertain>(decision.outcome)
        assertEquals(UncertainReason.Ai(JUDGE_PARSE_FAILED_REASON), uncertain.reason)
        assertEquals(2, client.requests.size, "只重试一次、不无限重来")
        val payload = payloadOf(decision)
        assertEquals("AI", payload["source"]!!.jsonPrimitive.content)
        assertEquals(JUDGE_PARSE_FAILED_REASON, payload["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun 硬规则码命中_直通_先于缺料_不经AI() = runTest {
        val client = QueueClient() // 一条脚本都没有——被调就会炸
        val judge = AiJudge(LlmCaller(client))
        val actor = char(1, rules = listOf(rule(1, HardRuleScope.ALWAYS, "hug", key = "no_hug"))) // 没设定卡（本会缺料）
        val snap = snapshot(candidate("hug"), actor, char(2))

        val decision = judge.decide(snap)

        val reject = assertIs<JudgmentOutcome.Reject>(decision.outcome)
        assertEquals(RejectReason.HardRule(HardRuleHit(1, "no_hug", HardRuleScope.ALWAYS, setOf("hug"))), reject.reason)
        assertTrue(decision.facts.missing.isNotEmpty(), "缺料在局中，但硬规则先赢")
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun 缺料_强制待确认_先于AI() = runTest {
        val client = QueueClient()
        val judge = AiJudge(LlmCaller(client))
        val snap = snapshot(candidate("fly", targetId = null), char(1, axes = softAxes(phys(1))), target = null)

        val decision = judge.decide(snap)

        val uncertain = assertIs<JudgmentOutcome.Uncertain>(decision.outcome)
        assertEquals(
            UncertainReason.Missing(listOf(MissingMaterial.StepCodeNotInTable("fly"))),
            uncertain.reason,
        )
        assertEquals("SYSTEM", payloadOf(decision)["source"]!!.jsonPrimitive.content, "缺料 payload 走系统形状")
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun API持续失败_降级系统裁决_沿用已算好的R() = runTest {
        val client = QueueClient().apply { failWith = LlmException(LlmErrorKind.PERSISTENT, "401 未授权") }
        val judge = AiJudge(LlmCaller(client))
        val snap = snapshot(candidate("hug"), char(1, axes = softAxes(phys(2))), char(2, axes = softAxes(phys(2))))

        val decision = judge.decide(snap)

        val uncertain = assertIs<JudgmentOutcome.Uncertain>(decision.outcome)
        val risk = assertIs<RiskScore>(decision.risk)
        assertEquals(UncertainReason.Risk(risk), uncertain.reason, "回落系统三支的结果")
        assertEquals(33, risk.value)
        assertEquals("401 未授权", decision.degradedReason)
        val payload = payloadOf(decision)
        assertEquals("SYSTEM", payload["source"]!!.jsonPrimitive.content)
        assertEquals("RISK", payload["reason"]!!.jsonPrimitive.content)
        assertEquals(1, client.requests.size, "持续错误不重试")
    }

    @Test
    fun 高分拒_AI可推翻_过() = runTest {
        val client = QueueClient("""{"verdict":"PASS","reason":"他们就是这样闹着玩的"}""")
        val judge = AiJudge(LlmCaller(client))
        // 拥抱 e=4、双方档 3 → 系统算 67 ≥ 65 拒；AI 冷读可以推翻程序分
        val snap = snapshot(candidate("hug"), char(1, axes = softAxes(phys(3))), char(2, axes = softAxes(phys(3))))

        val decision = judge.decide(snap)

        assertEquals(JudgmentOutcome.Pass, decision.outcome)
        assertEquals(67, decision.risk?.value, "R 是输入不是裁决")
        val request = client.requests.single()
        assertTrue(request.user.contains("风险分 R = 67"), "R 进了提示词")
    }

    // ===== 提示词冷读块 =====

    @Test
    fun 提示词_冷读块齐_规则带原文_粗算可推翻() = runTest {
        val client = QueueClient("""{"verdict":"PASS","reason":"可以是"}""")
        val judge = AiJudge(LlmCaller(client))
        val actor = char(
            id = 1,
            axes = softAxes(phys(2), verb(2), emo(3)),
            rules = listOf(rule(1, key = "no_longtext", description = "不看长文，会直接走开")),
            summary = "安静内敛",
            aliases = listOf("小A"),
            speech = "话少，句子短",
        )
        val target = char(id = 2, axes = softAxes(phys(2)), summary = "温和细腻")
        val cand = candidate("walk", "say", verbal = 1).copy(
            summary = "劝对方先吃饭",
            steps = listOf(
                EventStep("walk"),
                EventStep(
                    "say",
                    JsonObject(
                        mapOf(
                            "speaker" to JsonPrimitive("actor"),
                            "text" to JsonPrimitive("先吃饭，稿子不会跑。"),
                        ),
                    ),
                ),
            ),
            suitableTime = listOf(Period.EVENING),
        )
        val snap = snapshot(
            candidate = cand,
            actor = actor,
            target = target,
            relations = listOf(relation(1, 2, "A 照顾 B"), relation(2, 1)),
            scene = GrowScene(id = 9, name = "家", locationType = "PRIVATE", description = "客厅"),
            pairSummary = "姐弟",
            relationToneSummary = "温和克制",
            sharedTags = listOf("不提过去"),
            recent = listOf("早上一起吃了饭"),
            behaviorNames = mapOf("walk" to "WALK", "say" to "SAY"),
        )

        val decision = judge.decide(snap)

        assertEquals(JudgmentOutcome.Pass, decision.outcome)
        val request = client.requests.single()
        assertTrue(request.system.contains("冷读"))
        assertTrue(request.system.contains("\"REJECT\"（不会）"))
        assertTrue(request.system.contains("violated_rule"))
        val user = request.user
        assertTrue(user.contains("概要：劝对方先吃饭"))
        assertTrue(user.contains("1. WALK（walk）"))
        assertTrue(user.contains("2. SAY（say）——发起方 说：「先吃饭，稿子不会跑。」"))
        assertTrue(user.contains("适合时段：傍晚"))
        assertTrue(user.contains("角色1（发起方）——安静内敛"))
        assertTrue(user.contains("- 别名：小A"))
        assertTrue(user.contains("- 说话风格：话少，句子短"))
        assertTrue(user.contains("- no_longtext（恒生效）：不看长文，会直接走开"), "规则原文进提示词")
        assertTrue(user.contains("角色1 对 角色2：A 照顾 B（默认距离：亲近）"))
        assertTrue(user.contains("- 关系整体：姐弟"))
        assertTrue(user.contains("- 共享禁区：不提过去"))
        assertTrue(user.contains("家（私密）：客厅"))
        assertTrue(user.contains("- 早上一起吃了饭"))
        assertTrue(user.contains("风险分 R = "))
        assertTrue(user.contains("可被设定依据推翻"))
        assertTrue(user.endsWith("只输出 JSON。"))
    }
}
