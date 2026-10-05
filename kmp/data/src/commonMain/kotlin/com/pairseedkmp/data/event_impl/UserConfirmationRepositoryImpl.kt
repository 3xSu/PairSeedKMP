package com.pairseedkmp.data.event_impl

import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.data.event.toDomain
import com.pairseedkmp.domain.event.UserConfirmation
import com.pairseedkmp.domain.repository.UserConfirmationRepository

/** [UserConfirmationRepository] 的 SQLDelight 实现（只插不删）。 */
class UserConfirmationRepositoryImpl(private val database: PairSeedDatabase) : UserConfirmationRepository {

    private val queries get() = database.user_confirmationsQueries

    override fun insert(confirmation: UserConfirmation): Long {
        queries.insertUserConfirmation(
            event_id = confirmation.eventId,
            answered_period = confirmation.answeredPeriod.code,
            answer = confirmation.answer.code,
            answered_at = confirmation.answeredAt,
        )
        return queries.lastInsertedUserConfirmationId().executeAsOne()
    }

    override fun latestFor(eventId: Long): UserConfirmation? =
        queries.selectLatestUserConfirmation(eventId).executeAsOneOrNull()?.toDomain()
}
