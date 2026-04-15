package com.watranscribe.engine

/**
 * JNI bindings to whisper.cpp via our custom jni.c bridge.
 */
object WhisperJni {

    init {
        System.loadLibrary("watranscribe-jni")
    }

    /** Initialize a whisper context from a model file path. Returns a context pointer (0 on failure). */
    external fun initContext(modelPath: String): Long

    /** Free a whisper context. */
    external fun freeContext(contextPtr: Long)

    /**
     * Run full transcription on PCM float samples.
     * @param contextPtr context pointer from [initContext]
     * @param samples PCM 16kHz mono float array
     * @param numThreads number of CPU threads to use
     * @param callback optional callback invoked per-segment for streaming results
     * @return full transcribed text
     */
    external fun fullTranscribe(contextPtr: Long, samples: FloatArray, numThreads: Int, callback: SegmentCallback?): String

    /**
     * Get timed segments after transcription completes.
     * Returns lines of "startMs|endMs|text"
     */
    external fun getSegments(contextPtr: Long): String

    /** Callback interface for receiving segments as they are produced. */
    interface SegmentCallback {
        fun onSegment(text: String)
    }
}
