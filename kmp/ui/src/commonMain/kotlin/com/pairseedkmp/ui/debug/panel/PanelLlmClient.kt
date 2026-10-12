package com.pairseedkmp.ui.debug.panel

import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.LlmException
import com.pairseedkmp.domain.ai.LlmRequest
import com.pairseedkmp.domain.ai.LlmResponse
import com.pairseedkmp.domain.ai.LlmTask

/** 调用记录上限（面板展示留最近几条；防长跑把内存 / 渲染撑爆）。 */
const val DEFAULT_INVOCATION_LOG_CAPACITY: Int = 30

/** 一次 LLM 调用留痕（面板「提示词与输出」区块展示）。 */
data class LlmInvocation(
    /** 面板会话内自增序号（新的在前 = 序号大的在前）。 */
    val seq: Int,
    val task: LlmTask,
    /** 实际送出的系统词（覆盖生效后）。 */
    val system: String,
    val user: String,
    val temperature: Double,
    /** 本次调用的 system 是否来自面板覆盖（false = 内置默认）。 */
    val overridden: Boolean,
    /** 原始输出（失败时 null）。 */
    val responseText: String?,
    /** 失败摘要（成功时 null）：`错误分类：消息`。 */
    val error: String?,
    val elapsedMillis: Long,
)

/**
 * 面板调用装饰器（07）：包住当前模式的 [LlmClient]，在真实调用与链路之间干两件事——
 * ① **覆盖替换**：调用发生时现读 [overrideFor]（面板刚保存的立即生效；空串 / null = 内置默认），
 *    被替换的记 `overridden = true`；
 * ② **记录**：每次调用（含 [com.pairseedkmp.domain.ai.LlmCaller] 的每一次重试尝试）留一条
 *    [LlmInvocation] 进 [log]（新的在前，留最近 [capacity] 条）——链路不用知道面板存在。
 *
 * 失败（[LlmException]）记录后原样上抛：LlmCaller 的重试 / 各链路失败矩阵不受影响。
 * 边界：超时取消（`withTimeout` 的 CancellationException）不记——那不属于「模型的一次回答」。
 */
class PanelLlmClient(
    private val delegate: LlmClient,
    private val overrideFor: (LlmTask) -> String?,
    private val nowMillis: () -> Long,
    /** 面板会话内的调用记录表（`SnapshotStateList` 时 UI 实时刷新）。 */
    private val log: MutableList<LlmInvocation>,
    private val capacity: Int = DEFAULT_INVOCATION_LOG_CAPACITY,
) : LlmClient {

    /**
     * 面板会话内自增序号——本实例每次跑链路都新造，所以要接着共享 [log] 的最大序号往下排，
     * 否则两条不同的调用都会显示 `#0`（装机演练发现的显示缺陷）。
     */
    private var seq = (log.maxOfOrNull { it.seq } ?: -1) + 1

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val override = overrideFor(request.task)?.takeIf { it.isNotBlank() }
        val effective = if (override == null) request else request.copy(system = override)
        val started = nowMillis()
        return try {
            val response = delegate.complete(effective)
            record(effective, override != null, response.text, error = null, elapsed = nowMillis() - started)
            response
        } catch (e: LlmException) {
            record(
                effective, override != null, responseText = null,
                error = "${e.kind}：${e.message}", elapsed = nowMillis() - started,
            )
            throw e
        }
    }

    private fun record(
        request: LlmRequest,
        overridden: Boolean,
        responseText: String?,
        error: String?,
        elapsed: Long,
    ) {
        log.add(
            0,
            LlmInvocation(
                seq = seq++,
                task = request.task,
                system = request.system,
                user = request.user,
                temperature = request.temperature,
                overridden = overridden,
                responseText = responseText,
                error = error,
                elapsedMillis = elapsed,
            ),
        )
        while (log.size > capacity) log.removeAt(log.size - 1)
    }
}
