package com.pairseedkmp.domain.seed

import com.pairseedkmp.domain.repository.SeedRepository
import kotlin.random.Random

/**
 * 加权挑选（《工作链路·种子池生长链路》→《数据设计·种子记录》）：按被挑中次数反比加权随机——
 * 权重 = 1 / (1 + picked_count)，次数少的更容易被挑中（老种子不断线；池子不设上限）。
 * 挑中即记账：`picked_count` + 1、`last_picked_at` = now。
 * [random] 可注入（测试用固定种子验分布）。
 */
class SeedPicker(
    private val repository: SeedRepository,
    private val random: Random = Random.Default,
) {
    /** 从池里挑一颗（池空 → null）；挑中即写计数，返回带新计数的种子。 */
    fun pick(now: Long): IdeaSeed? {
        val picked = weightedPick(repository.all(), random) ?: return null
        repository.recordPick(picked.id, now)
        return picked.copy(pickedCount = picked.pickedCount + 1, lastPickedAt = now, updatedAt = now)
    }
}

/**
 * 纯挑选：权重 1/(1+pickedCount)，按累计权重抽一点；空池 → null。
 * 顺序遍历 + 浮点边界兜底取最后一颗（point 恰好落在边界外时）。
 */
internal fun weightedPick(seeds: List<IdeaSeed>, random: Random): IdeaSeed? {
    if (seeds.isEmpty()) return null
    val total = seeds.sumOf { 1.0 / (1 + it.pickedCount) }
    var point = random.nextDouble() * total
    for (seed in seeds) {
        point -= 1.0 / (1 + seed.pickedCount)
        if (point < 0) return seed
    }
    return seeds.last()
}
