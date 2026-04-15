#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Callback context for streaming segments back to Java
struct callback_context {
    JNIEnv *env;
    jobject callback;   // global ref, must be freed
    jmethodID on_segment;
};

static jstring safe_new_string_utf(JNIEnv *env, const char *text) {
    if (!text) text = "";
    return (*env)->NewStringUTF(env, text);
}

static void new_segment_callback(struct whisper_context *ctx, struct whisper_state *state, int n_new, void *user_data) {
    (void)state;
    struct callback_context *cb = (struct callback_context *)user_data;
    if (!cb || !cb->callback || !cb->on_segment) return;

    int n_segments = whisper_full_n_segments(ctx);
    for (int i = n_segments - n_new; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        jstring jtext = safe_new_string_utf(cb->env, text);
        (*cb->env)->CallVoidMethod(cb->env, cb->callback, cb->on_segment, jtext);
        // Check for Java exceptions from callback
        if ((*cb->env)->ExceptionCheck(cb->env)) {
            LOGE("Exception in onSegment callback");
            (*cb->env)->ExceptionClear(cb->env);
            (*cb->env)->DeleteLocalRef(cb->env, jtext);
            return;
        }
        (*cb->env)->DeleteLocalRef(cb->env, jtext);
    }
}

// initContext(modelPath: String): Long
JNIEXPORT jlong JNICALL
Java_com_watranscribe_engine_WhisperJni_initContext(JNIEnv *env, jobject thiz, jstring model_path) {
    (void)thiz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (!path) {
        LOGE("Failed to get model path string");
        return 0;
    }
    LOGI("Loading model from: %s", path);

    struct whisper_context_params params = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);

    (*env)->ReleaseStringUTFChars(env, model_path, path);

    if (!ctx) {
        LOGE("Failed to init whisper context");
        return 0;
    }
    LOGI("Model loaded successfully");
    return (jlong)ctx;
}

// freeContext(contextPtr: Long)
JNIEXPORT void JNICALL
Java_com_watranscribe_engine_WhisperJni_freeContext(JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env; (void)thiz;
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    if (ctx) {
        whisper_free(ctx);
        LOGI("Context freed");
    }
}

// fullTranscribe(contextPtr: Long, samples: FloatArray, numThreads: Int, callback: SegmentCallback?): String
JNIEXPORT jstring JNICALL
Java_com_watranscribe_engine_WhisperJni_fullTranscribe(JNIEnv *env, jobject thiz,
        jlong context_ptr, jfloatArray samples, jint num_threads, jobject callback) {
    (void)thiz;
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    if (!ctx) {
        LOGE("Null context pointer");
        return safe_new_string_utf(env, "");
    }

    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (!data) {
        LOGE("Failed to get float array elements");
        return safe_new_string_utf(env, "");
    }
    jsize data_len = (*env)->GetArrayLength(env, samples);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = 0;
    params.print_progress = 0;
    params.print_timestamps = 0;
    params.print_special = 0;
    params.translate = 0;
    params.language = "en";
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = 1;
    params.single_segment = 0;
    params.max_len = 0;              // Default segmentation (sentence-level)
    params.token_timestamps = 1;     // Token-level timestamps for highlighting
    params.suppress_blank = 1;       // Suppress blank outputs
    params.suppress_nst  = 1;        // Suppress non-speech tokens (ums, ahs, fillers)

    // Set up streaming callback if provided
    struct callback_context cb_ctx = {0};
    if (callback) {
        jclass cls = (*env)->GetObjectClass(env, callback);
        if (cls) {
            cb_ctx.env = env;
            cb_ctx.callback = (*env)->NewGlobalRef(env, callback);
            cb_ctx.on_segment = (*env)->GetMethodID(env, cls, "onSegment", "(Ljava/lang/String;)V");
            (*env)->DeleteLocalRef(env, cls);

            if (cb_ctx.on_segment && cb_ctx.callback) {
                params.new_segment_callback = new_segment_callback;
                params.new_segment_callback_user_data = &cb_ctx;
            }
        }
    }

    LOGI("PERF Starting: %d samples (%.1fs audio), %d threads, max_len=%d",
         data_len, (float)data_len / 16000.0f, num_threads, params.max_len);

    struct timespec t_start, t_end;
    clock_gettime(CLOCK_MONOTONIC, &t_start);

    int ret = whisper_full(ctx, params, data, data_len);

    clock_gettime(CLOCK_MONOTONIC, &t_end);
    long elapsed_ms = (t_end.tv_sec - t_start.tv_sec) * 1000 +
                      (t_end.tv_nsec - t_start.tv_nsec) / 1000000;

    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);

    // Clean up global ref
    if (cb_ctx.callback) {
        (*env)->DeleteGlobalRef(env, cb_ctx.callback);
        cb_ctx.callback = NULL;
    }

    if (ret != 0) {
        LOGE("whisper_full failed with code %d after %ldms", ret, elapsed_ms);
        return safe_new_string_utf(env, "");
    }

    int n_segments = whisper_full_n_segments(ctx);
    float audio_sec = (float)data_len / 16000.0f;
    float rtf = audio_sec > 0 ? (float)elapsed_ms / (audio_sec * 1000.0f) : 0;
    LOGI("PERF Done: %ldms, %d segments, RTF=%.2fx (1.0=realtime)", elapsed_ms, n_segments, rtf);

    int total_len = 0;
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) total_len += (int)strlen(text);
    }

    char *result = (char *)malloc(total_len + 1);
    if (!result) {
        LOGE("Failed to allocate result buffer");
        return safe_new_string_utf(env, "");
    }

    int pos = 0;
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            int len = (int)strlen(text);
            memcpy(result + pos, text, len);
            pos += len;
        }
    }
    result[pos] = '\0';

    jstring jresult = (*env)->NewStringUTF(env, result);
    free(result);
    return jresult;
}

// getSegments(contextPtr: Long): String
// Returns segments as "startMs|endMs|text\n" lines
JNIEXPORT jstring JNICALL
Java_com_watranscribe_engine_WhisperJni_getSegments(JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)thiz;
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    if (!ctx) return safe_new_string_utf(env, "");

    int n_segments = whisper_full_n_segments(ctx);

    // Calculate exact buffer size needed
    int buf_size = 0;
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        buf_size += (text ? (int)strlen(text) : 0) + 64; // 64 for timestamps + delimiters
    }

    char *buf = (char *)malloc(buf_size + 1);
    if (!buf) return safe_new_string_utf(env, "");

    buf[0] = '\0';
    int pos = 0;

    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (!text) text = "";
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10; // to ms
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        int written = snprintf(buf + pos, buf_size - pos, "%lld|%lld|%s\n", t0, t1, text);
        if (written > 0 && pos + written < buf_size) pos += written;
    }

    jstring jresult = (*env)->NewStringUTF(env, buf);
    free(buf);
    return jresult;
}
