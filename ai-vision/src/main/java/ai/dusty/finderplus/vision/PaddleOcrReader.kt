package ai.dusty.finderplus.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OCR with PP-OCR (PaddleOCR, Apache-2.0) via ONNX Runtime.
 *
 * No script is built in: the detector, the recognizer and the recognizer's character dictionary are
 * all resolved at call time, so any PP-OCR language pack works without changes here.
 *
 *  - Detector (`*_det.onnx`): script-agnostic. The PP-OCRv5 export accepts square input only (other
 *    shapes fail with a broadcast error), so the bitmap is letterboxed top-left onto a 640x640 black
 *    canvas and the probability map is cropped back to the content region before the DB postprocess.
 *  - Recognizer (`*_rec.onnx`): 48-high CTC. Class 0 is the blank and classes `1..N` map to dictionary
 *    symbols. Whether the model emits its own spaces (`use_space_char`) is inferred from the class
 *    count, so both kinds work without configuration.
 *  - Dictionary: one symbol per line, supplied as data. When the model does not emit spaces, they are
 *    restored from the gaps between detector word boxes.
 *
 * There is no angle classifier (PP-OCR's cls head has no open ONNX export), so text is assumed upright.
 * Preprocessing must match the reference pipeline exactly; a mismatch degrades accuracy without
 * failing. Not ready until both models and the dictionary are installed, so the OCR pass parks instead
 * of recording empty results.
 */
class PaddleOcrReader(
    private val detPath: () -> String?,
    private val recPath: () -> String?,
    /** Recognizer label map, one symbol per class 1..N. */
    private val dictionary: () -> List<String>?,
) : OcrReader {

    private val lock = Any()
    private var sessions: Pair<OrtSession, OrtSession>? = null
    /** Model paths of the last failed load; a failure is not retried until the models change. */
    private var failedFor: String? = null

    /** Decode symbols resolved to the model's class count, and whether the model spaces its own output. */
    @Volatile private var decodeSymbols: List<String>? = null
    @Volatile private var modelEmitsSpace = false

    override fun isReady(): Boolean = detPath() != null && recPath() != null && dictionary() != null

    private fun ensureSessions(): Pair<OrtSession, OrtSession>? {
        synchronized(lock) {
            sessions?.let { return it }
            val detModel = detPath() ?: return null
            val recModel = recPath() ?: return null
            val key = "$detModel|$recModel"
            if (failedFor == key) return null
            failedFor = key
            val det = OrtSessions.create(detModel, ai.dusty.finderplus.settings.AccelerationSettings.TEXT_READING) ?: return null
            val rec = OrtSessions.create(recModel, ai.dusty.finderplus.settings.AccelerationSettings.TEXT_READING)
                ?: run { det.close(); return null }
            failedFor = null
            return (det to rec).also { sessions = it }
        }
    }

    override suspend fun read(bitmap: Bitmap): OcrResult {
        val (det, rec) = ensureSessions() ?: return OcrResult("", lang = null, keywords = emptyList())
        return try {
            val lines = detect(det, bitmap)
            if (lines.isEmpty()) return OcrResult("", lang = null, keywords = emptyList())
            val sb = StringBuilder()
            for (line in lines) {
                val text = recognize(rec, bitmap, line)
                if (text.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
            OcrResult(sb.toString(), lang = null, keywords = emptyList())
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "paddle ocr read failed: ${t.message}")
            OcrResult("", lang = null, keywords = emptyList())
        }
    }

    // --- detection ---------------------------------------------------------------------------------

    /** Letterbox to 640x640, run the DB detector, and return text lines in reading order. */
    private fun detect(det: OrtSession, src: Bitmap): List<Line> {
        val w = src.width
        val h = src.height
        val scale = DET_SIDE.toFloat() / max(w, h)
        val nw = (w * scale).roundToInt().coerceIn(1, DET_SIDE)
        val nh = (h * scale).roundToInt().coerceIn(1, DET_SIDE)

        val canvas = Bitmap.createBitmap(DET_SIDE, DET_SIDE, Bitmap.Config.ARGB_8888)
        Canvas(canvas).drawBitmap(src, null, Rect(0, 0, nw, nh), null)
        val input = detTensor(canvas)
        canvas.recycle()

        val env = OrtEnvironment.getEnvironment()
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, 3, DET_SIDE.toLong(), DET_SIDE.toLong()),
        )
        val prob: Array<FloatArray> = tensor.use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { out -> OcrTensors.probabilityMap(out[0].value) }
        } ?: return emptyList()
        return dbPostprocess(prob, nw, nh, w, h)
    }

    /** BGR, /255, ImageNet-normalized, NCHW — the detector's documented input transform. */
    private fun detTensor(canvas: Bitmap): FloatArray {
        val pixels = IntArray(DET_SIDE * DET_SIDE)
        canvas.getPixels(pixels, 0, DET_SIDE, 0, 0, DET_SIDE, DET_SIDE)
        val out = FloatArray(3 * DET_SIDE * DET_SIDE)
        val plane = DET_SIDE * DET_SIDE
        for (i in pixels.indices) {
            val p = pixels[i]
            val b = (p and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val r = ((p shr 16) and 0xFF) / 255f
            out[i] = (b - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
            out[plane + i] = (g - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
            out[2 * plane + i] = (r - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
        }
        return out
    }

    /**
     * DB postprocess: threshold the probability map, label connected components, keep those whose mean
     * probability clears [BOX_THRESH], expand each by [UNCLIP] and merge overlapping rows into lines.
     * Coordinates are returned in the original image's pixel space.
     */
    private fun dbPostprocess(prob: Array<FloatArray>, nw: Int, nh: Int, w: Int, h: Int): List<Line> {
        val sx = w.toFloat() / nw
        val sy = h.toFloat() / nh
        val cells = nw * nh
        val visited = BooleanArray(cells)
        val stack = IntArray(cells)
        val comps = ArrayList<Comp>()

        for (start in 0 until cells) {
            if (visited[start] || prob[start / nw][start % nw] <= DET_THRESH) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var count = 0
            var sum = 0f
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % nw
                val cy = c / nw
                count++
                sum += prob[cy][cx]
                if (cx < x0) x0 = cx
                if (cx > x1) x1 = cx
                if (cy < y0) y0 = cy
                if (cy > y1) y1 = cy
                var dy = -1
                while (dy <= 1) {
                    var dx = -1
                    while (dx <= 1) {
                        if (dx != 0 || dy != 0) {
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx in 0 until nw && ny in 0 until nh) {
                                val nc = ny * nw + nx
                                if (!visited[nc] && prob[ny][nx] > DET_THRESH) {
                                    visited[nc] = true
                                    stack[sp++] = nc
                                }
                            }
                        }
                        dx++
                    }
                    dy++
                }
            }
            if (count < MIN_CELLS) continue
            if (sum / count <= BOX_THRESH) continue
            val cw = (x1 - x0 + 1).toFloat()
            val ch = (y1 - y0 + 1).toFloat()
            // Raw box (tight) drives space insertion; expanded box drives line grouping and crops.
            val rx0 = x0 * sx; val ry0 = y0 * sy; val rx1 = (x1 + 1) * sx; val ry1 = (y1 + 1) * sy
            if ((rx1 - rx0) * (ry1 - ry0) < MIN_AREA) continue
            val px = cw * UNCLIP; val py = ch * UNCLIP
            val ex0 = ((x0 - px).coerceAtLeast(0f)) * sx
            val ey0 = ((y0 - py).coerceAtLeast(0f)) * sy
            val ex1 = ((x1 + 1 + px).coerceAtMost(nw.toFloat())) * sx
            val ey1 = ((y1 + 1 + py).coerceAtMost(nh.toFloat())) * sy
            comps += Comp(rx0, ry0, rx1, ry1, ex0, ey0, ex1, ey1)
        }

        comps.sortWith(compareBy({ it.ey0 }, { it.ex0 }))
        val lines = ArrayList<Line>()
        for (c in comps) {
            var joined = false
            for (ln in lines) {
                val inter = min(ln.y1, c.ey1) - max(ln.y0, c.ey0)
                val union = max(ln.y1, c.ey1) - min(ln.y0, c.ey0)
                if (union > 0f && inter / union > Y_IOU) {
                    ln.add(c)
                    joined = true
                    break
                }
            }
            if (!joined) lines += Line(c)
        }
        lines.sortBy { it.y0 }
        return lines
    }

    // --- recognition -------------------------------------------------------------------------------

    /** Crop the line, run the CTC recognizer, and re-insert spaces from the detector's word gaps. */
    private fun recognize(rec: OrtSession, src: Bitmap, line: Line): String {
        val x0 = line.x0.toInt().coerceIn(0, src.width - 1)
        val y0 = line.y0.toInt().coerceIn(0, src.height - 1)
        val x1 = line.x1.roundToInt().coerceIn(x0 + 1, src.width)
        val y1 = line.y1.roundToInt().coerceIn(y0 + 1, src.height)

        val crop = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        val scaled = Bitmap.createScaledBitmap(crop, REC_W, REC_H, true)
        if (scaled != crop) crop.recycle()
        val input = recTensor(scaled)
        scaled.recycle()

        val env = OrtEnvironment.getEnvironment()
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_H.toLong(), REC_W.toLong()),
        )
        val text = tensor.use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { out ->
                val rows = OcrTensors.sequence(out[0].value) ?: return ""

                val symbols = resolveSymbols(rows.firstOrNull()?.size ?: 0) ?: return ""
                ctcDecode(rows, symbols)
            }
        }
        return if (modelEmitsSpace) text else withSpaces(text, line)
    }

    /**
     * Maps the model's output class count onto the dictionary, once. Class 0 is the CTC blank; if the
     * remaining `classCount - 1` classes are one more than the dictionary, the model was trained with
     * `use_space_char` and its last symbol is a space. Otherwise the dictionary is used as-is.
     */
    private fun resolveSymbols(classCount: Int): List<String>? {
        decodeSymbols?.let { return it }
        val dict = dictionary() ?: return null
        if (classCount <= 1) return null
        val symbolClasses = classCount - 1
        val resolved = if (symbolClasses == dict.size + 1) {
            modelEmitsSpace = true
            dict + " "
        } else {
            modelEmitsSpace = false
            dict
        }
        decodeSymbols = resolved
        return resolved
    }

    /** BGR, (x/255 - 0.5) / 0.5, NCHW — the recognizer's documented input transform. */
    private fun recTensor(bmp: Bitmap): FloatArray {
        val pixels = IntArray(REC_W * REC_H)
        bmp.getPixels(pixels, 0, REC_W, 0, 0, REC_W, REC_H)
        val out = FloatArray(3 * REC_W * REC_H)
        val plane = REC_W * REC_H
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
            out[plane + i] = ((((p shr 8) and 0xFF) / 255f) - 0.5f) / 0.5f
            out[2 * plane + i] = ((((p shr 16) and 0xFF) / 255f) - 0.5f) / 0.5f
        }
        return out
    }

    /** Greedy CTC: argmax per timestep, drop blank (index 0), collapse repeats, map through the dict. */
    private fun ctcDecode(rows: Array<FloatArray>, symbols: List<String>): String {
        val sb = StringBuilder()
        var last = 0
        for (row in rows) {
            var best = 0
            var bestP = row[0]
            for (c in 1 until row.size) {
                if (row[c] > bestP) {
                    bestP = row[c]
                    best = c
                }
            }
            if (best == 0) {
                last = 0
                continue
            }
            if (best == last) continue
            val idx = best - 1
            if (idx in symbols.indices) sb.append(symbols[idx])
            last = best
        }
        return sb.toString()
    }

    /**
     * For recognizers that emit no spaces. When the detector split a line into several word boxes, a
     * horizontal gap wider than [SPACE_GAP] of the median box height is a space, so the recovered words
     * tokenize for search. Single-box lines are returned unchanged.
     */
    private fun withSpaces(text: String, line: Line): String {
        if (text.isEmpty() || line.members.size < 2) return text
        val words = line.members.sortedBy { it.x0 }
        val heights = words.map { it.y1 - it.y0 }.sorted()
        val medianH = heights[heights.size / 2]
        val threshold = SPACE_GAP * medianH

        val gapAfterWord = BooleanArray(words.size)
        var gaps = 0
        for (i in 1 until words.size) {
            if (words[i].x0 - words[i - 1].x1 > threshold) {
                gapAfterWord[i - 1] = true
                gaps++
            }
        }
        if (gaps == 0) return text

        // Split the decoded characters across the word boxes by their share of total width, with a
        // space at each real gap. Approximate, because the CTC head gives no per-character positions.
        val totalW = words.sumOf { (it.x1 - it.x0).toDouble() }.toFloat()
        if (totalW <= 0f) return text
        val sb = StringBuilder(text.length + gaps)
        var consumed = 0
        for (i in words.indices) {
            val share = (words[i].x1 - words[i].x0) / totalW
            val end = if (i == words.size - 1) text.length else (consumed + (share * text.length)).roundToInt()
                .coerceIn(consumed, text.length)
            sb.append(text, consumed, end)
            consumed = end
            if (gapAfterWord[i] && consumed < text.length) sb.append(' ')
        }
        if (consumed < text.length) sb.append(text, consumed, text.length)
        return sb.toString()
    }

    /** A detected word box: raw (tight) corners for spacing, expanded corners for grouping/cropping. */
    private class Comp(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val ex0: Float, val ey0: Float, val ex1: Float, val ey1: Float,
    )

    /** A text line: the union of its member word boxes, kept for gap-based space insertion. */
    private class Line(first: Comp) {
        var x0 = first.ex0; var y0 = first.ey0; var x1 = first.ex1; var y1 = first.ey1
        val members = ArrayList<Word>().apply { add(Word(first.x0, first.x1, first.y0, first.y1)) }
        fun add(c: Comp) {
            x0 = min(x0, c.ex0); y0 = min(y0, c.ey0); x1 = max(x1, c.ex1); y1 = max(y1, c.ey1)
            members += Word(c.x0, c.x1, c.y0, c.y1)
        }
    }

    private class Word(val x0: Float, val x1: Float, val y0: Float, val y1: Float)

    private companion object {
        private const val TAG = "finderOcr"

        // Detector: square input, DB postprocess. Values from PP-OCR's inference config.
        private const val DET_SIDE = 640
        private const val DET_THRESH = 0.3f
        private const val BOX_THRESH = 0.6f
        private const val UNCLIP = 0.25f // half of unclip_ratio 1.5 applied to each side
        private const val MIN_CELLS = 3
        private const val MIN_AREA = 16f
        private const val Y_IOU = 0.35f
        private const val SPACE_GAP = 0.2f

        // Recognizer: 48-high CTC input; width pinned to 320 (the square-input v5 export's fixed width).
        private const val REC_W = 320
        private const val REC_H = 48

        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * Unwraps the OCR models' output tensors whatever their leading unit dimensions. PP-OCRv5 det returns
 * its probability map as `[1, 1, H, W]`, not `[1, H, W]`; a fixed-rank cast throws on every call, and
 * the failure looks like images with no text.
 */
internal object OcrTensors {

    /** `[1, 1, H, W]` or `[1, H, W]` or `[H, W]` → `[H, W]`; null for anything else. */
    fun probabilityMap(value: Any?): Array<FloatArray>? = squeezeTo2d(value)

    /** `[1, T, C]` or `[T, C]` → `[T, C]` (per-step class scores); null for anything else. */
    fun sequence(value: Any?): Array<FloatArray>? = squeezeTo2d(value)

    @Suppress("UNCHECKED_CAST")
    private fun squeezeTo2d(value: Any?): Array<FloatArray>? {
        var v: Any? = value
        while (v is Array<*>) {
            val first = v.firstOrNull() ?: return null
            if (first is FloatArray) return v as Array<FloatArray>
            if (v.size != 1) return null // a real batch or channel axis: not ours to collapse
            v = first
        }
        return null
    }
}
