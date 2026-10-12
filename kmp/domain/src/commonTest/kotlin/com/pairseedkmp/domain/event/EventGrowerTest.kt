package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmErrorKind
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.seed.IdeaSeed
import com.pairseedkmp.domain.seed.SeedPicker
import com.pairseedkmp.domain.seed.SeedSourceType
import com.pairseedkmp.domain.testing.FakeSeedRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 脚本化假 client：按任务分队列给响应（一条尝试里 A1 / 查重 / A2 交错，不靠全局次序）；记录全部请求。 */
private class TaskScriptedClient : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    private val queues = mutableMapOf<LlmTask, ArrayDeque<String>>()

    /** 每次调用前抛的异常（环境失败用）。 */
    var failWith: LlmException? = null

    fun on(task: LlmTask, vararg texts: String): TaskScriptedClient = apply {
        val queue = queues.getOrPut(task) { ArrayDeque() }
        texts.forEach { queue.addLast(it) }
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        failWith?.let { throw it }
        val queue = queues[request.task] ?: error("没给 ${request.task} 准备响应")
        return LlmResponse(queue.removeFirstOrNull() ?: error("${request.task} 的脚本用完了"))
    }
}

/** 假上下文源：[context] 为 null 模拟组装不出。 */
private class FakeContextSource(var context: EventGrowContext?) : EventContextSource {
    override fun snapshot(now: Long, utcOffsetSeconds: Int): EventGrowContext? = context
}

private fun seed(id: Long, keyword: String = "雨后的泥土味") = IdeaSeed(
    id = id,
    keyword = keyword,
    theme = "气味与记忆",
    sceneHintTags = emptyList(),
    suggestedTimeWindow = null,
    relationshipPushTarget = null,
    noveltyTarget = null,
    sourceType = SeedSourceType.MANUAL,
    qualityScore = null,
    pickedCount = 0,
    lastPickedAt = null,
    isDeleted = false,
    createdAt = 0L,
    updatedAt = 0L,
)

private fun context(
    checklist: List<ChecklistEvent> = emptyList(),
    recent: List<String> = emptyList(),
    inFlight: List<String> = emptyList(),
): EventGrowContext = GrowPackConfig(
    packId = 7,
    packName = "A 与 B",
    actor = GrowCharacter(id = 1, name = "A", summary = "安静内敛"),
    target = GrowCharacter(id = 2, name = "B", summary = "温和细腻"),
    scene = GrowScene(id = 9, name = "家", locationType = "PRIVATE", description = null),
    behaviors = listOf(
        GrowBehavior("walk", "WALK", "走路"),
        GrowBehavior("say", "SAY", "说话"),
        GrowBehavior("sit", "SIT", "坐"),
    ),
    recentSummaries = recent,
    checklistEvents = checklist,
    inFlightSummaries = inFlight,
).assemble(now = 8 * MILLIS_PER_MINUTE, utcOffsetSeconds = 0)

private const val GROW_WITH_SAY =
    """{"summary":"劝对方先吃饭","steps":[{"action":"walk"},{"action":"say","params":{"speaker":"actor","text":"先吃饭，稿子不会跑。"}}],"after":1}"""
private const val GROW_SILENT = """{"summary":"安静坐一会儿","steps":[{"action":"walk"},{"action":"sit"}]}"""
private const val TAG_WITH_SPEECH =
    """{"comparison_axes":{"verbal_directness":3,"emotion_visibility":2},"suitable_time":["EVENING"]}"""
private const val TAG_SILENT =
    """{"comparison_axes":{"verbal_directness":null,"emotion_visibility":1},"suitable_time":[]}"""

private fun grower(
    client: LlmClient,
    repository: FakeSeedRepository,
    source: EventContextSource,
    attemptsPerSeed: Int = DEFAULT_GROW_ATTEMPTS_PER_SEED,
    seedsPerRound: Int = DEFAULT_GROW_SEEDS_PER_ROUND,
): EventGrower = EventGrower(
    picker = SeedPicker(repository, Random(1)),
    contextSource = source,
    caller = LlmCaller(client),
    deduplicator = Deduplicator(LlmCaller(client)),
    attemptsPerSeed = attemptsPerSeed,
    seedsPerRound = seedsPerRound,
)

class EventGrowerTest {

    @Test
    fun 成功_两段请求序列_候选字段齐_种子记账() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1)))
        val client = TaskScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_WITH_SAY)
            .on(LlmTask.DEDUP, """{"same": false}""")
            .on(LlmTask.EVENT_TAG, TAG_WITH_SPEECH)
        val source = FakeContextSource(
            context(checklist = listOf(ChecklistEvent(11, "想收拾房间")), recent = listOf("一起看了会儿书")),
        )

        val result = grower(client, repo, source).grow(now = 900L, utcOffsetSeconds = 0)

        val grown = assertIs<EventGrowResult.Grown>(result)
        assertEquals(1, grown.attempts)
        assertEquals(1L, grown.seedId)
        val candidate = grown.candidate
        assertEquals(7L, candidate.packId)
        assertEquals(1L, candidate.actorId)
        assertEquals(2L, candidate.targetId)
        assertEquals(9L, candidate.sceneId)
        assertEquals("劝对方先吃饭", candidate.summary)
        assertEquals(11L, candidate.afterEventId, "after 编号按本次清单快照解析成事件 id")
        assertEquals(1L, candidate.sourceSeedId)
        assertEquals(ComparisonAxes(verbalDirectness = 3, emotionVisibility = 2), candidate.axes)
        assertEquals(listOf(Period.EVENING), candidate.suitableTime)
        assertEquals(3, candidate.steps[1].params.getValue("duration_sec").jsonPrimitive.int, "10 字 → ceil(10/4)")

        assertEquals(
            listOf(LlmTask.EVENT_GROW, LlmTask.DEDUP, LlmTask.EVENT_TAG),
            client.requests.map { it.task },
        )
        val dedup = client.requests[1].user
        assertTrue(dedup.contains("1. 一起看了会儿书"), "查重目标 = 近期在前：$dedup")
        assertTrue(dedup.contains("2. 想收拾房间"), "查重目标含清单：$dedup")
        val tag = client.requests[2]
        assertTrue(tag.user.contains("· 台词（共 1 句）："), "A2 读整理稿：${tag.user}")
        assertTrue(tag.responseSchema!!.contains(""""type":"integer","enum":[1,2,3,4]"""), "有台词固化档位")

        assertEquals(1, repo.stored().single().pickedCount, "挑中即记账")
    }

    @Test
    fun 无台词_verbal为null_schema固化null() = runTest {
        val client = TaskScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_SILENT)
            .on(LlmTask.DEDUP, """{"same": false}""")
            .on(LlmTask.EVENT_TAG, TAG_SILENT)
        val source = FakeContextSource(context(checklist = listOf(ChecklistEvent(11, "想收拾房间"))))

        val result = grower(client, FakeSeedRepository(listOf(seed(1))), source).grow(now = 900L, utcOffsetSeconds = 0)

        val candidate = assertIs<EventGrowResult.Grown>(result).candidate
        assertEquals(ComparisonAxes(verbalDirectness = null, emotionVisibility = 1), candidate.axes)
        assertTrue(candidate.suitableTime.isEmpty(), "空数组 = 不限")
        assertTrue(candidate.afterEventId == null, "无 after 不给指认")
        val tag = client.requests.last()
        assertTrue(tag.user.contains("· 台词：没有（整件事里没有人说话）"), tag.user)
        assertTrue(tag.responseSchema!!.contains(""""type":"null""""), "无台词固化 null")
    }

    @Test
    fun 技术失败_无效输出_同种子重试_不进反馈() = runTest {
        val client = TaskScriptedClient()
            .on(LlmTask.EVENT_GROW, "不是 JSON", GROW_SILENT)
            .on(LlmTask.DEDUP, """{"same": false}""")
            .on(LlmTask.EVENT_TAG, TAG_SILENT)
        val source = FakeContextSource(context(checklist = listOf(ChecklistEvent(11, "想收拾房间"))))

        val result = grower(client, FakeSeedRepository(listOf(seed(1))), source).grow(now = 900L, utcOffsetSeconds = 0)

        assertEquals(2, assertIs<EventGrowResult.Grown>(result).attempts)
        assertEquals(
            listOf(LlmTask.EVENT_GROW, LlmTask.EVENT_GROW, LlmTask.DEDUP, LlmTask.EVENT_TAG),
            client.requests.map { it.task },
        )
        assertTrue(
            !client.requests[1].user.contains("【上一轮生成的概要被判定为重复】"),
            "技术失败不带重复反馈重试：${client.requests[1].user}",
        )
    }

    @Test
    fun 内容重复_像的那条进下一轮提示词() = runTest {
        val client = TaskScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_SILENT, """{"summary":"在雨里慢慢走","steps":[{"action":"walk"}]}""")
            .on(LlmTask.DEDUP, """{"same": true, "index": 1}""", """{"same": false}""")
            .on(LlmTask.EVENT_TAG, TAG_SILENT)
        val source = FakeContextSource(context(recent = listOf("一起去了趟超市")))

        val result = grower(client, FakeSeedRepository(listOf(seed(1))), source).grow(now = 900L, utcOffsetSeconds = 0)

        val grown = assertIs<EventGrowResult.Grown>(result)
        assertEquals(2, grown.attempts, "重复计次后同种子重试")
        assertEquals("在雨里慢慢走", grown.candidate.summary)
        val second = client.requests[2].user
        assertTrue(second.contains("【上一轮生成的概要被判定为重复】"), second)
        assertTrue(second.contains("「一起去了趟超市」——同一件事"), "带像的那条与类型：$second")
        assertTrue(!client.requests[0].user.contains("【上一轮生成的概要被判定为重复】"), "首轮无反馈")
    }

    @Test
    fun 查重靶集含在流程内_待确认与进行中的复读命中() = runTest {
        val client = TaskScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_SILENT, GROW_SILENT)
            .on(LlmTask.DEDUP, """{"same": true, "index": 3}""", """{"same": true, "index": 3}""")
        val source = FakeContextSource(
            context(
                checklist = listOf(ChecklistEvent(11, "想收拾房间")),
                recent = listOf("一起看了会儿书"),
                inFlight = listOf("在窗边安静地坐着"),
            ),
        )

        val result = grower(
            client,
            FakeSeedRepository(listOf(seed(1))),
            source,
            attemptsPerSeed = 2,
            seedsPerRound = 1,
        ).grow(now = 900L, utcOffsetSeconds = 0)

        assertEquals(2, assertIs<EventGrowResult.Exhausted>(result).attempts, "两次都被判重 → 用尽")
        val first = client.requests[1].user
        assertTrue(first.contains("1. 一起看了会儿书"), first)
        assertTrue(first.contains("2. 想收拾房间"), first)
        assertTrue(first.contains("3. 在窗边安静地坐着"), "待确认 / 进行中进靶集（装机连爆双胞胎的修复）：$first")
        val second = client.requests[2].user
        assertTrue(second.contains("「在窗边安静地坐着」——同一件事"), "在流程内的那条作重复反馈进下一轮：$second")
    }

    @Test
    fun 每种子三次用尽_换种子不重挑同颗() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1, "甲"), seed(2, "乙")))
        val client = TaskScriptedClient().on(LlmTask.EVENT_GROW, *Array(9) { "不是 JSON" })
        val source = FakeContextSource(context())

        val result = grower(client, repo, source).grow(now = 900L, utcOffsetSeconds = 0)

        val exhausted = assertIs<EventGrowResult.Exhausted>(result)
        assertEquals(2, exhausted.seedsTried)
        assertEquals(6, exhausted.attempts, "两颗种子各 3 次")
        assertEquals(listOf(1, 1), repo.stored().map { it.pickedCount }, "每颗只被挑一次（exclude 生效）")
    }

    @Test
    fun 单种子用尽_Exhausted() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1)))
        val client = TaskScriptedClient().on(LlmTask.EVENT_GROW, *Array(3) { "不是 JSON" })

        val result = grower(client, repo, FakeContextSource(context())).grow(now = 900L, utcOffsetSeconds = 0)

        val exhausted = assertIs<EventGrowResult.Exhausted>(result)
        assertEquals(1, exhausted.seedsTried)
        assertEquals(3, exhausted.attempts)
    }

    @Test
    fun 池空_NoSeed_不调AI() = runTest {
        val client = TaskScriptedClient()
        val result = grower(client, FakeSeedRepository(), FakeContextSource(context())).grow(now = 900L, 0)
        assertIs<EventGrowResult.NoSeed>(result)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun 组装不出_NoContext_不挑种子不调AI() = runTest {
        val repo = FakeSeedRepository(listOf(seed(1)))
        val client = TaskScriptedClient()
        val result = grower(client, repo, FakeContextSource(null)).grow(now = 900L, 0)
        assertIs<EventGrowResult.NoContext>(result)
        assertTrue(client.requests.isEmpty())
        assertEquals(0, repo.stored().single().pickedCount, "没上下文连种子都不挑")
    }

    @Test
    fun 查重解析失败_计技术失败() = runTest {
        val client = TaskScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_SILENT, GROW_SILENT)
            .on(LlmTask.DEDUP, "不是 JSON", "不是 JSON", "不是 JSON", "不是 JSON")
        val source = FakeContextSource(context(recent = listOf("一起看了会儿书")))

        val result = grower(
            client,
            FakeSeedRepository(listOf(seed(1))),
            source,
            attemptsPerSeed = 2,
            seedsPerRound = 1,
        ).grow(now = 900L, utcOffsetSeconds = 0)

        assertEquals(2, assertIs<EventGrowResult.Exhausted>(result).attempts)
        assertEquals(
            listOf(
                LlmTask.EVENT_GROW, LlmTask.DEDUP, LlmTask.DEDUP,
                LlmTask.EVENT_GROW, LlmTask.DEDUP, LlmTask.DEDUP,
            ),
            client.requests.map { it.task },
            "查重解析失败重试一次仍失败 → 技术失败计次（每次尝试两次 DEDUP 调用）",
        )
    }

    @Test
    fun 无查重目标_不调查重AI() = runTest {
        val client = TaskScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_SILENT)
            .on(LlmTask.EVENT_TAG, TAG_SILENT)

        val result = grower(client, FakeSeedRepository(listOf(seed(1))), FakeContextSource(context()))
            .grow(now = 900L, utcOffsetSeconds = 0)

        assertIs<EventGrowResult.Grown>(result)
        assertEquals(listOf(LlmTask.EVENT_GROW, LlmTask.EVENT_TAG), client.requests.map { it.task })
    }

    @Test
    fun 环境失败_EnvFailed_不吞不重试() = runTest {
        val client = TaskScriptedClient().apply {
            failWith = LlmException(LlmErrorKind.PERSISTENT, "Key 无效")
        }
        val source = FakeContextSource(context())

        val result = grower(client, FakeSeedRepository(listOf(seed(1))), source).grow(now = 900L, 0)

        val failed = assertIs<EventGrowResult.EnvFailed>(result)
        assertEquals(LlmErrorKind.PERSISTENT, failed.error.kind)
        assertEquals(1, client.requests.size, "持续失败不重试")
    }
}
