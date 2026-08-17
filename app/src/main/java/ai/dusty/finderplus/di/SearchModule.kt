package ai.dusty.finderplus.di

import ai.dusty.finderplus.db.dao.ContentDao
import ai.dusty.finderplus.db.dao.FtsDao
import ai.dusty.finderplus.db.dao.MediaItemDao
import ai.dusty.finderplus.db.dao.MediaProfileDao
import ai.dusty.finderplus.db.vector.VectorStore
import ai.dusty.finderplus.search.DefaultSearchEngine
import ai.dusty.finderplus.search.SearchEngine
import ai.dusty.finderplus.text.TextEmbedder
import ai.dusty.finderplus.vision.ClipTextEncoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    @Provides fun mediaProfileDao(db: ai.dusty.finderplus.db.FinderDatabase): MediaProfileDao =
        db.mediaProfileDao()

    @Provides @Singleton
    fun searchEngine(
        ftsDao: FtsDao,
        mediaItemDao: MediaItemDao,
        contentDao: ContentDao,
        profileDao: MediaProfileDao,
        vectorStore: VectorStore,
        clipText: ClipTextEncoder,
        textEmbedder: TextEmbedder,
        db: ai.dusty.finderplus.db.FinderDatabase,
    ): SearchEngine = DefaultSearchEngine(
        ftsDao = ftsDao,
        mediaItemDao = mediaItemDao,
        contentDao = contentDao,
        profileDao = profileDao,
        vectorStore = vectorStore,
        clipText = clipText,
        textEmbedder = textEmbedder,
        speller = ai.dusty.finderplus.search.QuerySpeller(db.termDfDao()),
        voteDao = db.voteDao(),
    )
}
