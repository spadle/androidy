package com.androidy.voicereader.di

import android.content.Context
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
    fun provideGemmaLlmEngine(@ApplicationContext context: Context): GemmaLlmEngine {
        return GemmaLlmEngine(context)
    }

    @Provides
    @Singleton
    fun provideIntelligentTtsEngine(@ApplicationContext context: Context): IntelligentTtsEngine {
        return IntelligentTtsEngine(context)
    }
}
