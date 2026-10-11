package com.pairseedkmp.domain.event

/**
 * 生成上下文读侧端口（05）：data 侧读角色六表 / 场景 / 动作库 / 文档库 / 近期事件 / 清单，
 * 组装成一份 [EventGrowContext] 快照（实现落在 :kmp:data）。
 */
interface EventContextSource {
    /**
     * 按当前运行配置组装快照。
     * 没有运行配置 / 没指角色组 / 角色不足两人 / 没场景 → null（= 长不出一条）。
     * [now] 定当前时段与近期事件口径；[utcOffsetSeconds] = 本地时区偏移（秒），算分钟数用。
     */
    fun snapshot(now: Long, utcOffsetSeconds: Int): EventGrowContext?
}
