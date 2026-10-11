package ai.dusty.finderplus.db

/**
 * Integer codes persisted in the DB. These mirror the domain enums by ordinal; centralizing them
 * keeps the DAO SQL readable and prevents magic numbers from drifting. Do not renumber.
 */
object WorkState {
    const val PENDING = 0
    const val CLAIMED = 1
    const val RUNNING = 2
    const val DONE = 3
    const val FAILED = 4
    const val SKIPPED = 5
}

/** Mirrors [ai.dusty.finderplus.model.IndexState] ordinals. */
object ItemState {
    const val NEW = 0
    const val PARTIAL = 1
    const val DONE = 2
    const val FAILED = 3
    const val STALE = 4
}

/** Mirrors [ai.dusty.finderplus.model.RunStatus] ordinals. */
object RunState {
    const val IDLE = 0
    const val SCANNING = 1
    const val RUNNING = 2
    const val PAUSED = 3
    const val STOPPING = 4
    const val STOPPED = 5
    const val DONE = 6
    const val FAILED = 7
}
