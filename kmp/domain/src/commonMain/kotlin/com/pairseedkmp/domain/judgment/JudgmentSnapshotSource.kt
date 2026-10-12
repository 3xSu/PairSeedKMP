package com.pairseedkmp.domain.judgment

import com.pairseedkmp.domain.event.EventCandidate

/**
 * 判定快照读侧端口（06）：data 侧按候选引用的 actor / target / 场景，
 * 读软轴原文 / 硬规则 / 场景旗标 / 码表档位投影，组装成一份 [JudgmentSnapshot]（实现落在 :kmp:data）。
 */
interface JudgmentSnapshotSource {
    /**
     * 组不出快照 → null：候选引用的角色（已删 / 不存在）或场景读不到 = 组装出错。
     * 缺料（软档没填 / 步骤码不在码表）不算组不出——那是快照内容的事，由判定层 fail-safe 兜。
     */
    fun snapshot(candidate: EventCandidate): JudgmentSnapshot?
}
