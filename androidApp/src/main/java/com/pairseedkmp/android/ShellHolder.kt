package com.pairseedkmp.android

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.pairseedkmp.data.DATABASE_FILE_NAME
import com.pairseedkmp.data.DatabaseDriverFactory
import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.event_impl.BehaviorDurationSourceImpl
import com.pairseedkmp.data.event_impl.EventRepositoryImpl
import com.pairseedkmp.data.event_impl.SemiFixedTemplateRepositoryImpl
import com.pairseedkmp.data.event_impl.UserConfirmationRepositoryImpl
import com.pairseedkmp.data.runtime_impl.RuntimeProfileRepositoryImpl
import com.pairseedkmp.domain.event.EventConfirmer
import com.pairseedkmp.domain.event.EventSettler
import com.pairseedkmp.domain.event.EventTrigger
import com.pairseedkmp.ui.debug.DebugShellDeps
import com.pairseedkmp.ui.debug.ShellContext
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
        DebugSeedProvider.ensureSeeded(database, System.currentTimeMillis())

        val eventRepository = EventRepositoryImpl(database)
        val confirmationRepository = UserConfirmationRepositoryImpl(database)
        val templateRepository = SemiFixedTemplateRepositoryImpl(database)
        val profileRepository = RuntimeProfileRepositoryImpl(database)
        val durations = BehaviorDurationSourceImpl(database)

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
