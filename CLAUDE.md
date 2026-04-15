# Voice Note Hater (wa-transcribe)

Android app that auto-transcribes WhatsApp voice notes locally using whisper.cpp. No cloud, no internet required.

## Quick Start

```bash
# Build
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug

# Deploy to connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.watranscribe/.MainActivity

# Run tests
./gradlew testDebugUnitTest

# Watch logs during development
adb logcat -s TranscriptionRepo:D WhisperJNI:I WhisperEngine:D AudioDecoder:D WANotifListener:D QuickTranscribe:D

# Performance logs specifically
adb logcat | grep PERF
```

## Architecture

**MVVM + Repository + Hilt DI**, Jetpack Compose UI, Room DB, WorkManager background tasks.

```
app/src/main/java/com/watranscribe/
  data/
    local/          # Room: TranscriptionEntity, TranscriptionDao, AppDatabase
    repository/     # TranscriptionRepository, PreferencesRepository
  di/               # Hilt AppModule (DB, DAO providers)
  engine/           # Core engine layer
    WhisperJni.kt       # JNI bindings (loads libwatranscribe-jni.so)
    WhisperEngine.kt    # Thread-safe whisper context manager (Mutex-guarded)
    AudioDecoder.kt     # MediaCodec opus->PCM->16kHz mono float pipeline
    ModelManager.kt     # Download/manage GGML models from HuggingFace
    Benchmarker.kt      # Per-model benchmark runner
    TranscriptionNotifier.kt  # Notification management
    WhatsAppNotificationListener.kt  # NotificationListenerService for contact detection
    PersistentService.kt  # (unused, removed)
  ui/
    theme/          # Dark-only theme (black bg, white text, grey accents)
    navigation/     # NavHost: onboarding -> transcriptions -> settings -> benchmark
    onboarding/     # SAF folder picker
    transcriptions/ # Main list with streaming text, audio player, timed highlighting
    settings/       # Model download/selection, notification access, test notifications
    benchmark/      # Model comparison tool
  worker/
    TranscriptionWorker.kt      # Periodic 15min background scan
    QuickTranscribeWorker.kt    # Triggered by notification listener for new voice notes
app/src/main/cpp/
  jni.c             # C JNI bridge to whisper.cpp (segment callbacks, timestamps)
  CMakeLists.txt    # Links against prebuilt libwhisper.so
app/src/main/jniLibs/arm64-v8a/
  libwhisper.so, libggml.so, libggml-base.so, libggml-cpu.so  # Prebuilt from whisper.cpp
```

## Key Technical Details

### Whisper.cpp Native Build
The native libs were built from `/tmp/whisper.cpp` (cloned from ggerganov/whisper.cpp) with:
```bash
cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DBUILD_SHARED_LIBS=ON -DGGML_OPENMP=OFF  # MUST disable OpenMP for Android
```
**Critical**: OpenMP must be OFF (`-DGGML_OPENMP=OFF`) or the app crashes with `libomp.so not found`.

### JNI Bridge (`jni.c`)
- `initContext(modelPath)` -> returns context pointer
- `fullTranscribe(ctx, samples, threads, callback)` -> returns full text, calls `onSegment(text)` per segment
- `getSegments(ctx)` -> returns `"startMs|endMs|text\n"` lines for timed highlighting
- Key params: `suppress_blank=1`, `suppress_nst=1`, `token_timestamps=1`, `max_len=0`
- Uses global refs for callback objects, checks exceptions after JNI calls

### WhatsApp Voice Notes Structure
```
/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/
  202616/                    # YYYYWW (year + ISO week number)
    PTT-20260415-WA0019.opus # PTT-YYYYMMDD-WANNNN.opus
```
- Accessed via SAF (Storage Access Framework), URI persisted across reboots
- Scanned using ContentResolver queries (NOT DocumentFile — too slow for 1000+ files)
- Duration extracted via MediaMetadataRetriever during scan

### Contact Detection
- `WhatsAppNotificationListener` (NotificationListenerService) intercepts WhatsApp notifications
- DM: `EXTRA_TITLE` = contact name, `EXTRA_TEXT` = "Voice message (0:03)"
- Group: `EXTRA_MESSAGES` (MessagingStyle) has `sender` field, title = group name
- Format: "Sender @ Group" for groups, just "Sender" for DMs
- `PendingContactMatch` in-memory buffer matches sender to file by timestamp proximity (60s window)
- Requires user to manually enable in Android Settings > Notification access

### Audio Pipeline
```
.opus (SAF URI)
  -> MediaExtractor + MediaCodec (opus -> PCM 16-bit)
  -> pcmBytesToFloat (16-bit -> float [-1,1])
  -> resampleTo16kHz (48kHz stereo -> 16kHz mono, linear interpolation)
  -> whisper.cpp fullTranscribe
```

### Database
Room SQLite, currently version 4. Migrations are manual (MIGRATION_1_2 through MIGRATION_3_4).
Key columns: `filename`, `uri`, `transcription`, `duration_ms`, `conversation_folder`, `last_modified`, `status`, `segments_json`, `contact`, `model_used`.

### Models
Downloaded from `huggingface.co/ggerganov/whisper.cpp/resolve/main/` to `files/models/`.
Range: 32MB (tiny-q5) to 874MB (turbo-q8). English-only (`.en`) and multilingual available.
Model selection stored in DataStore preferences.

## Common Dev Tasks

### Rebuild native libs (if updating whisper.cpp)
```bash
git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git /tmp/whisper.cpp
export NDK=$ANDROID_HOME/ndk/27.2.12479018
$ANDROID_HOME/cmake/3.22.1/bin/cmake \
  -S /tmp/whisper.cpp -B /tmp/whisper-build-arm64 \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DGGML_OPENMP=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF
cmake --build /tmp/whisper-build-arm64 --config Release -j$(sysctl -n hw.ncpu)
cp /tmp/whisper-build-arm64/src/libwhisper.so app/src/main/jniLibs/arm64-v8a/
cp /tmp/whisper-build-arm64/ggml/src/lib*.so app/src/main/jniLibs/arm64-v8a/
```

### Push a model to device manually
```bash
curl -L -o /tmp/model.bin "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
adb push /tmp/model.bin /sdcard/model.bin
adb shell "cat /sdcard/model.bin | run-as com.watranscribe sh -c 'mkdir -p files/models && cat > files/models/ggml-base.en.bin'"
```

### Reset failed transcriptions
```bash
# Pull DB, fix, push back (no sqlite3 on most devices)
adb shell am force-stop com.watranscribe
adb shell "run-as com.watranscribe cat databases/wa_transcribe.db" > /tmp/wa.db
sqlite3 /tmp/wa.db "UPDATE transcriptions SET status='PENDING' WHERE status='FAILED';"
cat /tmp/wa.db | adb shell "run-as com.watranscribe sh -c 'cat > databases/wa_transcribe.db'"
adb shell "run-as com.watranscribe rm -f databases/wa_transcribe.db-wal databases/wa_transcribe.db-shm"
```

### Add a new DB column
1. Add field to `TranscriptionEntity.kt` with `@ColumnInfo(defaultValue = "")`
2. Bump version in `AppDatabase.kt`
3. Add `MIGRATION_N_N+1` with `ALTER TABLE` SQL
4. Register migration in `AppModule.kt`

## Design Decisions
- Dark mode only (pure black #000000, white text, grey accents)
- No auto-transcribe on first load — user taps to transcribe manually
- New incoming voice notes auto-transcribe via NotificationListener + QuickTranscribeWorker
- Notifications are dismissable (not ongoing) to avoid stuck notifications on crash
- arm64-v8a only (no armeabi-v7a) — covers 99%+ of modern Android phones
- Thread count: `availableProcessors() - 2` for whisper inference
