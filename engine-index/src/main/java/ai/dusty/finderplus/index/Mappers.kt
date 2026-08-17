package ai.rightone.finderplus.index

import ai.rightone.finderplus.db.ItemState
import ai.rightone.finderplus.db.entity.MediaItemEntity
import ai.rightone.finderplus.db.entity.WorkUnitEntity
import ai.rightone.finderplus.model.MediaItem
import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.Pass

/**
 * [generation] MUST be the value from the MediaStore digest. Persisting 0 here made every subsequent
 * scan see a generation mismatch, so every file looked "changed", got purged and re-indexed — silently
 * throwing away all completed AI work on each new run.
 */
internal fun MediaItem.toEntity(now: Long, generation: Long): MediaItemEntity = MediaItemEntity(
    id = id,
    content_uri = uri,
    kind = kind.ordinal,
    display_name = displayName,
    mime = mime,
    size_bytes = sizeBytes,
    date_taken = dateTakenMs,
    date_modified = dateModified,
    media_generation = generation,
    duration_ms = durationMs,
    width = width,
    height = height,
    lat = lat,
    lon = lon,
    place = place,
    bucket_id = bucketId,
    bucket_name = bucketName,
    content_hash = null,
    index_state = ItemState.NEW,
    pipeline_version = 0,
    first_seen_at = now,
    last_scanned_at = now,
    deleted = 0,
)

internal fun passUnitsFor(itemId: Long, kind: MediaKind, now: Long): List<WorkUnitEntity> =
    Pass.forKind(kind).map { p ->
        WorkUnitEntity(
            item_id = itemId,
            pass = p.ordinal,
            state = 0, // PENDING
            priority = p.priority,
            requires_model = p.model.ordinal,
            checkpoint = null,
            attempt_count = 0,
            max_attempts = 4,
            lease_owner = null,
            lease_expires_at = null,
            pipeline_version = 0,
            last_error = null,
            updated_at = now,
        )
    }
