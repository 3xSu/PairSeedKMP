package com.pairseedkmp.android

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.pairseedkmp.android.ai.AiSettingsStoreImpl
import com.pairseedkmp.android.ai.KeystoreSecretStore
import com.pairseedkmp.data.DATABASE_FILE_NAME
import com.pairseedkmp.data.DatabaseDriverFactory
import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.ai_impl.ApiKeyLlmClient
import com.pairseedkmp.data.ai_impl.LlmHttpClientFactory
import com.pairseedkmp.data.ai_impl.PresetLlmClient
import com.pairseedkmp.data.event_impl.BehaviorDurationSourceImpl
import com.pairseedkmp.data.event_impl.EventRepositoryImpl
import com.pairseedkmp.data.event_impl.SemiFixedTemplateRepositoryImpl
import com.pairseedkmp.data.event_impl.UserConfirmationRepositoryImpl
import com.pairseedkmp.data.grow_impl.EventContextSourceImpl
import com.pairseedkmp.data.judgment_impl.JudgmentSnapshotSourceImpl
import com.pairseedkmp.data.runtime_impl.RuntimeProfileRepositoryImpl
import com.pairseedkmp.data.seed_impl.HitokotoMaterialSource
import com.pairseedkmp.data.seed_impl.SeedMaterialHttpClientFactory
import com.pairseedkmp.data.seed_impl.SeedRepositoryImpl
import com.pairseedkmp.data.seed_impl.ZhihuDailyMaterialSource
import com.pairseedkmp.domain.ai.AiMode
import com.pairseedkmp.domain.ai.AiSettings
import com.pairseedkmp.domain.ai.LlmClient
import com.pairseedkmp.domain.event.EventConfirmer
import com.pairseedkmp.domain.event.EventSettler
import com.pairseedkmp.domain.event.EventTrigger
import com.pairseedkmp.ui.debug.AutoGrowDeps
import com.pairseedkmp.ui.debug.DebugShellDeps
import com.pairseedkmp.ui.debug.ForegroundSignal
import com.pairseedkmp.ui.debug.ShellContext
import com.pairseedkmp.ui.debug.panel.DebugPanelDeps
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 壳层的整条栈（driver → 库 → 仓库 → 事件核），调试壳从这里拿依赖；手动接线（无 DI）。
 * "清空数据重来" = 关掉旧库 → 删库文件 → 整条重建：数据与内存态（结算会话锚点、已裁决轮）一起归零。
 */
class ShellHolder(private val context: Context) {

    private var driver: SqlDriver? = null
    private var current: DebugShellDeps? = null

    // 面板用的 HTTP 客户端：懒建、跨「清空重来」复用（重建栈不重复建连接池）
    private val materialHttpClient by lazy { SeedMaterialHttpClientFactory.create() }
    private val llmHttpClient by lazy { LlmHttpClientFactory.create() }

    /** 当前依赖（壳层自己的 onResume 会话锚点等要用）。 */
    val deps: DebugShellDeps get() = checkNotNull(current) { "ShellHolder 还没 start()" }

    /** 建栈 + 首启自动种（幂等：种过就不动）。 */
    fun start(): DebugShellDeps = buildStack().also { current = it }

    /** 清空数据重来：见类注释；返回重建后的新依赖。 */
    fun clearAndReseed(): DebugShellDeps {
        driver?.close()
        driver = null
        context.deleteDatabase(DATABASE_FILE_NAME)
        return start()
    }

    private fun buildStack(): DebugShellDeps {
        val newDriver = DatabaseDriverFactory(context).createDriver()
        driver = newDriver
        val database = PairSeedDatabase(newDriver)
        DebugSeedProvider.ensureSeeded(context, database, System.currentTimeMillis())

        val eventRepository = EventRepositoryImpl(database)
        val confirmationRepository = UserConfirmationRepositoryImpl(database)
        val templateRepository = SemiFixedTemplateRepositoryImpl(database)
        val profileRepository = RuntimeProfileRepositoryImpl(database)
        val durations = BehaviorDurationSourceImpl(database)
        val settingsStore = AiSettingsStoreImpl(context)
        val secretStore = KeystoreSecretStore(context)

        // 面板与自动链共用的读侧 / 素材源 / client 构造（同一批实例、同一条规则）
        val seedRepository = SeedRepositoryImpl(database)
        val contextSource = EventContextSourceImpl(database)
        val snapshotSource = JudgmentSnapshotSourceImpl(database)
        val materialSources = listOf(
            HitokotoMaterialSource(materialHttpClient),
            ZhihuDailyMaterialSource(materialHttpClient),
        )
        val llmClientFor: suspend (AiSettings) -> LlmClient? = { settings ->
            when (settings.mode) {
                AiMode.PRESET -> PresetLlmClient()
                AiMode.API_KEY -> ApiKeyLlmClient(llmHttpClient, settingsStore, secretStore)
                AiMode.ON_DEVICE -> null // 端侧未接入（02 spike 遗留）
            }
        }

        // 调试面板（07）：debug 通道开才装配；release = null，页尾不渲染面板
        val panel = if (DebugSeedProvider.ENABLED) {
            DebugPanelDeps(
                seedRepository = seedRepository,
                eventRepository = eventRepository,
                durations = durations,
                contextSource = contextSource,
                snapshotSource = snapshotSource,
                materialSources = materialSources,
                settingsStore = settingsStore,
                secretStore = secretStore,
                profileRepository = profileRepository,
                llmClientFor = llmClientFor,
                promptStore = DebugPromptOverrideStore(context),
            )
        } else {
            null
        }

        // 自动链（08）：前台信号（onResume）→ 结算后跑——清单缺货补货、满仓长种子（UI 侧驱动）
        val autoGrow = AutoGrowDeps(
            seedRepository = seedRepository,
            eventRepository = eventRepository,
            durations = durations,
            contextSource = contextSource,
            snapshotSource = snapshotSource,
            materialSources = materialSources,
            settingsStore = settingsStore,
            llmClientFor = llmClientFor,
        )

        val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val stampFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        return DebugShellDeps(
            eventRepository = eventRepository,
            durations = durations,
            settler = EventSettler(eventRepository, profileRepository, durations),
            trigger = EventTrigger(eventRepository, templateRepository, profileRepository, durations),
            confirmer = EventConfirmer(eventRepository, confirmationRepository),
            context = { resolveContext(database) },
            now = { System.currentTimeMillis() },
            utcOffsetSeconds = { TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000 },
            formatClock = { clockFormat.format(Date(it)) },
            formatTime = { stampFormat.format(Date(it)) },
            debugEnabled = DebugSeedProvider.ENABLED,
            clearAndReseed = if (DebugSeedProvider.ENABLED) ::clearAndReseed else null,
            panel = panel,
            foreground = ForegroundSignal(),
            autoGrow = autoGrow,
        )
    }

    /** 解析当前角色组上下文：运行配置 → 组 → 角色（第一个 = actor、第二个 = target）→ 场景（配置指向的，没指取第一个）。 */
    private fun resolveContext(database: PairSeedDatabase): ShellContext? {
        val profile = database.runtime_profilesQueries.selectRuntimeProfile().executeAsOneOrNull() ?: return null
        val packId = profile.active_pack_id ?: return null
        val pack = database.character_packsQueries.selectCharacterPackById(packId).executeAsOneOrNull() ?: return null
        val characters = database.charactersQueries.selectCharactersByPack(packId).executeAsList()
        val actor = characters.getOrNull(0) ?: return null
        val target = characters.getOrNull(1)
        val scene = profile.active_scene_id
            ?.let { database.scenesQueries.selectSceneById(it).executeAsOneOrNull() }
            ?: database.scenesQueries.selectScenesByPack(packId).executeAsList().firstOrNull()
            ?: return null
        return ShellContext(
            packId = pack.id,
            packName = pack.pack_name,
            actorId = actor.id,
            actorName = actor.character_name,
            targetId = target?.id,
            targetName = target?.character_name,
            sceneId = scene.id,
            sceneName = scene.scene_name,
        )
    }
}
