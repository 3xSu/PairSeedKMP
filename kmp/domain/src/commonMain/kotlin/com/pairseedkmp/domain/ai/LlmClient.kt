package com.pairseedkmp.domain.ai

/**
 * LLM 调用端口（《AI 接入-计划·01》）。
 * 业务侧只面向本端口与 [LlmCaller] 编排；具体实现（预设 / 端侧 / API Key）在数据层。
 * 异步口径（2026-10-06）：端口一律 `suspend` + interface + 数据类（iOS 互操作就绪）。
 */
interface LlmClient {
    /** 调一次模型。失败抛 [LlmException]（瞬时 / 持续分类由实现负责）。 */
    suspend fun complete(request: LlmRequest): LlmResponse
}

/** 调用请求：提示词两段 + 采样温度 + 是否期望 JSON + 任务标签。 */
data class LlmRequest(
    val system: String,
    val user: String,
    val temperature: Double,
    val expectJson: Boolean,
    val task: LlmTask,
)

/** 任务标签：谁在调（预设按它返回内置响应；日志按它区分链路）。 */
enum class LlmTask {
    /** 05：事件生成。 */
    EVENT_GROW,

    /** 06：OOC 判定。 */
    JUDGE,

    /** 04：种子生长。 */
    SEED_GROW,

    /** 03：长文查重（「同一件事」）。 */
    DEDUP,
}

/** 调用结果：模型原始文本（JSON 提取见 [LlmJson]，解析不进端口）。 */
data class LlmResponse(val text: String)

/** 错误分类：瞬时（重试有意义）/ 持续（重试无意义）。 */
enum class LlmErrorKind { TRANSIENT, PERSISTENT }

/** 端口抛出的统一异常：分类由实现负责（429 / 5xx / 超时 → TRANSIENT；401 / 403 / 404 → PERSISTENT）。 */
class LlmException(
    val kind: LlmErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
