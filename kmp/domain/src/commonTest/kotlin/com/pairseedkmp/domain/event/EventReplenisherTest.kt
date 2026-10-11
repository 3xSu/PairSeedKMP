package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.ai.LlmCaller
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.dedup.Deduplicator
import com.pairseedkmp.domain.seed.IdeaSeed
import com.pairseedkmp.domain.seed.SeedPicker
import com.pairseedkmp.domain.seed.SeedSourceType
import com.pairseedkmp.domain.testing.FakeEventRepository
import com.pairseedkmp.domain.testing.FakeSeedRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 脚本化假 client：按任务分队列；只给补货测试要的最小子集。 */
private class ReplenishScriptedClient : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    private val queues = mutableMapOf<LlmTask, ArrayDeque<String>>()

    fun on(task: LlmTask, vararg texts: String): ReplenishScriptedClient = apply {
        val queue = queues.getOrPut(task) { ArrayDeque() }
        texts.forEach { queue.addLast(it) }
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        val queue = queues[request.task] ?: error("没给 ${request.task} 准备响应")
        return LlmResponse(queue.removeFirstOrNull() ?: error("${request.task} 的脚本用完了"))
    }
}

private fun seed(id: Long) = IdeaSeed(
    id = id,
    keyword = "雨后的泥土味",
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

private fun silentContext(): EventGrowContext = GrowPackConfig(
    packId = 7,
    packName = "A 与 B",
    actor = GrowCharacter(id = 1, name = "A", summary = "安静内敛"),
    target = GrowCharacter(id = 2, name = "B", summary = "温和细腻"),
    scene = GrowScene(id = 9, name = "家", locationType = "PRIVATE", description = null),
    behaviors = listOf(GrowBehavior("walk", "WALK", "走路"), GrowBehavior("sit", "SIT", "坐")),
).assemble(now = 0L, utcOffsetSeconds = 0)

private const val GROW_SILENT = """{"summary":"安静坐一会儿","steps":[{"action":"walk"},{"action":"sit"}]}"""
private const val TAG_SILENT =
    """{"comparison_axes":{"verbal_directness":null,"emotion_visibility":1},"suitable_time":[]}"""

private fun inListEvent(id: Long) = Event(
    id = id,
    packId = 7,
    actorId = 1,
    sceneId = 9,
    summary = "待做 $id",
    steps = listOf(EventStep("walk")),
    status = EventStatus.IN_LIST,
    origin = EventOrigin.SEED,
    durationSec = 5,
    expiresAt = Long.MAX_VALUE,
    createdAt = 0L,
    updatedAt = 0L,
)

private fun replenisher(client: LlmClient, events: FakeEventRepository, capacity: Int): EventReplenisher {
    val grower = EventGrower(
        picker = SeedPicker(FakeSeedRepository(listOf(seed(1))), Random(1)),
        contextSource = object : EventContextSource {
            override fun snapshot(now: Long, utcOffsetSeconds: Int): EventGrowContext = silentContext()
        },
        caller = LlmCaller(client),
        deduplicator = Deduplicator(LlmCaller(client)),
    )
    return EventReplenisher(grower, events, capacity)
}

/** 模拟下游（06 → 落库 → 进清单）：收下即插入一条 IN_LIST。 */
private fun insertingSink(events: FakeEventRepository) = GrowCandidateSink { candidate ->
    events.insert(
        Event(
            id = 0L,
            packId = candidate.packId,
            actorId = candidate.actorId,
            targetId = candidate.targetId,
            sceneId = candidate.sceneId,
            summary = candidate.summary,
            steps = candidate.steps,
            status = EventStatus.IN_LIST,
            origin = EventOrigin.SEED,
            durationSec = 5,
            expiresAt = Long.MAX_VALUE,
            createdAt = 0L,
            updatedAt = 0L,
        ),
    )
    true
}

class EventReplenisherTest {

    @Test
    fun 空清单_在线串行补至满仓() = runTest {
        val events = FakeEventRepository()
        val client = ReplenishScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_SILENT, GROW_SILENT, GROW_SILENT)
            .on(LlmTask.EVENT_TAG, TAG_SILENT, TAG_SILENT, TAG_SILENT)
        val replenisher = replenisher(client, events, capacity = 3)

        val result = replenisher.replenish(now = 900L, utcOffsetSeconds = 0, sink = insertingSink(events))

        assertEquals(ReplenishResult(delivered = 3), result)
        assertEquals(3, events.all().count { it.status == EventStatus.IN_LIST })
        assertEquals(
            listOf(
                LlmTask.EVENT_GROW, LlmTask.EVENT_TAG,
                LlmTask.EVENT_GROW, LlmTask.EVENT_TAG,
                LlmTask.EVENT_GROW, LlmTask.EVENT_TAG,
            ),
            client.requests.map { it.task },
            "一条一条串行长（无查重目标不调查重 AI）",
        )
    }

    @Test
    fun 已满仓_不补_零AI调用() = runTest {
        val events = FakeEventRepository(listOf(inListEvent(1), inListEvent(2), inListEvent(3)))
        val client = ReplenishScriptedClient()
        val replenisher = replenisher(client, events, capacity = 3)

        val result = replenisher.replenish(now = 900L, utcOffsetSeconds = 0, sink = insertingSink(events))

        assertEquals(ReplenishResult(delivered = 0), result)
        assertEquals(0, client.requests.size)
        assertEquals(3, events.all().size, "没人动清单")
    }

    @Test
    fun 下游没收下_停本轮() = runTest {
        val events = FakeEventRepository()
        val client = ReplenishScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_SILENT, GROW_SILENT)
            .on(LlmTask.EVENT_TAG, TAG_SILENT, TAG_SILENT)
        val replenisher = replenisher(client, events, capacity = 3)
        var sinkCalls = 0
        val refusing = GrowCandidateSink { sinkCalls++; false }

        val result = replenisher.replenish(now = 900L, utcOffsetSeconds = 0, sink = refusing)

        assertEquals(ReplenishResult(delivered = 0), result)
        assertEquals(1, sinkCalls, "没收下就停，不接着长")
        assertEquals(1, client.requests.count { it.task == LlmTask.EVENT_GROW })
    }

    @Test
    fun 重入守卫_上一轮未完_本轮直接跳过() = runTest {
        val events = FakeEventRepository()
        val client = ReplenishScriptedClient()
            .on(LlmTask.EVENT_GROW, GROW_SILENT)
            .on(LlmTask.EVENT_TAG, TAG_SILENT)
        val replenisher = replenisher(client, events, capacity = 1)

        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val gated = GrowCandidateSink {
            entered.complete(Unit)
            gate.await()
            true
        }

        val first = async { replenisher.replenish(now = 900L, utcOffsetSeconds = 0, sink = gated) }
        entered.await()
        val second = replenisher.replenish(now = 900L, utcOffsetSeconds = 0, sink = gated)
        assertEquals(ReplenishResult(delivered = 0, reentered = true), second)

        gate.complete(Unit)
        val firstResult = first.await()
        assertEquals(1, firstResult.delivered)
        assertFalse(firstResult.reentered)
    }
}
