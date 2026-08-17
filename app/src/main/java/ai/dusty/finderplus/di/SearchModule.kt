package ai.rightone.finderplus.di

import ai.rightone.finderplus.db.dao.ContentDao
import ai.rightone.finderplus.db.dao.FtsDao
import ai.rightone.finderplus.db.dao.MediaItemDao
import ai.rightone.finderplus.db.dao.MediaProfileDao
import ai.rightone.finderplus.db.vector.VectorStore
import ai.rightone.finderplus.search.DefaultSearchEngine
import ai.rightone.finderplus.search.SearchEngine
import ai.rightone.finderplus.text.TextEmbedder
import ai.rightone.finderplus.vision.ClipTextEncoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    @Provides fun mediaProfileDao(db: ai.rightone.finderplus.db.FinderDatabase): MediaProfileDao =
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
        db: ai.rightone.finderplus.db.FinderDatabase,
    ): SearchEngine = DefaultSearchEngine(
        ftsDao = ftsDao,
        mediaItemDao = mediaItemDao,
        contentDao = contentDao,
        profileDao = profileDao,
        vectorStore = vectorStore,
        clipText = clipText,
        textEmbedder = textEmbedder,
        speller = ai.rightone.finderplus.search.QuerySpeller(db.termDfDao()),
        voteDao = db.voteDao(),
    )
}
