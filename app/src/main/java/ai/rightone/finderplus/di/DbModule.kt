package ai.rightone.finderplus.di

import android.content.Context
import ai.rightone.finderplus.db.FinderDatabase
import ai.rightone.finderplus.db.WorkLedger
import ai.rightone.finderplus.db.dao.ContentDao
import ai.rightone.finderplus.db.dao.EmbeddingDao
import ai.rightone.finderplus.db.dao.FtsDao
import ai.rightone.finderplus.db.dao.IndexRunDao
import ai.rightone.finderplus.db.dao.MediaItemDao
import ai.rightone.finderplus.db.dao.WorkUnitDao
import ai.rightone.finderplus.db.vector.BruteForceVectorStore
import ai.rightone.finderplus.db.vector.VectorStore
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
    @Provides fun voteDao(db: FinderDatabase): ai.rightone.finderplus.db.dao.VoteDao = db.voteDao()
    @Provides fun embeddingDao(db: FinderDatabase): EmbeddingDao = db.embeddingDao()
    @Provides fun ftsDao(db: FinderDatabase): FtsDao = db.ftsDao()

    @Provides @Singleton
    fun vectorStore(embeddingDao: EmbeddingDao): VectorStore = BruteForceVectorStore(embeddingDao)

    @Provides @Singleton
    fun workLedger(workUnitDao: WorkUnitDao): WorkLedger = WorkLedger(workUnitDao)
}
