package ai.dusty.finderplus.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A detector label put to the user, and their answer once given.
 *
 * Pending rows (`verdict` null) are rewritten whenever the item is re-detected. Answered rows are the
 * user's supervision: they outlive re-indexing and resets, so a label the user rejected never comes
 * back and a confirmed one is never asked again. No foreign key for that reason — item ids are
 * MediaStore ids and stay stable across a reset.
 */
@Entity(
    tableName = "label_question",
    primaryKeys = ["item_id", "label"],
    indices = [Index(value = ["label"])],
)
data class LabelQuestionEntity(
    val item_id: Long,
    val label: String,
    /** Detector confidence when the question was raised. */
    val confidence: Float,
    /** Bitmask of the reasons the label was doubted; see `ai.dusty.finderplus.index.Doubt`. */
    val reasons: Int,
    /** What an independent model read instead, when that was a reason. */
    val alternative: String?,
    /** Null while pending, [YES] or [NO] once answered. */
    val verdict: Int?,
    val updated_at: Long,
) {
    companion object {
        const val NO = 0
        const val YES = 1
    }
}
