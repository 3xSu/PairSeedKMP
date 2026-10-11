package com.pairseedkmp.domain.seed

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmJson
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.dedup.DedupType
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.repository.SeedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

/** 同一工具重试上限（工作链路 1.3 / 2.5：满 5 次 → 换工具 / 问用户）。 */
const val SEED_GROW_MAX_ATTEMPTS_PER_TOOL: Int = 5

/** 生长温度：与生成链路同档高温（《00-链路总览》：生成高 / 判定低）。 */
private const val SEED_GROW_TEMPERATURE: Double = 0.9

/** 生长模式：自动（后台触发，轮转换工具）/ 手动（面板触发，单工具用尽问用户）。 */
enum class SeedGrowMode { AUTO, MANUAL }

/** 一次重复反馈：AI 产出的词 + 重复类型（进下一轮提示词 / 手动模式展示）。 */
data class SeedDuplicate(val keyword: String, val type: DedupType)

/** 生长一次的结果。 */
sealed interface GrowResult {
    /** 成功：新种子已入库（`source_type` = AI_VARIANT）。 */
    data class Grown(val seed: IdeaSeed, val attempts: Int) : GrowResult

    /** 手动模式：当前工具重试满 [SEED_GROW_MAX_ATTEMPTS_PER_TOOL] 次仍未成 → 询问用户是否继续（工作链路 2.5）。 */
    data class AskContinue(
        val toolLabel: String,
        val nextSourceIndex: Int,
        val duplicates: List<SeedDuplicate>,
    ) : GrowResult

    /** 全轮无果（自动模式）：轮过的工具各自用满上限；调度层稍后再来，不设硬性总上限。 */
    data class Exhausted(val attempts: Int) : GrowResult

    /** 采集侧全败：所有工具都取不到素材（环境类失败；失败不断链）。 */
    data class NoMaterial(val errors: List<String>) : GrowResult
}

/**
 * AI 生长回环（《工作链路·种子池生长链路》1 / 2）：取语料 → 提示词（含「已重复词 + 重复类型」反馈）
 * → 产 1 词 → 查重 → 重复则反馈重试（同工具 ≤5 次）→ 换工具轮转；成功入库 `source_type` = AI_VARIANT。
 *
 * 一轮 = 轮过所有工具（各 ≤[SEED_GROW_MAX_ATTEMPTS_PER_TOOL] 次；素材每次进工具时取一回）：
 * - [SeedGrowMode.AUTO]：工具轮转、全轮无果 → [GrowResult.Exhausted]（「不设硬性总上限」落在调度层）；
 * - [SeedGrowMode.MANUAL]：单工具用尽 → [GrowResult.AskContinue]（同意后从 `nextSourceIndex` 继续）。
 *
 * 失败口径：AI 环境失败（[com.pairseedkmp.domain.ai.LlmException]）原样穿出（调用方记日志）；
 * 采集失败 / 素材为空 → 跳过该工具；无效输出（非 JSON / 字段缺 / 空串）与重复一样计入本工具次数。
 */
class SeedGrower(
    private val repository: SeedRepository,
    private val deduplicator: Deduplicator,
    private val caller: LlmCaller,
    private val sources: List<SeedMaterialSource>,
) {
    /**
     * 长一颗：成功即在 [now] 时刻入库并返回 [GrowResult.Grown]。
     * [startSourceIndex] 用于手动模式续跑（从 [GrowResult.AskContinue.nextSourceIndex] 接上）。
     */
    suspend fun grow(
        now: Long,
        mode: SeedGrowMode = SeedGrowMode.AUTO,
        startSourceIndex: Int = 0,
    ): GrowResult {
        if (sources.isEmpty()) return GrowResult.NoMaterial(emptyList())
        val start = ((startSourceIndex % sources.size) + sources.size) % sources.size
        val duplicates = mutableListOf<SeedDuplicate>()
        val fetchErrors = mutableListOf<String>()
        var attempts = 0

        for (step in sources.indices) {
            val index = (start + step) % sources.size
            val source = sources[index]
            val material = fetchMaterial(source, fetchErrors)
            if (material.isEmpty()) continue

            repeat(SEED_GROW_MAX_ATTEMPTS_PER_TOOL) {
                attempts++
                val existing = repository.all().map { it.keyword }
                val request = buildSeedGrowRequest(material, source.label, existing, duplicates)
                val proposal = parseSeedProposal(caller.complete(request).text)
                if (proposal == null) return@repeat // 技术失败：计次、无反馈重试

                val hit = deduplicator.shortWord(proposal.keyword, existing)
                if (hit.isDuplicate) {
                    duplicates += SeedDuplicate(proposal.keyword, hit.type!!)
                    return@repeat
                }

                val seed = IdeaSeed(
                    id = 0,
                    keyword = proposal.keyword,
                    theme = proposal.theme,
                    sceneHintTags = emptyList(),
                    suggestedTimeWindow = null,
                    relationshipPushTarget = null,
                    noveltyTarget = null,
                    sourceType = SeedSourceType.AI_VARIANT,
                    qualityScore = null,
                    pickedCount = 0,
                    lastPickedAt = null,
                    isDeleted = false,
                    createdAt = now,
                    updatedAt = now,
                )
                return GrowResult.Grown(seed.copy(id = repository.insert(seed)), attempts)
            }

            if (mode == SeedGrowMode.MANUAL) {
                return GrowResult.AskContinue(source.label, (index + 1) % sources.size, duplicates.toList())
            }
        }
        return if (attempts == 0) GrowResult.NoMaterial(fetchErrors.toList()) else GrowResult.Exhausted(attempts)
    }

    private suspend fun fetchMaterial(source: SeedMaterialSource, errors: MutableList<String>): List<String> =
        try {
            source.fetch().filter { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "${source.label}：${e.message ?: e::class.simpleName}"
            emptyList()
        }
}

/** 重复类型的中文名（反馈进提示词用）。 */
private val DedupType.displayCn: String
    get() = when (this) {
        DedupType.EXACT -> "完全重复"
        DedupType.CHAR_SIMILAR -> "字形近似"
        DedupType.SAME_THING -> "同一件事"
    }

private val SEED_GROW_SYSTEM_PROMPT = """
    你是灵感种子生长器。程序给你一批生活化素材，你从中选一个词或短语作为【灵感词】，并给它配一个【互动主题】。
    - 灵感词 2-6 个字，取自素材，是一对角色之间一件小事的起点（如「雨后的泥土味」）
    - 互动主题是不超过 10 个字的短语，概括种子指向的互动方向（如「气味与记忆」）
    - 不要与【已有种子】重复或近似——重复会被退回重选
    - 只输出 JSON：{"keyword": "…", "theme": "…"}，不要解释
""".trimIndent()

/** 生长提案：`{keyword, theme}` 都非空才有效；无效 = 技术失败（计次、无反馈重试）。 */
internal data class SeedProposal(val keyword: String, val theme: String)

/** 解析生长提案（走 [LlmJson]，围栏 / 瑕疵容忍）。 */
internal fun parseSeedProposal(text: String): SeedProposal? {
    val obj = LlmJson.parse(text) ?: return null
    fun field(name: String): String? =
        (obj[name] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    return SeedProposal(field("keyword") ?: return null, field("theme") ?: return null)
}

/** 生长提示词：素材 + 已有种子（避开）+ 重复反馈（工作链路 1.3）；模型只产 `{keyword, theme}`。 */
internal fun buildSeedGrowRequest(
    material: List<String>,
    toolLabel: String,
    existingKeywords: List<String>,
    duplicates: List<SeedDuplicate>,
): LlmRequest {
    val materialBlock = material.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n")
    val existingBlock =
        if (existingKeywords.isEmpty()) "（池子还是空的，没有要避开的词）"
        else existingKeywords.joinToString("\n") { "- $it" }
    val feedbackBlock = if (duplicates.isEmpty()) "" else
        "\n【上一轮判断为重复】\n" +
            duplicates.joinToString("\n") { "「${it.keyword}」——${it.type.displayCn}" } +
            "\n这些词都重复了，换一个完全不同的方向。\n"

    val user = """
        【素材】（来源：$toolLabel）
        $materialBlock

        【已有种子】（不要重复或近似重复）
        $existingBlock
        $feedbackBlock
        从素材里选一个全新的灵感词，输出 JSON。
    """.trimIndent().trim()
    return LlmRequest(
        system = SEED_GROW_SYSTEM_PROMPT,
        user = user,
        temperature = SEED_GROW_TEMPERATURE,
        expectJson = true,
        task = LlmTask.SEED_GROW,
    )
}
