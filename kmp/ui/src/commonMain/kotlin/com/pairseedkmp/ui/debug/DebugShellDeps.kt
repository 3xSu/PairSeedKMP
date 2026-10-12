package com.pairseedkmp.ui.debug

import com.pairseedkmp.domain.ai.AiSettings
import com.pairseedkmp.domain.ai.AiSettingsStore
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.event.BehaviorDurationSource
import com.pairseedkmp.domain.event.EventConfirmer
import com.pairseedkmp.domain.event.EventContextSource
import com.pairseedkmp.domain.event.EventSettler
import com.pairseedkmp.domain.event.EventTrigger
import com.pairseedkmp.domain.judgment.JudgmentSnapshotSource
import com.pairseedkmp.domain.repository.EventRepository
import com.pairseedkmp.domain.repository.SeedRepository
import com.pairseedkmp.domain.seed.SeedMaterialSource
import com.pairseedkmp.ui.debug.panel.DebugPanelDeps

/**
 * 当前角色组上下文（壳层从库里解析后传进来）：
 * actor = 组内第一个角色、target = 第二个、场景 = 运行配置指向的（没指 → 组内第一个）。
 */
data class ShellContext(
    val packId: Long,
    val packName: String,
    val actorId: Long,
    val actorName: String,
    val targetId: Long?,
    val targetName: String?,
    val sceneId: Long,
    val sceneName: String,
)

/**
 * 前台信号槽（08 触发链）：壳层 onResume 里 [bump]；调试壳每秒 tick 对账——计数涨了 = 新一轮前台。
 * onResume 与 Compose tick 都在主线程，读写无需加锁。
 */
class ForegroundSignal {
    var count: Int = 0
        private set

    fun bump() {
        count++
    }
}

/**
 * 自动链依赖（08 装配）：onResume（结算之后）触发——清单 < 上限 → 自动补货（生成 → 判定 → 落库），
 * 清单已满 → 长一颗种子（04 维护池子）；后台协程跑、不重入。
 * 与调试面板同一批实例（同一条库连接）、同一条 [llmClientFor] 规则（预设 / API Key；端侧 null）。
 */
class AutoGrowDeps(
    val seedRepository: SeedRepository,
    val eventRepository: EventRepository,
    val durations: BehaviorDurationSource,
    val contextSource: EventContextSource,
    val snapshotSource: JudgmentSnapshotSource,
    val materialSources: List<SeedMaterialSource>,
    val settingsStore: AiSettingsStore,
    /** 按当前配置造 LLM client；null = 该模式没有可用实现（端侧未接入）。 */
    val llmClientFor: suspend (AiSettings) -> LlmClient?,
)

/**
 * 调试壳的全部依赖：`:androidApp` 手接好后传给 [com.pairseedkmp.ui.App]；UI 不认识数据库。
 * [clearAndReseed] = null 表示调试通道关闭（release）：不种、清空按钮禁用（2026-10-05 拍）。
 */
class DebugShellDeps(
    val eventRepository: EventRepository,
    val durations: BehaviorDurationSource,
    val settler: EventSettler,
    val trigger: EventTrigger,
    val confirmer: EventConfirmer,
    /** 当前角色组上下文；没有（空库 / release 不种）→ null。 */
    val context: () -> ShellContext?,
    val now: () -> Long,
    val utcOffsetSeconds: () -> Int,
    /** 时间格式化（平台实现由壳层注入）：`HH:mm:ss`。 */
    val formatClock: (Long) -> String,
    /** `MM-dd HH:mm`。 */
    val formatTime: (Long) -> String,
    val debugEnabled: Boolean,
    /** 清空数据重来（删库文件 + 重种 + 重建整条栈），返回重建后的新依赖；release → null。 */
    val clearAndReseed: (() -> DebugShellDeps)?,
    /** 调试面板依赖（07）；null = 页尾不渲染面板（release 壳不传）。 */
    val panel: DebugPanelDeps? = null,
    /** 前台信号（08 自动链触发点；壳层 onResume 里 bump）。 */
    val foreground: ForegroundSignal = ForegroundSignal(),
    /** 自动链依赖（08）；null = 不跑自动链。 */
    val autoGrow: AutoGrowDeps? = null,
)
