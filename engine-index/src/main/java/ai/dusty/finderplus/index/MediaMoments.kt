package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.toMediaItem
import ai.dusty.finderplus.db.vector.Vecs
import ai.dusty.finderplus.index.pass.KeyframesPassHandler
import ai.dusty.finderplus.media.FrameExtractor
import ai.dusty.finderplus.model.EmbeddingKind
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.Segment

/**
 * Fills in where keyframe and transcript-window vectors were taken, for indexes built before that was
 * stored. Keyframe times are recomputed from the video's sync samples (nothing is decoded), window
 * starts from the transcript, both exactly as indexing computes them. A time that cannot be found is
 * stored as [UNKNOWN] so the item is not retried every run.
 */
internal object MediaMoments {

    const val UNKNOWN = -1L

    /**
     * Of a video's keyframes, given as time and unit-length vector, the time of the most typical one:
     * the frame closest to the mean of all of them, so that one frame stands for what most of the video
     * shows. Null with no frames.
     */
    fun typical(frames: List<Pair<Long, FloatArray>>): Long? {
        val dim = frames.firstOrNull()?.second?.size ?: return null
        val same = frames.filter { it.second.size == dim }
        val mean = FloatArray(dim)
        for ((_, v) in same) for (i in 0 until dim) mean[i] += v[i]
        return same.maxByOrNull { (_, v) -> Vecs.dot(v, mean) }?.first
    }

    /** @return how many items were filled in; bounded by [limit] per kind so a run is not held up. */
    suspend fun backfill(db: FinderDatabase, frames: FrameExtractor, limit: Int = 200): Int {
        val dao = db.embeddingDao()
        var done = 0
        val clip = EmbeddingKind.IMAGE_CLIP.ordinal
        for (id in dao.itemsMissingTimes(clip, listOf(MediaKind.VIDEO.ordinal), 0, limit)) {
            val item = db.mediaItemDao().byId(id)?.toMediaItem() ?: continue
            // The rate and limit those keyframes were taken with, before either was a setting.
            val total = runCatching {
                frames.frameCount(item, KeyframesPassHandler.MAX_FRAMES, ai.dusty.finderplus.settings.MediaSettings.VIDEO_FPS.default)
            }.getOrDefault(0)
            for (ref in dao.sourceRefs(id, clip)) {
                val ts = if (total > 0) runCatching { frames.timestampOf(item, ref, total) }.getOrNull() else null
                dao.setTime(id, clip, ref, ts ?: UNKNOWN)
            }
            done++
        }
        val text = EmbeddingKind.TEXT.ordinal
        for (id in dao.itemsMissingTimes(text, listOf(MediaKind.VIDEO.ordinal, MediaKind.AUDIO.ordinal), 1, limit)) {
            val windows = TranscriptWindows.of(
                db.contentDao().speechSegments(id).map { Segment(it.item_id, it.start_ms, it.end_ms, it.text) },
            )
            // Index 0 is the item's document; window i is at index i.
            for (ref in dao.sourceRefs(id, text)) if (ref >= 1) dao.setTime(id, text, ref, windows.getOrNull(ref - 1)?.first ?: UNKNOWN)
            done++
        }
        return done
    }
}
