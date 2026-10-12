package com.pairseedkmp.ui.debug.panel

import com.pairseedkmp.domain.ai.LlmTask

/**
 * 提示词覆盖存储端口（07 调试面板「提示词区块」）：任务 → 系统词覆盖文本，保存 / 加载 / 清空走它。
 *
 * 实现落在 androidApp 的 debug source set（app 私有目录 JSON 文件：重启不丢、不进包）；
 * release 变体 = 不落盘空实现（[persistent] = false；且 release 壳不传面板依赖，面板不出现）。
 * 覆盖机制在调用侧生效：`PanelLlmClient` 每次调用现读覆盖，替换 `LlmRequest.system`。
 */
interface PromptOverrideStore {

    /** 存储是否持久（debug = true；release 空实现 = false，面板据此提示「临时」）。 */
    val persistent: Boolean

    /** 全部覆盖（任务名 → 覆盖文本；文件不存在 / 损坏 → 空表）。 */
    suspend fun load(): Map<String, String>

    /** 保存一个任务的覆盖。 */
    suspend fun save(task: LlmTask, system: String)

    /** 清掉一个任务的覆盖（面板「恢复内置」）。 */
    suspend fun clear(task: LlmTask)
}
