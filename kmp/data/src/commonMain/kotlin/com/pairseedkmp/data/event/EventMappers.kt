package com.pairseedkmp.data.event

import com.pairseedkmp.data.Events
import com.pairseedkmp.data.Semi_fixed_event_templates
import com.pairseedkmp.data.User_confirmations
import com.pairseedkmp.domain.common.Period
import com.pairseedkmp.domain.common.parsePeriods
import com.pairseedkmp.domain.event.ConfirmationAnswer
import com.pairseedkmp.domain.event.Event
import com.pairseedkmp.domain.event.EventOrigin
import com.pairseedkmp.domain.event.EventStatus
import com.pairseedkmp.domain.event.EventStepsJson
import com.pairseedkmp.domain.event.SemiFixedEventTemplate
import com.pairseedkmp.domain.event.UserConfirmation

// 生成行 ↔ 领域模型的映射（steps / suitable_time / 枚举都按 code 存取）：事件 / 半固定模板 / 确认记录。

internal fun Events.toDomain(): Event = Event(
    id = id,
    packId = pack_id,
    actorId = actor_id,
    targetId = target_id,
    sceneId = scene_id,
    summary = summary,
    steps = EventStepsJson.parse(steps),
    comparisonAxes = comparison_axes,
    confirmationPayload = confirmation_payload,
    suitableTime = parsePeriods(suitable_time),
    status = EventStatus.fromCode(status),
    origin = EventOrigin.fromCode(origin),
    sourceSeedId = source_seed_id,
    afterEventId = after_event_id,
    semiFixedTemplateCode = semi_fixed_template_code,
    occurredAt = occurred_at,
    durationSec = duration_sec.toInt(),
    expiresAt = expires_at,
    isDeleted = is_deleted != 0L,
    createdAt = created_at,
    updatedAt = updated_at,
)

internal fun Semi_fixed_event_templates.toDomain(): SemiFixedEventTemplate = SemiFixedEventTemplate(
    id = id,
    packId = pack_id,
    templateCode = template_code,
    nameCn = name_cn,
    summary = summary,
    steps = EventStepsJson.parse(steps),
    windowEarliest = window_earliest.toInt(),
    windowLatest = window_latest.toInt(),
    isDeleted = is_deleted != 0L,
    createdAt = created_at,
    updatedAt = updated_at,
)

internal fun User_confirmations.toDomain(): UserConfirmation = UserConfirmation(
    id = id,
    eventId = event_id,
    answeredPeriod = Period.fromCode(answered_period),
    answer = ConfirmationAnswer.fromCode(answer),
    answeredAt = answered_at,
)
