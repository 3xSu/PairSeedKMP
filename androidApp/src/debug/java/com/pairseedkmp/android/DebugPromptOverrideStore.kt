package com.pairseedkmp.android

import android.content.Context
import com.pairseedkmp.domain.ai.LlmTask
import com.pairseedkmp.ui.debug.panel.PromptOverrideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 提示词覆盖存储（07 debug 变体）：app 私有目录 JSON 文件——重启不丢、不进包、不进 git。
 * 路径 `filesDir/pairseed-debug/prompt_overrides.json`；文件损坏按「没存过」算（调试面板，不炸）。
 * 只存提示词文本，与 API Key 无关（Key 只进 Keystore）。
 */
class DebugPromptOverrideStore(private val context: Context) : PromptOverrideStore {

    override val persistent: Boolean = true

    override suspend fun load(): Map<String, String> = withContext(Dispatchers.IO) {
        val file = overrideFile()
        if (!file.exists()) {
            emptyMap()
        } else {
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                root.keys().asSequence()
                    .associateWith { root.optString(it) }
                    .filterValues { it.isNotBlank() }
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    override suspend fun save(task: LlmTask, system: String) = withContext(Dispatchers.IO) {
        val file = overrideFile()
        file.parentFile?.mkdirs()
        val root = readRoot(file)
        root.put(task.name, system)
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    override suspend fun clear(task: LlmTask) = withContext(Dispatchers.IO) {
        val file = overrideFile()
        if (!file.exists()) return@withContext
        val root = readRoot(file)
        root.remove(task.name)
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun overrideFile(): File = File(context.filesDir, "pairseed-debug/prompt_overrides.json")

    private fun readRoot(file: File): JSONObject =
        if (!file.exists()) {
            JSONObject()
        } else {
            try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                JSONObject()
            }
        }
}
