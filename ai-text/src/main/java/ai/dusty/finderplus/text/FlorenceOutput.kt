package ai.dusty.finderplus.text

import ai.dusty.finderplus.vision.DetectedRegion

/**
 * Florence-2's object output, read at token level: label text followed by one or more boxes, each
 * box four `<loc_N>` tokens (left, top, right, bottom) quantized into [bins] steps across the image.
 *
 * Kept free of ONNX and Android so the parsing is unit-tested.
 */
object FlorenceOutput {

    /**
     * Regions from generated tokens. [locOf] maps a token id to its bin (or -1 for ordinary text),
     * [text] decodes a run of text tokens, and a label's confidence is the probability the model gave
     * its text tokens together. Boxes are in a [width] × [height] image.
     */
    fun regions(
        ids: IntArray,
        probs: FloatArray,
        locOf: (Int) -> Int,
        text: (IntArray) -> String,
        width: Int,
        height: Int,
        bins: Int = 1000,
    ): List<DetectedRegion> {
        val out = ArrayList<DetectedRegion>()
        var label = ""
        var confidence = 0f
        val textIds = ArrayList<Int>()
        var textProb = 1f
        val locs = ArrayList<Int>(4)
        for (i in ids.indices) {
            val loc = locOf(ids[i])
            if (loc < 0) {
                textIds += ids[i]; textProb *= probs[i]
                locs.clear()
                continue
            }
            if (textIds.isNotEmpty()) {
                label = text(textIds.toIntArray()).trim()
                confidence = textProb
                textIds.clear(); textProb = 1f
            }
            locs += loc
            if (locs.size == 4) {
                if (label.isNotEmpty()) {
                    fun x(b: Int) = (((b + 0.5f) / bins) * width).toInt().coerceIn(0, width)
                    fun y(b: Int) = (((b + 0.5f) / bins) * height).toInt().coerceIn(0, height)
                    val l = x(locs[0]); val t = y(locs[1]); val r = x(locs[2]); val b = y(locs[3])
                    if (r > l && b > t) out += DetectedRegion(label, confidence, l, t, r, b)
                }
                locs.clear()
            }
        }
        return out
    }

    /**
     * The `no_repeat_ngram_size` rule: tokens that would repeat an n-gram already in [seq]. Returns the
     * banned next tokens for the current prefix.
     */
    fun bannedNext(seq: List<Int>, n: Int): Set<Int> {
        if (n <= 0 || seq.size < n - 1) return emptySet()
        if (n == 1) return seq.toSet()
        val prefix = seq.subList(seq.size - (n - 1), seq.size)
        val out = HashSet<Int>()
        for (start in 0..seq.size - n) {
            if (seq.subList(start, start + n - 1) == prefix) out += seq[start + n - 1]
        }
        return out
    }
}
