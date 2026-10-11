package ai.dusty.finderplus.model

/** The three media families finder+ indexes. Ordinals are persisted in the DB — do not reorder. */
enum class MediaKind { IMAGE, VIDEO, AUDIO }

/** What kicked off an index run. Ordinals are persisted — append only. */
enum class Trigger {
    MANUAL,
    SCHEDULED,
    BOOT_RESUME,

    /** The next bounded slice of an already-running index (self-rescheduled after a cool-down). */
    CONTINUATION,
}

/** Coarse per-item indexing state, projected from an item's [Pass] work units. */
enum class IndexState { NEW, PARTIAL, DONE, FAILED, STALE }

/** A gallery file mirrored from MediaStore. */
data class MediaItem(
    val id: Long,
    val uri: String,
    val kind: MediaKind,
    val displayName: String?,
    val mime: String?,
    val sizeBytes: Long,
    val dateTakenMs: Long?,
    val dateModified: Long,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val lat: Double?,
    val lon: Double?,
    val place: String?,
    val bucketId: Long?,
    val bucketName: String?,
    val indexState: IndexState,
    val pipelineVersion: Int,
)
