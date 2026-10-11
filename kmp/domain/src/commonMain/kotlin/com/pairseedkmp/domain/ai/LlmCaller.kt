package com.pairseedkmp.domain.ai

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** 调用超时默认值：120 秒（可配，随构造传入）。 */
const val DEFAULT_LLM_TIMEOUT_MILLIS: Long = 120_000

/** 瞬时错误重试间隔默认值：3 秒（"隔几秒"起步值）。 */
const val DEFAULT_LLM_RETRY_DELAY_MILLIS: Long = 3_000

/** 瞬时错误重试次数上限：2（即最多共 3 次尝试；不占各链路自己的重试额度）。 */
const val DEFAULT_LLM_MAX_RETRIES: Int = 2

/**
 * 调用编排（01）：给 [LlmClient] 套统一策略——
 * 超时（[timeoutMillis]）、瞬时错误重试（≤[maxRetries] 次、间隔 [retryDelayMillis]）、持续错误直接上抛。
 *
 * 边界：只管"这一次调用"。各链路的"同种子重试 / 换种子 / 重复反馈重写"属于业务层（04 / 05 / 06）。
 * 测试：重试与超时用 `runTest` 虚拟时间（`kotlinx-coroutines-test`），不用真等。
 */
class LlmCaller(
    private val client: LlmClient,
    private val timeoutMillis: Long = DEFAULT_LLM_TIMEOUT_MILLIS,
    private val maxRetries: Int = DEFAULT_LLM_MAX_RETRIES,
    private val retryDelayMillis: Long = DEFAULT_LLM_RETRY_DELAY_MILLIS,
) {
    /** 调一次（含超时与瞬时重试）；重试用尽 / 持续错误 → 抛 [LlmException]。 */
    suspend fun complete(request: LlmRequest): LlmResponse {
        var attempt = 0
        while (true) {
            try {
                return withTimeout(timeoutMillis) { client.complete(request) }
            } catch (e: TimeoutCancellationException) {
                // 超时 = 瞬时（重试有意义）
                if (attempt >= maxRetries) {
                    throw LlmException(LlmErrorKind.TRANSIENT, "调用超时（含重试共 ${attempt + 1} 次尝试）", e)
                }
                attempt++
                delay(retryDelayMillis)
            } catch (e: LlmException) {
                if (e.kind == LlmErrorKind.PERSISTENT || attempt >= maxRetries) throw e
                attempt++
                delay(retryDelayMillis)
            }
        }
    }
}
