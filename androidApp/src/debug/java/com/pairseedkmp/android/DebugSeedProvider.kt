package com.pairseedkmp.android

import android.content.Context
import android.util.Log
import com.pairseedkmp.data.PairSeedDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调试种子（debug 通道；2026-10-05 拍）：首启自动种、幂等；release 变体是空实现，内容不进包。
 *
 * 角色相关内容不在源码里：角色组 / 角色 / 设定卡 / 双向关系 / 硬规则 / 种子 / 文档片段
 * 从 `src/debug/assets/debug-seeds.local.json` 读取（本地文件，已加入 `.gitignore`）。
 * 文件缺失 / 解析失败 → 记日志、不种、不崩；别的机器克隆后没有该文件时 debug 版从空库启动。
 *
 * 通用骨架（与角色无关）仍在代码里：场景「家」、3 条半固定模板、17 码动作库、运行配置。
 */
object DebugSeedProvider {
    const val ENABLED = true

    private const val TAG = "DebugSeedProvider"
    private const val ASSET_NAME = "debug-seeds.local.json"

    fun ensureSeeded(context: Context, database: PairSeedDatabase, now: Long) {
        val root = readJson(context) ?: return
        val packJson = root.optJSONObject("pack") ?: return
        val packCode = packJson.optString("code").takeIf { it.isNotBlank() } ?: return

        // 幂等：组已在 = 种过，什么都不动（旧版种下的库想补新内容 → 清空数据重来）
        if (database.character_packsQueries.selectCharacterPackByCode(packCode).executeAsOneOrNull() != null) return

        try {
            database.transaction {
                val packs = database.character_packsQueries
                packs.insertCharacterPack(
                    packCode,
                    packJson.optString("name", packCode),
                    packJson.optString("summary").takeIf { it.isNotBlank() },
                    now,
                    now,
                )
                val packId = packs.lastInsertedCharacterPackId().executeAsOne()

                // 角色先落，拿 id 供设定卡 / 关系 / 硬规则引用
                val charactersJson = root.optJSONArray("characters") ?: JSONArray()
                val characterIds = linkedMapOf<String, Long>()
                for (i in 0 until charactersJson.length()) {
                    val character = charactersJson.optJSONObject(i) ?: continue
                    val code = character.optString("code").takeIf { it.isNotBlank() } ?: continue
                    database.charactersQueries.insertCharacter(
                        packId, code, character.optString("name", code),
                        character.optString("summary"), now, now,
                    )
                    characterIds[code] = database.charactersQueries.lastInsertedCharacterId().executeAsOne()
                }

                root.optJSONObject("sharedSpec")?.let { shared ->
                    database.pair_shared_specsQueries.insertPairSharedSpec(
                        pack_id = packId,
                        pair_summary = shared.optString("pairSummary"),
                        relation_tone_summary = shared.optString("relationToneSummary"),
                        shared_forbidden_tags = shared.optJsonArrayText("sharedForbiddenTags"),
                        created_at = now,
                        updated_at = now,
                    )
                }

                val scenes = database.scenesQueries
                scenes.insertScene(
                    pack_id = packId,
                    scene_code = "home",
                    scene_name = "家",
                    location_type = "PRIVATE",
                    description = "共用的家，有沙发、茶几和窗户。",
                    scene_image = "",
                    grid_size = 32L,
                    grid_cols = 60L,
                    grid_rows = 34L,
                    walk_mask = "",
                    place_mask = "",
                    created_at = now,
                    updated_at = now,
                )
                val sceneId = scenes.lastInsertedSceneId().executeAsOne()

                insertCharacterDetails(database, charactersJson, characterIds, now)

                val templates = database.semi_fixed_event_templatesQueries
                // 睡觉：跨天窗 23:00–07:00
                templates.insertSemiFixedTemplate(
                    pack_id = packId,
                    template_code = "sleep",
                    name_cn = "睡觉",
                    summary = "躺下睡了",
                    steps = """[{"action":"walk"},{"action":"lie_down"}]""",
                    window_earliest = 23L * 60,
                    window_latest = 7L * 60,
                    created_at = now,
                    updated_at = now,
                )
                // 吃饭：固定时刻 12:00
                templates.insertSemiFixedTemplate(
                    pack_id = packId,
                    template_code = "meal",
                    name_cn = "吃饭",
                    summary = "坐下吃了点东西",
                    steps = """[{"action":"walk"},{"action":"sit"},{"action":"eat"}]""",
                    window_earliest = 12L * 60,
                    window_latest = 12L * 60,
                    created_at = now,
                    updated_at = now,
                )
                // 散步：普通窗 16:00–17:00（台词说话人取本地角色组第一个角色 code）
                val firstCode = characterIds.keys.firstOrNull() ?: "A"
                templates.insertSemiFixedTemplate(
                    pack_id = packId,
                    template_code = "walk_out",
                    name_cn = "散步",
                    summary = "出门走了走",
                    steps = """[{"action":"walk"},{"action":"say","params":{"speaker":"$firstCode","text":"外面风挺舒服的。","duration_sec":6}}]""",
                    window_earliest = 16L * 60,
                    window_latest = 17L * 60,
                    created_at = now,
                    updated_at = now,
                )

                insertBehaviors(database, now)
                insertSeeds(database, root.optJSONArray("seeds"), now)
                insertDocuments(database, root.optJSONArray("documents"), packCode, now)

                database.runtime_profilesQueries.insertRuntimeProfile(
                    active_pack_id = packId,
                    active_scene_id = sceneId,
                    on_device_model = null,
                    api_key_configured = 0L,
                    last_seen_at = null,
                    created_at = now,
                    updated_at = now,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "本地种子写入失败（事务已回滚），本次不种", e)
        }
    }

    /** 设定卡 / 关系 / 硬规则：按角色 code 引用已落的 id。 */
    private fun insertCharacterDetails(
        database: PairSeedDatabase,
        charactersJson: JSONArray,
        characterIds: Map<String, Long>,
        now: Long,
    ) {
        for (i in 0 until charactersJson.length()) {
            val character = charactersJson.optJSONObject(i) ?: continue
            val code = character.optString("code")
            val characterId = characterIds[code] ?: continue

            character.optJSONObject("spec")?.let { spec ->
                database.character_specsQueries.insertCharacterSpec(
                    character_id = characterId,
                    aliases = spec.optJsonArrayText("aliases"),
                    soft_axes = spec.optJSONObject("softAxes")?.toString() ?: "{}",
                    speech_style_summary = spec.optString("speechStyle"),
                    preferred_affection_modes = spec.optJsonArrayText("preferred"),
                    disliked_affection_modes = spec.optJsonArrayText("disliked"),
                    body_spec = spec.optJSONObject("bodySpec")?.toString() ?: "{}",
                    wardrobe = spec.optJSONObject("wardrobe")?.toString() ?: "{}",
                    created_at = now,
                    updated_at = now,
                )
            }

            character.optJSONArray("relations")?.let { relations ->
                for (j in 0 until relations.length()) {
                    val relation = relations.optJSONObject(j) ?: continue
                    val targetId = characterIds[relation.optString("target")] ?: continue
                    database.character_target_relationsQueries.insertCharacterTargetRelation(
                        character_id = characterId,
                        target_character_id = targetId,
                        toward_target_summary = relation.optString("towardTargetSummary"),
                        default_distance = relation.optString("defaultDistance"),
                        likely_initiation_modes = relation.optJsonArrayText("likelyInitiationModes"),
                        sensitive_progression_tags = relation.optJsonArrayText("sensitiveProgressionTags"),
                        safe_comfort_actions = relation.optJsonArrayText("safeComfortActions"),
                        direct_addressing_summary = relation.optTextOrNull("directAddressingSummary"),
                        reference_addressing_summary = relation.optTextOrNull("referenceAddressingSummary"),
                        created_at = now,
                        updated_at = now,
                    )
                }
            }

            character.optJSONArray("hardRules")?.let { rules ->
                for (j in 0 until rules.length()) {
                    val rule = rules.optJSONObject(j) ?: continue
                    database.character_hard_rulesQueries.insertHardRule(
                        character_id = characterId,
                        rule_key = rule.optString("ruleKey"),
                        scope = rule.optString("scope"),
                        description = rule.optString("description"),
                        forbidden_codes = rule.optJsonArrayText("forbiddenCodes"),
                        notes = null,
                        sort_order = rule.optLong("sortOrder", 0L),
                        created_at = now,
                        updated_at = now,
                    )
                }
            }
        }
    }

    /** 17 码动作库（与角色无关的闭集；physical_level 按 2026-10-11 定档）。 */
    private fun insertBehaviors(database: PairSeedDatabase, now: Long) {
        val behaviors = database.behavior_templatesQueries
        // 单人 13（全 1 档；时长 demo 5 码照旧、其余合理默认）
        behaviors.insertBehaviorTemplate("stand", "Stand", "Character stands still in place.", "{actor}站着", "single", "none", 1L, 5L, "{}", 10L, now, now)
        behaviors.insertBehaviorTemplate("walk", "Walk", "Character walks from one place to another.", "{actor}走动", "single", "approach", 1L, 4L, "{}", 20L, now, now)
        behaviors.insertBehaviorTemplate("sit", "Sit", "Character sits down.", "{actor}坐下", "single", "none", 1L, 6L, "{}", 30L, now, now)
        behaviors.insertBehaviorTemplate("lie_down", "Lie Down", "Character lies down.", "{actor}躺下", "single", "none", 1L, 15L, "{}", 40L, now, now)
        behaviors.insertBehaviorTemplate("pick_up", "Pick Up", "Character picks something up.", "{actor}拿起东西", "single", "none", 1L, 3L, "{}", 50L, now, now)
        behaviors.insertBehaviorTemplate("put_down", "Put Down", "Character puts something down.", "{actor}放下东西", "single", "none", 1L, 3L, "{}", 60L, now, now)
        behaviors.insertBehaviorTemplate("hand_item", "Hand Item", "Character hands an item to the other person.", "{actor}把东西递给对方", "single", "none", 1L, 3L, "{}", 70L, now, now)
        behaviors.insertBehaviorTemplate("wave", "Wave", "Character waves a hand.", "{actor}挥手", "single", "none", 1L, 3L, "{}", 80L, now, now)
        behaviors.insertBehaviorTemplate("push_pull", "Push or Pull", "Character pushes or pulls something.", "{actor}推或拉", "single", "none", 1L, 4L, "{}", 90L, now, now)
        behaviors.insertBehaviorTemplate("eat", "Eat", "Character eats food.", "{actor}吃东西", "single", "none", 1L, 30L, "{}", 100L, now, now)
        behaviors.insertBehaviorTemplate("drink", "Drink", "Character drinks something.", "{actor}喝点东西", "single", "none", 1L, 4L, "{}", 110L, now, now)
        behaviors.insertBehaviorTemplate("say", "Say", "Character says a line (params: speaker / text / duration_sec).", "{actor}说话", "single", "none", 1L, 5L, "{}", 120L, now, now)
        behaviors.insertBehaviorTemplate("turn", "Turn", "Character turns toward the other person.", "{actor}转向对方", "single", "none", 1L, 2L, "{}", 130L, now, now)
        // 双人 4（牵手·靠 3 档 / 拥抱·抱起 4 档）
        behaviors.insertBehaviorTemplate("hold_hands", "Hold Hands", "The two characters hold hands.", "{actor}和{target}牵手", "pair", "none", 3L, 8L, "{}", 140L, now, now)
        behaviors.insertBehaviorTemplate("hug", "Hug", "The two characters hug.", "{actor}和{target}拥抱", "pair", "none", 4L, 6L, "{}", 150L, now, now)
        behaviors.insertBehaviorTemplate("lean", "Lean On", "One character leans on the other.", "{actor}靠在{target}身上", "pair", "none", 3L, 10L, "{}", 160L, now, now)
        behaviors.insertBehaviorTemplate("hold", "Hold Up", "One character holds the other up in their arms.", "{actor}抱起{target}", "pair", "carry", 4L, 6L, "{}", 170L, now, now)
    }

    private fun insertSeeds(database: PairSeedDatabase, seeds: JSONArray?, now: Long) {
        if (seeds == null) return
        for (i in 0 until seeds.length()) {
            val seed = seeds.optJSONObject(i) ?: continue
            database.idea_seedsQueries.insertSeed(
                keyword = seed.optString("keyword"),
                theme = seed.optString("theme"),
                scene_hint_tags = seed.optJsonArrayText("sceneTags"),
                suggested_time_window = seed.optTextOrNull("window"),
                relationship_push_target = seed.optLongOrNull("push"),
                novelty_target = seed.optLongOrNull("novelty"),
                source_type = "MANUAL",
                quality_score = null,
                created_at = now,
                updated_at = now,
            )
        }
    }

    private fun insertDocuments(
        database: PairSeedDatabase,
        documents: JSONArray?,
        packCode: String,
        now: Long,
    ) {
        if (documents == null) return
        for (i in 0 until documents.length()) {
            val doc = documents.optJSONObject(i) ?: continue
            val character = doc.optTextOrNull("character")
            database.document_snippetsQueries.insertDocumentSnippet(
                doc_code = doc.optString("docCode"),
                doc_type = doc.optString("docType"),
                pack_code = packCode,
                scope = if (character != null) "character" else "pair",
                character_code = character,
                title = doc.optTextOrNull("title"),
                summary = doc.optTextOrNull("summary"),
                body = doc.optTextOrNull("body"),
                tags = doc.optJsonArrayText("tags"),
                source_ref = "local",
                created_at = now,
                updated_at = now,
            )
        }
    }

    private fun readJson(context: Context): JSONObject? = try {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        JSONObject(text)
    } catch (e: Exception) {
        Log.i(TAG, "本地种子文件不可用（$ASSET_NAME）：跳过调试种子", e)
        null
    }
}

/** JSON 值为空 / 缺失 → null；否则取字符串（空白同样视为 null）。 */
private fun JSONObject.optTextOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** JSON 数组 → 紧凑 JSON 文本（喂库的 JSON 列）；缺失 / null → null。 */
private fun JSONObject.optJsonArrayText(key: String): String? =
    if (!has(key) || isNull(key)) null else optJSONArray(key)?.toString()

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)
