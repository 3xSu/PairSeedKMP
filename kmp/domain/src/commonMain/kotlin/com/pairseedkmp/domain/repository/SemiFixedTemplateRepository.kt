package com.pairseedkmp.domain.repository

import com.pairseedkmp.domain.event.SemiFixedEventTemplate

/** 半固定模板读写端口。 */
interface SemiFixedTemplateRepository {
    /** 该角色组的模板（未删除）。 */
    fun byPack(packId: Long): List<SemiFixedEventTemplate>

    /** 插入模板（调试种子用）；同 pack 同 code 已存在则忽略（幂等），返回最后 rowid。 */
    fun insert(template: SemiFixedEventTemplate): Long
}
