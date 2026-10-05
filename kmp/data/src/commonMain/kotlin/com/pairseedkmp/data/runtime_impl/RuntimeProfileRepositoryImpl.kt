package com.pairseedkmp.data.runtime_impl

import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.runtime.toDomain
import com.pairseedkmp.domain.repository.RuntimeProfileRepository
import com.pairseedkmp.domain.runtime.RuntimeProfile

/** [RuntimeProfileRepository] 的 SQLDelight 实现（纯单机一套，单行）。 */
class RuntimeProfileRepositoryImpl(private val database: PairSeedDatabase) : RuntimeProfileRepository {

    private val queries get() = database.runtime_profilesQueries

    override fun current(): RuntimeProfile? =
        queries.selectRuntimeProfile().executeAsOneOrNull()?.toDomain()

    override fun insert(profile: RuntimeProfile): Long {
        queries.insertRuntimeProfile(
            active_pack_id = profile.activePackId,
            active_scene_id = profile.activeSceneId,
            on_device_model = profile.onDeviceModel,
            api_key_configured = if (profile.apiKeyConfigured) 1L else 0L,
            last_seen_at = profile.lastSeenAt,
            created_at = profile.createdAt,
            updated_at = profile.updatedAt,
        )
        return queries.lastInsertedRuntimeProfileId().executeAsOne()
    }

    override fun heartbeat(id: Long, now: Long) {
        queries.updateRuntimeProfileHeartbeat(last_seen_at = now, updated_at = now, id = id)
    }
}
