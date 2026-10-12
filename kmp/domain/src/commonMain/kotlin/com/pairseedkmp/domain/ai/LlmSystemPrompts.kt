package com.pairseedkmp.domain.ai

import com.pairseedkmp.domain.dedup.LONG_TEXT_SYSTEM_PROMPT
import com.pairseedkmp.domain.event.GROW_SYSTEM_PROMPT
import com.pairseedkmp.domain.event.TAG_SYSTEM_PROMPT
import com.pairseedkmp.domain.judgment.JUDGE_SYSTEM_PROMPT
import com.pairseedkmp.domain.seed.SEED_GROW_SYSTEM_PROMPT

/**
 * 内置系统提示词注册表（07 调试面板用）：按任务取内置系统词**原文**，
 * 供面板「提示词区块」展示默认值 / 重置覆盖 / 对照编辑结果。
 *
 * 只读视图——编辑不落这里；覆盖机制在面板侧（debug 通道存本地文件，调用时替换 `LlmRequest.system`）。
 * 注册表与各链路常量同源（测试保证二者一致：`LlmSystemPromptsTest` 逐任务比对实际构建的请求）。
 */
object LlmSystemPrompts {

    /** 任务 → 内置系统词原文；本表覆盖 [LlmTask] 全部任务（不存在的任务没有）。 */
    fun defaultFor(task: LlmTask): String = when (task) {
        LlmTask.SEED_GROW -> SEED_GROW_SYSTEM_PROMPT
        LlmTask.EVENT_GROW -> GROW_SYSTEM_PROMPT
        LlmTask.EVENT_TAG -> TAG_SYSTEM_PROMPT
        LlmTask.JUDGE -> JUDGE_SYSTEM_PROMPT
        LlmTask.DEDUP -> LONG_TEXT_SYSTEM_PROMPT
    }
}
