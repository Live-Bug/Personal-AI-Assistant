# 📘 The Ultimate AURA Guide: Complete Android & Project Deep Dive
> **Written specifically for Data Engineers who have never written Android code.**  
> *A friendly, zero-jargon, hand-holding breakdown of Android concepts, Gradle build tools, and every single file in the project.*

---

# 📑 TABLE OF CONTENTS
1. [PART 1: Android Concepts 101 (The Data Engineer Analogy)](#part-1-android-concepts-101)
2. [PART 2: The Android Build System (Gradle Demystified)](#part-2-the-android-build-system)
3. [PART 3: Exhaustive File-by-File Breakdown](#part-3-exhaustive-file-by-file-breakdown)
   - [A. Build & Configuration Files](#a-build--configuration-files)
   - [B. System & Android OS Integration](#b-system--android-os-integration)
   - [C. AI & Offline Inference (Gemma 2B)](#c-ai--offline-inference)
   - [D. Ambient Audio & Speech Pipeline](#d-ambient-audio--speech-pipeline)
   - [E. Database & Storage Layer (SQLite Room)](#e-database--storage-layer)
   - [F. State Management & Orchestration (ViewModel)](#f-state-management--orchestration)
   - [G. User Interface (Jetpack Compose)](#g-user-interface)
   - [H. Storage Utilities](#h-storage-utilities)
4. [PART 4: Step-by-Step Data Flow Traces (Follow the Data!)](#part-4-step-by-step-data-flow-traces)

---

# PART 1: Android Concepts 101
*(If you know Python, SQL, and data pipelines, here is how Android works under the hood).*

### 1. What is an Android App (APK)?
In data engineering, an application is often packaged as a Docker container or a Python wheel.
In Android, the entire app (compiled Java/Kotlin bytecode, images, database schemas, native C++ libraries) is compiled and zipped into a single file called an **`.apk`** (Android Package). The phone's operating system unpacks and runs this.

### 2. The Big 4 Android Components:
Android doesn't just run a `main()` script from top to bottom. Instead, the Android OS communicates with your app via specialized components:

| Android Component | What it is | Data Engineering Analogy |
|---|---|---|
| **Activity** (`MainActivity.kt`) | A single screen window with a graphical user interface. When the app is open, this is what the user sees. | A Streamlit / Dash UI dashboard. |
| **Service** (`AuraForegroundService.kt`) | A background worker that runs tasks without a UI. | A background Celery worker or daemon process. |
| **Foreground Service** | A special Service that displays a persistent notification in the phone's status bar. Android kills background apps aggressively to save battery; a Foreground Service tells Android: *"Do not kill this process, it's actively doing work for the user (recording audio)!"* | A process running with `nohup` or `systemd` with `Restart=always`. |
| **Application** (`AuraApplication.kt`) | The root parent container that stays alive as long as any part of the app is alive. Used to create global singletons like databases. | A global connection pool (e.g. SQLAlchemy engine). |

### 3. What is Jetpack Compose?
- **The Old Way (Pre-2021):** Android UI used XML files (`layout.xml`) with static trees and manually updated them via `findViewById()`.
- **The Modern Way (What we use):** **Jetpack Compose**. It is a **declarative UI framework** written 100% in Kotlin (similar to React or Flutter). You write functions annotated with `@Composable`. When your data (`State`) changes, the UI automatically re-draws itself!

### 4. What are Coroutines and StateFlow?
- **Coroutines:** Lightweight threads. Instead of blocking the phone's main thread (which would freeze the screen), heavy tasks (like reading a 1.5GB AI model or calling a weather API) are launched on `Dispatchers.IO` (background thread pool).
- **StateFlow:** A reactive data stream (like an observable Kafka topic or Python generator). When `AuraViewModel` updates a value (e.g., `isListening = true`), any UI component watching that StateFlow updates instantly.

### 5. What is Room?
Room is Google's official abstraction layer over **SQLite**. SQLite is an embedded relational SQL database engine that exists natively on every Android phone.
Room lets us define:
- **Entities** = SQL Tables (annotated with `@Entity`)
- **DAOs (Data Access Objects)** = SQL Queries (annotated with `@Query("SELECT * FROM ...")`)
- **Database** = The connection manager that creates the `.db` file on the phone's internal storage.

---

# PART 2: The Android Build System (Gradle Demystified)

Android projects use **Gradle** as their build automation tool (similar to how Python uses `pyproject.toml` / `poetry` / `pip`, or how Java uses `Maven`).

Here is what every Gradle file in the root directory does:

### 1. `settings.gradle.kts`
- **What it does:** The very first file Gradle reads when starting.
- **Why it exists:** It defines the project name (`rootProject.name = "Aura"`) and specifies where to download libraries from (Google's Maven repository, Maven Central). It also declares which modules exist (`include(":app")`).

### 2. `build.gradle.kts` (Project Root)
- **What it does:** The top-level configuration that applies across all sub-modules.
- **Why it exists:** It declares which build plugins are available (e.g., the Android Application plugin and Kotlin plugin) without actually configuring the app itself.

### 3. `gradle/libs.versions.toml` (Version Catalog)
- **What it does:** A centralized dictionary of all library dependencies and version numbers.
- **Why it exists:** Instead of hardcoding `"androidx.compose:2.0.0"` in multiple places, you define it here once (e.g. `compose = "1.7.0"`). Any module references it via `alias(libs.plugins.kotlin.android)`.

### 4. `gradle.properties`
- **What it does:** Environment variables for the Gradle compiler daemon.
- **Key settings:**
  - `org.gradle.jvmargs=-Xmx2048m`: Allocates 2GB of RAM to the Gradle compiler daemon.
  - `android.useAndroidX=true`: Tells the compiler to use modern AndroidX packages.
  - `android.nonTransitiveRClass=true`: Speeds up compilation by namespacing resources.

### 5. `local.properties` (GIT-IGNORED)
- **What it does:** Machine-specific settings that should **never** be checked into Git.
- **Contains:**
  1. `sdk.dir`: The local directory where Android SDK is installed on your computer (`C:\Users\...\AppData\Local\Android\Sdk`).
  2. `WEATHER_API_KEY`: Our OpenWeather API secret key. Because this file is git-ignored, your secret API key will never be leaked to public GitHub.

### 6. `gradlew.bat` & `gradle/wrapper/gradle-wrapper.properties`
- **What it is:** "Gradle Wrapper".
- **Why it matters:** It ensures that anyone who clones the project doesn't have to manually install Gradle. Running `gradlew.bat` automatically downloads the exact required version of Gradle (`8.7`) into a cache and runs the build.

---

# PART 3: Exhaustive File-by-File Breakdown

Let's walk through every single file in the project, module by module.

---

## A. Build & Configuration Files

### 📄 `app/build.gradle.kts` (The App's Recipe)
- **Role:** Tells the Android compiler how to build the `app` module.
- **Key Sections Explained:**
  - `compileSdk = 35`: The version of Android APIs the code is compiled against (Android 15).
  - `minSdk = 29`: The minimum Android version required to run Aura (Android 10).
  - `targetSdk = 35`: The version of Android the app was tested on and designed for.
  - **The Secrets Reader:**
    ```kotlin
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    buildConfigField("String", "WEATHER_API_KEY", "\"${localProperties.getProperty("WEATHER_API_KEY")}\"")
    ```
    This reads the API key from `local.properties` at build time and generates a Java constant `BuildConfig.WEATHER_API_KEY`, so code can access it safely without hardcoding!
  - `dependencies { ... }`: The packages we import:
    - `com.google.mediapipe:tasks-genai`: The C++ on-device LLM engine.
    - `androidx.room:*`: SQLite database ORM.
    - `androidx.compose.*`: Jetpack Compose declarative UI.
    - `com.squareup.retrofit2:*`: REST HTTP client for weather.

---

## B. System & Android OS Integration

### 📄 `app/src/main/AndroidManifest.xml`
- **Role:** The contract between the app and the Android OS kernel.
- **Key Sections Explained:**
  - `<uses-permission android:name="android.permission.RECORD_AUDIO" />`: Requests permission to access the phone's microphone.
  - `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />`: Requests broad filesystem access so the user can select their `.bin` Gemma model from *any* folder (Downloads, SD card, custom folders).
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />`: Permits the app to keep the microphone active when the user locks their screen.
  - `<application ... android:icon="@mipmap/ic_launcher">`: Sets the app icon to our custom glowing AURA logo.
  - `<uses-native-library android:name="libOpenCL.so" android:required="false" />`: Tells Android that if the phone's GPU supports OpenCL, link it so MediaPipe can accelerate Gemma inference on the GPU.
  - `<activity android:name=".MainActivity" ...>`: Declares that `MainActivity` is the entry screen with intent filter `MAIN` and `LAUNCHER`.

### 📄 `app/src/main/java/com/aura/companion/AuraApplication.kt`
- **Role:** The application-level lifecycle singleton.
- **What it does:** When the user taps the app icon, the OS boots `AuraApplication` first. It instantiates:
  ```kotlin
  val database: AuraDatabase by lazy { AuraDatabase.getDatabase(this) }
  ```
  Using `by lazy` ensures the SQLite database is only created when first accessed, saving startup time and memory.

### 📄 `app/src/main/java/com/aura/companion/MainActivity.kt`
- **Role:** The visual host activity that manages permissions and boots the UI.
- **What it does:**
  1. `requestRequiredPermissions()`: When the app launches, it checks if `RECORD_AUDIO`, `POST_NOTIFICATIONS`, and `MANAGE_APP_ALL_FILES_ACCESS` are granted. If not, it displays the standard Android permission prompt.
  2. `startAuraService()`: Starts `AuraForegroundService` so ambient listening can continue if the user minimizes the app.
  3. `setContent { AuraTheme { AuraNavHost() } }`: Loads the Compose user interface.

### 📄 `app/src/main/java/com/aura/companion/service/AuraForegroundService.kt`
- **Role:** Keeps Aura alive in the background.
- **Why it exists:** Android will kill any idle or background app after a few minutes to save battery. By running a `ForegroundService` with a persistent notification in the status bar (`"Aura is listening in background"`), Android guarantees the audio recording process will not be terminated.

---

## C. AI & Offline Inference (Gemma 2B)

### 📄 `ai/GemmaManager.kt`
- **Role:** On-device Large Language Model (LLM) Manager.
- **What it does:** Wraps Google's MediaPipe Tasks GenAI engine.
- **Key Functions:**
  1. `initialize(customPath: String? = null)`:
     - Thread-safe using `inferenceMutex.withLock` so two threads never try to load or query the model simultaneously.
     - Checks user's custom chosen path ➔ then checks saved path in `SharedPreferences` ➔ then checks default folders (`/storage/emulated/0/Download/...`).
     - Loads model into phone RAM via `LlmInference.createFromOptions()`.
  2. `evaluateAndExtract(conversationText)`:
     - Prompts Gemma to classify conversation speech.
     - If the text is filler or small talk ("yeah", "okay", "haha"), Gemma responds with `DISCARD`, which is dropped.
     - If it contains valuable commitments or facts, Gemma returns a 1-sentence summary that is saved into long-term memory.
  3. `correctAndInterpretSpeech(rawText)`:
     - Phone microphones across a room suffer from acoustic noise and phonetic mishearings.
     - Gemma reads the raw transcript and fixes misheard domain terms (e.g. *"date and friendship"* ➔ *"data pipeline"*, *"caugh car"* ➔ *"kafka"*) without changing the meaning.
  4. `extractAllTasks(rawText)`:
     - Asks Gemma: *"Extract all actionable tasks into TASK: [action] | TIME: [deadline]"*.
     - Parses the output into structured Kotlin data objects.
  5. `generateResponse(userInput, memoryContext, taskContext, onlineData)`:
     - Answers questions asked by the user in the "Ask Aura" dialog.
     - Injects today's conversation and relevant past memories into the context window, so Aura answers accurately based on your actual conversations.

### 📄 `ai/OnlineLookupManager.kt`
- **Role:** Handles real-time web lookups (weather/news) while enforcing strict privacy.
- **What it does:**
  1. `needsOnlineData(query)`: Checks if user's question mentions weather, temperature, rain, or news.
  2. `extractCity(query)`: Uses regex patterns (`\b(?:in|for|of|at)\s+([A-Za-z\s]+)`) to extract the requested city (e.g. *"What is the weather in Delhi?"* ➔ `"Delhi"`, *"Tokyo temperature"* ➔ `"Tokyo"`).
  3. `fetchWeather(city)`: Calls the OpenWeather REST API using Retrofit.
  4. `callLogs`: Maintains an in-memory audit log of every outbound HTTP request (timestamp, query, response) so the user can verify in the **Privacy Shield** screen that no audio or personal conversation was leaked.

---

## D. Ambient Audio & Speech Pipeline

### 📄 `audio/SpeechManager.kt`
- **Role:** Speech-to-Text (ASR) audio ingestion.
- **What it does:**
  - Wraps Android's `SpeechRecognizer` class.
  - Implements an event listener (`RecognitionListener`):
    - `onPartialResults()`: Emits interim transcribed words into `recognizedText: StateFlow<String>` in real-time as words are spoken.
    - `onResults()`: Flushes the completed speech segment.
    - `onError()`: If silence occurs or no speech is recognized, it immediately restarts listening instead of crashing.
  - `pauseListening()` & `resumeListening()`: Coordinated with `TTSManager` so Aura never records her own spoken replies!

### 📄 `audio/TTSManager.kt`
- **Role:** Text-To-Speech engine.
- **What it does:** Converts Aura's generated AI text responses into natural spoken voice using Android's native `TextToSpeech` synthesizer. Emits `isSpeaking: StateFlow<Boolean>` so the rest of the app knows when speaking begins and ends.

---

## E. Database & Storage Layer (SQLite Room)

### 📄 `data/db/AuraDatabase.kt`
- **Role:** Room Database Configuration.
- **What it does:** Creates the SQLite database file (`aura_database.db`) on internal storage and registers the 3 tables: `ConversationSegment`, `Memory`, and `Task`.

### 📄 `data/db/ConversationSegment.kt` & `ConversationSegmentDao.kt`
- **Role:** Raw Conversation Log.
- **Entity Fields:** `id` (auto-increment primary key), `text` (transcribed speech), `timestamp` (time in milliseconds), `speakerId` (speaker ID).
- **DAO Queries:** `getTodaySegments(startOfDay)` retrieves all conversation blocks recorded since 12:00 AM today, used to provide context to Gemma.

### 📄 `data/db/Memory.kt` & `MemoryDao.kt`
- **Role:** The "Second Brain" permanent knowledge base.
- **Entity Fields:** `id`, `userInput`, `aiResponse`, `category` (e.g. "summary", "task", "weather"), `tags` (e.g. "#meeting #project"), `timestamp`.
- **DAO Queries:** `searchMemories(query)` performs a SQL `LIKE %query%` search to find relevant past facts when answering user queries.

### 📄 `data/db/Task.kt` & `TaskDao.kt`
- **Role:** Extracted Action Items & Todos.
- **Entity Fields:** `id`, `title` (e.g. "Call John"), `dueTime` (e.g. "5:00 PM"), `isCompleted` (Boolean), `createdAt`, `completedAt`.
- **DAO Queries:** `getPendingTasks()` returns all uncompleted tasks, which is displayed on the Tasks screen and injected into Gemma's context.

### 📄 `data/api/ApiClient.kt` & `ApiModels.kt`
- **Role:** HTTP Networking.
- **What it does:** Sets up a Retrofit client with OkHttp pointing to `https://api.openweathermap.org/data/2.5/` and maps JSON responses into Kotlin data classes (`WeatherResponse`, `WeatherMain`).

---

## F. State Management & Orchestration (ViewModel)

### 📄 `viewmodel/AuraViewModel.kt` *(The Core Conductor)*
- **Role:** The central brain that connects Audio, AI, Database, and UI.
- **Key Mechanics Explained:**
  1. **Dual-Trigger Sliding Audio Window:**
     - Instead of processing audio every 2 seconds (which would overload the mobile CPU), audio is buffered.
     - **Trigger 1 (Max Cap = 60s):** If someone talks continuously for 60 seconds, the window flushes automatically.
     - **Trigger 2 (Silence Watchdog = 30s):** If someone speaks and then stops, a 30-second silence timer begins. If nobody speaks for 30s, the conversation is considered ended and the buffer flushes.
  2. **Automated Batch Processing:**
     - When the window flushes, the text is saved to `ConversationSegment`.
     - In the background, `gemmaManager.extractAllTasks()` checks for action items and adds them to `TaskDao`.
     - `gemmaManager.evaluateAndExtract()` generates a 1-sentence summary if the chunk was important.
  3. **`loadModel(customPath)`:**
     - Called when the user picks a model via the file picker. Reboots Gemma with the chosen `.bin` path and updates `_uiState` to ready.
  4. **`askQuestion(query)`:**
     - Pulls today's conversation context from Room + past memories.
     - If query is weather, extracts the city and fetches live weather.
     - Prompts Gemma with the assembled context.
     - Speaks the response with `ttsManager` and saves to `MemoryDao`.

---

## G. User Interface (Jetpack Compose)

### 📄 `ui/navigation/AuraNavHost.kt`
- **Role:** Screen Navigation.
- **What it does:** Sets up a Jetpack Compose `NavHost` with 4 bottom navigation tabs:
  - 🏠 **Aura** (`HomeScreen`)
  - 🧠 **Memories** (`MemoryScreen`)
  - ✅ **Tasks** (`TasksScreen`)
  - 🛡️ **Privacy** (`PrivacyScreen`)

### 📄 `ui/home/HomeScreen.kt`
- **Role:** The main screen.
- **What it displays:**
  - **Header Row:** The glowing AURA logo icon, a compact model pill (`Gemma 2B`) that launches the file picker on tap, and the `Local`/`Online` status indicator.
  - **Model Missing Banner:** If no model is found on the phone, renders a prominent button: **`[Select Model File (.bin)]`**.
  - **Status Card:** Shows whether Aura is actively listening or in standby.
  - **Live Feed (`LazyColumn`):** Streams conversation cards, system notifications, and AI summaries with auto-scrolling.
  - **Action Bar:** "Ask Aura" icon button, main microphone Start/Pause toggle button, and "Day Summary" button.

### 📄 `ui/tasks/TasksScreen.kt`
- **Role:** The Task Dashboard.
- **What it does:** Displays all tasks extracted by Gemma from spoken conversations. Users can tap checkboxes to mark tasks as done (persisting to SQLite), delete tasks, or add tasks manually.

### 📄 `ui/memory/MemoryScreen.kt`
- **Role:** Second Brain Search.
- **What it does:** Shows all summarized conversation memories and query history with an interactive search bar and tag filtering.

### 📄 `ui/privacy/PrivacyScreen.kt`
- **Role:** Privacy Shield Transparency Dashboard.
- **What it does:** Displays our 4 core privacy guarantees and lists every single outbound HTTP network request in real-time, proving that zero conversation audio ever leaves the phone.

### 📄 `ui/theme/Color.kt`, `Theme.kt`, `Type.kt`
- **Role:** Design System.
- **What it does:** Defines the dark cyber/ambient theme: `#0D0B18` (deep dark background), `#D0BCFF` (neon violet), `#80DEEA` (electric cyan), and typography font scales.

---

## H. Storage Utilities

### 📄 `util/PathUtils.kt`
- **Role:** Resolves physical file paths from Android Document URIs.
- **Why this file is vital:**
  - Modern Android uses "Scoped Storage". When a user picks a file, Android gives the app a virtual URI: `content://com.android.providers.downloads.documents/document/raw%3A...`
  - MediaPipe's C++ native engine **cannot open virtual URIs**; it requires a physical POSIX path: `/storage/emulated/0/Download/gemma-2b-it-cpu-int4.bin`.
  - `PathUtils.getPathFromUri()` parses document IDs, resolves external storage paths across internal storage and SD cards, and falls back to app cache if necessary.

---

# PART 4: Step-by-Step Data Flow Traces

### Trace 1: When You Tap the App Icon (App Launch)
```
1. Android OS boots AuraApplication 
   └── AuraDatabase created (SQLite connection established)
2. MainActivity starts
   ├── Requests Permissions (Microphone, Storage, Notifications)
   ├── Starts AuraForegroundService (Persistent notification appears)
   └── Launches AuraNavHost (Jetpack Compose UI renders)
3. AuraViewModel boots
   ├── SpeechManager & TTSManager initialized
   └── GemmaManager.initialize() runs in background thread (Dispatchers.IO)
       ├── Checks custom path -> SharedPreferences -> /Download/
       ├── MediaPipe loads Gemma 2B weights into memory
       └── UI updates: Header displays [📁 Gemma 2B] and status turns to "Standby"
```

### Trace 2: You Speak a Task ("Remind me to submit project by 5 PM")
```
1. Microphone captures audio -> SpeechManager (ASR)
2. SpeechManager streams interim text to AuraViewModel
3. 30 seconds of silence occurs -> Silence Watchdog flushes buffer
4. Text saved to ConversationSegment (SQLite)
5. GemmaManager.extractAllTasks() runs on-device:
   └── Extracts: Title = "Submit project", DueTime = "5:00 PM"
6. Task saved to TaskDao (SQLite)
7. UI updates: Tasks screen immediately shows the new checkbox todo item!
```

### Trace 3: You Ask "What is the weather in Tokyo?"
```
1. You tap "Ask Aura" and submit query
2. OnlineLookupManager.needsOnlineData("weather in Tokyo") returns WEATHER
3. OnlineLookupManager.extractCity("weather in Tokyo") extracts "Tokyo"
4. OpenWeather API is called with city="Tokyo" -> returns "22°C, Sunny"
5. Request is logged to Privacy Shield callLogs
6. AuraViewModel pulls today's conversation from SQLite
7. GemmaManager generates response using local context + live weather
8. TTSManager speaks aloud: "The weather in Tokyo is 22°C and sunny."
```
