package ai.dusty.finderplus.db

import ai.dusty.finderplus.db.entity.MediaItemEntity
import ai.dusty.finderplus.model.IndexState
import ai.dusty.finderplus.model.MediaItem
import ai.dusty.finderplus.model.MediaKind

/** Public entity → domain mapping, shared by the search and index engines. */
fun MediaItemEntity.toMediaItem(): MediaItem = MediaItem(
    id = id,
    uri = content_uri,
    kind = MediaKind.entries[kind],
    displayName = display_name,
    mime = mime,
    sizeBytes = size_bytes,
    dateTakenMs = date_taken,
    dateModified = date_modified,
    durationMs = duration_ms,
    width = width,
    height = height,
    lat = lat,
    lon = lon,
    place = place,
    bucketId = bucket_id,
    bucketName = bucket_name,
    indexState = IndexState.entries[index_state],
    pipelineVersion = pipeline_version,
)
