# 🌟 AURA — Ambient On-Device Personal AI Companion

> **100% Private, Always-Listening Companion powered entirely on-device by Google Gemma 2B and MediaPipe GenAI.**
> *Built for Hackathon / Makeathon 2026*

---

## 📌 Overview

AURA is an ambient personal AI companion inspired by modern AI wearables (like OMI / Friend), but built entirely into your Android smartphone with **zero server dependency** for speech and core reasoning:

- 🎙️ **Ambient Listening & Sliding Audio Windowing**: Listens to conversations in the background with a 60-second cap and 30-second silence watchdog.
- 🧠 **On-Device Gemma 2B LLM**: Summarizes conversations, cleans phonetic speech errors, and filters out casual small talk entirely on your phone.
- ⚡ **Dynamic Model Picker**: Select your `.bin` model file from **any** folder (Downloads, SD card, custom folders) with one tap — no hardcoded paths required!
- ✅ **Automatic Task & Commitment Extraction**: Automatically extracts action items, todos, and deadlines into a prioritized task list.
- 🛡️ **Privacy Shield**: Zero audio ever leaves the device. Only optional real-time lookups (e.g. OpenWeather) make network calls, and every outbound request is logged in the Privacy Shield UI.

---

## 🚀 Quick Start for Teammates & Collaborators

### 1. Clone the Repository
```bash
git clone https://github.com/Live-Bug/Personal-AI-Assistant.git
cd Personal-AI-Assistant
```

### 2. Configure `local.properties` (API Key)
In the project root directory, create or edit `local.properties` and add:
```properties
WEATHER_API_KEY=8607b8473f6168d5cc58040b2579bd38
```
*(This file is git-ignored to keep credentials safe).*

### 3. Obtain the Gemma 2B Model File
Download the Gemma 2B CPU/GPU quantized INT4 model (`.bin` format) from Kaggle:
- **Model:** [Gemma 2B IT (Instruction Tuned) - MediaPipe INT4](https://www.kaggle.com/models/google/gemma/tfLite)
- Recommended file: `gemma-2b-it-cpu-int4.bin` or `gemma-2b-it-gpu-int4.bin` (~1.4 GB)
- Place it anywhere on your phone (e.g. in your phone's `Download/` folder).

### 4. Build and Run in Android Studio
1. Open this folder in **Android Studio (Ladybug / Koala / Jellyfish)**.
2. Allow Gradle sync to complete.
3. Run on a physical Android device (Android 10+ recommended, 6GB+ RAM).
4. On first launch, grant Microphone and All Files Access permissions.
5. Tap **`[Select Model File (.bin)]`** to choose your `.bin` file. Aura will load it into memory and become **Ready**!

---

## 🏗️ Architecture & Pitch Documentation

Full High-Level Design (HLD), Low-Level Design (LLD), component diagrams, and pitch scripts are included in this repo:

- 📄 **PDF Presentation Guide**: [`AURA_Architecture_Guide.pdf`](./AURA_Architecture_Guide.pdf)
- 🌐 **Interactive HTML Pitch Doc**: [`AURA_System_Architecture_and_Presentation_Guide.html`](./AURA_System_Architecture_and_Presentation_Guide.html)
- 📝 **Markdown Spec**: [`AURA_SYSTEM_ARCHITECTURE_AND_PITCH_GUIDE.md`](./AURA_SYSTEM_ARCHITECTURE_AND_PITCH_GUIDE.md)

---

## 🛠️ Tech Stack

| Layer | Technologies |
|---|---|
| **Language** | Kotlin 2.0+ (Coroutine Flows, StateFlow) |
| **UI** | Jetpack Compose (Material 3 Dark Theme) |
| **On-Device LLM** | Google Gemma 2B (MediaPipe Tasks GenAI, CPU/GPU backend) |
| **Speech & Audio** | Android SpeechRecognizer, TextToSpeech, Foreground Service |
| **Local Database** | SQLite via Room (Conversations, Memories, Tasks) |
| **Networking** | Retrofit 2 + OkHttp (Weather & Real-time query fallback) |
