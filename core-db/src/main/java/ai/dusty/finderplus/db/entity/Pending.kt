package ai.dusty.finderplus.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A label edit made in a preview, held until the user saves their changes or the next index run applies
 * them. One row per item and label: a later edit of the same label replaces the earlier one.
 */
@Entity(tableName = "pending_label", primaryKeys = ["item_id", "label"])
data class PendingLabelEntity(
    val item_id: Long,
    val label: String,
    /** One of [ADD], [REMOVE], [ACCEPT], [DECLINE]. */
    val action: Int,
    val created_at: Long,
) {
    companion object {
        const val ADD = 1
        const val REMOVE = 2
        /** A suggested label confirmed. */
        const val ACCEPT = 3
        /** A suggested label rejected. */
        const val DECLINE = 4
    }
}

/** One measurement for the diagnostics page: how long something took, or why work paused. */
@Entity(tableName = "diag_event", indices = [Index(value = ["kind", "at"])])
data class DiagEventEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val name: String,
    val ms: Long,
    val detail: String?,
    val at: Long,
)
