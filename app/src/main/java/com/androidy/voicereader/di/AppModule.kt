package com.androidy.voicereader.di

import android.content.Context
import androidx.room.Room
import com.androidy.voicereader.data.ReadingHistoryDao
import com.androidy.voicereader.data.ReadingHistoryDatabase
import com.androidy.voicereader.data.SettingsRepository
import com.androidy.voicereader.llm.GemmaLlmEngine
import com.androidy.voicereader.tts.IntelligentTtsEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository {
        return SettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideReadingHistoryDatabase(@ApplicationContext context: Context): ReadingHistoryDatabase {
        return Room.databaseBuilder(context, ReadingHistoryDatabase::class.java, "reading_history.db")
            .build()
    }

    @Provides
    @Singleton
    fun provideReadingHistoryDao(database: ReadingHistoryDatabase): ReadingHistoryDao {
        return database.readingHistoryDao()
    }

    @Provides
    @Singleton
    fun provideGemmaLlmEngine(@ApplicationContext context: Context): GemmaLlmEngine {
        return GemmaLlmEngine(context)
    }

    @Provides
    @Singleton
    fun provideIntelligentTtsEngine(@ApplicationContext context: Context): IntelligentTtsEngine {
        return IntelligentTtsEngine(context)
    }
}
