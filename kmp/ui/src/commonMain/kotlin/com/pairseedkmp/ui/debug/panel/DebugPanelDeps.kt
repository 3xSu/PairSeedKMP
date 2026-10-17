package com.pairseedkmp.ui.debug.panel

import com.pairseedkmp.domain.ai.AiSettings
import com.pairseedkmp.domain.ai.AiSettingsStore
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.ai.SecretStore
import com.pairseedkmp.domain.event.BehaviorDurationSource
import com.pairseedkmp.domain.event.EventContextSource
import com.pairseedkmp.domain.judgment.JudgmentSnapshotSource
import com.pairseedkmp.domain.judgment.samples.JudgeSample
import com.pairseedkmp.domain.repository.EventRepository
import com.pairseedkmp.domain.repository.RuntimeProfileRepository
import com.pairseedkmp.domain.repository.SeedRepository
import com.pairseedkmp.domain.seed.SeedMaterialSource

/**
 * 调试面板依赖（07）：`:androidApp` 手接好后随 [com.pairseedkmp.ui.debug.DebugShellDeps] 传进来；
 * 壳层只在调试通道开启（debug 种）时构造它——release 传 null，面板不出现。
 *
 * 仓库 / 时长与调试壳是同一批实例（同一条库连接），面板与壳看到同一份数据。
 * [llmClientFor] = 按运行配置现造 client：预设 / API Key 各回各实现；端侧未接入（02 遗留）→ null。
 */
class DebugPanelDeps(
    val seedRepository: SeedRepository,
    val eventRepository: EventRepository,
    val durations: BehaviorDurationSource,
    val contextSource: EventContextSource,
    val snapshotSource: JudgmentSnapshotSource,
    val materialSources: List<SeedMaterialSource>,
    val settingsStore: AiSettingsStore,
    val secretStore: SecretStore,
    val profileRepository: RuntimeProfileRepository,
    /** 按当前配置造 LLM client；null = 该模式没有可用实现（端侧未接入）。 */
    val llmClientFor: suspend (AiSettings) -> LlmClient?,
    val promptStore: PromptOverrideStore,
    /** 判定样例（调试夹具；由壳层从 debug 依赖注入，ui 不直接依赖 fixtures 模块）。 */
    val samples: List<JudgeSample>,
)
