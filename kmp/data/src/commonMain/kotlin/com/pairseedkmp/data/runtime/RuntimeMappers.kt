package com.pairseedkmp.data.runtime

import com.pairseedkmp.data.Runtime_profiles
import com.pairseedkmp.domain.runtime.RuntimeProfile

// 生成行 ↔ 领域模型的映射：运行配置。

internal fun Runtime_profiles.toDomain(): RuntimeProfile = RuntimeProfile(
    id = id,
    activePackId = active_pack_id,
    activeSceneId = active_scene_id,
    onDeviceModel = on_device_model,
    apiKeyConfigured = api_key_configured != 0L,
    lastSeenAt = last_seen_at,
    createdAt = created_at,
    updatedAt = updated_at,
)
