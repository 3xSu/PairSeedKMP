package com.pairseedkmp.domain.ai

import com.pairseedkmp.domain.dedup.buildLongTextRequest
import com.pairseedkmp.domain.event.EventGrowContext
import com.pairseedkmp.domain.event.GROW_SYSTEM_PROMPT
import com.pairseedkmp.domain.event.GrowBehavior
import com.pairseedkmp.domain.event.GrowCharacter
import com.pairseedkmp.domain.event.GrowPackConfig
import com.pairseedkmp.domain.event.GrowScene
import com.pairseedkmp.domain.event.TAG_SYSTEM_PROMPT
import com.pairseedkmp.domain.event.assemble
import com.pairseedkmp.domain.event.buildGrowRequest
import com.pairseedkmp.domain.event.buildTagRequest
import com.pairseedkmp.domain.judgment.RiskScore
import com.pairseedkmp.domain.judgment.buildJudgeRequest
import com.pairseedkmp.domain.judgment.candidate
import com.pairseedkmp.domain.judgment.char
import com.pairseedkmp.domain.judgment.snapshot
import com.pairseedkmp.domain.seed.IdeaSeed
import com.pairseedkmp.domain.seed.SeedSourceType
import com.pairseedkmp.domain.seed.buildSeedGrowRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 07 内置系统提示词注册表（`LlmSystemPrompts`）：
 * ① 每个任务都有内置系统词且互不相同（面板「重置默认」的语义基础）；
 * ② 注册表与各链路实际构建的请求同源——逐任务构造真实 LlmRequest 比对 `system`，
 *    防止注册表与链路常量漂移（面板展示的默认值必须就是实际用的）。
 */
class LlmSystemPromptsTest {

    @Test
    fun `注册表覆盖全部任务且互不相同`() {
        val all = LlmTask.entries.map { task -> task to LlmSystemPrompts.defaultFor(task) }
        all.forEach { (task, prompt) ->
            assertTrue(prompt.isNotBlank(), "任务 $task 的内置系统词是空串")
        }
        val distinct = all.map { it.second }.distinct()
        assertEquals(all.size, distinct.size, "有任务共用同一段内置系统词——面板覆盖会串台")
    }

    @Test
    fun `注册表与各链路实际构建的请求同源`() {
        // 种子生长
        val seedRequest = buildSeedGrowRequest(
            material = listOf("雨后的泥土味"),
            toolLabel = "测试工具",
            existingKeywords = emptyList(),
            duplicates = emptyList(),
        )
        assertEquals(LlmSystemPrompts.defaultFor(LlmTask.SEED_GROW), seedRequest.system)

        // 事件生长（A1 生成段）
        val growRequest = buildGrowRequest(sampleGrowContext(), sampleSeed(), emptyList())
        assertEquals(GROW_SYSTEM_PROMPT, growRequest.system)
        assertEquals(LlmSystemPrompts.defaultFor(LlmTask.EVENT_GROW), growRequest.system)

        // 事件打标（A2）
        assertEquals(TAG_SYSTEM_PROMPT, buildTagRequest("整理稿", hasSpeech = true).system)
        assertEquals(LlmSystemPrompts.defaultFor(LlmTask.EVENT_TAG), buildTagRequest("整理稿", true).system)

        // 判定
        val judgeSnapshot = snapshot(candidate("sit"), char(1), char(2))
        val judgeRequest = buildJudgeRequest(judgeSnapshot, RiskScore(0, emptyList()))
        assertEquals(LlmSystemPrompts.defaultFor(LlmTask.JUDGE), judgeRequest.system)

        // 长文查重
        val dedupRequest = buildLongTextRequest("待查", listOf("目标"))
        assertEquals(LlmSystemPrompts.defaultFor(LlmTask.DEDUP), dedupRequest.system)
    }

    private fun sampleGrowContext(): EventGrowContext = GrowPackConfig(
        packId = 1,
        packName = "测试组",
        actor = GrowCharacter(id = 1, name = "A", summary = "安静"),
        target = GrowCharacter(id = 2, name = "B", summary = "温和"),
        scene = GrowScene(id = 1, name = "家", locationType = "PRIVATE", description = "共用"),
        behaviors = listOf(GrowBehavior(code = "sit", name = "坐", description = null)),
    ).assemble(now = 0L, utcOffsetSeconds = 0)

    private fun sampleSeed(): IdeaSeed = IdeaSeed(
        id = 1,
        keyword = "雨后的泥土味",
        theme = "气味与记忆",
        sceneHintTags = emptyList(),
        suggestedTimeWindow = null,
        relationshipPushTarget = null,
        noveltyTarget = null,
        sourceType = SeedSourceType.AI_VARIANT,
        qualityScore = null,
        pickedCount = 0,
        lastPickedAt = null,
        isDeleted = false,
        createdAt = 0,
        updatedAt = 0,
    )
}
