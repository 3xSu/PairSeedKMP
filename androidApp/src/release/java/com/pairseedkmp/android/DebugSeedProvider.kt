package com.pairseedkmp.android

import android.content.Context
import com.pairseedkmp.data.PairSeedDatabase

/** 调试种子的 release 变体：空实现——release 包不携带任何预置数据（2026-10-05 拍）。 */
object DebugSeedProvider {
    const val ENABLED = false

    fun ensureSeeded(context: Context, database: PairSeedDatabase, now: Long) {
        // release：不种；"产品默认内容随包"要做时走正式通道另拍
    }
}
