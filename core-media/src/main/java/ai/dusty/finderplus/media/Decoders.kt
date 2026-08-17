package ai.dusty.finderplus.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.os.Build
import android.media.MediaFormat
import java.nio.ByteOrder
import android.media.MediaMetadataRetriever
import android.net.Uri
import ai.dusty.finderplus.model.MediaItem

/** A single extracted video frame plus the source timestamp (drives "where in the video" hits). */
data class Keyframe(
    val index: Int,
    val timestampMs: Long,
    val bitmap: Bitmap,
    /** Variance-of-Laplacian focus measure; higher is sharper. Recorded for observability. */
    val sharpness: Double = 0.0,
)

/** One sync (I-)frame located by walking the container: when it is, and how many bytes it took. */
internal data class SyncSample(val timeUs: Long, val size: Int)

/**
 * Adaptive keyframe extraction. The engine calls [frameCount] once, then [frameAt] per index so it
 * can commit + checkpoint after each frame and resume mid-video. See docs/design/03-AI-PIPELINE.md §2.
 */
interface FrameExtractor {
    fun frameCount(item: MediaItem, maxFrames: Int): Int
    fun frameAt(item: MediaItem, index: Int, total: Int): Keyframe?
}

/**
 * Keyframe extraction tuned for throughput. Three things make video sampling cheap enough to run over
 * a whole gallery — see docs/design/10-VIDEO-THROUGHPUT.md:
 *
 * 1. **One retriever per video, not per frame.** Opening/closing `MediaMetadataRetriever` for every
 *    frame re-parses the container each time. It is now opened once and reused across the video's
 *    frames, then released.
 * 2. **Decode straight to the analysis size.** `getScaledFrameAtTime` lets the decoder emit a small
 *    bitmap instead of decoding 4K and downscaling afterwards — a large saving per frame in both time
 *    and memory, since every consumer (ML Kit, CLIP) wants ≤1024 px anyway.
 * 3. **Skip near-duplicate frames.** A perceptual hash (64-bit dHash) of each frame is compared with
 *    the previous one; static scenes are dropped before any model runs. Most video is highly redundant
 *    at 1 frame/s, so this removes the bulk of the inference work rather than the decode work.
 */
class AndroidFrameExtractor(
    private val context: Context,
    /** Target long edge for extracted frames; matches the image pipeline's analysis size. */
    private val targetEdgePx: Int = 512,
    /** Nominal sampling rate. ~1 fps is the useful ceiling for scene-level classification. */
    private val samplesPerSecond: Double = 1.0,
) : FrameExtractor, AutoCloseable {

    private var retriever: MediaMetadataRetriever? = null
    private var openUri: String? = null
    private var lastHash: Long? = null
    private var lastHashUri: String? = null
    private var syncUri: String? = null
    private var syncSamples: List<SyncSample> = emptyList()

    override fun frameCount(item: MediaItem, maxFrames: Int): Int {
        val syncs = syncSamplesFor(item.uri)
        if (syncs.size >= 2) {
            // Sample the video's own scene structure: never ask for more frames than there are
            // I-frames, since anything else would decode the same I-frame twice.
            val byRate = item.durationMs?.let { (it / 1000.0 * samplesPerSecond).toInt() } ?: syncs.size
            return minOf(syncs.size, maxFrames, byRate.coerceAtLeast(1))
        }
        // Fallback: containers with no usable sync metadata get the old time grid.
        val durationMs = item.durationMs ?: return 1
        val bySeconds = (durationMs / 1000.0 * samplesPerSecond).toInt()
        return bySeconds.coerceIn(1, maxFrames)
    }

    override fun frameAt(item: MediaItem, index: Int, total: Int): Keyframe? {
        val tsMs = timestampFor(item, index, total) ?: return null
        val r = openFor(item.uri) ?: return null
        return try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // Decoder-side scaling: avoids decoding full-resolution frames we would only shrink.
                r.getScaledFrameAtTime(
                    tsMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetEdgePx, targetEdgePx,
                )
            } else {
                r.getFrameAtTime(tsMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return null

            // Drop frames that are visually identical to the previous one: static scenes would
            // otherwise cost a full labeling + OCR + embedding pass each.
            val hash = PerceptualHash.dHash(bmp)
            val prev = lastHash?.takeIf { lastHashUri == item.uri }
            lastHash = hash
            lastHashUri = item.uri
            if (prev != null && PerceptualHash.hamming(prev, hash) <= DUPLICATE_DISTANCE) {
                return null // caller treats null as "nothing to index at this position"
            }
            Keyframe(index, tsMs, bmp, sharpness = Sharpness.varianceOfLaplacian(bmp))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Choose the timestamp for sample [index] of [total].
     *
     * With sync metadata: the I-frames are split into [total] contiguous buckets and the **largest**
     * I-frame in each bucket wins. Compressed size is a free sharpness proxy — a motion-blurred frame
     * has less high-frequency detail and therefore compresses smaller — so this picks the crispest
     * frame per shot **without decoding anything to compare**. Measuring focus properly would require
     * decoding every candidate, which is exactly the cost we are trying to avoid.
     */
    private fun timestampFor(item: MediaItem, index: Int, total: Int): Long? {
        val syncs = syncSamplesFor(item.uri)
        if (syncs.size >= 2 && total >= 1) {
            val bucketSize = syncs.size.toDouble() / total
            val from = (index * bucketSize).toInt().coerceIn(0, syncs.size - 1)
            val to = (((index + 1) * bucketSize).toInt()).coerceIn(from + 1, syncs.size)
            val best = syncs.subList(from, to).maxByOrNull { it.size } ?: return null
            return best.timeUs / 1000L
        }
        val durationMs = item.durationMs ?: return null
        return if (total <= 1) durationMs / 2 else durationMs * index / (total - 1).coerceAtLeast(1)
    }

    /**
     * Walk the container reading only sample *metadata* — flags, presentation time and size. This
     * decodes nothing, so locating every scene boundary in a video is cheap, and it replaces guessing
     * timestamps on a fixed grid and then snapping to the nearest sync frame (which decoded typical
     * I-frames about twice).
     */
    private fun syncSamplesFor(uri: String): List<SyncSample> {
        if (syncUri == uri) return syncSamples
        val out = ArrayList<SyncSample>(64)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, Uri.parse(uri), null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i; break
                }
            }
            if (track >= 0) {
                ex.selectTrack(track)
                while (out.size < MAX_SYNC_SAMPLES) {
                    val flags = ex.sampleFlags
                    if (flags < 0) break // end of stream
                    if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        val size = ex.sampleSize
                        out += SyncSample(ex.sampleTime, if (size > 0) size.toInt() else 0)
                    }
                    if (!ex.advance()) break
                }
            }
        } catch (_: Exception) {
            out.clear()
        } finally {
            runCatching { ex.release() }
        }
        syncUri = uri
        syncSamples = out
        return out
    }

    private fun openFor(uri: String): MediaMetadataRetriever? {
        if (openUri == uri) return retriever
        close()
        return try {
            MediaMetadataRetriever().also {
                it.setDataSource(context, Uri.parse(uri))
                retriever = it
                openUri = uri
            }
        } catch (_: Exception) {
            close(); null
        }
    }

    override fun close() {
        runCatching { retriever?.release() }
        retriever = null
        openUri = null
    }

    private companion object {
        /** Hamming distance below which two frames count as the same shot. */
        const val DUPLICATE_DISTANCE = 6

        /** Guard against pathological files: stop enumerating after this many I-frames. */
        const val MAX_SYNC_SAMPLES = 4_000
    }
}

/**
 * Variance-of-Laplacian focus measure. A blurred image has little high-frequency energy, so the
 * Laplacian's variance collapses. Computed on a downscaled grayscale copy to keep it cheap.
 */
object Sharpness {

    fun varianceOfLaplacian(bitmap: Bitmap, side: Int = 96): Double {
        val small = Bitmap.createScaledBitmap(bitmap, side, side, true)
        val px = IntArray(side * side)
        small.getPixels(px, 0, side, 0, 0, side, side)
        if (small != bitmap) small.recycle()

        val gray = IntArray(px.size) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        // 4-neighbour Laplacian kernel over the interior.
        for (y in 1 until side - 1) {
            for (x in 1 until side - 1) {
                val i = y * side + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - side] + gray[i + side] - 4 * gray[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return (sumSq / n) - (mean * mean)
    }
}

/** 64-bit difference hash — cheap, rotation-agnostic enough, and good at spotting static shots. */
object PerceptualHash {

    fun dHash(bitmap: Bitmap): Long {
        // 9x8 grayscale: each row yields 8 comparisons -> 64 bits.
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            var prev = luma(small.getPixel(0, y))
            for (x in 1 until 9) {
                val cur = luma(small.getPixel(x, y))
                if (cur > prev) hash = hash or (1L shl bit)
                prev = cur
                bit++
            }
        }
        if (small != bitmap) small.recycle()
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

/** Decoded 16 kHz mono PCM for whisper, delivered chunk-by-chunk so ASR is resumable. */
interface PcmDecoder {
    /**
     * Decode [item]'s audio from [fromMs] as ~[chunkMs] windows of 16 kHz mono float samples.
     * The engine consumes one chunk, commits its transcript, advances the checkpoint, then pulls the
     * next — so a kill loses at most one chunk. Implementation: MediaExtractor + MediaCodec + resample.
     */
    suspend fun decodeChunks(
        item: MediaItem,
        fromMs: Long,
        chunkMs: Long,
        onChunk: suspend (startMs: Long, endMs: Long, samples16k: FloatArray) -> Boolean,
    )
}

interface ThumbnailLoader {
    /** Small bitmap for a search result card. */
    fun thumbnail(item: MediaItem, sizePx: Int): Bitmap?
}

/**
 * MediaExtractor + MediaCodec → 16 kHz mono float PCM, delivered in ~[chunkMs] windows.
 *
 * ASR models expect 16 kHz mono (`mtmd_get_audio_sample_rate` reports 16000), while gallery media is
 * typically 44.1/48 kHz stereo AAC. This decodes, downmixes and resamples in a streaming fashion so
 * memory stays bounded regardless of file length, and hands each window to [onChunk] — which the
 * engine commits together with the resume checkpoint. Returning false from [onChunk] stops promptly,
 * giving cooperative stop a window-level boundary.
 */
class AndroidPcmDecoder(private val context: Context) : PcmDecoder {

    override suspend fun decodeChunks(
        item: MediaItem,
        fromMs: Long,
        chunkMs: Long,
        onChunk: suspend (startMs: Long, endMs: Long, samples16k: FloatArray) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(item.uri), null)

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) return // no audio track (silent video)

            extractor.selectTrack(track)
            if (fromMs > 0) extractor.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Source-rate mono accumulator; one window's worth is resampled at a time.
            val neededSrc = (chunkMs * srcRate / 1000L).toInt().coerceAtLeast(1)
            var acc = FloatArray(neededSrc * 2)
            var accLen = 0
            var windowStartMs = fromMs
            var sawEos = false
            val info = MediaCodec.BufferInfo()

            while (true) {
                // ---- feed ----
                if (!sawEos) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // ---- drain ----
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        val mono = toMonoFloat(out, info, codec.outputFormat, channels)
                        if (accLen + mono.size > acc.size) {
                            acc = acc.copyOf(maxOf(acc.size * 2, accLen + mono.size))
                        }
                        System.arraycopy(mono, 0, acc, accLen, mono.size)
                        accLen += mono.size
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outIdx, false)

                    // Emit whole windows as they become available.
                    while (accLen >= neededSrc) {
                        val window = resampleTo16k(acc, neededSrc, srcRate)
                        val keepGoing = onChunk(windowStartMs, windowStartMs + chunkMs, window)
                        System.arraycopy(acc, neededSrc, acc, 0, accLen - neededSrc)
                        accLen -= neededSrc
                        windowStartMs += chunkMs
                        if (!keepGoing) return
                    }

                    if (eos) {
                        // Trailing partial window: only worth transcribing if it holds real audio.
                        if (accLen > srcRate / 2) {
                            val window = resampleTo16k(acc, accLen, srcRate)
                            val endMs = windowStartMs + accLen * 1000L / srcRate
                            onChunk(windowStartMs, endMs, window)
                        }
                        return
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && sawEos) {
                    return // decoder produced nothing more
                }
            }
        } catch (_: Exception) {
            return // unsupported/corrupt audio: treat as "no speech" rather than failing the item
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Interleaved PCM (16-bit or float) → mono float in [-1, 1]. */
    private fun toMonoFloat(
        buf: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
        outFormat: MediaFormat,
        fallbackChannels: Int,
    ): FloatArray {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val channels = runCatching { outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
            .getOrDefault(fallbackChannels).coerceAtLeast(1)
        val encoding = runCatching { outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) }
            .getOrDefault(AudioFormat.ENCODING_PCM_16BIT)

        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frames = fb.remaining() / channels
            FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += fb.get(i * channels + c)
                sum / channels
            }
        } else {
            val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
            val frames = sb.remaining() / channels
            FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += sb.get(i * channels + c) / 32768f
                sum / channels
            }
        }
    }

    /** Linear resample to 16 kHz — ample for speech recognition and cheap enough to run per window. */
    private fun resampleTo16k(src: FloatArray, n: Int, srcRate: Int): FloatArray {
        if (srcRate == TARGET_RATE) return src.copyOf(n)
        val outN = (n.toLong() * TARGET_RATE / srcRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outN)
        val ratio = srcRate.toDouble() / TARGET_RATE
        for (i in 0 until outN) {
            val x = i * ratio
            val i0 = x.toInt().coerceIn(0, n - 1)
            val i1 = (i0 + 1).coerceAtMost(n - 1)
            val frac = (x - i0).toFloat()
            out[i] = src[i0] * (1f - frac) + src[i1] * frac
        }
        return out
    }

    private companion object {
        const val TARGET_RATE = 16_000
        const val TIMEOUT_US = 10_000L
    }
}

/** Shared bitmap decode: downscaled + EXIF-rotated, used by the image pipeline and thumbnails. */
object Bitmaps {
    fun decodeDownscaled(context: Context, uri: String, maxEdgePx: Int): Bitmap? {
        VaultCrypto.init(context)   // vaulted media decrypts inline; harmless when already set
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openMedia(resolver, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > maxEdgePx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return openMedia(resolver, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        // EXIF rotation applied by the caller via androidx.exifinterface during the METADATA pass.
    }
}
