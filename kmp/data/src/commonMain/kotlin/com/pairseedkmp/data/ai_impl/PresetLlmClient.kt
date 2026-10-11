package com.pairseedkmp.data.ai_impl

import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask

/**
 * 预设 provider（01）：无模型跑通全链路 / 确定性回归。
 *
 * 形状口径（与真实输出一致，随各节点 schema 定稿）：
 * - 事件生成：`summary` / `steps` / `comparison_axes`（三维 0-100，`verbal_directness` 可空）/ `suitable_time`（时段码数组）/ `after?`
 * - 判定：`{verdict, reason, violated_rule?}`，verdict ∈ PASS / UNCERTAIN / REJECT
 * - 种子生长：`{keyword, theme}`；查重：`{same, index?}`
 *
 * 内容口径：动作码 ∈ 调试种子动作库（walk / sit / say / lie_down / eat）；
 * 「说」步骤只给 speaker / text——`duration_sec` 由链路程序算（05），预设不代劳。
 *
 * 轮转：同 task 第 n 次调用回第 n 条（取模回到第一条）——连续补货 / 重试时拿得到不同内容。
 * 计数器不设锁（单线程调用口径：UI / 生长编排都在主协程）。
 */
class PresetLlmClient : LlmClient {

    private val cursor = mutableMapOf<LlmTask, Int>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val variants = PRESETS.getValue(request.task)
        val index = (cursor[request.task] ?: 0) % variants.size
        cursor[request.task] = index + 1
        return LlmResponse(variants[index])
    }

    private companion object {
        val PRESETS: Map<LlmTask, List<String>> = mapOf(
            LlmTask.EVENT_GROW to listOf(
                """
                {
                  "summary": "在窗边坐了一会儿，安静地看外面",
                  "steps": [
                    { "action": "walk" },
                    { "action": "sit" }
                  ],
                  "comparison_axes": { "physical_intimacy": 10, "verbal_directness": null, "emotion_visibility": 30 },
                  "suitable_time": ["EVENING"]
                }
                """.trimIndent(),
                """
                {
                  "summary": "给对方递了杯热水，说了一句话",
                  "steps": [
                    { "action": "walk" },
                    { "action": "say", "params": { "speaker": "target", "text": "今天好像有点冷，喝点热的。" } },
                    { "action": "sit" }
                  ],
                  "comparison_axes": { "physical_intimacy": 20, "verbal_directness": 40, "emotion_visibility": 55 },
                  "suitable_time": ["EVENING", "NIGHT"]
                }
                """.trimIndent(),
                """
                {
                  "summary": "把沙发边收拾了一下，坐下来戴上耳机",
                  "steps": [
                    { "action": "walk" },
                    { "action": "sit" },
                    { "action": "eat" }
                  ],
                  "comparison_axes": { "physical_intimacy": 5, "verbal_directness": null, "emotion_visibility": 25 },
                  "suitable_time": ["AFTERNOON"],
                  "after": 1
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
