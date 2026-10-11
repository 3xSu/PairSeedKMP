package com.pairseedkmp.domain.dedup

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmJson
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmTask
import kotlinx.serialization.json.JsonPrimitive

/**
 * 短词 L2 相似度阈值默认值：0.75（计划起步 0.85；2026-10-07 按种子 / 事件样例抽查下调——
 * 0.85 只对 ≥7 字 1 字差生效，0.75 兜住 ≥4 字 1 字差且样例零假阳；抽查表与已知代价见
 * `.agent-docs/.tests/查重链路.md`）。
 */
const val DEFAULT_SHORT_WORD_THRESHOLD: Double = 0.75

/** 长文「同一件事」判定温度：取低值求稳定（生成链路用高温，两边口径不同）。 */
private const val DEDUP_LONG_TEXT_TEMPERATURE: Double = 0.2

private val LONG_TEXT_SYSTEM_PROMPT =
    """你是查重判定器。判断【待查概要】与【已有清单】中某一条是否为「同一件事」——同一次或同一类行动换了个说法；只是主题相关、具体做法不同不算。只输出 JSON，不要解释。"""

/**
 * 查重链路（《工作链路·查重链路》）：独立多链路共用的去重——输入「待查对象 + 目标库（调用方给）」，
 * 输出「是否重复 + 重复类型 + 命中对象」。
 *
 * 两入口分叉的是**对象形态**（短词 / 长文），不是目标库；目标库与阈值都是参数：
 * - [shortWord]：规范化 → L1 精确 → L2 编辑距离相似度（≥ [shortWordThreshold]）——纯程序，同步；
 * - [longText]：规范化 → L1 精确（与短词共用第一闸，命中省掉 AI）→ AI「同一件事」判定。
 *
 * 失败口径：AI 返回解析失败 → 重试一次 → 仍失败抛 [DedupParseException]（= 技术失败，由 05 计次）；
 * 环境失败（[com.pairseedkmp.domain.ai.LlmException]）不在此列、原样穿过。
 * 调用方负责：目标库怎么取、重复反馈怎么进提示词、技术失败怎么记账（04 / 05）。
 */
class Deduplicator(
    private val caller: LlmCaller,
    private val shortWordThreshold: Double = DEFAULT_SHORT_WORD_THRESHOLD,
) {
    /** 短词入口：同步、零 AI。 */
    fun shortWord(candidate: String, targets: List<String>): DedupResult =
        matchShortWord(candidate, targets, shortWordThreshold)

    /** 长文入口：先走共用第一闸，再进 AI 判定（解析失败重试一次，仍失败 = 技术失败上抛）。 */
    suspend fun longText(candidate: String, targets: List<String>): DedupResult {
        if (targets.isEmpty()) return DedupResult.NONE
        exactMatch(candidate, targets)?.let { return it }
        val request = buildLongTextRequest(candidate, targets)
        val first = caller.complete(request).text
        val firstAnswer = parseSameThing(first, targets.size)
        val (text, answer) = if (firstAnswer is SameThingAnswer.Invalid) {
            val second = caller.complete(request).text
            second to parseSameThing(second, targets.size)
        } else {
            first to firstAnswer
        }
        return when (answer) {
            is SameThingAnswer.Same ->
                DedupResult.hit(DedupType.SAME_THING, answer.index, targets[answer.index])
            SameThingAnswer.NotSame -> DedupResult.NONE
            SameThingAnswer.Invalid ->
                throw DedupParseException("长文查重解析失败（含一次重试）；原文：${text.take(80)}")
        }
    }
}

/**
 * 第一闸（短词 / 长文共用）：规范化后精确相等 → [DedupType.EXACT]。
 * 候选规范化后为空（纯空白 / 标点）→ null（放行，不参与匹配）。
 */
internal fun exactMatch(candidate: String, targets: List<String>): DedupResult? {
    val normalized = normalize(candidate)
    if (normalized.isEmpty()) return null
    val index = targets.indexOfFirst { normalize(it) == normalized }
    return if (index >= 0) DedupResult.hit(DedupType.EXACT, index, targets[index]) else null
}

/**
 * 短词全流程（L1 → L2）：命中 [DedupType.EXACT] 或 [DedupType.CHAR_SIMILAR]；全未中 → [DedupResult.NONE]。
 * [threshold] 是相似度下限（含）；并列取更靠前的目标。L3 语义近义（embedding）后置留位。
 */
fun matchShortWord(candidate: String, targets: List<String>, threshold: Double): DedupResult {
    exactMatch(candidate, targets)?.let { return it }
    val normalized = normalize(candidate)
    if (normalized.isEmpty()) return DedupResult.NONE
    var bestIndex = -1
    var bestScore = 0.0
    for (index in targets.indices) {
        val score = similarity(normalized, normalize(targets[index]))
        if (score > bestScore) {
            bestScore = score
            bestIndex = index
        }
    }
    return if (bestIndex >= 0 && bestScore >= threshold) {
        DedupResult.hit(DedupType.CHAR_SIMILAR, bestIndex, targets[bestIndex])
    } else {
        DedupResult.NONE
    }
}

/** 长文 AI 判定结果（内部中间态）。 */
internal sealed interface SameThingAnswer {
    /** 是同一件事；[index] 为调用方列表下标（0 起，已从模型的 1 起转换）。 */
    data class Same(val index: Int) : SameThingAnswer

    /** 不是同一件事。 */
    data object NotSame : SameThingAnswer

    /** 解析失败（空返回 / 非 JSON / 字段缺失 / index 越界）→ 可重试一次。 */
    data object Invalid : SameThingAnswer
}

/**
 * 解析「同一件事」判定（`{same, index?}`）：same 必须是布尔；same=true 必须带 0 起换算后
 * 落在 1..[targetCount] 内的 index；其余一律 [SameThingAnswer.Invalid]。
 */
internal fun parseSameThing(text: String, targetCount: Int): SameThingAnswer {
    val obj = LlmJson.parse(text) ?: return SameThingAnswer.Invalid
    val same = (obj["same"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        ?: return SameThingAnswer.Invalid
    if (!same) return SameThingAnswer.NotSame
    val index = (obj["index"] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: return SameThingAnswer.Invalid
    if (index !in 1..targetCount) return SameThingAnswer.Invalid
    return SameThingAnswer.Same(index - 1)
}

/** 长文判定请求：目标全量进提示词（编号 1 起），模型只回 `{same, index?}`。 */
internal fun buildLongTextRequest(candidate: String, targets: List<String>): LlmRequest {
    val list = targets.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    val user = """
        【待查概要】
        $candidate

        【已有清单】
        $list

        问：待查概要与其中某一条是同一件事吗？
        只输出 JSON：{"same": true} 或 {"same": false}。
        same = true 时必须带 "index"（同一件事那条的编号，从 1 起；有多条像的指认最像的一条）；same = false 时不带 index。
    """.trimIndent().trim()
    return LlmRequest(
        system = LONG_TEXT_SYSTEM_PROMPT,
        user = user,
        temperature = DEDUP_LONG_TEXT_TEMPERATURE,
        expectJson = true,
        task = LlmTask.DEDUP,
    )
}

/**
 * 长文查重解析失败（空返回 / 非 JSON / 字段缺失 / index 越界，且重试一次仍失败）。
 * 口径：**技术失败**——由 05 计次（同种子重试额度）；环境失败（[com.pairseedkmp.domain.ai.LlmException]）
 * 不在此列、原样穿过。
 */
class DedupParseException(message: String) : Exception(message)
