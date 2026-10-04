package com.pairseedkmp.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Android 侧：AndroidSqliteDriver 建库（库文件 = 应用私有 databases/pairseedkmp.db，不加密）。 */
actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(PairSeedDatabase.Schema, context, DATABASE_FILE_NAME)
}
