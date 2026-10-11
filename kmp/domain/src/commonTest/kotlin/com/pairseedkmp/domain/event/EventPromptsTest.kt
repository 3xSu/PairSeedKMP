package com.pairseedkmp.domain.event

import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.domain.common.MILLIS_PER_MINUTE
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.dedup.DedupType
import com.pairseedkmp.domain.seed.IdeaSeed
import com.pairseedkmp.domain.seed.SeedSourceType
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ideaSeed(keyword: String = "雨后的泥土味", theme: String = "气味与记忆") = IdeaSeed(
    id = 5,
    keyword = keyword,
    theme = theme,
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

private fun growContext(
    checklist: List<ChecklistEvent> = emptyList(),
    recent: List<String> = emptyList(),
    documents: List<GrowDocumentRef> = emptyList(),
) = GrowPackConfig(
    packId = 7,
    packName = "A 与 B",
    actor = GrowCharacter(
        id = 1,
        name = "A",
        summary = "安静内敛",
        aliases = listOf("小 A"),
        softAxesRaw = """{"proactivity":{"level":2,"text":"不先开口"}}""",
        speechStyleSummary = "话少",
    ),
    target = GrowCharacter(id = 2, name = "B", summary = "温和细腻", preferredAffectionModes = listOf("递吃的")),
    relations = listOf(
        GrowRelation(
            fromCharacterId = 1,
            toCharacterId = 2,
            towardTargetSummary = "熟络的室友",
            defaultDistance = "亲近",
            likelyInitiationModes = listOf("默默做事"),
            safeComfortActions = listOf("一起吃饭"),
            sensitiveProgressionTags = listOf("身体接触"),
        ),
    ),
    pairSummary = "同居室友",
    relationToneSummary = "轻松日常",
    sharedForbiddenTags = listOf("冲突"),
    scene = GrowScene(id = 9, name = "家", locationType = "PRIVATE", description = "有沙发"),
    behaviors = listOf(GrowBehavior("walk", "WALK", "走路"), GrowBehavior("say", "SAY", "说话")),
    documents = documents,
    recentSummaries = recent,
    checklistEvents = checklist,
).assemble(now = 8 * 60 * MILLIS_PER_MINUTE, utcOffsetSeconds = 0)

class EventPromptsTest {

    // ===== 生成段解析 =====

    @Test
    fun 生成段_合法输出_围栏容忍_说步参数清洗() {
        val text = """```json
            {"summary": " 劝对方先吃饭 ", "steps": [{"action":"walk","params":{"duration_sec":99}},{"action":"say","params":{"speaker":"actor","text":" 先吃饭。 ","extra":1}}], "after": 1}
            ```"""
        val parsed = parseGrowOutput(text, allowedCodes = setOf("walk", "say"), checklistSize = 2)!!

        assertEquals("劝对方先吃饭", parsed.summary)
        assertEquals(1, parsed.afterNumber)
        assertEquals(listOf("walk", "say"), parsed.steps.map { it.action })
        assertTrue(parsed.steps[0].params.isEmpty(), "非「说」步 params 一律清掉：${parsed.steps[0].params}")
        assertEquals("actor", parsed.steps[1].params.getValue("speaker").jsonPrimitive.content)
        assertEquals("先吃饭。", parsed.steps[1].params.getValue("text").jsonPrimitive.content)
        assertEquals(2, parsed.steps[1].params.size, "「说」步只留 speaker / text")
    }

    @Test
    fun 生成段_拒绝矩阵() {
        val allowed = setOf("walk", "say")
        val rejected = listOf(
            "不是 JSON",
            """{"summary": " ", "steps": [{"action":"walk"}]}""",
            """{"summary": "x", "steps": []}""",
            """{"summary": "x"}""",
            """{"summary": "x", "steps": [{"action":"fly"}]}""",
            """{"summary": "x", "steps": [{"action":"say"}]}""",
            """{"summary": "x", "steps": [{"action":"say","params":{"speaker":"B","text":"嗨"}}]}""",
            """{"summary": "x", "steps": [{"action":"say","params":{"speaker":"actor","text":"  "}}]}""",
            """{"summary": "x", "steps": [{"action":"walk"}], "after": 0}""",
            """{"summary": "x", "steps": [{"action":"walk"}], "after": 3}""",
            """{"summary": "x", "steps": [{"action":"walk"}], "after": "一"}""",
        )
        for (text in rejected) {
            assertNull(parseGrowOutput(text, allowed, checklistSize = 2), "应拒绝：$text")
        }
        assertNull(
            parseGrowOutput("""{"summary": "x", "steps": [{"action":"walk"}], "after": 1}""", allowed, checklistSize = 0),
            "清单空时任何 after 都越界",
        )
    }

    @Test
    fun 生成段_无after与after为null_都给null编号() {
        val allowed = setOf("walk")
        assertNull(parseGrowOutput("""{"summary": "x", "steps": [{"action":"walk"}]}""", allowed, 2)!!.afterNumber)
        assertNull(
            parseGrowOutput("""{"summary": "x", "steps": [{"action":"walk"}], "after": null}""", allowed, 2)!!.afterNumber,
        )
    }

    // ===== 打标段解析 + 事实固化 =====

    @Test
    fun 打标段_合法输出() {
        val parsed = parseTagOutput(
            """{"comparison_axes":{"verbal_directness":3,"emotion_visibility":2},"suitable_time":["EVENING","NIGHT"]}""",
            hasSpeech = true,
        )!!
        assertEquals(3, parsed.verbalDirectness)
        assertEquals(2, parsed.emotionVisibility)
        assertEquals(listOf(Period.EVENING, Period.NIGHT), parsed.suitableTime)

        val silent = parseTagOutput(
            """{"comparison_axes":{"verbal_directness":null,"emotion_visibility":1},"suitable_time":[]}""",
            hasSpeech = false,
        )!!
        assertNull(silent.verbalDirectness)
        assertTrue(silent.suitableTime.isEmpty())
    }

    @Test
    fun 打标段_一致性守卫_有台词不得null_无台词不得给档() {
        val withSpeech = """{"comparison_axes":{"verbal_directness":null,"emotion_visibility":2},"suitable_time":["MORNING"]}"""
        assertNull(parseTagOutput(withSpeech, hasSpeech = true), "有「说」步却回 null → 拒")

        val withoutSpeech = """{"comparison_axes":{"verbal_directness":2,"emotion_visibility":2},"suitable_time":["MORNING"]}"""
        assertNull(parseTagOutput(withoutSpeech, hasSpeech = false), "无「说」步却给了档 → 拒")
    }

    @Test
    fun 打标段_拒绝矩阵() {
        val rejected = listOf(
            "不是 JSON",
            """{"suitable_time":[]}""",
            """{"comparison_axes":{"emotion_visibility":2},"suitable_time":[]}""",
            """{"comparison_axes":{"verbal_directness":0,"emotion_visibility":2},"suitable_time":[]}""",
            """{"comparison_axes":{"verbal_directness":5,"emotion_visibility":2},"suitable_time":[]}""",
            """{"comparison_axes":{"verbal_directness":3},"suitable_time":[]}""",
            """{"comparison_axes":{"verbal_directness":3,"emotion_visibility":5},"suitable_time":[]}""",
            """{"comparison_axes":{"verbal_directness":3,"emotion_visibility":2}}""",
            """{"comparison_axes":{"verbal_directness":3,"emotion_visibility":2},"suitable_time":"EVENING"}""",
            """{"comparison_axes":{"verbal_directness":3,"emotion_visibility":2},"suitable_time":["DAWN"]}""",
        )
        for (text in rejected) {
            assertNull(parseTagOutput(text, hasSpeech = true), "应拒绝：$text")
        }
    }

    @Test
    fun 打标段schema_事实固化_可空性由说步有无定() {
        val withSpeech = tagStageSchema(hasSpeech = true)
        assertTrue(withSpeech.contains(""""verbal_directness":{"type":"integer","enum":[1,2,3,4]}"""), withSpeech)
        assertFalse(withSpeech.contains(""""type":"null""""), "有台词不放行 null：$withSpeech")

        val noSpeech = tagStageSchema(hasSpeech = false)
        assertTrue(noSpeech.contains(""""verbal_directness":{"type":"null"}"""), noSpeech)
        assertFalse(noSpeech.contains(""""verbal_directness":{"type":"integer""""), "无台词不给 verbal 放行档位：$noSpeech")
    }

    // ===== 整理稿 =====

    @Test
    fun 整理稿_逐字形状_含台词() {
        val steps = EventStepsJson.parse(
            """[{"action":"walk"},{"action":"say","params":{"speaker":"actor","text":"先吃饭。"}},{"action":"say","params":{"speaker":"target","text":"知道了。"}}]""",
        )
        assertEquals(
            "下面是一条已经生成好的事件（已整理成文字，直接读即可）：\n" +
                "· 概要：劝对方先吃饭\n" +
                "· 动作顺序：walk，say，say\n" +
                "· 台词（共 2 句）：\n" +
                "  1. 发起方 说：先吃饭。\n" +
                "  2. 另一方 说：知道了。\n" +
                "请据此完成两个字段的输出。",
            renderEventDigest("劝对方先吃饭", steps),
        )
    }

    @Test
    fun 整理稿_没有台词与空白台词() {
        val noSay = renderEventDigest("安静坐一会儿", EventStepsJson.parse("""[{"action":"walk"},{"action":"sit"}]"""))
        assertTrue(noSay.contains("· 台词：没有（整件事里没有人说话）"), noSay)

        assertEquals(
            emptyList(),
            digestSayLines(EventStepsJson.parse("""[{"action":"say","params":{"speaker":"actor","text":"  "}}]""")),
            "空白 text 的「说」不算台词",
        )
        assertEquals(
            listOf("（未注明谁说） 说：嗨"),
            digestSayLines(EventStepsJson.parse("""[{"action":"say","params":{"text":"嗨"}}]""")),
            "speaker 缺失仍算台词（防御层；正常输出过不了 parseGrowOutput）",
        )
    }

    // ===== 台词时长（程序算） =====

    @Test
    fun 台词时长_四字每秒_向上取整_至少一秒() {
        assertEquals(3, sayDurationSeconds("先吃饭，稿子不会跑。")) // 10 字 → ceil(10/4)
        assertEquals(1, sayDurationSeconds("先吃饭。")) // 4 字
        assertEquals(2, sayDurationSeconds("先吃饭再忙吧。")) // 7 字
        assertEquals(1, sayDurationSeconds("")) // 至少 1 秒
        assertEquals(1, sayDurationSeconds("  \n "), "空白不计字")
    }

    @Test
    fun 补齐说步时长_其余步骤原样() {
        val steps = EventStepsJson.parse(
            """[{"action":"walk"},{"action":"say","params":{"speaker":"actor","text":"先吃饭。"}}]""",
        )
        val filled = steps.withSayDurations()

        assertTrue(filled[0].params.isEmpty())
        assertEquals("actor", filled[1].params.getValue("speaker").jsonPrimitive.content)
        assertEquals("先吃饭。", filled[1].params.getValue("text").jsonPrimitive.content)
        assertEquals(1, filled[1].params.getValue("duration_sec").jsonPrimitive.int)
    }

    // ===== 软轴渲染 =====

    @Test
    fun 软轴渲染_五维顺序_缺项跳过() {
        assertEquals(
            listOf("主动性：第 3 档——会先开口", "说话直接度：第 1 档"),
            parseSoftAxesLines("""{"proactivity":{"level":3,"text":"会先开口"},"verbal_directness":{"level":1}}"""),
        )
        assertEquals(
            listOf("情绪外露：藏得深"),
            parseSoftAxesLines("""{"emotion_visibility":{"text":"藏得深"}}"""),
            "只有 text 也渲染",
        )
        assertTrue(parseSoftAxesLines(null).isEmpty())
        assertTrue(parseSoftAxesLines("不是 JSON").isEmpty())
        assertTrue(parseSoftAxesLines("{}").isEmpty())
    }

    // ===== 请求组装 =====

    @Test
    fun 生成请求_上下文块齐_两条钉死在系统词() {
        val context = growContext(
            checklist = listOf(ChecklistEvent(11, "想收拾房间")),
            recent = listOf("一起看了会儿书"),
            documents = listOf(GrowDocumentRef("voice_ref", "口头禅", "先说再想")),
        )
        val request = buildGrowRequest(context, ideaSeed(), duplicates = emptyList())

        assertEquals(LlmTask.EVENT_GROW, request.task)
        assertEquals(0.9, request.temperature)
        assertNull(request.responseSchema)
        assertTrue(request.system.contains("只能挑、不能编"), "动作码钉死")
        assertTrue(request.system.contains("先吃饭，稿子不会跑。"), "「说」步完整写法示例")

        assertTrue(request.user.contains("- walk（WALK）：走路"))
        assertTrue(request.user.contains("1. 想收拾房间"))
        assertTrue(request.user.contains("- 一起看了会儿书"))
        assertTrue(request.user.contains("关键词：雨后的泥土味；主题：气味与记忆"))
        assertTrue(request.user.contains("- 口头禅：先说再想"), "参考素材进提示词")
        assertTrue(request.user.contains("主动性：第 2 档——不先开口"), "软轴进角色块")
        assertTrue(request.user.contains("现在是早晨（MORNING）"), "当前时段进提示词")
        assertFalse(request.user.contains("【上一轮生成的概要被判定为重复】"), "首轮无重复反馈")
    }

    @Test
    fun 生成请求_重复反馈带像的那条与类型() {
        val request = buildGrowRequest(
            growContext(checklist = listOf(ChecklistEvent(11, "想收拾房间"))),
            ideaSeed(),
            duplicates = listOf(GrowDuplicateFeedback("一起去了趟超市", DedupType.SAME_THING)),
        )
        assertTrue(request.user.contains("【上一轮生成的概要被判定为重复】"))
        assertTrue(request.user.contains("「一起去了趟超市」——同一件事"), request.user)
        assertTrue(request.user.contains("换一个明显不同的角度"))
    }

    @Test
    fun 打标请求_user是整理稿_schema随请求_温度低() {
        val steps = EventStepsJson.parse("""[{"action":"say","params":{"speaker":"actor","text":"先吃饭。"}}]""")
        val digest = renderEventDigest("劝对方先吃饭", steps)
        val request = buildTagRequest(digest, hasSpeech = true)

        assertEquals(LlmTask.EVENT_TAG, request.task)
        assertEquals(0.2, request.temperature)
        assertEquals(digest, request.user)
        assertEquals(tagStageSchema(hasSpeech = true), request.responseSchema)
        assertTrue(request.system.contains("不是判断合不合适"), "档位 = 客观属性钉死")
    }
}
