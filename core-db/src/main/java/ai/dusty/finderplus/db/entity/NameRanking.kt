package ai.dusty.finderplus.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One name from a photo's group of candidates, as the user ranked it. Each ranking is a group of
 * outputs scored relative to each other, the form group-relative policy optimization (GRPO) learns
 * from. Like answers to questions, rows are the user's supervision and outlive re-indexing, so there
 * is no foreign key.
 */
@Entity(
    tableName = "name_ranking",
    primaryKeys = ["item_id", "label"],
    indices = [Index(value = ["label"])],
)
data class NameRankingEntity(
    val item_id: Long,
    val label: String,
    /** 1 for the best name; null for a name the user left unchosen. */
    val rank: Int?,
    /** 1 for the first chosen name down to 1/k for the k-th; 0 unchosen. */
    val reward: Float,
    /** [reward] relative to the group: minus the group's mean, over its standard deviation. */
    val advantage: Float,
    val group_size: Int,
    val created_at: Long,
)
