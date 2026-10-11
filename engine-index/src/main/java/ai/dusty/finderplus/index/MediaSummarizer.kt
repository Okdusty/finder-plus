package ai.dusty.finderplus.index

import java.util.Calendar
import java.util.Locale

/** Everything already known about one media item; every field is a by-product of passes that ran. */
data class MediaEvidence(
    val displayName: String? = null,
    /** Whole-image labels (ML Kit), highest confidence first. */
    val labels: List<String> = emptyList(),
    /** Detected objects, possibly repeated across video frames. */
    val objects: List<String> = emptyList(),
    /** Open-vocabulary concepts recognized from the shipped vocabulary. */
    val concepts: List<String> = emptyList(),
    /** Labels the user taught, or that matched a prototype the user taught. Highest trust. */
    val userLabels: List<String> = emptyList(),
    val ocrText: String? = null,
    val transcript: String? = null,
    val peopleNames: List<String> = emptyList(),
    val faceCount: Int = 0,
    val place: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Capture time, epoch millis. Drives the date words that make "summer 2025" searchable. */
    val dateTakenMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val kind: String? = null,
    /**
     * Distinctive words from this item's text, already filtered by corpus frequency. Passed in because
     * deciding what is distinctive needs the whole corpus, which a per-item summarizer cannot see.
     */
    val textKeywords: List<String> = emptyList(),
)

/** The searchable artifact: a human-readable summary plus a normalized key-object list. */
data class MediaSummary(
    val summary: String,
    val keyObjects: List<String>,
)

/**
 * Fuses all available evidence about a media item into one summary and a ranked key-object list.
 *
 * Deterministic and model-free, so it improves search with no download. Much of its value is turning
 * stored numbers (capture time, duration, orientation, aspect ratio) into words a text query can
 * match. For video, how often a label recurs across frames ranks what the clip is about, and one-off
 * detections drop out.
 */
object MediaSummarizer {

    /**
     * Labels too common in this corpus to distinguish anything, supplied per call. Decided from corpus
     * counts rather than a fixed list, so it works in any language and adapts to the library: a
     * collection of food photos should not suppress `food`, one full of receipts should. See [TermStats].
     */
    fun interface CommonLabels {
        fun isCommon(label: String): Boolean

        companion object {
            /** Suppress nothing — the correct behaviour before the corpus has been counted. */
            val NONE = CommonLabels { false }
        }
    }

    private val MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val WEEKDAYS = arrayOf(
        "", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
    )

    fun summarize(
        e: MediaEvidence,
        maxObjects: Int = 12,
        commonLabels: CommonLabels = CommonLabels.NONE,
    ): MediaSummary {
        val keyObjects = rankObjects(e, maxObjects, commonLabels)
        val summary = buildString {
            // The user's own words lead: they are ground truth, and putting them first makes the
            // clipboard text read as theirs rather than as the model's guess.
            e.userLabels.distinct().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
                append("Labelled ").append(it.joinToString(", ")).append(". ")
            }
            if (keyObjects.isNotEmpty()) {
                append("Shows ").append(keyObjects.take(8).joinToString(", ")).append(". ")
            }
            peopleClause(e)?.let { append(it).append(' ') }
            mediaClause(e)?.let { append(it).append(' ') }
            e.place?.takeIf { it.isNotBlank() }?.let { append("Taken in ").append(it).append(". ") }
            whenClause(e.dateTakenMs)?.let { append(it).append(' ') }

            // Spoken and on-screen words are the highest-precision search signal: quote them verbatim
            // but bounded, so one long transcript cannot dominate the profile.
            e.transcript?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("Says: ").append(it.take(400)).append(' ')
            }
            // Distinctive words rather than a prefix of the raw text: the profile already carries the
            // full text, and a prefix is arbitrary. Falls back to the prefix until keywords exist.
            e.textKeywords.takeIf { it.isNotEmpty() }?.let {
                append("Mentions ").append(it.take(8).joinToString(", ")).append(". ")
            } ?: e.ocrText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("Text on screen: ").append(it.take(300)).append(' ')
            }
            e.album?.takeIf { it.isNotBlank() }?.let { append("Album ").append(it).append('.') }
        }.trim()

        return MediaSummary(summary = summary, keyObjects = keyObjects)
    }

    /** Capture time as words people type: year, month name, weekday, part of day, season. */
    internal fun whenClause(dateTakenMs: Long?): String? {
        if (dateTakenMs == null || dateTakenMs <= 0L) return null
        val cal = Calendar.getInstance().apply { timeInMillis = dateTakenMs }
        val year = cal.get(Calendar.YEAR)
        // A timestamp before digital photography is a bad value, not an old photo; emitting date words
        // for it would attach confident nonsense to the profile.
        if (year < 1990 || year > 2100) return null

        val month = MONTHS[cal.get(Calendar.MONTH)]
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val weekday = WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK)]
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val partOfDay = when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
        // Northern-hemisphere seasons; the device's own locale cannot tell us the hemisphere, and this
        // is a search aid rather than an astronomical claim.
        val season = when (cal.get(Calendar.MONTH)) {
            11, 0, 1 -> "winter"
            2, 3, 4 -> "spring"
            5, 6, 7 -> "summer"
            else -> "autumn"
        }
        return "Taken $weekday $day $month $year, in the $partOfDay, $season."
    }

    /** Kind, length and shape — the structural facts that separate a panorama from a screenshot. */
    private fun mediaClause(e: MediaEvidence): String? {
        val parts = ArrayList<String>(3)
        e.durationMs?.takeIf { it > 0 }?.let { parts += "Length ${humanDuration(it)}" }

        val w = e.width ?: 0
        val h = e.height ?: 0
        if (w > 0 && h > 0) {
            val ratio = maxOf(w, h).toFloat() / minOf(w, h)
            parts += when {
                ratio >= 2.2f -> "panorama"
                w > h -> "landscape orientation"
                h > w -> "portrait orientation"
                else -> "square"
            }
            parts += "${w}x$h"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.plus(".")
    }

    private fun humanDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d h %d min", h, m)
            m > 0 -> String.format(Locale.US, "%d min %d sec", m, s)
            else -> String.format(Locale.US, "%d sec", s)
        }
    }

    /**
     * Rank by how often a term was observed, then by source priority. For video this turns "dog seen in
     * 14 of 18 frames" into a headline term and demotes a single stray detection.
     */
    private fun rankObjects(e: MediaEvidence, limit: Int, commonLabels: CommonLabels): List<String> {
        val counts = LinkedHashMap<String, Int>()
        fun add(term: String, weight: Int, filterCommon: Boolean = true) {
            val key = term.trim().lowercase()
            if (key.length < 2) return
            if (filterCommon && commonLabels.isCommon(key)) return
            counts[key] = (counts[key] ?: 0) + weight
        }
        // Weighted by how specific and trustworthy each source is: user label > concept > detector
        // box > whole-image label. User labels bypass the frequency filter: a label the user applies
        // often is meant every time, and suppressing it would punish the labelling the app learns from.
        e.userLabels.forEach { add(it, 5, filterCommon = false) }
        e.concepts.forEach { add(it, 3) }
        // The people clause states who is in the frame; the detector's generic `person` would only
        // repeat it ("Shows person… One person.").
        e.objects.filterNot { it == "person" && (e.faceCount > 0 || e.peopleNames.isNotEmpty()) }.forEach { add(it, 2) }
        e.labels.forEach { add(it, 1) }

        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(limit)
    }

    private fun peopleClause(e: MediaEvidence): String? {
        if (e.peopleNames.isNotEmpty()) {
            return "With " + e.peopleNames.distinct().joinToString(", ") + "."
        }
        return when {
            e.faceCount <= 0 -> null
            e.faceCount == 1 -> "One person."
            e.faceCount == 2 -> "Two people."
            else -> "${e.faceCount} people."
        }
    }
}
