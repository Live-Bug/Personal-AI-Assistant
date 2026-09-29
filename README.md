# AURA

AURA is an always-listening personal AI companion for Android. Every step runs on the phone: capturing
conversations, transcribing them, and turning each one into a summary, tasks and memories. The only thing
that goes online is an optional real-time lookup (weather, news).

It is a prototype, built around the idea behind Omi-style wearables, with the phone as the device.
The problem statement is in [`Problem Statement.txt`](Problem%20Statement.txt).

> The other documents in this repo (`AURA_PROJECT_KNOWLEDGE_TRANSFER.md`,
> `AURA_SYSTEM_ARCHITECTURE_AND_PITCH_GUIDE.md`, `AURA_TEST_READ_ALOUD_SCRIPT.md`, the HTML/PDF guides)
> describe an earlier version: MediaPipe, Gemma 2B, Android's `SpeechRecognizer`, and 60-second
> processing blocks. Where they differ, this README is current.

## How it works

```
 mic (16 kHz, foreground service)
   │
   ▼
 Silero VAD ──► utterances ──► Parakeet TDT speech-to-text        (sherpa-onnx, CPU)
                                   │
                                   ▼
                     ConversationTracker ── stores each line in Room
                                   │  closes a conversation after 2 min of silence,
                                   │  when listening stops, or after 60 min
                                   ▼
                     ConversationProcessor ── one Gemma call per ~10 min chunk
                                   │          → title, summary, action items, memories
                                   ▼
                     Room: conversations · tasks · memories (full-text indexed)
                                   ▲
 Home "Ask Aura" ── AuraAssistant ─┘  answers from memories, summaries, tasks
                        │               and the live transcript
                        └── OnlineLookupManager (weather/news only, every call logged)
```

- **Listening** is controlled by the mic in the middle of the bottom bar, and it is off by default.
  - The first tap asks for microphone and notification permission, then (once per install) for an
    exemption from battery optimization, and starts the foreground service.
  - Tapping again stops the service; so does **Stop** in the notification.
  - The icon shows the state: off, starting, listening, and a waveform while it hears speech.
  - If listening was left on, it turns back on at the next launch.
- **Speech-to-text** uses Silero VAD to cut the audio into utterances, which Parakeet TDT 110M
  (English, int8) transcribes with punctuation and casing. Audio is only held in memory and is
  never written to disk.
- **Batch processing.** A closed conversation goes through Gemma 4 E2B once per chunk of about
  8,000 characters (roughly 10 minutes of speech). A longer conversation gets one extra call to
  merge the chunk summaries.
  - The prompt asks for an explicit `keep` decision first, so greetings, small talk and TV or radio
    audio are discarded instead of being saved as memories.
  - Conversations under 8 words skip the model entirely.
  - If no model file is available, conversations wait in the queue and are processed once one is
    chosen.
- **Model lifecycle.** Gemma (about 2.3 GB in memory) loads when it's first needed and is released
  after 5 idle minutes. After the first launch, loading takes about 5 s because the GPU kernels are
  cached.
- **Memories** come from two places: Gemma picks them out of conversations, and typing
  "remember that …" saves one directly. Questions to Aura are not stored as memories. Search uses
  SQLite FTS4 and matches individual words.

## Screens

| Tab | What it shows |
|---|---|
| Home | Summarized conversations and your chat with Aura, in time order. The conversation in progress appears as a live card. Type here to ask a question, add a task ("remind me to …"), or save a memory ("remember that …"). |
| Tasks | Tasks pulled from conversations or added by hand, with checkboxes and deadlines. |
| Memories | Memories, with search. |
| Settings | Battery-optimization status, model file and state, the privacy statement, and a log of every network call. |

## Tech stack

| Part | Choice |
|---|---|
| Language model | Gemma 4 E2B (`gemma-4-E2B-it.litertlm`) via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) `0.17.1`. It runs on the GPU (OpenCL), falls back to CPU, and uses speculative decoding with the model's bundled drafter. |
| Speech | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) `1.13.8`: Silero VAD + NVIDIA Parakeet TDT 110M (int8) |
| UI | Jetpack Compose, Material 3 with dynamic color (wallpaper-based), light and dark |
| Storage | Room 2.8 (KSP), with FTS4 tables for memories and conversation summaries |
| Network | Retrofit, for OpenWeatherMap only |
| Build | AGP 8.13, Kotlin 2.3, Gradle 8.14, JDK 21+ (LiteRT-LM ships Java 21 bytecode) |

Min SDK 29, target SDK 35. Native libraries ship for `arm64-v8a` and `x86_64` only.

## Code map

```
app/src/main/java/com/aura/companion/
├── AuraApplication.kt          app-wide singletons: DB, Gemma, capture, tracker, processor
├── MainActivity.kt             model-file access, resumes listening if it was left on
├── service/AuraForegroundService.kt   microphone foreground service, notification, wake lock
├── capture/
│   ├── AsrEngine.kt            sherpa-onnx VAD + Parakeet wrapper
│   └── CaptureController.kt    AudioRecord loop → utterances
├── pipeline/
│   ├── ConversationTracker.kt  groups utterances into conversations
│   └── ConversationProcessor.kt  batch summarization queue
├── ai/
│   ├── GemmaManager.kt         LiteRT-LM engine, load/unload, prompts, JSON parsing
│   ├── AuraAssistant.kt        answers questions from local context
│   └── OnlineLookupManager.kt  weather/news, with the call log
├── data/db/                    Room entities, DAOs, FtsQuery
├── viewmodel/AuraViewModel.kt
└── ui/                         navigation (bottom bar + mic), home, tasks, memory, settings, theme
```

## Build

You need JDK 21 or newer and the Android SDK.

```bash
# Linux/macOS (the repo only has gradlew.bat, so call the wrapper jar directly)
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug

# Windows
gradlew.bat :app:assembleDebug
```

The first build downloads about 160 MB from the sherpa-onnx GitHub releases and checks each file's
SHA-256 (see `fetchSpeechModels()` in `app/build.gradle.kts`):

- the sherpa-onnx AAR, into `app/libs/`
- the VAD and Parakeet models, into `app/src/main/assets/asr/`

Both locations are git-ignored. The debug APK is about 320 MB, mostly the speech model and the
native libraries for two ABIs.

**Weather API key.** Put your own key in `local.properties` as `WEATHER_API_KEY=...`. The build
script still falls back to a hardcoded key, which should be rotated and removed.

## Run

1. Put the model on the phone. Download `gemma-4-E2B-it.litertlm` (2.59 GB) from
   [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
   on Hugging Face, then push it to one of the paths the app searches:
   ```bash
   adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/
   # or /sdcard/Download/ (the app asks for all-files access to read it there)
   ```
   You can also pick any `.litertlm` file in **Settings → Choose model file**.
2. Install and open the app:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Tap the mic and accept the prompts. Talk near the phone, then stay quiet for 2 minutes; the
   conversation card appears on Home once it has been summarized.

Useful logcat tags: `CaptureController`, `ConversationTracker`, `ConversationProcessor` and
`GemmaManager`. Debug builds also log prefill and decode speed for each model call.

## Tuning knobs

| Setting | Where | Value |
|---|---|---|
| Silence that closes a conversation | `ConversationTracker.GAP_MS` | 2 min |
| Longest conversation before it's force-closed | `ConversationTracker.MAX_DURATION_MS` | 60 min |
| Minimum words worth processing | `ConversationProcessor.MIN_WORDS` | 8 |
| Transcript chunk per Gemma call | `GemmaManager.MAX_TRANSCRIPT_CHARS` | 8,000 chars |
| Gemma context (KV cache) | `GemmaManager.MAX_NUM_TOKENS` | 4,096 tokens |
| Idle time before Gemma is released | `GemmaManager.IDLE_UNLOAD_MS` | 5 min |
| Pause that ends an utterance / longest utterance | `AsrEngine` (Silero config) | 0.8 s / 20 s |

## Privacy boundary

- Raw audio is never saved or uploaded; each utterance is transcribed in memory and discarded.
- Transcripts, conversations, tasks and memories live in a local Room database.
- All reasoning (summaries, extraction, answers) runs on-device with Gemma.
- Only weather and news lookups use the internet. They run when a typed question asks for them,
  and each call is listed under **Settings → Network calls**. Android's green mic indicator shows
  whenever the microphone is open.

## Known limitations

- **English only**, and speakers are not told apart (no diarization).
- **Model accuracy.** Gemma 4 E2B sometimes mangles spoken numbers ("forty two thousand" became
  "forty thousand" in testing), and it can occasionally mistake TV audio for conversation.
- **Background survival.** Android won't restart a microphone service from the background, so if the
  system kills the app, listening stays off until the app is opened again. Stock Android keeps it
  alive with the battery exemption; some OEM builds (Samsung, Xiaomi) are more aggressive.
- **No data migration.** A database schema change wipes local data (prototype setting).
- **News** is a placeholder until a NewsAPI key is added.
