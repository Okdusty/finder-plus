package ai.rightone.finderplus.index

import android.content.Context
import android.graphics.Bitmap
import ai.rightone.finderplus.media.Bitmaps

/**
 * Single-entry decoded-bitmap cache. An item's cheap passes (IMAGE_LABEL then OCR) are claimed
 * consecutively — they share a priority tier and are ordered by id — so caching just the last decode
 * halves the JPEG decode work per photo, which is a large share of the indexing CPU (and battery).
 *
 * Not thread-safe by design: the engine drains work units sequentially.
 */
class DecodedImageCache(private val context: Context) {

    private var key: String? = null
    private var cached: Bitmap? = null

    fun get(uri: String, maxEdgePx: Int): Bitmap? {
        val k = "$uri@$maxEdgePx"
        val hit = cached
        if (k == key && hit != null && !hit.isRecycled) return hit
        // Drop the reference (no explicit recycle: ML Kit may still be unwinding off-thread).
        cached = null
        key = null
        val decoded = Bitmaps.decodeDownscaled(context, uri, maxEdgePx) ?: return null
        key = k
        cached = decoded
        return decoded
    }

    fun clear() {
        cached = null
        key = null
    }
}
