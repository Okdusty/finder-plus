package ai.dusty.finderplus.vision

import android.content.Context
import java.io.File

/**
 * Recognizer label map for [PaddleOcrReader], supplied as data rather than code so the OCR script is a
 * configuration choice, not a compiled-in assumption.
 *
 * Resolution order, cached after the first success:
 *  1. a runtime override file (`filesDir/ocr/rec_dict.txt`), if present — dropping a different
 *     dictionary there installs a different script with no new build;
 *  2. the bundled default asset (`assets/ocr/rec_dict.txt`), one symbol per line, UTF-8.
 *
 * Each line is one CTC label: line `i` is the symbol for class `i + 1` (class 0 is the blank). Whether
 * the model also emits spaces is decided by [PaddleOcrReader] from the model's output width, so this
 * file never encodes that — the same dictionary works whether or not a model was trained with spaces.
 */
object OcrDictionary {
    const val OVERRIDE_RELATIVE_PATH = "ocr/rec_dict.txt"
    private const val ASSET = "ocr/rec_dict.txt"

    @Volatile private var cached: List<String>? = null

    /** The recognizer symbols, or null if neither the override nor the bundled asset can be read. */
    fun symbols(context: Context): List<String>? {
        cached?.let { return it }
        val override = File(context.filesDir, OVERRIDE_RELATIVE_PATH)
        val lines = (readFile(override) ?: readAsset(context))?.takeIf { it.isNotEmpty() }
        return lines?.also { cached = it }
    }

    private fun readFile(file: File): List<String>? =
        if (file.isFile) runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() else null

    private fun readAsset(context: Context): List<String>? =
        runCatching {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }.getOrNull()
}
