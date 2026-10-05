package com.pairseedkmp.android

import com.pairseedkmp.data.PairSeedDatabase

/**
 * 调试种子（debug 通道；2026-10-05 拍）：首启自动种、幂等；release 变体是空实现，内容不进包。
 * 调试内容不进仓库（本地数据通道随后续节点接入）；本提交保留空实现。
 */
object DebugSeedProvider {
    const val ENABLED = true

    fun ensureSeeded(database: PairSeedDatabase, now: Long) {
        // 调试内容在本地文件中（不进仓库）
    }
}
