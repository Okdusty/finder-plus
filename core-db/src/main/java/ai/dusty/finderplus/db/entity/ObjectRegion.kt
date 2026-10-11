package ai.dusty.finderplus.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * The CLIP vector of the region a detected object name points at: a crop of its box, or the whole
 * image when the box covers it. Derived data, rewritten whenever the item is described again; what the
 * user's answers on a name are compared by.
 */
@Entity(
    tableName = "object_region",
    primaryKeys = ["item_id", "label"],
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ObjectRegionEntity(
    val item_id: Long,
    val label: String,
    /** Unit-length float32 vector, as stored for embeddings. */
    val vec: ByteArray,
    /** For a video: where in the video the described frame is. Null for photos and when not known. */
    val ts_ms: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ObjectRegionEntity && other.item_id == item_id && other.label == label &&
            other.vec.contentEquals(vec) && other.ts_ms == ts_ms
    override fun hashCode(): Int = (item_id.hashCode() * 31 + label.hashCode()) * 31 + vec.contentHashCode()
}
