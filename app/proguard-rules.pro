# LiteRT (formerly TensorFlow Lite) — core on-device ML runtime
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**

# LiteRT-LM — LLM-specific layer on LiteRT
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# MediaPipe (fallback LLM inference)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Hilt
-keep class dagger.hilt.** { *; }
