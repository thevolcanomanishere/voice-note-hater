# Voice-to-Text Models for Android (Kotlin) — 2026 Research

> Moving beyond Whisper: the best on-device ASR options for Android/Kotlin development.

---

## 1. Moonshine v2 — Best Whisper Replacement

**Repo:** [github.com/moonshine-ai/moonshine](https://github.com/moonshine-ai/moonshine)  
**License:** MIT  
**Model sizes:** 26MB (Tiny) → 245MB (Medium Streaming)

### Why it beats Whisper

Whisper requires fixed 30-second audio chunks and zero-pads shorter segments, creating constant compute overhead. Moonshine eliminates zero-padding and scales processing proportionally to actual audio length — achieving up to a **5x speed-up** overall.

Moonshine v2 introduces a streaming encoder architecture that begins processing while the user is still speaking, enabling genuinely low-latency live transcription.

| Model | WER | Size |
|---|---|---|
| Moonshine Tiny | ~5.5% | 26MB |
| Moonshine Base | ~4.8% | 61MB |
| Moonshine Medium Streaming | **6.65%** | 245MB |
| Whisper Large V3 (reference) | 7.44% | ~1.5GB |

### Language support

Specialized monolingual "Flavors" models for Arabic, Chinese, Japanese, Korean, Ukrainian, and Vietnamese. At 27M parameters they average **48% lower error rates than Whisper Tiny** and outperform the 9× larger Whisper Small.

### Android/Kotlin integration

Official Android Studio project examples ship in the repo. Models use ONNX `.ort` flatbuffer format (memory-mappable, INT8 quantized). No API keys, no account needed.

```kotlin
// build.gradle.kts
implementation("ai.moonshine:moonshine-android:x.y.z")
```

---

## 2. Sherpa-ONNX — Most Flexible Framework

**Repo:** [github.com/k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)  
**License:** Apache 2.0  
**Kotlin API:** Native, auto-generated bindings

### What it is

A next-gen Kaldi runtime wrapping ONNX models — a model zoo + inference engine in one, with first-class Kotlin support. Covers STT, TTS, speaker diarization, VAD, speech enhancement, and source separation, all 100% offline.

The stack is: ONNX models (hardware-vendor neutral) → ONNX Runtime (CPU, CUDA, CoreML, NNAPI) → auto-generated Kotlin/Java/Swift/C#/Python/Go/JS bindings.

### Notable models available for Android

| Model | Languages | Size | Notes |
|---|---|---|---|
| `streaming-zipformer-bilingual-zh-en` | Chinese + English | ~80MB | Real-time on Cortex-A7 |
| `sense-voice-zh-en-ja-ko-yue-int8` | ZH, EN, JA, KO, Cantonese | ~120MB | INT8 quantized |
| Moonshine Tiny/Base/Streaming | English + 8 languages | 26–245MB | Official APKs provided |
| `nemo-canary-180m-flash` | EN, ES, DE, FR | ~180MB | Flash-quantized |
| Cohere Transcribe | English | ~300MB | Recently added (2026) |

### Kotlin integration

```kotlin
// build.gradle.kts
implementation("com.github.k2-fsa:sherpa-onnx-android:1.10.0")
```

```kotlin
val model = SherpaOnnx(
    tokens = "tokens.txt",
    encoder = "encoder.onnx",
    decoder = "decoder.onnx",
    useGPU = true
)
model.start()
model.acceptWaveform(shortArrayOf(/* PCM frames */))
val text = model.text
```

Build the AAR yourself targeting specific ABIs:
```bash
./build-android.sh arm64-v8a   # also: armeabi-v7a, x86_64
```

---

## 3. Picovoice Cheetah — Best for Production / Commercial Apps

**Site:** [picovoice.ai/platform/cheetah](https://picovoice.ai/platform/cheetah/)  
**License:** Commercial (free tier available)  
**Languages:** English, French, German, Italian, Portuguese, Spanish

### What it is

A purpose-built streaming STT engine with a polished, officially supported Android SDK. Processes audio entirely on-device — internet is only required for license key validation on first run.

In 2025 Picovoice expanded Cheetah to 6 languages and released **Cheetah Fast**, an ultra-low latency variant targeting conversational AI pipelines.

### Kotlin quick start

```kotlin
// build.gradle.kts
implementation("ai.picovoice:cheetah-android:x.y.z")
```

```kotlin
val cheetah = Cheetah.Builder()
    .setAccessKey(ACCESS_KEY)
    .setModelPath("cheetah_params.pv")
    .build(applicationContext)

// In your audio loop:
val (partialTranscript, isEndpoint) = cheetah.process(pcmFrame)
if (isEndpoint) {
    val finalTranscript = cheetah.flush()
}
```

### Key differentiators

- Custom vocabulary training via Picovoice Console (no-code)
- Integrated VAD endpoint detection
- Pairs well with Picovoice's Orca TTS and Leopard batch STT for a full voice pipeline
- Production support and SLAs available

---

## 4. Android Built-in SpeechRecognizer API

For simple use cases where offline capability and privacy are not requirements.

```kotlin
val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
}
speechRecognizer.setRecognitionListener(object : RecognitionListener {
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)
    }
    // ... implement remaining interface methods
})
speechRecognizer.startListening(intent)
```

**Caveat:** Requires Google Search app on device, sends audio to Google servers, no offline support.

---

## Summary Comparison

| Option | Size | Streaming | Offline | Kotlin API | License | Best For |
|---|---|---|---|---|---|---|
| **Moonshine v2** | 26–245MB | ✅ | ✅ | ✅ Official | MIT | Whisper replacement, live transcription |
| **Sherpa-ONNX** | 60–300MB+ | ✅ | ✅ | ✅ Native | Apache 2.0 | Model flexibility, multilingual |
| **Picovoice Cheetah** | Small | ✅ | ✅* | ✅ Official | Commercial | Production apps, custom vocab |
| **Android SpeechRecognizer** | 0 (cloud) | ✅ | ❌ | ✅ Built-in | Free | Quick prototypes |

> *Cheetah inference is fully on-device. Internet required only for AccessKey licence validation.

---

## Recommendation

If you're already comfortable with Whisper's ONNX integration pattern, **Moonshine v2 via Sherpa-ONNX** is the cleanest upgrade path — you get Moonshine's model quality with Sherpa's mature Kotlin bindings, VAD, and diarization all in one library.

For multilingual apps targeting CJK languages or less-resourced languages, Sherpa-ONNX's **SenseVoice** model is worth benchmarking.

For commercial products where you need custom vocabulary, support contracts, and a stable versioned SDK, **Picovoice Cheetah** is the professional choice.

---

*Researched April 2026*
