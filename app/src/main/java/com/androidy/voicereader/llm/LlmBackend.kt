package com.androidy.voicereader.llm

/**
 * Abstraction over on-device LLM backends.
 * Allows swapping between LiteRT-LM (preferred) and MediaPipe (fallback).
 */
interface LlmBackend {
    val name: String
    suspend fun load(modelPath: String)
    suspend fun generateResponse(prompt: String): String
    fun close()
}
