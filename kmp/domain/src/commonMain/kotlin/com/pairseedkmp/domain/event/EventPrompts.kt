package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.ai.LlmJson
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.dedup.DedupType
import com.pairseedkmp.domain.seed.IdeaSeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 生成段（A1）温度：高温求发散（《00-链路总览》：生成高 / 判定低）。 */
private const val GROW_TEMPERATURE: Double = 0.9

/** 打标段（A2）温度：低值求稳定（端侧 spike §10：平铺 digest 降到 0.2 后一致性 9/10 → 事实固化后 10/10）。 */
private const val TAG_TEMPERATURE: Double = 0.2

/** 「说」动作码（台词挂在它上面）。 */
const val SAY_ACTION_CODE: String = "say"

/** 「说」步骤允许的 speaker 值域。 */
internal val SAY_SPEAKERS = setOf("actor", "target")

/** 档位值域（两维 1–4 档）。 */
internal val AXIS_RANGE = 1..4

/** 台词语速：4 字 / 秒（起步值；中文正常语速研究区间 3.6–4.3，调研 `.agent-docs/reports/台词生成调研.md`）。 */
const val SPEECH_CHARS_PER_SECOND: Int = 4

// ===== 生成段（A1）：写概要 + 步骤 + 台词 + 接续指认 =====

/**
 * 生成段系统词（从零写，骨架沿用端侧 spike v3.1 已验证形态）：三段「定义 / 用途 / 依据」+ 完整写法示例。
 * 钉死两条（05 计划）：① 动作码只能从【动作库】挑（只挑不编）；② `after` 只从【清单】指认编号。
 */
internal val GROW_SYSTEM_PROMPT = """
    你是角色互动事件生成器。根据【输入】里的角色设定、关系、当前时间、动作库、参考素材和灵感种子，生成一条新的互动事件。
    输出包含下面 4 个部分。每个部分先讲清：它是什么（定义）、系统拿它做什么（用途）、你依据什么填（依据）。全部读完再开始写。

    1. summary（概要）
       定义：这条事件的一句话概要。
       用途：显示在清单和历史页；系统也拿它和近期事件比对，判断是不是“差不多的事”（查重）。
       依据：从灵感种子出发（要能看出种子的影子）；只写“谁对谁做了什么”（谁主动都可以）；10-20 字。
       注意：写你自己根据种子造出的这件事，不要把灵感种子原句抄一遍；不写具体动作、物品、场景细节——系统靠它比对“像不像”，细节写多了，同一件事换个说法就对不上。
       示例：“劝对方先吃饭再忙。”

    2. steps（步骤序列）
       定义：角色按顺序依次做的一串动作；事件发生后就按这个顺序做。
       依据：动作只能从【动作库】里挑（只能挑、不能编）；连贯一体的动作（如先做饭再吃饭）合成同一条事件的多步骤，按发生顺序排；做什么、怎么排要合角色性格、关系和种子情境。
       注意：至少写 1 步（不能输出空数组）；每一步只写一个动作码；「说」也是其中一个动作，台词挂在它上面（见第 3 条）；除「说」外的步骤，params 可以整个省略——不要给别的步骤填 speaker 或 text。

    3. 「说」步骤——台词写在这里（对 steps 里的「说」补充说明）
       定义：角色说出一句话。播到这一步，角色头顶弹对话框显示这句话。
       依据：有话说才写「说」步骤（一条事件 0-2 句；整件事可以不说话——不说话就一个「说」步骤都不写）；说什么参考【说话样本】贴近该角色的风格，但不要照抄。
       写法：一个带台词的「说」步骤长这样——{"action":"say","params":{"speaker":"actor","text":"先吃饭，稿子不会跑。"}}
       —— 台词写进 params 的 text 字段；speaker 说明这句话是谁说的（actor = 发起方，target = 另一方）；单句不超过 30 字；text 里只写说出来的话本身——不带引号、不写旁白、不写动作描述、不写“某某说：”这样的前缀。

    4. after（接续指认，可选）
       定义：这条事件之后，紧接着做【清单】里的哪一件（给清单条目的编号）。
       依据：只有当这条事件做完、“接着还想做清单里另一件独立的事”时才给（如“收拾完顺路去拿快递”）；同一件事的连贯动作全部合成 steps（如同一条事件里先做饭再吃饭），不要用 after。
       注意：只能填【清单】里出现的编号，不能自己编、不能填不存在的编号；没有合适的就不给这个字段（大多数情况都不给）。

    输出：严格按 summary → steps → after（如需要）的顺序，只输出 JSON，不要任何解释文字。
""".trimIndent()

/** 一次重复反馈：被判定与已有事件“像的那条”的概要 + 重复类型（进下一轮提示词换角度重写）。 */
data class GrowDuplicateFeedback(val summary: String, val type: DedupType)

/** 生成请求：组装好的上下文块 + 种子 + 重复反馈（工作链路 1.3：把「像的那条」写进提示词）。 */
internal fun buildGrowRequest(
    context: EventGrowContext,
    seed: IdeaSeed,
    duplicates: List<GrowDuplicateFeedback>,
): LlmRequest {
    val config = context.config
    val user = buildString {
        appendLine("## 当前时间")
        appendLine("现在是${context.period.displayCn()}（${context.period.code}）。")

        appendLine("## 角色设定")
        append(config.actor.toPromptBlock("发起方"))
        append(config.target.toPromptBlock("另一方"))

        appendLine("## 关系")
        if (config.relations.isEmpty()) {
            appendLine("（没有更细的关系设定，按上面的角色设定相处。）")
        } else {
            config.relations.forEach { relation ->
                val fromIsActor = relation.fromCharacterId == config.actor.id
                val from = if (fromIsActor) config.actor.name else config.target.name
                val to = if (fromIsActor) config.target.name else config.actor.name
                appendLine("$from 对 $to：${relation.towardTargetSummary}（默认距离：${relation.defaultDistance}）")
                if (relation.likelyInitiationModes.isNotEmpty()) {
                    appendLine("- 更可能主动发起：${relation.likelyInitiationModes.joinToString("、")}")
                }
                if (relation.safeComfortActions.isNotEmpty()) {
                    appendLine("- 稳定安全的互动：${relation.safeComfortActions.joinToString("、")}")
                }
                if (relation.sensitiveProgressionTags.isNotEmpty()) {
                    appendLine("- 高风险推进（生成时避开）：${relation.sensitiveProgressionTags.joinToString("、")}")
                }
                relation.directAddressingSummary?.takeIf { it.isNotBlank() }?.let { appendLine("- 当面称呼：$it") }
                relation.referenceAddressingSummary?.takeIf { it.isNotBlank() }?.let { appendLine("- 对外提及时称呼：$it") }
            }
        }

        appendLine("## 两人之间的共享设定")
        val sharedLines = buildList {
            config.pairSummary?.takeIf { it.isNotBlank() }?.let { add("关系整体：$it") }
            config.relationToneSummary?.takeIf { it.isNotBlank() }?.let { add("相处基调：$it") }
            if (config.sharedForbiddenTags.isNotEmpty()) {
                add("共享禁区（不要碰）：${config.sharedForbiddenTags.joinToString("、")}")
            }
        }
        if (sharedLines.isEmpty()) appendLine("（暂无）") else sharedLines.forEach { appendLine("- $it") }

        appendLine("## 场景")
        val privacy = when (config.scene.locationType) {
            "PRIVATE" -> "（私密）"
            "PUBLIC" -> "（公开）"
            else -> ""
        }
        val sceneText = config.scene.description?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        appendLine("${config.scene.name}$privacy$sceneText")

        appendLine("## 动作库（只能从这里挑动作码，不能自己编）")
        context.allowedBehaviors.forEach { behavior ->
            appendLine("- ${behavior.code}（${behavior.name}）：${behavior.description?.takeIf { it.isNotBlank() } ?: "—"}")
        }

        val voices = config.documents.filter { it.docType == "voice_ref" }
        if (voices.isNotEmpty()) {
            appendLine("## 说话样本（只说“这类角色会怎么说话”，不要照抄）")
            voices.forEach { appendLine("- ${it.renderLine()}") }
        }
        val activities = config.documents.filter { it.docType == "activity_ref" }
        if (activities.isNotEmpty()) {
            appendLine("## 活动素材（参考氛围，不要照抄）")
            activities.forEach { appendLine("- ${it.renderLine()}") }
        }

        appendLine("## 近期已经发生过的事（不要生成和它们差不多的事）")
        if (config.recentSummaries.isEmpty()) appendLine("（暂无）") else
            config.recentSummaries.forEach { appendLine("- $it") }

        appendLine("## 清单里已有的待做事件（不要重复；“接着想做”只能从这里面指认编号）")
        if (context.checklist.isEmpty()) appendLine("（清单里还没有待做事件——这次不要给 after 字段）") else
            context.checklist.forEach { appendLine("${it.number}. ${it.summary}") }

        appendLine("## 灵感种子")
        appendLine("关键词：${seed.keyword}；主题：${seed.theme}")

        if (duplicates.isNotEmpty()) {
            appendLine("【上一轮生成的概要被判定为重复】")
            duplicates.forEach { appendLine("「${it.summary}」——${it.type.displayCn()}") }
            appendLine("这些和已有事件太像了，换一个明显不同的角度重新写。")
        }
        append("请根据以上信息生成一条互动事件，输出 JSON。")
    }
    return LlmRequest(
        system = GROW_SYSTEM_PROMPT,
        user = user,
        temperature = GROW_TEMPERATURE,
        expectJson = true,
        task = LlmTask.EVENT_GROW,
    )
}

/** 生成段产物（解析 + 结构校验后的中间态）；[afterNumber] = 清单编号（1 起）。 */
internal data class GrowOutput(
    val summary: String,
    val steps: List<EventStep>,
    val afterNumber: Int?,
)

/**
 * 解析生成段输出，做结构校验（任一项不满足 → null = 技术失败，同种子重试计次）：
 * JSON 对象；`summary` 非空；`steps` 非空且动作码 ∈ [allowedCodes]；「说」步参数齐
 * （speaker ∈ {actor,target}、text 非空）；`after`（若给）∈ 1..[checklistSize]。
 * 清洗：非「说」步骤的 params 一律清掉（不许模型自己带时长 / 台词）；「说」步只留 speaker / text。
 */
internal fun parseGrowOutput(text: String, allowedCodes: Set<String>, checklistSize: Int): GrowOutput? {
    val obj = LlmJson.parse(text) ?: return null
    val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (summary.isEmpty()) return null

    val stepsArray = obj["steps"] as? JsonArray ?: return null
    if (stepsArray.isEmpty()) return null
    val steps = stepsArray.map { element ->
        val stepObj = element as? JsonObject ?: return null
        val code = (stepObj["action"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (code !in allowedCodes) return null
        if (code != SAY_ACTION_CODE) {
            EventStep(code)
        } else {
            val params = stepObj["params"] as? JsonObject ?: return null
            val speaker = (params["speaker"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (speaker !in SAY_SPEAKERS) return null
            val sayText = (params["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (sayText.isEmpty()) return null
            EventStep(code, buildJsonObject {
                put("speaker", speaker)
                put("text", sayText)
            })
        }
    }

    var afterNumber: Int? = null
    val afterElement = obj["after"]
    if (afterElement != null && afterElement !is JsonNull) {
        val number = (afterElement as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        if (number !in 1..checklistSize) return null
        afterNumber = number
    }
    return GrowOutput(summary, steps, afterNumber)
}

// ===== 程序整理稿（digest）：A2 打标段的输入（端侧 spike §10 验证形态）=====

/** 整理稿：概要 / 动作顺序 / 台词逐条平铺——A2 不再自己翻 JSON 找「说」步骤。 */
internal fun renderEventDigest(summary: String, steps: List<EventStep>): String {
    val sb = StringBuilder()
    sb.appendLine("下面是一条已经生成好的事件（已整理成文字，直接读即可）：")
    sb.appendLine("· 概要：$summary")
    sb.appendLine("· 动作顺序：${steps.joinToString("，") { it.action }}")
    val says = digestSayLines(steps)
    if (says.isEmpty()) {
        sb.appendLine("· 台词：没有（整件事里没有人说话）")
    } else {
        sb.appendLine("· 台词（共 ${says.size} 句）：")
        says.forEachIndexed { index, line -> sb.appendLine("  ${index + 1}. $line") }
    }
    sb.append("请据此完成两个字段的输出。")
    return sb.toString()
}

/** 台词行（digest 与「有没有台词」判定共用）：speaker 显示成 发起方 / 另一方；空白 text 跳过。 */
internal fun digestSayLines(steps: List<EventStep>): List<String> =
    steps.filter { it.action == SAY_ACTION_CODE }.mapNotNull { step ->
        val text = (step.params["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return@mapNotNull null
        val who = when ((step.params["speaker"] as? JsonPrimitive)?.contentOrNull) {
            "actor" -> "发起方"
            "target" -> "另一方"
            else -> "（未注明谁说）"
        }
        "$who 说：$text"
    }

// ===== 打标段（A2）：读 digest → 两维档位 + 适合时段 =====

/**
 * 打标段系统词（骨架沿用端侧 spike v3.1/A′ 已验证形态）：档位 = 客观属性（不是越界判断），
 * verbal 只看「台词」那几行；时段用四码、空数组 = 不限。
 */
internal val TAG_SYSTEM_PROMPT = """
    请阅读用户消息里给出的一条已经生成好的事件（已整理成文字：概要 / 动作顺序 / 台词），完成两件事：
    1. 给这项事件填两个客观档位（comparison_axes）；
    2. 判断它适合在哪些时段发生（suitable_time）。
    两部分各自的定义、用途、填写依据如下——特别注意：填档位时要看「台词」那几行，依据你眼前实际写着的内容来填。

    comparison_axes（两个客观档位）
    定义：只描述“这件事本身是什么样”的两把尺子，各在 1-4 档里选一个整数。
    用途：它们是事件的客观属性，系统会拿它和每个角色各自的脾气分开比较，谁接不接受由系统判。所以你的任务只是如实描述这件事：不是判断合不合适，不是判断越不越界。
    - verbal_directness（话有多直白）——只看「台词」那几行：
        ① 台词写着「没有」→ 填 null（这件事不涉及说话）；
        ② 有 → 看这些话的说法：绕着说、只说一半 = 1；话留白、要对方领会 = 2；基本直说、少有修饰 = 3；想什么说什么 = 4。
        一句含蓄一句直白时，以最直白的那句为准。
    - emotion_visibility（情绪有多看得见）：
        定义：这件事做出来、话说出来之后，对方能看出多少情绪——不是角色心里多激动，是“表现出多少”。
        填法：看动作顺序和台词整体给人的感觉：1 看不出（情绪压着）；2 偏内敛（只露一点）；3 偏外露（能明显看出）；4 全写在脸上（藏不住）。

    suitable_time（适合发生的时段）
    定义：这件事适合在哪些时段发生。
    用途：系统只在适合的时段安排它发生；弹给用户确认时“摘时段”也用这里。
    依据：从事件本身的性质想（比如劝吃饭适合饭点）。只能填 MORNING / AFTERNOON / EVENING / NIGHT，可以多个；拿不准或什么时段都行 → 空数组 []（表示不限）。

    输出：严格按 comparison_axes → suitable_time 的顺序生成 JSON，不要任何解释文字。
""".trimIndent()

/**
 * 打标段输出 schema——**事实固化**（2026-10-10，端侧 spike §14）：`verbal_directness` 的可空性
 * 由程序按「说」步有无判定并固化进 schema：有台词 → 只放行 1-4（null 不可输出）；无 → 只放行 null。
 * 供支持约束解码的 provider 用（端侧）；预设 provider 用它模拟约束（data 侧读同一份）；API 映射暂不带。
 */
fun tagStageSchema(hasSpeech: Boolean): String {
    val verbal = if (hasSpeech) """{"type":"integer","enum":[1,2,3,4]}""" else """{"type":"null"}"""
    return """{"type":"object","additionalProperties":false,"properties":{"comparison_axes":{"type":"object","additionalProperties":false,"properties":{"verbal_directness":$verbal,"emotion_visibility":{"type":"integer","enum":[1,2,3,4]}},"required":["verbal_directness","emotion_visibility"]},"suitable_time":{"type":"array","items":{"type":"string","enum":["MORNING","AFTERNOON","EVENING","NIGHT"]}}},"required":["comparison_axes","suitable_time"]}"""
}

/** 打标请求：user = 整理稿（digest）；固化后的 schema 随请求带下去。 */
internal fun buildTagRequest(digest: String, hasSpeech: Boolean): LlmRequest = LlmRequest(
    system = TAG_SYSTEM_PROMPT,
    user = digest,
    temperature = TAG_TEMPERATURE,
    expectJson = true,
    task = LlmTask.EVENT_TAG,
    responseSchema = tagStageSchema(hasSpeech),
)

/** 打标段产物（解析 + 校验 + 一致性守卫后的中间态）。 */
internal data class TagOutput(
    val verbalDirectness: Int?,
    val emotionVisibility: Int,
    val suitableTime: List<Period>,
)

/**
 * 解析打标段输出（任一项不满足 → null = 技术失败）：
 * `comparison_axes.{verbal_directness, emotion_visibility}`（档位 ∈ 1–4；verbal 可 null）+
 * `suitable_time`（时段码数组，可空 = 不限）。
 * 一致性守卫（防御层——schema 固化之外的兜底）：有台词 → verbal 必须非 null；无 → 必须 null。
 */
internal fun parseTagOutput(text: String, hasSpeech: Boolean): TagOutput? {
    val obj = LlmJson.parse(text) ?: return null
    val axes = obj["comparison_axes"] as? JsonObject ?: return null

    val verbalElement = axes["verbal_directness"] ?: return null
    val verbal = if (verbalElement is JsonNull) {
        null
    } else {
        val value = (verbalElement as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        if (value !in AXIS_RANGE) return null
        value
    }
    if (hasSpeech != (verbal != null)) return null

    val emotion = (axes["emotion_visibility"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
    if (emotion !in AXIS_RANGE) return null

    val timeArray = obj["suitable_time"] as? JsonArray ?: return null
    val periods = timeArray.map { element ->
        val code = (element as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        Period.entries.firstOrNull { it.code == code } ?: return null
    }
    return TagOutput(verbal, emotion, periods)
}

// ===== 台词时长（程序算，不交给 AI 估） =====

/** 台词时长（秒）：字数 ÷ [SPEECH_CHARS_PER_SECOND]，向上取整、至少 1 秒（空白不计字）。 */
internal fun sayDurationSeconds(text: String): Int {
    val chars = text.count { !it.isWhitespace() }
    return ((chars + SPEECH_CHARS_PER_SECOND - 1) / SPEECH_CHARS_PER_SECOND).coerceAtLeast(1)
}

/** 给「说」步补/盖程序算的 `duration_sec`（其余步骤原样）。 */
internal fun List<EventStep>.withSayDurations(): List<EventStep> = map { step ->
    if (step.action != SAY_ACTION_CODE) return@map step
    val text = (step.params["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val speaker = (step.params["speaker"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    step.copy(params = buildJsonObject {
        put("speaker", speaker)
        put("text", text)
        put("duration_sec", sayDurationSeconds(text))
    })
}

// ===== 渲染小件 =====

/** 时段中文名（提示词里给模型看的）。 */
internal fun Period.displayCn(): String = when (this) {
    Period.MORNING -> "早晨"
    Period.AFTERNOON -> "下午"
    Period.EVENING -> "傍晚"
    Period.NIGHT -> "夜里"
}

/** 重复类型中文（反馈进提示词）。 */
private fun DedupType.displayCn(): String = when (this) {
    DedupType.EXACT -> "完全相同"
    DedupType.CHAR_SIMILAR -> "字形近似"
    DedupType.SAME_THING -> "同一件事"
}

/** 文档片段渲染一行：[title] 与截到 [DEFAULT_DOC_BODY_CHARS] 字的正文。 */
private fun GrowDocumentRef.renderLine(): String {
    val head = title?.takeIf { it.isNotBlank() }
    val text = body?.takeIf { it.isNotBlank() }?.take(DEFAULT_DOC_BODY_CHARS)
    return listOfNotNull(head, text).joinToString("：")
}

/** 软轴维度渲染顺序与中文名（对齐《数据设计》五维顺序）。 */
private val SOFT_AXIS_LABELS = listOf(
    "proactivity" to "主动性",
    "emotion_visibility" to "情绪外露",
    "expression_mode" to "表达方式",
    "physical_intimacy" to "身体接触",
    "verbal_directness" to "说话直接度",
)

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 软轴 JSON → 提示词行（「主动性：第 3 档——<text>」；非法 / 空 → 空表）。 */
internal fun parseSoftAxesLines(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val obj = runCatching { lenientJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
    return SOFT_AXIS_LABELS.mapNotNull { (key, label) ->
        val entry = obj[key] as? JsonObject ?: return@mapNotNull null
        val level = (entry["level"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val text = (entry["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        when {
            level != null && text.isNotEmpty() -> "$label：第 $level 档——$text"
            level != null -> "$label：第 $level 档"
            text.isNotEmpty() -> "$label：$text"
            else -> null
        }
    }
}

/** 角色设定块（名字 / 摘要 / 别名 / 软轴 / 说话风格 / 喜恶）——进生成提示词。 */
private fun GrowCharacter.toPromptBlock(role: String): String = buildString {
    appendLine("${name}（$role）——$summary")
    if (aliases.isNotEmpty()) appendLine("- 别名：${aliases.joinToString("、")}")
    parseSoftAxesLines(softAxesRaw).forEach { appendLine("- $it") }
    speechStyleSummary?.takeIf { it.isNotBlank() }?.let { appendLine("- 说话风格：$it") }
    if (preferredAffectionModes.isNotEmpty()) appendLine("- 喜欢的方式：${preferredAffectionModes.joinToString("、")}")
    if (dislikedAffectionModes.isNotEmpty()) appendLine("- 不喜欢的方式：${dislikedAffectionModes.joinToString("、")}")
}
