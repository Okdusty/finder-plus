package ai.dusty.finderplus.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.entity.LabelQuestionEntity
import ai.dusty.finderplus.db.entity.MediaItemEntity
import ai.dusty.finderplus.db.entity.NameRankingEntity
import ai.dusty.finderplus.db.entity.SearchVoteEntity
import ai.dusty.finderplus.db.entity.TagEntity
import ai.dusty.finderplus.index.VaultPolicy
import ai.dusty.finderplus.model.TagSource
import ai.dusty.finderplus.settings.PrefsSettingsStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Copies of what the app holds, written to a place the user picks:
 *
 *  - **The index**, the whole database as one SQLite file, for keeping or for reading elsewhere.
 *  - **The user's own work**: labels they gave, answers, rankings, votes, label rules, settings, search
 *    history and private-folder rules. It restores onto a fresh install or a rebuilt index, matching
 *    each file by its id, else its name and size, else its name and date taken.
 *  - **Training data**: rankings, answers and votes as JSON Lines, one record per line.
 *
 * Vault keys are never included; they have their own recovery file.
 */
@Singleton
class Backups @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val db: FinderDatabase,
    private val vaultPolicy: VaultPolicy,
    private val history: ai.dusty.finderplus.ui.popup.SearchHistory,
) {
    private val settings: PrefsSettingsStore get() = (context.applicationContext as ai.dusty.finderplus.FinderApp).settings

    // ---- the index ---------------------------------------------------------------------------------

    /** The whole database, as a consistent snapshot even while indexing writes. Returns its size. */
    fun exportIndex(target: Uri): Long {
        val tmp = File(context.cacheDir, "finder-index-export.db").also { it.delete() }
        try {
            val sql = db.openHelper.writableDatabase
            if (Build.VERSION.SDK_INT >= 30) {
                sql.execSQL("VACUUM INTO ?", arrayOf(tmp.absolutePath))
            } else {
                // No VACUUM INTO before SQLite 3.27: fold the log into the file and copy it.
                sql.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                context.getDatabasePath(db.openHelper.databaseName ?: "finder.db").copyTo(tmp, overwrite = true)
            }
            write(target) { out -> tmp.inputStream().use { it.copyTo(out) } }
            return tmp.length()
        } finally {
            tmp.delete()
        }
    }

    // ---- the user's own work -----------------------------------------------------------------------

    data class Restored(val matched: Int, val unmatched: Int, val settings: Int)

    suspend fun backUpWork(target: Uri): Int {
        val items = HashMap<Long, MediaItemEntity?>()
        suspend fun key(id: Long): JSONObject {
            val row = items.getOrPut(id) { db.mediaItemDao().byId(id) }
            return JSONObject().put("id", id).put("name", row?.display_name).put("size", row?.size_bytes).put("taken", row?.date_taken)
        }
        val labels = JSONArray()
        for (t in db.contentDao().tagsBySource(TagSource.USER.ordinal)) labels.put(JSONObject().put("item", key(t.item_id)).put("label", t.label))
        val answers = JSONArray()
        for (q in db.labelQuestionDao().answered()) answers.put(
            JSONObject().put("item", key(q.item_id)).put("label", q.label).put("confidence", q.confidence.toDouble())
                .put("reasons", q.reasons).put("alternative", q.alternative).put("verdict", q.verdict).put("at", q.updated_at)
        )
        val rankings = JSONArray()
        for (r in db.nameRankingDao().all()) rankings.put(
            JSONObject().put("item", key(r.item_id)).put("label", r.label).put("rank", r.rank).put("reward", r.reward.toDouble())
                .put("advantage", r.advantage.toDouble()).put("group", r.group_size).put("at", r.created_at)
        )
        val votes = JSONArray()
        for (v in db.voteDao().allVotes()) votes.put(JSONObject().put("item", key(v.item_id)).put("term", v.term).put("score", v.score.toDouble()))
        val root = JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("created", System.currentTimeMillis())
            .put("settings", JSONObject(settings.exportChanged() as Map<*, *>))
            .put("history", JSONArray(history.rank(Int.MAX_VALUE).map { JSONObject().put("q", it.query).put("n", it.count).put("at", it.lastUsed) }))
            .put("vault", JSONObject().put("auto", vaultPolicy.auto)
                .put("rules", JSONObject(vaultPolicy.rules().mapValues { it.value.name } as Map<*, *>)))
            .put("labels", labels).put("answers", answers).put("rankings", rankings).put("votes", votes)
        write(target) { it.write(root.toString().toByteArray()) }
        return labels.length() + answers.length() + rankings.length() + votes.length()
    }

    suspend fun restoreWork(source: Uri): Restored {
        val root = JSONObject(context.contentResolver.openInputStream(source)!!.use { it.readBytes().decodeToString() })
        require(root.optString("format") == FORMAT) { "not a finder+ backup" }
        val settingsRestored = root.optJSONObject("settings")?.let { o ->
            settings.importValues(o.keys().asSequence().associateWith { o.getString(it) })
        } ?: 0
        root.optJSONArray("history")?.let { a -> for (i in 0 until a.length()) history.record(a.getJSONObject(i).getString("q")) }
        root.optJSONObject("vault")?.let { v ->
            if (v.has("auto")) vaultPolicy.auto = v.getBoolean("auto")
            v.optJSONObject("rules")?.let { r ->
                for (k in r.keys()) runCatching { vaultPolicy.set(k, VaultPolicy.Decision.valueOf(r.getString(k))) }
            }
        }

        var matched = 0; var unmatched = 0
        val found = HashMap<String, Long?>()
        suspend fun item(o: JSONObject): Long? = found.getOrPut(o.toString()) {
            val dao = db.mediaItemDao()
            val name = o.optString("name").takeIf { it.isNotEmpty() && it != "null" }
            dao.byId(o.getLong("id"))?.takeIf { name == null || it.display_name == name }?.id
                ?: name?.let { n -> o.optLong("size", -1).takeIf { it >= 0 }?.let { dao.byNameAndSize(n, it)?.id } }
                ?: name?.let { n -> o.optLong("taken", -1).takeIf { it > 0 }?.let { dao.byNameAndDate(n, it)?.id } }
        }.also { if (it == null) unmatched++ else matched++ }

        val content = db.contentDao()
        root.optJSONArray("labels")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val id = item(o.getJSONObject("item")) ?: continue
                val label = o.getString("label")
                if (content.tagsForItem(id).none { it.source == TagSource.USER.ordinal && it.label == label }) {
                    content.insertTags(listOf(TagEntity(item_id = id, source = TagSource.USER.ordinal, label = label, confidence = 1f)))
                }
            }
        }
        root.optJSONArray("answers")?.let { a ->
            val rows = ArrayList<LabelQuestionEntity>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val id = item(o.getJSONObject("item")) ?: continue
                val label = o.getString("label")
                val verdict = o.getInt("verdict")
                rows += LabelQuestionEntity(
                    id, label, o.optDouble("confidence", 0.0).toFloat(), o.optInt("reasons"),
                    o.optString("alternative").takeIf { it.isNotEmpty() && it != "null" }, verdict, o.optLong("at"),
                )
                // A label the user said no to does not come back with the restore.
                if (verdict == LabelQuestionEntity.NO) {
                    content.deleteTagRows(id, label, listOf(TagSource.OBJECT.ordinal, TagSource.OBJECT_REVIEW.ordinal))
                }
            }
            if (rows.isNotEmpty()) db.labelQuestionDao().upsert(rows)
        }
        root.optJSONArray("rankings")?.let { a ->
            val rows = ArrayList<NameRankingEntity>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val id = item(o.getJSONObject("item")) ?: continue
                rows += NameRankingEntity(
                    id, o.getString("label"), if (o.isNull("rank")) null else o.getInt("rank"),
                    o.getDouble("reward").toFloat(), o.getDouble("advantage").toFloat(), o.getInt("group"), o.getLong("at"),
                )
            }
            if (rows.isNotEmpty()) db.nameRankingDao().insert(rows)
        }
        root.optJSONArray("votes")?.let { a ->
            val rows = ArrayList<SearchVoteEntity>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val id = item(o.getJSONObject("item")) ?: continue
                rows += SearchVoteEntity(o.getString("term"), id, o.getDouble("score").toFloat(), System.currentTimeMillis())
            }
            if (rows.isNotEmpty()) db.voteDao().putVotes(rows)
        }
        // Search text is rebuilt with the restored labels at the next index.
        db.mediaItemDao().markArtifactsStale()
        return Restored(matched, unmatched, settingsRestored)
    }

    // ---- training data -----------------------------------------------------------------------------

    /**
     * Rankings (one record per photo, its names with rank, reward and group-relative advantage),
     * answers (one record per item and name) and votes (one per word and item), as JSON Lines. Each
     * record names its file, so it can be joined to the picture elsewhere.
     */
    suspend fun exportTraining(target: Uri): Int {
        var n = 0
        val sb = StringBuilder()
        suspend fun file(id: Long): JSONObject {
            val row = db.mediaItemDao().byId(id)
            return JSONObject().put("item_id", id).put("name", row?.display_name).put("folder", row?.bucket_name)
                .put("taken", row?.date_taken).put("uri", row?.content_uri)
        }
        for ((id, rows) in db.nameRankingDao().all().groupBy { it.item_id }) {
            val names = JSONArray()
            rows.sortedWith(compareBy(nullsLast()) { it.rank }).forEach {
                names.put(JSONObject().put("label", it.label).put("rank", it.rank).put("reward", it.reward.toDouble()).put("advantage", it.advantage.toDouble()))
            }
            sb.append(JSONObject().put("type", "ranking").put("file", file(id)).put("names", names).put("at", rows.maxOf { it.created_at })).append('\n'); n++
        }
        for (q in db.labelQuestionDao().answered()) {
            sb.append(
                JSONObject().put("type", "answer").put("file", file(q.item_id)).put("label", q.label)
                    .put("verdict", if (q.verdict == LabelQuestionEntity.YES) "yes" else "no").put("model_confidence", q.confidence.toDouble())
                    .put("at", q.updated_at)
            ).append('\n'); n++
        }
        for (v in db.voteDao().allVotes()) {
            sb.append(JSONObject().put("type", "vote").put("query", v.term).put("file", file(v.item_id)).put("score", v.score.toDouble())).append('\n'); n++
        }
        write(target) { it.write(sb.toString().toByteArray()) }
        return n
    }

    private inline fun write(target: Uri, block: (java.io.OutputStream) -> Unit) {
        context.contentResolver.openOutputStream(target, "wt")!!.use(block)
    }

    private companion object {
        const val FORMAT = "finderplus-work"
        const val VERSION = 1
    }
}
