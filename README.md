# Voice Note Hater

Because nobody has time to listen, or open WhatsApp just to read one voice note.

An Android app that auto-transcribes WhatsApp voice messages locally and shows the transcript in notifications, so you can read the message without opening WhatsApp. No cloud. No account. No internet required.

## What it does

- Auto-transcribes new WhatsApp voice messages in the background and posts transcript notifications
- Lets you read incoming voice notes from the notification shade without opening WhatsApp
- Fixes the Android WhatsApp limitation where transcription is manual per-message instead of automatic
- Transcribes WhatsApp voice notes using two on-device engines: [whisper.cpp](https://github.com/ggerganov/whisper.cpp) and [Moonshine](https://github.com/usefulsensors/moonshine) (via the official `ai.moonshine:moonshine-voice` Android SDK)
- Auto-detects new incoming voice notes via a `NotificationListenerService` and transcribes them in the background
- Identifies who sent each voice note from WhatsApp notifications (DMs and groups)
- Streams transcription text live as it's processed
- Karaoke-style highlighting during audio playback — each word/line pops to white in sync with audio
- 9 whisper models + 3 Moonshine models to pick from
- Compares any two models side-by-side on your own voice notes with the built-in benchmark

## Screenshots

<p align="center">
  <img src="screenshots/transcriptions.png" width="250" />
  <img src="screenshots/settings.png" width="250" />
  <img src="screenshots/benchmark.png" width="250" />
</p>

## Features

**Transcription**
- Tap any voice note to transcribe on-demand
- Live streaming text — watch words appear as the engine processes
- Karaoke-style word / line highlighting during playback, driven off wall-clock timing (audio and text stay in lock-step even on opus files where `MediaPlayer.currentPosition` lies)
- Long-press to copy the full transcript
- Retranscribe any voice note with a different model — the result is saved with the model name attached

**Auto-transcribe**
- `NotificationListenerService` catches WhatsApp voice-message posts, extracts sender (or "Sender @ Group" for groups), and fires a one-shot `QuickTranscribeWorker` via WorkManager
- When transcription finishes, the app posts a notification with the transcript text, so you can read it immediately from the shade
- On bind, the listener replays any WhatsApp notifications already in the tray (Android doesn't do this automatically after the OS kills the app)
- Optional 15-minute backup scan catches anything the listener missed because the OS froze our process — can be toggled off in Settings
- Both paths post progress + completion notifications
- Zero work when idle: no persistent foreground service

**Engines & models**
- Choose per-transcription which engine and model to use
- Long-press a downloaded model to delete it
- Built-in benchmark with a multi-select picker, engine badges, and side-by-side speed/transcript comparison

**Storage & UI**
- Dark mode only (pure black)
- Model storage usage visible at a glance
- Battery-optimization shortcut in Settings — one tap to whitelist the app so the listener stays alive on aggressive OEMs (OnePlus / Oppo / Xiaomi etc.)

## Requirements

- Android 8.0+ (API 26)
- arm64-v8a device (covers 99%+ of modern Android phones)
- ~30MB minimum (Moonshine Tiny) / ~150MB (Whisper Base)
- WhatsApp installed with voice notes accessible via Storage Access Framework

## Building

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools  # or your SDK path
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See [CLAUDE.md](CLAUDE.md) for the detailed development guide: native lib rebuilding, DB migrations, JNI bridge, debugging commands.

## Tech Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Architecture | MVVM + Repository + `TranscriptionEngine` interface |
| DI | Hilt (engine multi-binding `Map<String, TranscriptionEngine>`) |
| DB | Room (SQLite), JSON segments format with optional per-word timings |
| Async | Coroutines + Flow |
| Background | WorkManager + NotificationListenerService |
| Engines | whisper.cpp (JNI) · Moonshine (`ai.moonshine:moonshine-voice`) |
| File Access | Storage Access Framework |
| Audio | MediaCodec + MediaExtractor → 16 kHz mono float PCM |

## Models

### Whisper (via whisper.cpp)

| Model | Size | Speed | Quality | Languages |
|---|---|---|---|---|
| Tiny Q5 | 32 MB | ~8× realtime | Fair | English |
| Tiny | 78 MB | ~8× realtime | Fair | English |
| Base Q5 | 60 MB | ~3× realtime | Good | English |
| Base | 148 MB | ~2× realtime | Good | English |
| Small Q5 | 190 MB | ~1.5× realtime | High | English |
| Small | 488 MB | ~1× realtime | High | English |
| Medium Q5 | 539 MB | ~0.6× realtime | Very high | English |
| Turbo Q5 | 574 MB | ~0.5× realtime | Excellent | All |
| Turbo Q8 | 874 MB | ~0.4× realtime | Best | All |

Whisper provides fine-grained per-token timestamps, which the UI already uses for word-level highlighting.

### Moonshine (via official `ai.moonshine:moonshine-voice` SDK)

| Model | Size on disk | WER | Notes |
|---|---|---|---|
| Moonshine Tiny (EN) | ~42 MB | 12.7% | Fastest — great for quick previews |
| Moonshine Base (EN) | ~120 MB | 10.0% | Better accuracy, still lightweight |
| Moonshine Medium Streaming (EN) | ~245 MB | 6.65% | Best quality — streaming architecture |

Moonshine handles any audio length natively (no 30 s zero-padding like Whisper). Line-level timings are precise and come directly from the model; the SDK currently doesn't expose per-word timings, so Moonshine transcripts highlight at line granularity.

Speed figures are indicative — measured on a Pixel-class device. Run the built-in benchmark (Settings → Benchmark models) for numbers on your own hardware.

## How it works

1. **First launch** — grant folder access to WhatsApp Voice Notes via Android's folder picker. Grant notification access so the listener can detect new voice notes. If you're on OnePlus / Oppo / Xiaomi, tap the "Allow background activity" row in Settings to whitelist the app from battery optimization — otherwise the OS will freeze the listener.
2. **Scan** — indexes `.opus` files from WhatsApp's `YYYYWW/PTT-YYYYMMDD-WANNNN.opus` structure via ContentResolver.
3. **Transcribe** — decodes opus → PCM → 16 kHz mono float, routes to the selected engine (`WhisperEngine` or `MoonshineEngine`) via the common `TranscriptionEngine` interface.
4. **Auto-detect** — `WhatsAppNotificationListener` catches voice-message notifications, extracts sender, and triggers `QuickTranscribeWorker`. Transcription completes with a notification showing the text so you usually don't need to open WhatsApp at all.
5. **Results** — stored in Room with JSON `segments_json` (start/end/text per line, optional word array) and the model name. Playback highlights at word granularity for whisper, line granularity for Moonshine.

## Architecture notes

- `TranscriptionEngine` interface has two implementations wired via Hilt multi-binding. Each engine is a `@Singleton` with its own suspending `loadModel` / `transcribeWithTimings` / `release` methods, all guarded by a coroutine `Mutex` so a model switch can never race with an in-flight inference (this used to cause SIGSEGVs).
- `SegmentsCodec` handles the DB column format. New rows are JSON; old pipe-separated whisper rows still parse via a fallback branch.
- Moonshine uses the streaming API (`addAudioToStream` in ~1 s chunks) even for batch files — that's the path that emits live `LineStarted` / `LineTextChanged` / `LineCompleted` events for UI streaming. Events fire synchronously on the caller thread per the SDK source, so there's no completion barrier to wait on.
- The audio player's slider tracks wall-clock elapsed time since play/seek start, not `MediaPlayer.currentPosition` — the latter is unreliable on `.opus`.

## License

Private project.
