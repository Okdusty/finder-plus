package ai.dusty.finderplus.index

import ai.dusty.finderplus.model.Segment

/**
 * Transcript segments grouped into windows of about [WINDOW_MS], each with where it starts. The text
 * leg embeds one vector per window, and the start is where a match in that window opens the media.
 */
object TranscriptWindows {
    /** A minute of speech is ~150 words — well inside the text model's window, and one topic or so. */
    const val WINDOW_MS = 60_000L

    fun of(segments: List<Segment>, windowMs: Long = WINDOW_MS): List<Pair<Long, String>> {
        if (segments.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Long, String>>()
        val sb = StringBuilder()
        var windowStart = segments.first().startMs
        for (s in segments) {
            if (s.startMs - windowStart >= windowMs && sb.isNotEmpty()) {
                out += windowStart to sb.toString().trim(); sb.setLength(0); windowStart = s.startMs
            }
            sb.append(s.text).append(' ')
        }
        if (sb.isNotBlank()) out += windowStart to sb.toString().trim()
        return out
    }
}
