package com.pairseedkmp.data.ai_impl

import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask

/**
 * 预设 provider（01）：无模型跑通全链路 / 确定性回归。
 *
 * 形状口径（与真实输出一致，随各节点 schema 定稿）：
 * - 事件生成（05 两段形态）：生成段 `{summary, steps, after?}`（「说」步骤只给 speaker / text——
 *   `duration_sec` 由链路程序算，预设不代劳）；打标段按请求里的固化 schema 动态回
 *   `{comparison_axes(两维 1-4), suitable_time}`——有台词 → verbal 1-4 轮转，无 → null（事实固化）
 * - 判定：`{verdict, reason, violated_rule?}`，verdict ∈ PASS / UNCERTAIN / REJECT
 * - 种子生长：`{keyword, theme}`；查重：`{same, index?}`
 *
 * 内容口径：动作码 ∈ 调试种子动作库（walk / sit / say / lie_down / eat）；生成段 6 条——
 * 自动补货连着出多条，3 条轮转太早开始重样（08 扩充）。
 * 轮转：同 task 第 n 次调用回第 n 条（取模回到第一条）——连续补货 / 重试时拿得到不同内容。
 * 计数器不设锁（单线程调用口径：UI / 生长编排都在主协程）。
 */
class PresetLlmClient : LlmClient {

    private val cursor = mutableMapOf<LlmTask, Int>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        if (request.task == LlmTask.EVENT_TAG) return LlmResponse(tagResponse(request))
        val variants = PRESETS.getValue(request.task)
        val index = (cursor[request.task] ?: 0) % variants.size
        cursor[request.task] = index + 1
        return LlmResponse(variants[index])
    }

    /** 打标段：verbal 按固化 schema 定（含 `"type":"null"` = 没台词 → null），情绪 / 时段轮转。 */
    private fun tagResponse(request: LlmRequest): String {
        val hasSpeech = request.responseSchema?.contains("\"type\":\"null\"") != true
        val index = cursor[LlmTask.EVENT_TAG] ?: 0
        cursor[LlmTask.EVENT_TAG] = index + 1
        val verbal = if (hasSpeech) "${1 + index % 4}," else "null,"
        val emotion = 1 + (index + 1) % 4
        val times = TAG_TIME_VARIANTS[index % TAG_TIME_VARIANTS.size]
        return """{ "comparison_axes": { "verbal_directness": $verbal "emotion_visibility": $emotion }, "suitable_time": $times }"""
    }

    private companion object {
        /** 打标段的时段轮转（含一条空数组 = 不限，覆盖两种形态）。 */
        val TAG_TIME_VARIANTS = listOf(
            """["EVENING"]""",
            """["EVENING", "NIGHT"]""",
            """["AFTERNOON", "EVENING"]""",
            """[]""",
        )

        val PRESETS: Map<LlmTask, List<String>> = mapOf(
            LlmTask.EVENT_GROW to listOf(
                """
                {
                  "summary": "把窗边的杂物收拢，安静坐一会儿",
                  "steps": [
                    { "action": "walk" },
                    { "action": "sit" }
                  ]
                }
                """.trimIndent(),
                """
                {
                  "summary": "给对方倒了杯热水，嘱咐趁热喝",
                  "steps": [
                    { "action": "walk" },
                    { "action": "say", "params": { "speaker": "target", "text": "先喝口热的再忙。" } },
                    { "action": "sit" }
                  ]
                }
                """.trimIndent(),
                """
                {
                  "summary": "一起收拾完茶几，接着去归置零食",
                  "steps": [
                    { "action": "walk" },
                    { "action": "sit" },
                    { "action": "eat" }
                  ],
                  "after": 1
                }
                """.trimIndent(),
                """
                {
                  "summary": "把零食袋推过去，两个人分着吃完了",
                  "steps": [
                    { "action": "sit" },
                    { "action": "say", "params": { "speaker": "actor", "text": "最后一把，归你。" } },
                    { "action": "eat" }
                  ]
                }
                """.trimIndent(),
                """
                {
                  "summary": "把毯子给对方搭上，各自躺下准备睡了",
                  "steps": [
                    { "action": "walk" },
                    { "action": "lie_down" }
                  ]
                }
                """.trimIndent(),
                """
                {
                  "summary": "递了只耳机过去，挨着听完一首歌",
                  "steps": [
                    { "action": "walk" },
                    { "action": "say", "params": { "speaker": "actor", "text": "就一首，听完再忙。" } },
                    { "action": "sit" }
                  ]
                }
                """.trimIndent(),
            ),
            LlmTask.JUDGE to listOf(
                """{ "verdict": "PASS", "reason": "符合日常相处风格，未越过设定边界。" }""",
                """{ "verdict": "PASS", "reason": "关系亲近但保持各自空间，分寸合适。" }""",
                """{ "verdict": "UNCERTAIN", "reason": "不确定这个时间点对方是否愿意被搭话。" }""",
                """{ "verdict": "REJECT", "reason": "角色不会主动做这样的举动。", "violated_rule": "安静内敛，不主动拉近距离" }""",
            ),
            LlmTask.SEED_GROW to listOf(
                """{ "keyword": "雨后的泥土味", "theme": "气味与记忆" }""",
                """{ "keyword": "深夜便利店", "theme": "城市里的安静角落" }""",
                """{ "keyword": "旧毛衣", "theme": "衣物与温度" }""",
            ),
            LlmTask.DEDUP to listOf(
                """{ "same": false }""",
                """{ "same": false }""",
                """{ "same": true, "index": 1 }""",
            ),
        )
    }
}
