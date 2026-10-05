package com.pairseedkmp.domain.repository

import com.pairseedkmp.domain.event.UserConfirmation

/** 确认记录读写端口（只插不删）。 */
interface UserConfirmationRepository {
    /** 插入一条回答，返回库分配的 id（传入的 `UserConfirmation.id` 忽略）。 */
    fun insert(confirmation: UserConfirmation): Long

    /** 该事件最新一条（改答 = 再插一条，按 answered_at 最新生效）。 */
    fun latestFor(eventId: Long): UserConfirmation?
}
