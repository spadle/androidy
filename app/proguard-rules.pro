# LiteRT-LM (Google's on-device LLM runtime)
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# MediaPipe (fallback LLM inference)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Hilt
-keep class dagger.hilt.** { *; }
