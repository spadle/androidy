# Voice Reader — Session Handoff

## Project Overview
Android app: AI-powered voice reader that extracts text from any app screen, analyzes it with an on-device LLM, and reads it back intelligently with voice modulation.

**Use case**: Open a long Facebook post → say "read this" → the AI scrolls, extracts text, skips filler/ads, emphasizes important parts, adds commentary, and reads it via TTS.

## Branch
All code is on: `claude/android-voice-text-reader-4dfQ0`

Switch to it:
```bash
git fetch --all
git checkout claude/android-voice-text-reader-4dfQ0
```

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **DI**: Hilt (KSP)
- **LLM**: Gemma 4 E2B (2.5B params, ~1.3GB) via LiteRT-LM (primary) / MediaPipe (fallback)
- **TTS**: Android TextToSpeech with SSML support + fallback to setPitch/setSpeechRate
- **STT**: Android SpeechRecognizer (continuous listening)
- **Screen reading**: AccessibilityService
- **Architecture**: MVVM, Foreground Service, pipeline pattern
- **Min SDK**: 26 | **Target SDK**: 35 | **Java**: 17

## Architecture

```
Voice Command (SpeechRecognizer)
    → ReadingPipeline
        → ScreenReaderAccessibilityService (extract + scroll)
        → GemmaLlmEngine (analyze, annotate with [IMPORTANT/SKIP/NOTE/SUMMARY/NORMAL])
        → IntelligentTtsEngine (SSML or fallback, voice modulation per segment type)
```

### Key Components

| Component | File | Purpose |
|-----------|------|---------|
| AccessibilityService | `accessibility/ScreenReaderAccessibilityService.kt` | Extracts text from any app, auto-scrolls |
| Voice Listener | `service/VoiceCommandListener.kt` | Continuous SpeechRecognizer, trigger phrases |
| Foreground Service | `service/VoiceAgentService.kt` | Background operation, coordinates all parts |
| LLM Engine | `llm/GemmaLlmEngine.kt` | Dual-backend (LiteRT-LM + MediaPipe), auto model discovery |
| LLM Backends | `llm/LiteRtLmBackend.kt`, `llm/MediaPipeBackend.kt` | Abstracted inference backends |
| TTS Engine | `tts/IntelligentTtsEngine.kt` | Voice modulation per segment type |
| SSML Renderer | `tts/SsmlRenderer.kt` | Converts segments to SSML with prosody/emphasis/breaks |
| Pipeline | `pipeline/ReadingPipeline.kt` | Orchestrates Extract → Analyze → Speak |
| UI | `ui/MainScreen.kt`, `ui/MainViewModel.kt` | Compose UI with setup checklist |
| DI | `di/AppModule.kt` | Hilt singleton providers |
| App | `VoiceReaderApp.kt` | @HiltAndroidApp |

## LLM Backend Strategy

Priority order for model files:
1. `gemma-4-e2b-it.litertlm` → LiteRT-LM (NPU/GPU accelerated, best)
2. `gemma-4-e2b-it.tflite` → LiteRT-LM via LiteRT core (GPU)
3. `gemma-4-e2b-it.task` → MediaPipe (GPU)
4. `gemma-4-e4b-it.*` → Gemma 4 E4B variants (larger)
5. `gemma-2b-it-gpu-int4.bin` → Legacy Gemma 2B

**TFLite IS LiteRT** (Google rebranded TensorFlow Lite → LiteRT in 2024). Dependencies:
- `com.google.ai.edge.litert:litert` — core runtime
- `com.google.ai.edge.litert:litert-gpu` — GPU delegate
- `com.google.ai.edge.litertlm:litertlm-android` — LLM layer
- `com.google.mediapipe:tasks-genai:0.10.27` — fallback

## Dependencies (gradle/libs.versions.toml)
- AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28
- Compose BOM 2024.12.01, Hilt 2.56
- MediaPipe 0.10.27, LiteRT 2.0.0, LiteRT-LM 1.0.0

## To Test
1. Open project in Android Studio, switch to branch above
2. Let Gradle sync
3. Download Gemma 4 E2B model from HuggingFace (`litert-community/gemma-4-E2B-it-litert-lm`)
4. Push model to device: `adb push gemma-4-e2b-it.task /sdcard/Android/data/com.androidy.voicereader/files/`
5. Run on physical device (needs mic + accessibility permissions)
6. Enable accessibility service in Settings
7. Tap "Initialize AI Engine" → "Start Voice Agent"
8. Open any app → say "read this"

## What's Done (MVP complete)
- [x] Full project scaffolding (Gradle, manifests, Hilt)
- [x] AccessibilityService with auto-scrolling text extraction
- [x] Voice command listener with trigger phrases
- [x] Gemma 4 E2B integration (LiteRT-LM + MediaPipe dual backend)
- [x] Intelligent TTS with SSML + voice modulation
- [x] Reading pipeline (Voice → Extract → Analyze → Speak)
- [x] Foreground service for background operation
- [x] Jetpack Compose UI with setup checklist
- [x] Gradle wrapper for clone-and-build

## What's Next (potential)
- [ ] Settings screen (speech rate, pitch, trigger phrases, model selection)
- [ ] Reading history with color-coded annotated segments
- [ ] Pause/Resume/Replay voice commands
- [ ] In-app model download manager
- [ ] Floating overlay bubble for status while in other apps
- [ ] On-device TTS model (TFLite-based) for better offline quality
- [ ] The user had a second idea they forgot — ask them about it
