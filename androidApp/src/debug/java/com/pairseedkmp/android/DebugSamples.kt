package com.pairseedkmp.android

import com.pairseedkmp.debugfixtures.JudgeSampleSet
import com.pairseedkmp.domain.judgment.samples.JudgeSample

/** 判定样例（debug 变体）：来自 `:kmp:debug-fixtures`；release 变体是空表。 */
object DebugSamples {
    val ALL: List<JudgeSample> = JudgeSampleSet.ALL
}
