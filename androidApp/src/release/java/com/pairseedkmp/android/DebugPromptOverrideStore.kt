package com.pairseedkmp.android

import android.content.Context
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.ui.debug.panel.PromptOverrideStore

/**
 * 提示词覆盖存储（07 release 变体）：不落盘空实现——release 不带调试面板存储（2026-10-11 拍，沿用调试通道口径）；
 * 且 release 壳不传面板依赖（`DebugShellDeps.panel = null`），面板本身也不出现。
 */
class DebugPromptOverrideStore(@Suppress("UNUSED_PARAMETER") context: Context) : PromptOverrideStore {

    override val persistent: Boolean = false

    override suspend fun load(): Map<String, String> = emptyMap()

    override suspend fun save(task: LlmTask, system: String) = Unit

    override suspend fun clear(task: LlmTask) = Unit
}
