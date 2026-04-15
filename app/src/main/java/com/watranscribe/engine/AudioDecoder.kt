package com.watranscribe.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

data class DecodedAudio(
    val samples: FloatArray,
    val durationMs: Long
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = samples.contentHashCode()
}

private const val TAG = "AudioDecoder"

@Singleton
class AudioDecoder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun decode(uri: Uri): DecodedAudio = withContext(Dispatchers.IO) {
        Log.d(TAG, "decode: starting for URI=$uri")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = findAudioTrack(extractor)
                ?: error("No audio track found in $uri")
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("No MIME type in track format")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            Log.d(TAG, "decode: mime=$mime, rate=$sampleRate, channels=$channelCount, duration=${durationUs/1000}ms")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmStream = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmStream.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) outputDone = true
                    }
                    // INFO_OUTPUT_FORMAT_CHANGED, INFO_OUTPUT_BUFFERS_CHANGED — just continue
                }
            }

            codec.stop()
            codec.release()
            codec = null

            val pcmBytes = pcmStream.toByteArray()
            Log.d(TAG, "decode: decoded ${pcmBytes.size} PCM bytes")

            val floatSamples = pcmBytesToFloat(pcmBytes)
            val resampled = resampleTo16kHz(floatSamples, sampleRate, channelCount)

            Log.d(TAG, "decode: resampled to ${resampled.size} float samples (16kHz mono), duration=${durationUs/1000}ms")
            DecodedAudio(samples = resampled, durationMs = durationUs / 1000)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /** Convert 16-bit PCM byte array to float array normalized to [-1.0, 1.0] */
    internal fun pcmBytesToFloat(pcmBytes: ByteArray): FloatArray {
        val shortBuffer = ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val floats = FloatArray(shortBuffer.remaining())
        for (i in floats.indices) {
            floats[i] = shortBuffer.get(i) / 32768f
        }
        return floats
    }

    /** Resample to 16kHz mono if needed */
    internal fun resampleTo16kHz(
        samples: FloatArray,
        sourceSampleRate: Int,
        channels: Int
    ): FloatArray {
        val mono = if (channels > 1) {
            FloatArray(samples.size / channels) { i ->
                var sum = 0f
                for (ch in 0 until channels) {
                    sum += samples[i * channels + ch]
                }
                sum / channels
            }
        } else {
            samples
        }

        if (sourceSampleRate == 16000) return mono

        val ratio = 16000.0 / sourceSampleRate
        val outputSize = (mono.size * ratio).toInt()
        if (outputSize == 0) return floatArrayOf()

        return FloatArray(outputSize) { i ->
            val srcIndex = i / ratio
            val srcIndexInt = srcIndex.toInt()
            val frac = (srcIndex - srcIndexInt).toFloat()
            if (srcIndexInt + 1 < mono.size) {
                mono[srcIndexInt] * (1f - frac) + mono[srcIndexInt + 1] * frac
            } else {
                mono[srcIndexInt.coerceAtMost(mono.size - 1)]
            }
        }
    }
}
