package com.watranscribe

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for audio processing pure functions.
 */
class AudioDecoderTest {

    private fun pcmBytesToFloat(pcmBytes: ByteArray): FloatArray {
        if (pcmBytes.isEmpty()) return floatArrayOf()
        val shortBuffer = ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val floats = FloatArray(shortBuffer.remaining())
        for (i in floats.indices) {
            floats[i] = shortBuffer.get(i) / 32768f
        }
        return floats
    }

    private fun resampleTo16kHz(samples: FloatArray, sourceSampleRate: Int, channels: Int): FloatArray {
        val mono = if (channels > 1) {
            FloatArray(samples.size / channels) { i ->
                var sum = 0f
                for (ch in 0 until channels) {
                    sum += samples[i * channels + ch]
                }
                sum / channels
            }
        } else samples

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

    @Test
    fun `pcmBytesToFloat converts silence to zeros`() {
        val silence = ByteArray(4)
        val floats = pcmBytesToFloat(silence)
        assertEquals(2, floats.size)
        assertEquals(0f, floats[0], 0.001f)
        assertEquals(0f, floats[1], 0.001f)
    }

    @Test
    fun `pcmBytesToFloat converts max positive`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0x7F)
        val floats = pcmBytesToFloat(bytes)
        assertEquals(1, floats.size)
        assertEquals(32767f / 32768f, floats[0], 0.001f)
    }

    @Test
    fun `pcmBytesToFloat converts max negative`() {
        val bytes = byteArrayOf(0x00, 0x80.toByte())
        val floats = pcmBytesToFloat(bytes)
        assertEquals(1, floats.size)
        assertEquals(-1f, floats[0], 0.001f)
    }

    @Test
    fun `pcmBytesToFloat handles empty input`() {
        val floats = pcmBytesToFloat(byteArrayOf())
        assertEquals(0, floats.size)
    }

    @Test
    fun `resampleTo16kHz passes through 16kHz mono`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        val output = resampleTo16kHz(input, 16000, 1)
        assertArrayEquals(input, output, 0.001f)
    }

    @Test
    fun `resampleTo16kHz downsamples 48kHz to 16kHz`() {
        val input = FloatArray(4800) { (it % 100) / 100f }
        val output = resampleTo16kHz(input, 48000, 1)
        assertEquals(1600, output.size)
    }

    @Test
    fun `resampleTo16kHz converts stereo to mono`() {
        val input = floatArrayOf(1.0f, -1.0f, 0.5f, -0.5f)
        val output = resampleTo16kHz(input, 16000, 2)
        assertEquals(2, output.size)
        assertEquals(0f, output[0], 0.001f)
        assertEquals(0f, output[1], 0.001f)
    }

    @Test
    fun `resampleTo16kHz handles empty input`() {
        val output = resampleTo16kHz(floatArrayOf(), 48000, 1)
        assertEquals(0, output.size)
    }

    @Test
    fun `resampling preserves signal range`() {
        val input = FloatArray(480) { kotlin.math.sin(2.0 * Math.PI * 440 * it / 48000.0).toFloat() }
        val output = resampleTo16kHz(input, 48000, 1)
        assertTrue(output.all { it in -1.0f..1.0f })
        assertEquals(160, output.size)
    }
}
