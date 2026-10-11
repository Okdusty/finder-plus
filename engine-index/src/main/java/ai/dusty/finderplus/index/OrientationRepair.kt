package ai.dusty.finderplus.index

import android.content.Context
import android.provider.MediaStore
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.model.Pass

/**
 * One-time repair for indexes built before image passes applied EXIF orientation. Only photos that
 * MediaStore reports as rotated are requeued; the rest were read correctly. OBJECTS and FACES are not
 * listed because their version bump re-runs them everywhere.
 */
internal object OrientationRepair {

    private const val PREFS = "finder-index-repairs"
    private const val DONE = "orientation_v1"
    private val PASSES = listOf(Pass.OCR, Pass.IMAGE_EMBED, Pass.CONCEPTS, Pass.TEXT_EMBED)

    suspend fun runOnce(context: Context, db: FinderDatabase, now: Long): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) return 0
        val rotated = ArrayList<Long>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.ORIENTATION} != 0", null, null,
            )?.use { c -> while (c.moveToNext()) rotated += c.getLong(0) }
        }.onFailure { return 0 } // try again next scan rather than mark a repair that never ran
        var requeued = 0
        for (pass in PASSES) for (chunk in rotated.chunked(500)) {
            requeued += db.workUnitDao().requeueForItems(pass.ordinal, chunk, now)
        }
        prefs.edit().putBoolean(DONE, true).apply()
        android.util.Log.i("finderIndex", "orientation repair: ${rotated.size} rotated photos, $requeued units requeued")
        return requeued
    }
}
