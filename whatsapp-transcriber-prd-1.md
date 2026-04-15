# PRD: WhatsApp Voice Transcriber
**Platform:** Android 11+ | **Language:** Kotlin | **Status:** Draft

---

## Problem

WhatsApp voice messages are inaccessible without listening. There's no native transcription. Users waste time scrubbing through audio to find information.

## Goal

A local Android app that auto-transcribes WhatsApp voice notes and surfaces them inline — no cloud, no account, no internet required.

---

## User Flow

1. First launch → user grants folder access via system folder picker (SAF)
2. App indexes existing voice notes and transcribes them in the background
3. New voice notes are detected and transcribed automatically
4. User browses transcriptions in a simple list, searchable by text

---

## Technical Architecture

### File Access
- **Storage Access Framework (SAF)** — `ACTION_OPEN_DOCUMENT_TREE` for one-time folder grant
- Persist URI permission across reboots via `takePersistableUriPermission()`
- Target: `/sdcard/WhatsApp/Media/WhatsApp Voice Notes/` or `Android/data/com.whatsapp/` (user selects)
- Watch for new files using `FileObserver` or periodic `WorkManager` polling

### Audio Pipeline
```
.opus file (SAF URI)
  → MediaCodec decoder (OPUS → PCM 16kHz mono)
  → Whisper.cpp via JNI (whisper-android bindings)
  → Transcript string
```
- Use `MediaExtractor` + `MediaCodec` for opus decoding
- Whisper model: `ggml-base.en.bin` (~150MB) stored in app's internal storage
- First-launch model download or bundle as an asset

### Transcription Engine
- **whisper.cpp** via [whisper-android](https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android)
- JNI bindings — call from a Kotlin coroutine on `Dispatchers.Default`
- Model: `base` for balance of speed/accuracy; `tiny` for low-end devices

### Storage
- **Room (SQLite)** — store transcriptions keyed by filename + last-modified timestamp
- Schema: `id`, `filename`, `uri`, `transcription`, `duration_ms`, `created_at`, `conversation_folder`

### Background Processing
- **WorkManager** — `PeriodicWorkRequest` every 15 min to scan for new files
- Alternatively: foreground service with `FileObserver` for near-realtime detection
- Transcription runs on a background thread, never blocks UI

---

## Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Architecture | MVVM + Repository |
| Async | Coroutines + Flow |
| Background | WorkManager |
| DB | Room |
| Transcription | whisper.cpp (JNI) |
| File Access | SAF (`DocumentFile` API) |
| DI | Hilt |
| Build | Gradle (Kotlin DSL) |

---

## Screens

### 1. Onboarding
- Explain what the app does
- Button to open folder picker → select WhatsApp Voice Notes folder
- Store granted URI

### 2. Transcriptions List
- Grouped by WhatsApp conversation folder (subfolder name)
- Each item: filename/date, duration, transcription preview
- Tap to expand full transcript
- Search bar (queries Room)

### 3. Settings
- Change watched folder
- Select Whisper model size (tiny / base / small)
- Toggle background scanning on/off
- Storage used

---

## Permissions

```xml
<!-- Only these two — no mic, no network -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

SAF handles file access without broad storage permission on API 33+.

---

## Key Risks & Mitigations

| Risk | Mitigation |
|---|---|
| WhatsApp moves files to `Android/data/` (scoped) | SAF handles both paths; user picks correct folder |
| Opus decoding failures | Fallback: copy to cache dir, decode with FFmpeg static lib |
| Whisper too slow on low-end devices | Offer `tiny` model; show progress indicator |
| Large backlog on first run | Batch process with WorkManager; show progress in notification |
| WhatsApp encrypts `.opus` files | Unlikely (local files are unencrypted); document as known limitation |

---

## Out of Scope (v1)

- Real-time transcription while message plays
- Sharing transcriptions to WhatsApp
- Multi-language model switching in-app
- iOS support
- Transcription of video messages

---

## Success Metrics (personal use)

- New voice note transcribed within 60s of receipt
- >90% word accuracy on clear speech (base model baseline)
- App uses <100MB RAM during transcription
- Battery impact negligible when idle
