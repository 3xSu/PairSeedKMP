package com.pairseedkmp.domain.judgment

import com.pairseedkmp.domain.ai.LlmJson
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.event.EventStep
import com.pairseedkmp.domain.event.SAY_ACTION_CODE
import com.pairseedkmp.domain.event.displayCn
import com.pairseedkmp.domain.event.parseSoftAxesLines
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 判定温度（06 §4：冷读、低温度；起步值 0.2，与打标段同档）。 */
internal const val JUDGE_TEMPERATURE: Double = 0.2

/**
 * 判定系统词（06 计划 §4 第 3 层·API 路线）：三档定义 + 「不会」必须指得出违反哪条 + 冷读纪律。
 * 输入块（用户消息）由 [buildJudgeRequest] 组装：这条事件 / 出场角色 / 关系 / 共享设定 / 场景 /
 * 最近已发生 / 程序粗算——**不复用生成时的上下文**（避免模型为自己的生成辩护）。
 */
internal val JUDGE_SYSTEM_PROMPT = """
    你是这对角色生活剧本的审稿人，要做一次独立冷读：给你一条已经生成好的互动事件，以及这对角色的完整设定与近期事实，判断——以他们的性格和关系，这件事他们到底做不做得出来。
    这次判断是独立复核：只依据用户消息里的材料，不要设想生成这条事件时考虑过什么，也不要为它找台阶。

    三个结论（verdict）：
    - "PASS"（会）：有设定或关系支持这件事，至少不与任何一条设定冲突。
    - "UNCERTAIN"（拿不准）：找不到明确依据说他们会做，也说不上具体违反了哪条设定，只是整体感觉不像他们。
    - "REJECT"（不会）：你能明确指出这件事具体违反了哪一条设定。判 REJECT 必须同时给出 violated_rule——填被违反的那条：优先从「不会做的事」里挑规则名，其他情况写清是哪个档位设定（如「身体接触：我的档位偏低，不接受这种推进」）。指不出具体违反哪条，就只能判 "UNCERTAIN"。

    判断依据（按这个顺序想）：
    1.「不会做的事」（硬规则）：命中任何一条即 "REJECT"；标注了场合的规则只在该场合生效，与现场不合就不算命中。
    2. 三个档位尺（身体接触 / 说话直接度 / 情绪外露）：事件与角色档位冲突得越明显，「不会」的可能性越大。
    3. 两人关系与共享设定：这件事的推进方式、说话方式，在他们之间合不合适。
    4.「程序粗算」一栏是程序按档位差粗略算的参考分，只是参考——你若在设定里找到具体依据，可以推翻它（判高或判低都可以）。

    输出：严格只输出 JSON，不要任何解释文字，字段顺序 verdict → reason → violated_rule（仅 REJECT 时给出）：
    {"verdict":"PASS|UNCERTAIN|REJECT","reason":"一句话理由","violated_rule":"仅 REJECT 时填被违反的那条设定"}
""".trimIndent()

/**
 * 判定输出 schema（约束解码用；字段与 [parseJudgeOutput] 对齐）：
 * verdict 闭集；violated_rule 可空、仅 REJECT 有值（是否真正引用到由解析层的降级规则兜）。
 */
fun judgeStageSchema(): String =
    """{"type":"object","additionalProperties":false,"properties":{"verdict":{"type":"string","enum":["PASS","UNCERTAIN","REJECT"]},"reason":{"type":"string"},"violated_rule":{"type":["string","null"]}},"required":["verdict","reason"]}"""

/** 判定请求：system = 三档纪律；user = 冷读块；R 作输入（[risk]）。 */
internal fun buildJudgeRequest(snapshot: JudgmentSnapshot, risk: RiskScore): LlmRequest {
    val candidate = snapshot.candidate
    val target = snapshot.target
    val user = buildString {
        appendLine("## 这条事件")
        appendLine("概要：${candidate.summary}")
        appendLine("步骤（按顺序）：")
        candidate.steps.forEachIndexed { index, step ->
            appendLine("  ${index + 1}. ${renderStep(step, snapshot.behaviorNames)}")
        }
        appendLine(
            if (candidate.suitableTime.isEmpty()) {
                "适合时段：不限"
            } else {
                "适合时段：${candidate.suitableTime.joinToString("、") { it.displayCn() }}"
            },
        )

        appendLine("## 出场角色")
        append(snapshot.actor.toJudgeBlock("发起方"))
        target?.let { append(it.toJudgeBlock("另一方")) }

        appendLine("## 两人的关系")
        val nameOf = buildMap {
            put(snapshot.actor.id, snapshot.actor.name)
            target?.let { put(it.id, it.name) }
        }
        if (snapshot.relations.isEmpty() || target == null) {
            appendLine("（没有更细的关系设定，按上面的角色设定相处。）")
        } else {
            for (relation in snapshot.relations) {
                val from = nameOf[relation.fromCharacterId] ?: continue
                val to = nameOf[relation.toCharacterId] ?: continue
                appendLine("$from 对 $to：${relation.towardTargetSummary}（默认距离：${relation.defaultDistance}）")
                if (relation.likelyInitiationModes.isNotEmpty()) {
                    appendLine("- 更可能主动发起：${relation.likelyInitiationModes.joinToString("、")}")
                }
                if (relation.safeComfortActions.isNotEmpty()) {
                    appendLine("- 稳定安全的互动：${relation.safeComfortActions.joinToString("、")}")
                }
                if (relation.sensitiveProgressionTags.isNotEmpty()) {
                    appendLine("- 敏感推进（容易踩线）：${relation.sensitiveProgressionTags.joinToString("、")}")
                }
                relation.directAddressingSummary?.takeIf { it.isNotBlank() }?.let { appendLine("- 当面称呼：$it") }
                relation.referenceAddressingSummary?.takeIf { it.isNotBlank() }?.let { appendLine("- 对外提及时称呼：$it") }
            }
        }

        appendLine("## 两人之间的共享设定")
        val sharedLines = buildList {
            snapshot.pairSummary?.takeIf { it.isNotBlank() }?.let { add("关系整体：$it") }
            snapshot.relationToneSummary?.takeIf { it.isNotBlank() }?.let { add("相处基调：$it") }
            if (snapshot.sharedForbiddenTags.isNotEmpty()) {
                add("共享禁区：${snapshot.sharedForbiddenTags.joinToString("、")}")
            }
        }
        if (sharedLines.isEmpty()) appendLine("（暂无）") else sharedLines.forEach { appendLine("- $it") }

        snapshot.scene?.let { scene ->
            appendLine("## 场景")
            val privacy = when (snapshot.sceneVisibility) {
                SceneVisibility.PRIVATE -> "（私密）"
                SceneVisibility.PUBLIC -> "（公开）"
                null -> ""
            }
            val text = scene.description?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            appendLine("${scene.name}$privacy$text")
        }

        appendLine("## 最近已发生的事（新的在前）")
        if (snapshot.recentSummaries.isEmpty()) {
            appendLine("（无）")
        } else {
            snapshot.recentSummaries.forEach { appendLine("- $it") }
        }

        appendLine("## 程序粗算（参考，可被设定依据推翻）")
        appendLine("风险分 R = ${risk.value}（0–100；≥$RISK_REJECT_THRESHOLD 系统按越界拦、≤$RISK_PASS_THRESHOLD 系统按正常放行）")
        risk.deviations.forEach { deviation ->
            val dim = deviation.dim.displayCn()
            if (deviation.delta == 0) {
                appendLine("- $dim：事件第 ${deviation.eventLevel} 档、对方能接受到第 ${deviation.tolerance} 档，没超出")
            } else {
                appendLine(
                    "- $dim：事件第 ${deviation.eventLevel} 档、对方能接受到第 ${deviation.tolerance} 档，" +
                        "超出 ${deviation.delta} 档（该维得分 ${deviation.score}）",
                )
            }
        }

        append("请判断：这件事他们做不做得出来？只输出 JSON。")
    }
    return LlmRequest(
        system = JUDGE_SYSTEM_PROMPT,
        user = user,
        temperature = JUDGE_TEMPERATURE,
        expectJson = true,
        task = LlmTask.JUDGE,
        responseSchema = judgeStageSchema(),
    )
}

/** 三档代号（解析层闭集；与 schema enum 对齐）。 */
internal enum class JudgeVerdictCode(val code: String) {
    PASS("PASS"),
    UNCERTAIN("UNCERTAIN"),
    REJECT("REJECT"),
    ;

    companion object {
        fun fromCode(code: String): JudgeVerdictCode? = entries.firstOrNull { it.code == code }
    }
}

/** 判定产物（解析 + 校验后的中间态；[violatedRule] 仅 REJECT 有意义）。 */
internal data class JudgeOutput(
    val verdict: JudgeVerdictCode,
    val reason: String,
    val violatedRule: String?,
)

/**
 * 解析判定输出（任一项不满足 → null = 技术失败，调用方重试一次）：
 * `verdict`（闭集，容忍大小写 / 空白）+ `reason`（必填非空白）+ `violated_rule`（可缺）；
 * 「REJECT 是否真的引用到违反条」不在这里否——那是降级规则（指不出 → 拿不准），不是解析失败。
 */
internal fun parseJudgeOutput(text: String): JudgeOutput? {
    val obj = LlmJson.parse(text) ?: return null
    val code = (obj["verdict"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase() ?: return null
    val verdict = JudgeVerdictCode.fromCode(code) ?: return null
    val reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (reason.isEmpty()) return null
    val violatedRule = (obj["violated_rule"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return JudgeOutput(verdict = verdict, reason = reason, violatedRule = violatedRule)
}

// ===== 渲染小件 =====

/** 单步一行：动作名（码）+「说」步的台词（speaker → 发起方 / 另一方）。 */
private fun renderStep(step: EventStep, behaviorNames: Map<String, String>): String {
    val name = behaviorNames[step.action]?.takeIf { it.isNotBlank() }
    val head = if (name != null) "$name（${step.action}）" else step.action
    if (step.action != SAY_ACTION_CODE) return head
    val text = (step.params["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return head
    val who = when ((step.params["speaker"] as? JsonPrimitive)?.contentOrNull) {
        "actor" -> "发起方"
        "target" -> "另一方"
        else -> "（未注明谁说）"
    }
    return "$head——$who 说：「$text」"
}

/** 角色冷读块（名字 / 摘要 / 别名 / 软轴 / 说话风格 / 喜恶 + 硬规则原文）。 */
private fun JudgeCharacter.toJudgeBlock(role: String): String = buildString {
    appendLine("${name}（$role）——${card.summary}")
    if (card.aliases.isNotEmpty()) appendLine("- 别名：${card.aliases.joinToString("、")}")
    parseSoftAxesLines(card.softAxesRaw).forEach { appendLine("- $it") }
    card.speechStyleSummary?.takeIf { it.isNotBlank() }?.let { appendLine("- 说话风格：$it") }
    if (card.preferredAffectionModes.isNotEmpty()) {
        appendLine("- 喜欢的方式：${card.preferredAffectionModes.joinToString("、")}")
    }
    if (card.dislikedAffectionModes.isNotEmpty()) {
        appendLine("- 不喜欢的方式：${card.dislikedAffectionModes.joinToString("、")}")
    }
    appendLine("- 不会做的事（硬规则，命中任何一条就是「不会」）：")
    if (hardRules.isEmpty()) {
        appendLine("  （无）")
    } else {
        hardRules.forEach { rule ->
            val scope = rule.scope.displayCn()
            val desc = rule.description?.takeIf { it.isNotBlank() }
            appendLine(if (desc == null) "  - ${rule.ruleKey}（$scope）" else "  - ${rule.ruleKey}（$scope）：$desc")
        }
    }
}

/** 硬规则作用域中文（提示词里给模型看的）。 */
private fun HardRuleScope.displayCn(): String = when (this) {
    HardRuleScope.ALWAYS -> "恒生效"
    HardRuleScope.PUBLIC -> "仅公开场合"
    HardRuleScope.PRIVATE -> "仅私密场合"
}

/** 风险三维中文（与软轴五维同两把尺的名字对齐）。 */
private fun RiskDim.displayCn(): String = when (this) {
    RiskDim.PHYSICAL -> "身体接触"
    RiskDim.VERBAL -> "说话直接度"
    RiskDim.EMOTION -> "情绪外露"
}
