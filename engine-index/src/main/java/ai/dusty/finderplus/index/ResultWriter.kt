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
 * Per-unit [PassContext]. Each [emit] persists the result rows AND advances the work-unit checkpoint
 * inside ONE Room transaction, so a crash between emits can only lose the current, not-yet-emitted
 * micro-batch. Partial rows are keyed for idempotency (§6), so redoing that batch converges.
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
     * True for a user stop **or** an exhausted slice budget.
     *
     * Folding the deadline in here is what keeps a single long unit from overrunning the worker. The
     * drain loop checks the deadline before claiming a unit, but one transcription of a 4-minute
     * recording is many 30-second windows and can run far past it — long enough for JobScheduler to
     * kill the worker outright. That is not a graceful stop: it burns one of only three permitted job
     * timeouts per 24 hours, and after the third the platform defers the app's work heavily.
     *
     * A pass that honours this returns STOPPED with its checkpoint intact, so nothing is redone.
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

            // Both vector writes reject degenerate output at the boundary rather than trusting the
            // encoder. A zero vector is not a weak signal, it is a *poisonous* one: its cosine with
            // everything is 0, so it ties with every other zero vector and injects arbitrary ordering
            // into similarity search. This has already happened twice — a stubbed CLIP encoder wrote
            // 16,253 of them, and the text embedder is a stub returning zeros to this day — and both
            // times the cost was a full re-embed, because nothing downstream can tell a real vector
            // from a fabricated one. Storing nothing is recoverable; storing zeros is not.
            is PartialResult.ImageVector ->
                if (usable(result.vec)) {
                    db.embeddingDao().insert(listOf(embedding(EmbeddingKind.IMAGE_CLIP, result.sourceRef, result.vec, result.modelId)))
                } else dropped("image", result.modelId)

            is PartialResult.TextVector ->
                if (usable(result.vec)) {
                    db.embeddingDao().insert(listOf(embedding(EmbeddingKind.TEXT_TRANSCRIPT, result.sourceRef, result.vec, result.modelId)))
                } else dropped("text", result.modelId)

            is PartialResult.Caption -> {
                // Stored as a document rather than a tag: a sentence has words worth full-text
                // matching individually, and the profile builder quotes documents, not labels.
                val docId = ensureDoc(DocSource.CAPTION)
                content.setDocumentText(docId, result.text, null)
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
                    db.embeddingDao().insert(listOf(embedding(EmbeddingKind.IMAGE_CLIP, result.frameIndex, result.vec, result.modelId)))
                }
            }
        }
    }

    /** A vector is usable when it exists and is not uniformly zero. */
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

    private fun embedding(kind: EmbeddingKind, sourceRef: Int, vec: FloatArray, modelId: String): EmbeddingEntity {
        val normalized = Vecs.normalized(vec)
        return EmbeddingEntity(item_id = itemId, kind = kind.ordinal, source_ref = sourceRef,
            dim = normalized.size, model_id = modelId, vec = Vecs.toBytes(normalized))
    }

    private companion object {
        const val TRANSCRIPT_STREAM = 0
        const val KEYFRAME_STREAM_BASE = 1 // keyframe OCR segments use source_ref = frameIndex + 1
    }
}
