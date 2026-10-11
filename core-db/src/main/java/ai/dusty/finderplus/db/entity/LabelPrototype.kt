package ai.dusty.finderplus.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A learned prototype for one user label, in CLIP embedding space.
 *
 * This is the same mechanism as face clustering, generalized past faces: the backbone model is never
 * retrained. Naming a photo stores that photo's frozen image embedding as an **exemplar**, and the
 * running mean of a label's exemplars becomes its prototype. Any other photo whose embedding is close
 * to that prototype can then be suggested the same label — which is nearest-class-mean classification,
 * works from a single example, and costs a dot product rather than a training run.
 *
 * [negativeCentroid] accumulates rejected suggestions, so saying "no" actually teaches the label what
 * it is *not* instead of being discarded.
 */
@Entity(tableName = "label_prototype", indices = [Index(value = ["label"], unique = true)])
data class LabelPrototypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** Running mean of positive exemplars, L2-normalized. */
    val centroid: ByteArray,
    val exemplar_count: Int,
    val negative_centroid: ByteArray?,
    val negative_count: Int,
    val updated_at: Long,
    /**
     * CLIP **text** embedding of the label's own name ("a photo of a bicycle"), which encodes what the
     * backbone already knows about the concept. Kept separately from [centroid] so the world prior is
     * never overwritten by the user's exemplars — it is blended with them at scoring time, with its
     * weight decaying as real examples accumulate. Null for labels taught before a text tower existed.
     */
    val text_prior: ByteArray? = null,
    /** 0 = seeded from the zero-shot vocabulary, 1 = taught by the user. */
    @ColumnInfo(defaultValue = "0") val origin: Int = ORIGIN_SEED,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LabelPrototypeEntity) return false
        return id == other.id && label == other.label && exemplar_count == other.exemplar_count
    }
    override fun hashCode(): Int = (id.hashCode() * 31 + label.hashCode()) * 31 + exemplar_count

    companion object {
        const val ORIGIN_SEED = 0
        const val ORIGIN_TAUGHT = 1
    }
}
