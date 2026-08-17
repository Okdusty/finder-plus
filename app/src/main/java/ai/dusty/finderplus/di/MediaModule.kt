package ai.dusty.finderplus.di

import android.content.Context
import ai.dusty.finderplus.media.AndroidFrameExtractor
import ai.dusty.finderplus.media.AndroidMediaStoreReader
import ai.dusty.finderplus.media.AndroidPcmDecoder
import ai.dusty.finderplus.media.FrameExtractor
import ai.dusty.finderplus.media.MediaStoreReader
import ai.dusty.finderplus.media.PcmDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides @Singleton
    fun mediaStoreReader(@ApplicationContext context: Context): MediaStoreReader =
        AndroidMediaStoreReader(context)

    @Provides @Singleton
    fun frameExtractor(@ApplicationContext context: Context): FrameExtractor =
        AndroidFrameExtractor(context)

    @Provides @Singleton
    fun pcmDecoder(@ApplicationContext context: Context): PcmDecoder =
        AndroidPcmDecoder(context)
}
