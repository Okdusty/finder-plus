package ai.dusty.finderplus.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Inventory mirror of MediaStore. [id] IS the MediaStore `_ID` (stable per volume), so it is not
 * auto-generated. See docs/design/01-DB-ENGINE.md §2.1.
 */
@Entity(
    tableName = "media_item",
    indices = [
        Index("index_state"),
        Index("bucket_id"),
        Index("date_taken"),
    ],
)
data class MediaItemEntity(
    @PrimaryKey val id: Long,
    val content_uri: String,
    val kind: Int,
    val display_name: String?,
    val mime: String?,
    val size_bytes: Long,
    val date_taken: Long?,
    val date_modified: Long,
    val media_generation: Long,
    val duration_ms: Long?,
    val width: Int?,
    val height: Int?,
    val lat: Double?,
    val lon: Double?,
    val place: String?,
    val bucket_id: Long?,
    val bucket_name: String?,
    val content_hash: String?,
    val index_state: Int,
    val pipeline_version: Int,
    val first_seen_at: Long,
    val last_scanned_at: Long,
    val deleted: Int,
    /**
     * Set when the file has been moved into the hidden vault: the absolute path it originally lived
     * at (the restore target). While set, [content_uri] is a `file://` URI into the vault and the
     * item has no MediaStore row — invisible to every gallery, fully served by this app.
     */
    val original_path: String? = null,
)

/**
 * The resumable work ledger — the heart of the engine. One row per (item, pass). See §2.2.
 * The composite index on (state, priority, requires_model, id) is the claim hot path.
 */
@Entity(
    tableName = "work_unit",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["item_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("state", "priority", "requires_model", "id"),
        Index("item_id"),
        Index(value = ["item_id", "pass"], unique = true),
    ],
)
data class WorkUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_id: Long,
    val pass: Int,
    val state: Int,
    val priority: Int,
    val requires_model: Int,
    val checkpoint: String?,
    val attempt_count: Int,
    val max_attempts: Int,
    val lease_owner: String?,
    val lease_expires_at: Long?,
    val pipeline_version: Int,
    val last_error: String?,
    val updated_at: Long,
)

/** One row per "update" press / scheduled run. Carries the cooperative stop flag. See §2.3. */
@Entity(tableName = "index_run")
data class IndexRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trigger: Int,
    val status: Int,
    val stop_requested: Int,
    val total_units: Int,
    val done_units: Int,
    val failed_units: Int,
    val started_at: Long,
    val finished_at: Long?,
    val last_generation: Long,
)

@Entity(
    tableName = "tag",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("item_id"), Index(value = ["item_id", "source", "label"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_id: Long,
    val source: Int,
    val label: String,
    val confidence: Float,
)

@Entity(
    tableName = "document",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("item_id"), Index(value = ["item_id", "source"], unique = true)],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_id: Long,
    val source: Int,
    val lang: String?,
    val text: String,
    val created_at: Long,
)

@Entity(
    tableName = "segment",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["document_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("item_id"), Index("document_id"),
        Index(value = ["item_id", "source_ref", "start_ms"], unique = true)],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val document_id: Long,
    val item_id: Long,
    val source_ref: Int,
    val start_ms: Long,
    val end_ms: Long,
    val text: String,
)

/**
 * The consolidated "AI revision" — one long searchable text per item aggregating every pass's output
 * (name, category, labels, OCR, transcript, place, album). Rebuilt whenever a text pass completes.
 * This is the human/AI-readable content profile and the text copied to the clipboard.
 * See docs/design/05-MEDIA-PROFILE.md.
 */
@Entity(
    tableName = "media_profile",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["item_id"], unique = true)],
)
data class MediaProfileEntity(
    @PrimaryKey val item_id: Long,
    val text: String,
    val updated_at: Long,
)

/**
 * One detected face. [embedding] is null until a face-embedding model is installed; [cluster_id]
 * groups faces of the same person and is assigned by incremental clustering once embeddings exist.
 * Storing the box now means embeddings can be backfilled later without re-detecting.
 * See docs/design/09-PEOPLE-AND-VLM.md.
 */
@Entity(
    tableName = "face",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("item_id"), Index("cluster_id"),
        Index(value = ["item_id", "box_left", "box_top"], unique = true)],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_id: Long,
    val box_left: Int,
    val box_top: Int,
    val box_right: Int,
    val box_bottom: Int,
    /** Fraction of the image area, used to prefer prominent faces when clustering. */
    val area_ratio: Float,
    val smiling: Float?,
    val eyes_open: Float?,
    val embedding: ByteArray?,
    val cluster_id: Long?,
    val created_at: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEntity) return false
        return id == other.id && item_id == other.item_id &&
            box_left == other.box_left && box_top == other.box_top &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + item_id.hashCode()
        r = 31 * r + box_left
        r = 31 * r + box_top
        r = 31 * r + (embedding?.contentHashCode() ?: 0)
        return r
    }
}

/** A person = a face cluster the user can name. The name is what makes them searchable. */
@Entity(tableName = "person", indices = [Index(value = ["name"])])
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null until the user names the cluster; unnamed clusters are still groupable. */
    val name: String?,
    val cover_face_id: Long?,
    /** Running centroid of the cluster's embeddings, for cheap incremental assignment. */
    val centroid: ByteArray?,
    val face_count: Int,
    val updated_at: Long,
    /** Name the VLM suggested for this cluster — a suggestion awaiting confirmation, not a fact. */
    val proposed_name: String? = null,
    /** How many separate photos produced the same suggestion; higher means more trustworthy. */
    val proposal_votes: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonEntity) return false
        return id == other.id && name == other.name && face_count == other.face_count
    }

    override fun hashCode(): Int = (id.hashCode() * 31 + (name?.hashCode() ?: 0)) * 31 + face_count
}

@Entity(
    tableName = "embedding",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("kind"), Index("item_id"),
        Index(value = ["item_id", "kind", "source_ref"], unique = true)],
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_id: Long,
    val kind: Int,
    val source_ref: Int,
    val dim: Int,
    val model_id: String,
    val vec: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id && item_id == other.item_id && kind == other.kind &&
            source_ref == other.source_ref && vec.contentEquals(other.vec)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + item_id.hashCode()
        result = 31 * result + kind
        result = 31 * result + source_ref
        result = 31 * result + vec.contentHashCode()
        return result
    }
}

/**
 * Document frequency of one term, per vocabulary [scope].
 *
 * Exists so "is this word worth indexing?" can be answered from the gallery's own statistics instead of
 * a hard-coded word list, which cannot cover languages nobody anticipated. The corpus size for each scope
 * is stored as the row with an empty [term], so a count and its denominator can never disagree.
 */
@Entity(
    tableName = "term_df",
    primaryKeys = ["term", "scope"],
    indices = [Index(value = ["scope", "doc_count"])],
)
data class TermDfEntity(
    val term: String,
    val scope: Int,
    val doc_count: Int,
)
