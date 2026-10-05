package com.pairseedkmp.data.event_impl

import com.pairseedkmp.data.PairSeedDatabase
import com.pairseedkmp.domain.event.BehaviorDurationSource

/** [BehaviorDurationSource] 的 SQLDelight 实现（查 `behavior_templates.duration_seconds`）。 */
class BehaviorDurationSourceImpl(private val database: PairSeedDatabase) : BehaviorDurationSource {

    override fun durationSecondsFor(behaviorCode: String): Int? =
        database.behavior_templatesQueries.selectDurationSecondsByCode(behaviorCode)
            .executeAsOneOrNull()
            ?.toInt()
}
