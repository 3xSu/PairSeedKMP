package com.pairseedkmp.ui.debug

import com.pairseedkmp.domain.event.BehaviorDurationSource
import com.pairseedkmp.domain.event.EventConfirmer
import com.pairseedkmp.domain.event.EventSettler
import com.pairseedkmp.domain.event.EventTrigger
import com.pairseedkmp.domain.repository.EventRepository

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
)
