package ai.dusty.finderplus.ui.popup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's own searches, suggested when the pop-up opens empty.
 *
 * Only committed queries are kept (a result acted on, a suggestion picked, the keyboard's search key,
 * or the pop-up closed on results), so half-typed prefixes never appear. Stored in app-private storage
 * and never leaves the phone; any entry can be removed.
 */
@Singleton
class SearchHistory(private val file: File) {

    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "search-history.tsv"))

    data class Entry(val query: String, val count: Int, val lastUsed: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false

    private val _version = MutableStateFlow(0)
    /** Bumped on every change, for screens that show the history. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Count a committed search. Case and spacing do not split one query into several. */
    @Synchronized
    fun record(raw: String, now: Long = System.currentTimeMillis()) {
        val query = raw.trim().replace(Regex("\\s+"), " ")
        if (query.length < MIN_LENGTH) return
        ensureLoaded()
        val key = keyOf(query)
        val prev = entries.remove(key)
        // Latest spelling wins for display; insertion order keeps the newest last.
        entries[key] = Entry(query, (prev?.count ?: 0) + 1, now)
        if (entries.size > MAX_ENTRIES) {
            val evict = entries.values.sortedBy { score(it, now) }.take(entries.size - MAX_ENTRIES)
            evict.forEach { entries.remove(keyOf(it.query)) }
        }
        save()
    }

    @Synchronized
    fun remove(query: String) {
        ensureLoaded()
        if (entries.remove(keyOf(query)) != null) save()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        loaded = true
        file.delete()
        _version.value++
    }

    /** Previous search first (usually the one being re-run), then most-used with slow decay, up to [limit]. */
    @Synchronized
    fun rank(limit: Int, now: Long = System.currentTimeMillis()): List<Entry> {
        ensureLoaded()
        if (entries.isEmpty()) return emptyList()
        val previous = entries.values.maxBy { it.lastUsed }
        val rest = entries.values.filter { it !== previous }.sortedByDescending { score(it, now) }
        return (listOf(previous) + rest).take(limit)
    }

    /** Use count, halved for every [HALF_LIFE_DAYS] since last use, so an abandoned habit fades below a current one. */
    private fun score(e: Entry, now: Long): Double {
        val ageDays = ((now - e.lastUsed).coerceAtLeast(0)) / 86_400_000.0
        return e.count * Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
    }

    private fun keyOf(q: String) = q.lowercase(java.util.Locale.ROOT)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.isFile) return
            file.readLines().forEach { line ->
                val p = line.split('\t', limit = 3)
                if (p.size == 3) {
                    val e = Entry(p[2], p[0].toIntOrNull() ?: return@forEach, p[1].toLongOrNull() ?: return@forEach)
                    entries[keyOf(e.query)] = e
                }
            }
        }
    }

    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                for (e in entries.values) w.write("${e.count}\t${e.lastUsed}\t${e.query.replace('\t', ' ').replace('\n', ' ')}\n")
            }
            tmp.renameTo(file)
        }
        _version.value++
    }

    private companion object {
        const val MIN_LENGTH = 2
        const val MAX_ENTRIES = 200
        const val HALF_LIFE_DAYS = 30.0
    }
}
