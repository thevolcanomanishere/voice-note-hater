package com.watranscribe.di

import android.content.Context
import androidx.room.Room
import com.watranscribe.data.local.AppDatabase
import com.watranscribe.data.local.TranscriptionDao
import com.watranscribe.engine.MoonshineEngine
import com.watranscribe.engine.TranscriptionEngine
import com.watranscribe.engine.WhisperEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "wa_transcribe.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()

    @Provides
    fun provideTranscriptionDao(db: AppDatabase): TranscriptionDao = db.transcriptionDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @IntoMap
    @StringKey("whisper")
    abstract fun bindWhisperEngine(engine: WhisperEngine): TranscriptionEngine

    @Binds
    @IntoMap
    @StringKey("moonshine")
    abstract fun bindMoonshineEngine(engine: MoonshineEngine): TranscriptionEngine
}
