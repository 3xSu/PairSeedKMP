package com.pairseedkmp.domain.repository

import com.pairseedkmp.domain.seed.IdeaSeed

/** 种子池读写端口（04）。池子全局一份、不设上限（种子可复用，老种子不断线）。 */
interface SeedRepository {
    /** 全部未删除种子（按 id 升序；挑选 / 查重目标 / 池子展示用）。 */
    fun all(): List<IdeaSeed>

    /** 关键词搜索：包含匹配（前缀 / 同字都命中；04 人为补充防重复输入用）。 */
    fun search(fragment: String): List<IdeaSeed>

    /** 插入种子，返回新行 id。 */
    fun insert(seed: IdeaSeed): Long

    /** 挑中记账：`picked_count` + 1、`last_picked_at` = [pickedAt]、`updated_at` 同（反比权重由此生效）。 */
    fun recordPick(id: Long, pickedAt: Long)
}
