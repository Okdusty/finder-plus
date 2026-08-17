package ai.dusty.finderplus.di

import android.content.Context
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.WorkLedger
import ai.dusty.finderplus.db.dao.ContentDao
import ai.dusty.finderplus.db.dao.EmbeddingDao
import ai.dusty.finderplus.db.dao.FtsDao
import ai.dusty.finderplus.db.dao.IndexRunDao
import ai.dusty.finderplus.db.dao.MediaItemDao
import ai.dusty.finderplus.db.dao.WorkUnitDao
import ai.dusty.finderplus.db.vector.BruteForceVectorStore
import ai.dusty.finderplus.db.vector.VectorStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DbModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): FinderDatabase = FinderDatabase.build(context)

    @Provides fun mediaItemDao(db: FinderDatabase): MediaItemDao = db.mediaItemDao()
    @Provides fun workUnitDao(db: FinderDatabase): WorkUnitDao = db.workUnitDao()
    @Provides fun indexRunDao(db: FinderDatabase): IndexRunDao = db.indexRunDao()
    @Provides fun contentDao(db: FinderDatabase): ContentDao = db.contentDao()
    @Provides fun voteDao(db: FinderDatabase): ai.dusty.finderplus.db.dao.VoteDao = db.voteDao()
    @Provides fun embeddingDao(db: FinderDatabase): EmbeddingDao = db.embeddingDao()
    @Provides fun ftsDao(db: FinderDatabase): FtsDao = db.ftsDao()

    @Provides @Singleton
    fun vectorStore(embeddingDao: EmbeddingDao): VectorStore = BruteForceVectorStore(embeddingDao)

    @Provides @Singleton
    fun workLedger(workUnitDao: WorkUnitDao): WorkLedger = WorkLedger(workUnitDao)
}
