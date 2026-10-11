package ai.dusty.finderplus.index

import androidx.room.withTransaction
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.entity.DocumentEntity
import ai.dusty.finderplus.db.entity.EmbeddingEntity
import ai.dusty.finderplus.db.entity.FaceEntity
import ai.dusty.finderplus.db.entity.SegmentEntity
import ai.dusty.finderplus.db.entity.TagEntity
import ai.dusty.finderplus.db.vector.Vecs
import ai.dusty.finderplus.index.pass.PartialResult
import ai.dusty.finderplus.index.pass.PassContext
import ai.dusty.finderplus.index.work.Checkpoint
import ai.dusty.finderplus.index.work.Checkpoints
import ai.dusty.finderplus.model.DocSource
import ai.dusty.finderplus.model.EmbeddingKind
import ai.dusty.finderplus.model.TagSource

/**
 * Per-unit [PassContext]. Each [emit] persists the result rows and advances the work-unit checkpoint
 * in one Room transaction, so a crash can only lose the batch not yet emitted. Partial rows are keyed
 * for idempotency (§6), so redoing that batch converges.
 */
internal class ResultWriter(
    private val db: FinderDatabase,
    private val itemId: Long,
    private val unitId: Long,
    private val leaseMs: Long,
    private val stop: StopSignal,
    /**
     * When this slice must give up the CPU. Handlers see it through [isStopRequested], so a
     * long-running checkpointed pass yields at its next safe boundary.
     */
    private val deadline: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : PassContext {

    /**
     * True for a user stop or an exhausted slice budget. The drain loop only checks the deadline
     * before claiming a unit, so a long checkpointed pass (a transcription, say) must yield here;
     * otherwise JobScheduler kills the worker, which uses up one of the few job timeouts allowed per
     * day and gets the app's work deferred. A pass that honours this returns STOPPED with its
     * checkpoint intact.
     */
    override suspend fun isStopRequested(): Boolean = now() >= deadline || stop.isStopRequested()

    override suspend fun emit(result: PartialResult, next: Checkpoint) {
        db.withTransaction {
            writeResult(result)
            val cursor = Checkpoints.encode(next)
            if (cursor != null) db.workUnitDao().checkpoint(unitId, cursor, now(), leaseMs)
        }
    }

    private suspend fun writeResult(result: PartialResult) {
        val content = db.contentDao()
        when (result) {
            is PartialResult.Meta ->
                db.mediaItemDao().setGeo(itemId, result.place, result.lat, result.lon)

            is PartialResult.LabelTags -> {
                for (source in result.tags.map { it.source }.toSet() + result.clearSources) {
                    content.clearTags(itemId, source.ordinal)
                }
                content.insertTags(result.tags.map {
                    TagEntity(item_id = itemId, source = it.source.ordinal, label = it.label, confidence = it.confidence)
                })
                result.questions?.let { db.labelQuestionDao().replacePending(itemId, it) }
                result.regions?.let { r ->
                    db.objectRegionDao().replaceForItem(itemId, r.map { (label, v) ->
                        ai.dusty.finderplus.db.entity.ObjectRegionEntity(itemId, label, ai.dusty.finderplus.db.vector.Vecs.toBytes(v), result.regionsAtMs)
                    })
                }
            }

            is PartialResult.OcrText -> {
                val docId = ensureDoc(DocSource.OCR)
                content.setDocumentText(docId, result.text, result.lang)
                content.clearTags(itemId, TagSource.OCR_KEYWORD.ordinal)
                content.insertTags(result.keywords.map {
                    TagEntity(item_id = itemId, source = TagSource.OCR_KEYWORD.ordinal, label = it, confidence = 1f)
                })
            }

            is PartialResult.TranscriptSegments -> {
                val docId = ensureDoc(DocSource.TRANSCRIPT)
                content.insertSegments(result.segments.map {
                    SegmentEntity(document_id = docId, item_id = itemId, source_ref = TRANSCRIPT_STREAM,
                        start_ms = it.startMs, end_ms = it.endMs, text = it.text)
                })
            }

            // Both vector writes reject degenerate output rather than trusting the encoder. A zero
            // vector has cosine 0 with everything, so it injects arbitrary ordering into similarity
            // search, and nothing downstream can tell it from a real one. Storing nothing is
            // recoverable; storing zeros costs a full re-embed.
            is PartialResult.ImageVector ->
                if (usable(result.vec)) {
                    db.embeddingDao().insert(listOf(embedding(EmbeddingKind.IMAGE_CLIP, result.sourceRef, result.vec, result.modelId)))
                } else dropped("image", result.modelId)

            is PartialResult.TextVectors -> {
                // Replaced as a set, which also removes any degenerate rows written earlier.
                db.embeddingDao().clear(itemId, EmbeddingKind.TEXT.ordinal)
                val rows = result.vectors.mapIndexedNotNull { i, v ->
                    when {
                        v.isEmpty() -> null // nothing to embed at this slot (an item with no text)
                        usable(v) -> embedding(EmbeddingKind.TEXT, i, v, result.modelId, result.startsMs.getOrNull(i))
                        else -> { dropped("text", result.modelId); null }
                    }
                }
                if (rows.isNotEmpty()) db.embeddingDao().insert(rows)
            }

            is PartialResult.Caption -> {
                // Stored as a document rather than a tag: a sentence has words worth full-text
                // matching individually, and the profile builder quotes documents, not labels.
                val docId = ensureDoc(DocSource.CAPTION)
                content.setDocumentText(docId, result.text, null)
                content.setDocumentTime(docId, result.atMs)
            }

            is PartialResult.Faces -> {
                // Replace this item's faces so a re-run converges rather than accumulating duplicates.
                db.faceDao().clearFaces(itemId)
                db.faceDao().insertFaces(result.faces.map {
                    FaceEntity(
                        item_id = itemId,
                        box_left = it.left, box_top = it.top, box_right = it.right, box_bottom = it.bottom,
                        area_ratio = it.areaRatio, smiling = it.smiling, eyes_open = it.eyesOpen,
                        embedding = null, cluster_id = null, created_at = now(),
                    )
                })
                // The face pass owns the FACE source outright, so a re-run replaces it wholesale.
                content.clearTags(itemId, TagSource.FACE.ordinal)
                if (result.tags.isNotEmpty()) {
                    content.insertTags(result.tags.map {
                        TagEntity(item_id = itemId, source = it.source.ordinal, label = it.label, confidence = it.confidence)
                    })
                }
            }

            is PartialResult.Frame -> {
                if (!result.ocrText.isNullOrBlank()) {
                    val docId = ensureDoc(DocSource.OCR)
                    content.insertSegments(listOf(
                        SegmentEntity(document_id = docId, item_id = itemId, source_ref = result.frameIndex + KEYFRAME_STREAM_BASE,
                            start_ms = result.timestampMs, end_ms = result.timestampMs, text = result.ocrText)
                    ))
                }
                if (result.vec != null) {
                    db.embeddingDao().insert(listOf(embedding(EmbeddingKind.IMAGE_CLIP, result.frameIndex, result.vec, result.modelId, result.timestampMs)))
                }
            }
        }
    }

    private fun usable(vec: FloatArray): Boolean = vec.isNotEmpty() && vec.any { it != 0f }

    private fun dropped(kind: String, modelId: String) {
        android.util.Log.w("finderWriter", "dropped degenerate $kind vector for item $itemId from '$modelId'")
    }

    private suspend fun ensureDoc(source: DocSource): Long {
        db.contentDao().document(itemId, source.ordinal)?.let { return it.id }
        return db.contentDao().upsertDocument(
            DocumentEntity(item_id = itemId, source = source.ordinal, lang = null, text = "", created_at = now())
        )
    }

    private fun embedding(kind: EmbeddingKind, sourceRef: Int, vec: FloatArray, modelId: String, tsMs: Long? = null): EmbeddingEntity {
        val normalized = Vecs.normalized(vec)
        return EmbeddingEntity(item_id = itemId, kind = kind.ordinal, source_ref = sourceRef,
            dim = normalized.size, model_id = modelId, vec = Vecs.toBytes(normalized), ts_ms = tsMs)
    }

    private companion object {
        const val TRANSCRIPT_STREAM = SegmentEntity.TRANSCRIPT_STREAM
        const val KEYFRAME_STREAM_BASE = SegmentEntity.KEYFRAME_STREAM_BASE
    }
}
