package com.pairseedkmp.domain.dedup

/**
 * 编辑距离与相似度（《工作链路·查重链路》1.2 短词 L2；细节见《种子去重方案调研》）。
 * 输入按约定是 [normalize] 之后的文本；种子量级（几千词 × 几个字）用两行滚动 DP 足够。
 */

/** Levenshtein 编辑距离（增 / 删 / 替各计 1；按 UTF-16 码元比较）。 */
fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(
                prev[j] + 1,                      // 删
                curr[j - 1] + 1,                  // 增
                prev[j - 1] + substitution,       // 替
            )
        }
        val swap = prev
        prev = curr
        curr = swap
    }
    return prev[b.length]
}

/** 归一化相似度 = 1 − dist / max(len)；两串都空按相同处理（1.0）。 */
fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    val longest = maxOf(a.length, b.length)
    return 1.0 - editDistance(a, b).toDouble() / longest
}
