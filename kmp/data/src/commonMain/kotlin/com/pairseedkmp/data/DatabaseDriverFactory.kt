package com.pairseedkmp.data

import app.cash.sqldelight.db.SqlDriver

/** 库文件名：pairseedkmp.db（2026-10-04 拍）。Android 落在应用私有 databases/ 目录。 */
const val DATABASE_FILE_NAME = "pairseedkmp.db"

/**
 * 建库入口：各平台给各自的驱动实现。
 * 现在只有 Android 的 actual（androidMain）；iOS 留到做 iOS 时补。
 */
expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
