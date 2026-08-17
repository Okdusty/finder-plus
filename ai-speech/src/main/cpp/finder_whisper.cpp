// JNI bridge: OpenAI Whisper via whisper.cpp, as a second speech engine alongside Qwen3-ASR.
//
// Why a second engine at all: Qwen3-ASR through llama.cpp's mtmd path measures ~1.6 s of compute per
// 1 s of audio on this device (prefill-dominated, and exactly linear in audio length), which puts a
// 6.4-hour backlog at ~10 hours. whisper.cpp is a far more heavily optimized encoder and is the only
// alternative that keeps Turkish — NVIDIA's Parakeet is faster still but its 25 languages exclude it,
// and Nemotron-ASR has no on-device inference path at all.
//
// This shares llama.cpp's ggml (whisper.cpp guards its own copy behind `if (NOT TARGET ggml)`), so it
// reuses the same Vulkan backend rather than adding a second native stack.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <chrono>

#include "whisper.h"

#define WTAG "finderWhisperNative"
#define WLOGI(...) __android_log_print(ANDROID_LOG_INFO,  WTAG, __VA_ARGS__)
#define WLOGE(...) __android_log_print(ANDROID_LOG_ERROR, WTAG, __VA_ARGS__)

namespace {

std::string wjstr(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_rightone_finderplus_speech_WhisperNative_init(
        JNIEnv * env, jobject, jstring jModel, jboolean useGpu) {
    const std::string path = wjstr(env, jModel);
    if (path.empty()) {
        WLOGE("init: empty model path");
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    // Same rationale as the ASR path: ggml keeps work on the CPU when no usable Vulkan device is
    // present, so requesting the GPU is never fatal.
    cparams.use_gpu = useGpu;
    cparams.flash_attn = true;

    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        WLOGE("init: failed to load %s", path.c_str());
        return 0;
    }
    WLOGI("init ok (gpu=%d, multilingual=%d, vocab=%d)",
          (int) useGpu, whisper_is_multilingual(ctx), whisper_model_n_vocab(ctx));
    return (jlong) (intptr_t) ctx;
}

/**
 * Transcribe one PCM window.
 *
 * @return `t0|t1|text` per segment, newline-separated, with timestamps in milliseconds. Segment
 *   granularity is Whisper's own, which is finer than the fixed window the caller supplies — so the
 *   Kotlin side gets real per-utterance timings instead of one span covering the whole chunk.
 */
JNIEXPORT jstring JNICALL
Java_ai_rightone_finderplus_speech_WhisperNative_transcribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray jPcm, jstring jLang, jint threads) {
    auto * ctx = (whisper_context *) (intptr_t) handle;
    if (ctx == nullptr || jPcm == nullptr) return env->NewStringUTF("");

    const jsize n_samples = env->GetArrayLength(jPcm);
    if (n_samples <= 0) return env->NewStringUTF("");
    std::vector<float> pcm((size_t) n_samples);
    env->GetFloatArrayRegion(jPcm, 0, n_samples, pcm.data());

    const std::string lang = wjstr(env, jLang);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads        = threads > 0 ? threads : 4;
    wparams.language         = lang.empty() ? "auto" : lang.c_str();
    wparams.translate        = false;   // transcribe in the spoken language; this is an index, not a translator
    wparams.print_progress   = false;
    wparams.print_realtime   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    // Whisper hallucinates fluent text on silence; both of these are the documented mitigations and
    // matter more for a gallery (full of silent clips) than for dictation.
    wparams.no_speech_thold  = 0.6f;
    wparams.suppress_blank   = true;
    wparams.temperature      = 0.0f;
    wparams.single_segment   = false;

    const auto t0 = std::chrono::steady_clock::now();
    if (whisper_full(ctx, wparams, pcm.data(), (int) pcm.size()) != 0) {
        WLOGE("transcribe: whisper_full failed");
        return env->NewStringUTF("");
    }
    const auto t1 = std::chrono::steady_clock::now();

    const int n_seg = whisper_full_n_segments(ctx);
    std::string out;
    for (int i = 0; i < n_seg; ++i) {
        const char * text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr || *text == '\0') continue;
        // whisper timestamps are in centiseconds.
        const int64_t seg_t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t seg_t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        if (!out.empty()) out += "\n";
        out += std::to_string(seg_t0) + "|" + std::to_string(seg_t1) + "|" + text;
    }

    const long ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    const long audio_ms = (long) (n_samples * 1000L / WHISPER_SAMPLE_RATE);
    WLOGI("window: %ldms for %ldms audio (%.2fx realtime) segments=%d",
          ms, audio_ms, audio_ms > 0 ? (double) ms / audio_ms : 0.0, n_seg);

    return env->NewStringUTF(out.c_str());
}

/** Language Whisper detected for the last window, or "" when it could not tell. */
JNIEXPORT jstring JNICALL
Java_ai_rightone_finderplus_speech_WhisperNative_detectedLanguage(JNIEnv * env, jobject, jlong handle) {
    auto * ctx = (whisper_context *) (intptr_t) handle;
    if (ctx == nullptr) return env->NewStringUTF("");
    const int id = whisper_full_lang_id(ctx);
    const char * s = id >= 0 ? whisper_lang_str(id) : nullptr;
    return env->NewStringUTF(s ? s : "");
}

JNIEXPORT void JNICALL
Java_ai_rightone_finderplus_speech_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free((whisper_context *) (intptr_t) handle);
}

} // extern "C"
