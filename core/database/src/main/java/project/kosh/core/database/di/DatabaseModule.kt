package project.kosh.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.dao.AlbumDao
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.repository.AlbumRepository
import project.kosh.core.database.repository.DefaultAlbumRepository
import project.kosh.core.database.repository.DefaultMediaRepository
import project.kosh.core.database.repository.MediaRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(
        impl: DefaultMediaRepository
    ): MediaRepository

    @Binds
    @Singleton
    abstract fun bindAlbumRepository(
        impl: DefaultAlbumRepository
    ): AlbumRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideKoshDatabase(
        @ApplicationContext context: Context
    ): KoshDatabase = Room.databaseBuilder(
        context,
        KoshDatabase::class.java,
        KoshDatabase.DATABASE_NAME
    )
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    @Provides
    fun provideMediaAssetDao(database: KoshDatabase): MediaAssetDao = database.mediaAssetDao()

    @Provides
    fun provideMediaCryptoDao(database: KoshDatabase): MediaCryptoDao = database.mediaCryptoDao()

    @Provides
    fun provideAlbumDao(database: KoshDatabase): AlbumDao = database.albumDao()
}
