package ai.rightone.finderplus.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ai.rightone.finderplus.db.dao.ContentDao
import ai.rightone.finderplus.db.dao.EmbeddingDao
import ai.rightone.finderplus.db.dao.FtsDao
import ai.rightone.finderplus.db.dao.IndexRunDao
import ai.rightone.finderplus.db.dao.MediaItemDao
import ai.rightone.finderplus.db.dao.FaceDao
import ai.rightone.finderplus.db.dao.LabelPrototypeDao
import ai.rightone.finderplus.db.dao.MediaProfileDao
import ai.rightone.finderplus.db.dao.TermDfDao
import ai.rightone.finderplus.db.dao.WorkUnitDao
import ai.rightone.finderplus.db.entity.DocumentEntity
import ai.rightone.finderplus.db.entity.EmbeddingEntity
import ai.rightone.finderplus.db.entity.FaceEntity
import ai.rightone.finderplus.db.entity.IndexRunEntity
import ai.rightone.finderplus.db.entity.MediaItemEntity
import ai.rightone.finderplus.db.entity.MediaProfileEntity
import ai.rightone.finderplus.db.entity.LabelPrototypeEntity
import ai.rightone.finderplus.db.entity.PersonEntity
import ai.rightone.finderplus.db.entity.SegmentEntity
import ai.rightone.finderplus.db.entity.TagEntity
import ai.rightone.finderplus.db.entity.SearchVoteEntity
import ai.rightone.finderplus.db.entity.TermDfEntity
import ai.rightone.finderplus.db.entity.WorkUnitEntity

@Database(
    entities = [
        MediaItemEntity::class,
        WorkUnitEntity::class,
        IndexRunEntity::class,
        TagEntity::class,
        DocumentEntity::class,
        SegmentEntity::class,
        EmbeddingEntity::class,
        MediaProfileEntity::class,
        FaceEntity::class,
        PersonEntity::class,
        LabelPrototypeEntity::class,
        TermDfEntity::class,
        SearchVoteEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class FinderDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun workUnitDao(): WorkUnitDao
    abstract fun indexRunDao(): IndexRunDao
    abstract fun contentDao(): ContentDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun ftsDao(): FtsDao
    abstract fun mediaProfileDao(): MediaProfileDao
    abstract fun faceDao(): FaceDao
    abstract fun labelPrototypeDao(): LabelPrototypeDao
    abstract fun termDfDao(): TermDfDao
    abstract fun voteDao(): ai.rightone.finderplus.db.dao.VoteDao

    companion object {
        fun build(context: Context): FinderDatabase =
            Room.databaseBuilder(context, FinderDatabase::class.java, "finder.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(SchemaCallback)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                )
                .build()

        /**
         * Adds the people tables. A real migration (not destructive fallback) because a gallery index
         * represents hours of on-device AI work that must never be thrown away by an app update.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS face (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        item_id INTEGER NOT NULL,
                        box_left INTEGER NOT NULL,
                        box_top INTEGER NOT NULL,
                        box_right INTEGER NOT NULL,
                        box_bottom INTEGER NOT NULL,
                        area_ratio REAL NOT NULL,
                        smiling REAL,
                        eyes_open REAL,
                        embedding BLOB,
                        cluster_id INTEGER,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(item_id) REFERENCES media_item(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_face_item_id ON face(item_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_face_cluster_id ON face(cluster_id)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_face_item_id_box_left_box_top ON face(item_id, box_left, box_top)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS person (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT,
                        cover_face_id INTEGER,
                        centroid BLOB,
                        face_count INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_person_name ON person(name)")
            }
        }

        /**
         * Adds VLM name-proposal columns. A suggestion from a model is stored separately from the
         * confirmed name so an incorrect guess can never masquerade as user-verified identity.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE person ADD COLUMN proposed_name TEXT")
                db.execSQL("ALTER TABLE person ADD COLUMN proposal_votes INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds learned label prototypes — knowledge lives in embedding space, so this is just a table. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS label_prototype (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label TEXT NOT NULL,
                        centroid BLOB NOT NULL,
                        exemplar_count INTEGER NOT NULL,
                        negative_centroid BLOB,
                        negative_count INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_label_prototype_label ON label_prototype(label)")
            }
        }

        /**
         * Gives every label a text prior — the CLIP text embedding of its own name — so a concept is
         * scoreable from the moment it is named, before the user has demonstrated a single example.
         * Existing prototypes keep working: a null prior simply means "judge on exemplars alone".
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE label_prototype ADD COLUMN text_prior BLOB")
                db.execSQL("ALTER TABLE label_prototype ADD COLUMN origin INTEGER NOT NULL DEFAULT 0")
                // Everything that already existed got there by the user naming it.
                db.execSQL("UPDATE label_prototype SET origin = 1")
            }
        }

        /**
         * Retires everything tied to the old CLIP ViT-B/32 vector space, which the ViT-B/16 upgrade
         * invalidates.
         *
         * This has to be destructive. Both encoders emit 512-d unit vectors, so an index holding a
         * mixture does not error — cosine similarity between a B/32 vector and a B/16 vector is simply
         * meaningless, and the only symptom is search quietly returning unrelated results. Keeping the
         * old rows would be strictly worse than dropping them.
         *
         * Nothing irreplaceable is lost: prototypes are rebuilt from the user's own USER-source tags
         * once re-embedding finishes, and those tags are untouched here.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM label_prototype")
                db.execSQL("DELETE FROM embedding WHERE kind = 0")
                // Concept tags were derived from those vectors, so they are stale by construction.
                db.execSQL("DELETE FROM tag WHERE source IN (7, 8)")
            }
        }

        /**
         * Removes work units left behind by the deleted VLM captioning pass.
         *
         * Pass identity is an enum **ordinal**, and the removed CAPTION pass held ordinal 9 — which the
         * new CONCEPTS pass now reuses. Its 4,772 abandoned rows therefore looked like CONCEPTS work
         * while carrying the old pass's priority (80) and a `requires_model` of 5, a residency code that
         * no longer exists. Worse, `UNIQUE(item_id, pass)` makes enqueue a silent no-op, so those rows
         * blocked every genuine CONCEPTS unit from ever being created: no error, no failure, simply no
         * concept labels.
         *
         * They are identified by the vanished residency code, which nothing valid can have.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM work_unit WHERE requires_model >= 5")
            }
        }

        /**
         * Drops the per-frame label union from every video.
         *
         * The keyframe pass ran the ML Kit labeler on each of up to 20 frames and attached every result
         * to the file, with nothing requiring two frames to agree. Measured here before removing it: 608
         * videos held 6,902 LABEL tags — 11.4 apiece against 3.5 for a photo — and single files carried
         * mutually exclusive sets like `Aircraft, Bird, Dog, Musical instrument, Toe, Wool`. The labels
         * were not individually unlikely; a union of twenty independent classifications simply is not a
         * description of a video.
         *
         * Deleted rather than recomputed because these tags have no correct version: what a video is
         * about is now derived by [ai.rightone.finderplus.model.Pass.CONCEPTS] from the keyframe
         * embeddings, which are already stored. That is why this is a `DELETE` and not a pass-version
         * bump — a bump would re-extract and re-label 611 already-processed videos, hours of work, to
         * produce a signal that is no longer used.
         *
         * Only `kind = 1` (video) and only `source = 0` (LABEL): a photo's labels come from a single
         * frame, have never had this problem, and are left alone.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM tag WHERE source = 0 AND item_id IN (SELECT id FROM media_item WHERE kind = 1)"
                )
            }
        }

        /**
         * Drops the OCR keyword tags.
         *
         * They were the tokens of the recognized text, re-stored as tags — and they had become 90% of
         * every tag in the database: 27,496 rows whose most frequent entries were `the` 91, `and` 83,
         * `bir` 80, `com` 67, `için` 57, `Posted` 53. 81% of the distinct ones appeared exactly once, and
         * case was never folded, so `the` and `The` were counted separately.
         *
         * They were also pure duplication. The recognized text is stored whole and indexed as its own
         * weighted FTS column, so every one of those words was already searchable. A rebuilt profile made
         * the redundancy plain — the same tokens appeared three times over:
         *
         * ```
         *   Summary: ... Text on screen: MLICA Colpol alemlere akacağız
         *   Tags: Colpol MLICA akacağız alemlere
         *   Text: MLICA Colpol alemlere akacağız
         * ```
         *
         * That text is what a tap copies to the clipboard, and those tokens diluted the `tags` FTS column
         * that real labels are ranked by. Deleted rather than recomputed: nothing regenerates them, since
         * the OCR pass no longer emits keywords at all.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM tag WHERE source = 2")
            }
        }

        /**
         * Adds the corpus term-frequency table.
         *
         * This replaces hard-coded filtering. Which words are worth indexing was previously decided by an
         * English label stoplist plus, for OCR, nothing at all — so the most common tags in the database
         * became `the`, `and`, `bir`, `için`. Counting the gallery's own documents answers the same
         * question without naming a language.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS term_df (
                        term TEXT NOT NULL,
                        scope INTEGER NOT NULL,
                        doc_count INTEGER NOT NULL,
                        PRIMARY KEY(term, scope)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_term_df_scope_doc_count ON term_df(scope, doc_count)")
            }
        }

        /**
         * Revives terminally-failed work units.
         *
         * The only failures on record are four "InputImage width and height should be at least 32!"
         * units — a 23 KB gif and a 2.5 KB ico that ML Kit cannot accept, each retried to exhaustion.
         * The passes now check dimensions up front and report SKIPPED, so these rows resolve cleanly
         * once re-run; leaving them FAILED would leave two items permanently marked broken for what is
         * a size precondition, not damage.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE work_unit SET state = 0, attempt_count = 0, last_error = NULL WHERE state = 4")
            }
        }

        /**
         * The vocabulary stopped labelling *form*. "screenshot of a social media post" and
         * "low quality compressed image" categorize the medium; nobody searches for the wrapper.
         * Machine applications of the retired labels are purged (sources 5=VLM, 6=LEARNED,
         * 8=SUGGESTED - USER rows are never touched), their untaught seed prototypes dropped, and
         * every CONCEPTS unit requeued (pass 9; states DONE/SKIPPED) so the pure-arithmetic re-run
         * relabels the gallery against the content-first vocabulary. Stored CLIP vectors make this
         * cheap - no model re-inference, just dot products.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val retired = "'portrait of a person', 'group photo of people', 'photo of a child', 'photo of a baby', 'photo of a couple', 'photo of a family', 'crowd of people', 'professional headshot', 'full body photo of a person', 'screenshot of a chat conversation', 'screenshot of a social media post', 'screenshot of a social media profile', 'screenshot of a web page', 'screenshot of a video call', 'screenshot of a map', 'screenshot of a music player', 'screenshot of a video game', 'screenshot of a spreadsheet', 'screenshot of an email', 'screenshot of a bank transaction', 'screenshot of a calendar', 'screenshot of computer code', 'screenshot of a settings menu', 'screenshot of a video player', 'screenshot of a shopping page', 'screenshot of a news article', 'screenshot of a weather forecast', 'meme with caption text', 'error message on a screen', 'blurry out-of-focus photograph', 'dark underexposed photograph', 'overexposed bright photograph', 'black and white photograph', 'panorama photograph', 'aerial drone photograph', 'close-up macro photograph', 'long exposure photograph', 'night photograph', 'photograph with motion blur', 'product photograph on a plain background', 'photograph of a mirror reflection', 'photograph taken through glass', 'low quality compressed image', 'photograph of a photograph'"
                db.execSQL("DELETE FROM tag WHERE source IN (5, 6, 8) AND label IN ($retired)")
                db.execSQL("DELETE FROM label_prototype WHERE exemplar_count = 0 AND label IN ($retired)")
                db.execSQL(
                    "UPDATE work_unit SET state = 0, attempt_count = 0, last_error = NULL, checkpoint = NULL, lease_owner = NULL " +
                        "WHERE pass = 9 AND state IN (3, 5)"
                )
            }
        }

        /** Implicit search-vote memory: (query term, item) -> bounded score. See SearchVoteEntity. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS search_vote (" +
                        "term TEXT NOT NULL, item_id INTEGER NOT NULL, score REAL NOT NULL, " +
                        "updated_at INTEGER NOT NULL, PRIMARY KEY(term, item_id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_vote_term ON search_vote (term)")
            }
        }

        /** Vault support: where a hidden file originally lived, for full restore. Null = not vaulted. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_item ADD COLUMN original_path TEXT")
            }
        }

        // WAL is set via the Room builder (setJournalMode); Android's execSQL rejects
        // journal_mode=WAL because it returns a row. §10.
        // These PRAGMAs return NO rows, so execSQL is valid.
        private val VOID_PRAGMAS = listOf(
            "PRAGMA synchronous = NORMAL",
            "PRAGMA foreign_keys = ON",
            "PRAGMA temp_store = MEMORY",
        )

        // These PRAGMAs RETURN a row when set; on Android they MUST go through query(), not execSQL.
        private val VALUE_PRAGMAS = listOf(
            "PRAGMA busy_timeout = 5000",
            "PRAGMA mmap_size = 268435456",
            "PRAGMA wal_autocheckpoint = 1000",
        )

        /**
         * FTS table Room does not manage — created once and kept in sync by the engine. §2.5.
         * Uses FTS4 (the default tokenizer) because many OEM system-SQLite builds — including this
         * Samsung/Exynos device — ship without the FTS5 module, which makes `USING fts5(...)` abort
         * and roll back Room's whole onCreate. FTS4 is universally available. To restore FTS5 + bm25
         * ranking, bundle a SQLite build with FTS5 (e.g. requery/androidx sqlite-bundled) and switch
         * this back. See docs/design/04-SEARCH.md.
         */
        private const val CREATE_FTS = """
            CREATE VIRTUAL TABLE IF NOT EXISTS media_fts USING fts4(
                name, tags, ocr, transcript, place, bucket
            )
        """

        private object SchemaCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_FTS)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                VOID_PRAGMAS.forEach(db::execSQL)
                VALUE_PRAGMAS.forEach { db.query(it).use { c -> c.moveToFirst() } }
                // Defensive: ensure the FTS table exists even if a prior create was interrupted.
                db.execSQL(CREATE_FTS)
            }
        }
    }
}
