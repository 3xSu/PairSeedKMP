package com.pairseedkmp.android.ai

import android.content.Context
import com.pairseedkmp.domain.ai.AiMode
import com.pairseedkmp.domain.ai.AiSettings
import com.pairseedkmp.domain.ai.AiSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** AI 运行配置的 prefs 实现（01）：非密钥区（模式 / 基址 / 模型名）；Key 本体走 [KeystoreSecretStore]。 */
class AiSettingsStoreImpl(context: Context) : AiSettingsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun current(): AiSettings = withContext(Dispatchers.IO) {
        AiSettings(
            mode = prefs.getString(KEY_MODE, null)
                ?.let { name -> AiMode.entries.firstOrNull { it.name == name } }
                ?: AiMode.PRESET, // 首启默认预设：无模型也能跑通全链路
            baseUrl = prefs.getString(KEY_BASE_URL, null).orEmpty(),
            model = prefs.getString(KEY_MODEL, null).orEmpty(),
        )
    }

    override suspend fun update(settings: AiSettings) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL, settings.model)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "pairseedkmp_ai_settings"
        const val KEY_MODE = "mode"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
    }
}
