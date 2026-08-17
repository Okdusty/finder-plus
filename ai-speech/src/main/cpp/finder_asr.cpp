// JNI bridge: Qwen3-ASR speech recognition via llama.cpp's multimodal (mtmd) audio path.
//
// llama.cpp officially supports Qwen3-ASR 0.6B / 1.7B (tools/mtmd/models/qwen3a.cpp), which is why it
// is used here instead of Whisper: Qwen3-ASR's 30 supported languages include Turkish, where Whisper's
// small tiers are weakest.
//
// One context is created per file and reused across that file's audio windows. Each window resets the
// KV cache so windows are independent — the caller (Qwen3SpeechRecognizer) supplies ~30 s of
// 16 kHz mono float PCM that has already passed voice-activity detection.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <cstring>
#include <mutex>
#include <chrono>
#include <cstdio>
#include <sched.h>
#include <unistd.h>

#ifdef FINDER_HAS_VULKAN
#include <vulkan/vulkan.h>
#endif

#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "finderAsrNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Upper bound on generated tokens per audio window. A 30 s window of dense speech is well under this;
// the cap stops a degenerate loop from spinning forever on the battery.
constexpr int kMaxNewTokens = 512;

// Sized to the work actually done, which measurement showed was 16x smaller than what was allocated.
//
// One 30 s window uses ~497 positions: ~413 audio tokens from the encoder plus ~84 generated. The old
// 8192-token context and 2048-token batch therefore reserved a **1,323 MiB** Vulkan compute buffer for
// a job needing a fraction of it. On a phone GPU that is not free headroom — it is memory bandwidth and
// allocator pressure on the critical path, and prefill was measured at 47.4 s of a 50.1 s window
// (94.6%) while decode ran at a perfectly healthy 31.5 tok/s.
//
// 1024 leaves ~2x margin over the observed 497. A 512-token micro-batch is the usual sweet spot for
// ggml: large enough to keep the GPU busy, small enough that a single dispatch is not enormous.
constexpr int kNCtx         = 1024;
constexpr int kNBatch       = 512;

struct AsrContext {
    llama_model   * model     = nullptr;
    llama_context * lctx      = nullptr;
    mtmd_context  * mctx      = nullptr;
    std::string     last_lang;
    bool            gpu       = false;

    ~AsrContext() {
        if (mctx)  mtmd_free(mctx);
        if (lctx)  llama_free(lctx);
        if (model) llama_model_free(model);
    }
};

bool g_backend_ready = false;

// llama.cpp calls the log callback per *fragment*, not per line — model-load progress arrives as a
// stream of single "." characters. Emitting one logcat line per fragment floods the ring buffer and
// evicts the backend-selection lines we actually care about, so fragments are accumulated and only
// flushed on newline.
std::mutex  g_log_mu;
std::string g_log_line;
bool        g_log_verbose = false;

void android_log_cb(ggml_log_level level, const char * text, void *) {
    if (text == nullptr) return;
    std::lock_guard<std::mutex> lock(g_log_mu);
    g_log_line += text;
    for (size_t nl; (nl = g_log_line.find('\n')) != std::string::npos; ) {
        const std::string line = g_log_line.substr(0, nl);
        g_log_line.erase(0, nl + 1);
        if (line.empty()) continue;
        if (level >= GGML_LOG_LEVEL_ERROR)  LOGE("%s", line.c_str());
        else if (g_log_verbose)             LOGI("%s", line.c_str());
    }
    // A progress stream that never terminates a line must not grow without bound.
    if (g_log_line.size() > 4096) g_log_line.clear();
}

void ensure_backend() {
    if (!g_backend_ready) {
        // The callback MUST be installed before backend init. Device discovery — including
        // ggml-vulkan's capability line (fp16, warp size, matrix cores) — is logged during
        // llama_backend_init(), so installing the hook afterwards silently loses exactly the lines
        // worth having.
        llama_log_set(android_log_cb, nullptr);
        llama_backend_init();
        g_backend_ready = true;
    }
}

/** Build the model's own chat prompt with the media marker as the user's content. */
std::string build_prompt(llama_model * model) {
    const std::string marker = mtmd_default_marker();
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) {
        // No embedded template: the marker alone still tokenizes correctly for ASR models.
        return marker;
    }
    llama_chat_message msg{"user", marker.c_str()};
    std::vector<char> buf(4096);
    int32_t n = llama_chat_apply_template(tmpl, &msg, 1, /*add_ass=*/true, buf.data(), (int32_t) buf.size());
    if (n < 0) return marker;
    if (n > (int32_t) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (n < 0) return marker;
    }
    return std::string(buf.data(), n);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

/**
 * Enumerate the ggml compute devices actually registered in this build, so "is Vulkan live?" is
 * answered by the runtime rather than inferred from a log line that may have been evicted. Returns
 * one `name|description|TYPE|totalMiB` record per device, newline-separated.
 */
JNIEXPORT jstring JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_backends(JNIEnv * env, jobject) {
    ensure_backend();
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        const char * name = ggml_backend_dev_name(dev);
        const char * desc = ggml_backend_dev_description(dev);
        const char * type = "CPU";
        switch (ggml_backend_dev_type(dev)) {
            // IGPU is a distinct type in current ggml, and it is the one a phone reports. Omitting it
            // made this probe answer "gpu=false" for a Xclipse 940 that was demonstrably running all
            // 29 layers — a diagnostic that lies is worse than no diagnostic.
            case GGML_BACKEND_DEVICE_TYPE_IGPU:  type = "IGPU";  break;
            case GGML_BACKEND_DEVICE_TYPE_GPU:   type = "GPU";   break;
            case GGML_BACKEND_DEVICE_TYPE_ACCEL: type = "ACCEL"; break;
            default: break;
        }
        size_t free_mem = 0, total_mem = 0;
        ggml_backend_dev_memory(dev, &free_mem, &total_mem);
        if (!out.empty()) out += "\n";
        out += std::string(name ? name : "?") + "|" + (desc ? desc : "?") + "|" + type + "|" +
               std::to_string(total_mem / (1024 * 1024));
    }
    return env->NewStringUTF(out.c_str());
}

/**
 * Ask the Vulkan driver directly what it supports.
 *
 * ggml decides internally whether to use cooperative-matrix ("matrix core") shader paths, and if the
 * driver does not expose the extension it silently falls back to plain FP32 shaders — which is a
 * candidate explanation for a 47 s prefill over ~400 tokens. Reading it from the driver rather than
 * inferring it from throughput is the only way to settle that.
 *
 * @return `device|driverVersion|apiVersion|ext,ext,...` for the first physical device, or "" when the
 *   build has no Vulkan headers.
 */
JNIEXPORT jstring JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_vulkanCaps(JNIEnv * env, jobject) {
#ifndef FINDER_HAS_VULKAN
    return env->NewStringUTF("");
#else
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "finder-caps";
    app.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;

    VkInstance inst = VK_NULL_HANDLE;
    if (vkCreateInstance(&ici, nullptr, &inst) != VK_SUCCESS) {
        return env->NewStringUTF("");
    }

    uint32_t n_dev = 0;
    vkEnumeratePhysicalDevices(inst, &n_dev, nullptr);
    if (n_dev == 0) { vkDestroyInstance(inst, nullptr); return env->NewStringUTF(""); }
    std::vector<VkPhysicalDevice> devs(n_dev);
    vkEnumeratePhysicalDevices(inst, &n_dev, devs.data());

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(devs[0], &props);

    uint32_t n_ext = 0;
    vkEnumerateDeviceExtensionProperties(devs[0], nullptr, &n_ext, nullptr);
    std::vector<VkExtensionProperties> exts(n_ext);
    vkEnumerateDeviceExtensionProperties(devs[0], nullptr, &n_ext, exts.data());

    // Only the extensions that decide which ggml shader path is taken; the full list is ~150 entries.
    static const char * kInteresting[] = {
        "VK_KHR_cooperative_matrix", "VK_NV_cooperative_matrix", "VK_NV_cooperative_matrix2",
        "VK_KHR_shader_float16_int8", "VK_KHR_16bit_storage", "VK_KHR_8bit_storage",
        "VK_KHR_shader_integer_dot_product", "VK_EXT_subgroup_size_control",
        "VK_KHR_shader_subgroup_extended_types", "VK_KHR_buffer_device_address",
    };

    std::string found;
    for (const char * want : kInteresting) {
        for (const auto & e : exts) {
            if (std::strcmp(e.extensionName, want) == 0) {
                if (!found.empty()) found += ",";
                found += want;
                break;
            }
        }
    }

    char buf[256];
    snprintf(buf, sizeof(buf), "%s|driver %u.%u.%u|api %u.%u.%u|extTotal %u|",
             props.deviceName,
             VK_VERSION_MAJOR(props.driverVersion), VK_VERSION_MINOR(props.driverVersion),
             VK_VERSION_PATCH(props.driverVersion),
             VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
             VK_VERSION_PATCH(props.apiVersion), n_ext);

    const std::string out = std::string(buf) + (found.empty() ? "(none of interest)" : found);
    vkDestroyInstance(inst, nullptr);
    return env->NewStringUTF(out.c_str());
#endif
}

/**
 * Pin the calling thread — and therefore every ggml worker it spawns — to the fastest cores.
 *
 * ggml splits each layer evenly across its threads and waits for all of them, so the slowest thread
 * gates every layer. On a heterogeneous SoC that makes an unpinned thread pool actively harmful: this
 * device has four 1.96 GHz cores alongside a 3.21 GHz one, so a pool spread across all ten runs at
 * little-core speed. It is why raising the thread count from 4 to 8 measured *worse*, not better.
 *
 * The fast set is discovered rather than hard-coded: cores are classified by their reported max
 * frequency and anything at the minimum tier is excluded. Child threads inherit the affinity mask, so
 * calling this on the worker before inference is enough.
 *
 * @return how many cores the thread is now pinned to, or 0 if the kernel refused. Refusal is expected
 *   and harmless: the mask must intersect the process's cpuset, so without the [CpuBooster] having moved
 *   us out of a little-core-only cpuset there is nothing to pin to.
 */
JNIEXPORT jint JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_pinFastCores(JNIEnv *, jobject) {
    const int n_cpu = (int) sysconf(_SC_NPROCESSORS_CONF);
    if (n_cpu <= 1) return 0;

    std::vector<long> khz((size_t) n_cpu, 0);
    long max_khz = 0, min_khz = 0;
    for (int i = 0; i < n_cpu; i++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE * f = fopen(path, "re");
        if (f == nullptr) continue;
        long v = 0;
        if (fscanf(f, "%ld", &v) == 1) khz[(size_t) i] = v;
        fclose(f);
        if (v > max_khz) max_khz = v;
        if (v > 0 && (min_khz == 0 || v < min_khz)) min_khz = v;
    }
    // Nothing readable, or a homogeneous SoC: pinning would only reduce the scheduler's options.
    if (max_khz == 0 || min_khz == max_khz) return 0;

    cpu_set_t set;
    CPU_ZERO(&set);
    int n_pinned = 0;
    for (int i = 0; i < n_cpu && i < CPU_SETSIZE; i++) {
        if (khz[(size_t) i] > min_khz) { CPU_SET(i, &set); n_pinned++; }
    }
    if (n_pinned == 0) return 0;
    if (sched_setaffinity(0, sizeof(set), &set) != 0) return 0;
    LOGI("pinned to %d fast cores (excluding the %ld kHz tier of %d total)", n_pinned, min_khz, n_cpu);
    return n_pinned;
}

/** Route llama.cpp's INFO-level logs to logcat. Enabled around model load to capture backend choice. */
JNIEXPORT void JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_setVerboseLogging(JNIEnv *, jobject, jboolean on) {
    std::lock_guard<std::mutex> lock(g_log_mu);
    g_log_verbose = on;
}

JNIEXPORT jlong JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_init(
        JNIEnv * env, jobject, jstring jModel, jstring jProjector, jboolean useGpu, jint threads) {
    ensure_backend();

    const std::string model_path = jstr(env, jModel);
    const std::string proj_path  = jstr(env, jProjector);
    if (model_path.empty() || proj_path.empty()) {
        LOGE("init: empty model or projector path");
        return 0;
    }

    auto * ctx = new AsrContext();

    llama_model_params mparams = llama_model_default_params();
    // Offload everything we can to the GPU; ggml silently keeps layers on CPU when the Vulkan
    // backend is absent or the device is unsupported, so this is safe to request unconditionally.
    mparams.n_gpu_layers = useGpu ? 999 : 0;
    ctx->model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (ctx->model == nullptr) {
        LOGE("init: failed to load model %s", model_path.c_str());
        delete ctx;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx       = kNCtx;
    cparams.n_batch     = kNBatch;
    cparams.n_ubatch    = kNBatch;
    cparams.n_threads   = threads > 0 ? threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    ctx->lctx = llama_init_from_model(ctx->model, cparams);
    if (ctx->lctx == nullptr) {
        LOGE("init: failed to create context");
        delete ctx;
        return 0;
    }

    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu        = useGpu;
    mp.print_timings  = false;
    mp.n_threads      = cparams.n_threads;
    mp.warmup         = false;
    ctx->mctx = mtmd_init_from_file(proj_path.c_str(), ctx->model, mp);
    if (ctx->mctx == nullptr) {
        LOGE("init: failed to load audio projector %s", proj_path.c_str());
        delete ctx;
        return 0;
    }
    // Do NOT hard-require audio: the same loader serves the vision-only VLM captioning path.
    LOGI("init: audio=%d vision=%d", (int) mtmd_support_audio(ctx->mctx), (int) mtmd_support_vision(ctx->mctx));

    ctx->gpu = useGpu;
    LOGI("init ok (gpu_requested=%d, sample_rate=%d, threads=%d)",
         (int) useGpu, mtmd_get_audio_sample_rate(ctx->mctx), cparams.n_threads);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jstring JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_transcribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray jPcm, jstring jLangHint) {
    auto * ctx = (AsrContext *) (intptr_t) handle;
    if (ctx == nullptr || jPcm == nullptr) return env->NewStringUTF("");

    const jsize n_samples = env->GetArrayLength(jPcm);
    if (n_samples <= 0) return env->NewStringUTF("");

    std::vector<float> pcm((size_t) n_samples);
    env->GetFloatArrayRegion(jPcm, 0, n_samples, pcm.data());

    // Each window is decoded independently, so start from a clean KV cache.
    llama_memory_clear(llama_get_memory(ctx->lctx), true);

    mtmd_bitmap * audio = mtmd_bitmap_init_from_audio(pcm.size(), pcm.data());
    if (audio == nullptr) {
        LOGE("transcribe: could not wrap audio");
        return env->NewStringUTF("");
    }

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const std::string prompt = build_prompt(ctx->model);

    mtmd_input_text text{};
    text.text          = prompt.c_str();
    text.text_len      = prompt.size();
    text.add_special   = true;
    text.parse_special = true;

    const mtmd_bitmap * bitmaps[1] = { audio };
    const int32_t tok = mtmd_tokenize(ctx->mctx, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(audio);
    if (tok != 0) {
        LOGE("transcribe: tokenize failed (%d)", tok);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("");
    }

    const auto t_prefill0 = std::chrono::steady_clock::now();
    llama_pos n_past = 0;
    const int32_t ev = mtmd_helper_eval_chunks(
            ctx->mctx, ctx->lctx, chunks, /*n_past=*/0, /*seq_id=*/0,
            /*n_batch=*/kNBatch, /*logits_last=*/true, &n_past);
    mtmd_input_chunks_free(chunks);
    if (ev != 0) {
        LOGE("transcribe: eval failed (%d)", ev);
        return env->NewStringUTF("");
    }
    const auto t_prefill1 = std::chrono::steady_clock::now();

    // Greedy decode. ASR is a transcription task, so argmax is both correct and the cheapest option.
    const llama_vocab * vocab = llama_model_get_vocab(ctx->model);
    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::string out;
    char piece[512];
    int n_generated = 0;
    bool hit_cap = true;

    for (int i = 0; i < kMaxNewTokens; ++i) {
        const float * logits = llama_get_logits_ith(ctx->lctx, -1);
        if (logits == nullptr) break;

        llama_token best = 0;
        float best_logit = logits[0];
        for (int32_t t = 1; t < n_vocab; ++t) {
            if (logits[t] > best_logit) { best_logit = logits[t]; best = t; }
        }
        if (llama_vocab_is_eog(vocab, best)) { hit_cap = false; break; }

        const int32_t np = llama_token_to_piece(vocab, best, piece, sizeof(piece), 0, /*special=*/false);
        if (np > 0) out.append(piece, np);

        llama_batch batch = llama_batch_get_one(&best, 1);
        if (llama_decode(ctx->lctx, batch) != 0) { hit_cap = false; break; }
        n_past++;
        n_generated++;
    }

    // Prefill/decode split per window. Without it there is no way to tell an expensive audio encoder
    // from a decoder that is running to its token cap on repetition — the fixes for those are opposite.
    const auto t_decode1 = std::chrono::steady_clock::now();
    const auto ms = [](auto a, auto b) {
        return (long) std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count();
    };
    const long pf = ms(t_prefill0, t_prefill1);
    const long dc = ms(t_prefill1, t_decode1);
    LOGI("window: prefill=%ldms decode=%ldms tokens=%d (%.1f tok/s)%s n_past=%d",
         pf, dc, n_generated,
         dc > 0 ? (n_generated * 1000.0 / dc) : 0.0,
         hit_cap ? " HIT_TOKEN_CAP" : "", (int) n_past);

    return env->NewStringUTF(out.c_str());
}


// Caption one RGB888 image with a vision-language model loaded through the SAME init as ASR — the
// loader never required audio, precisely so this path could exist. Greedy decode, hard token cap:
// captions are search text, not prose, and every extra token is decoder time multiplied by the gallery.
JNIEXPORT jstring JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_caption(
        JNIEnv * env, jobject, jlong handle, jbyteArray jRgb, jint w, jint h, jint maxTokens,
        jstring jPrompt) {
    auto * ctx = (AsrContext *) (intptr_t) handle;
    if (ctx == nullptr || jRgb == nullptr || w <= 0 || h <= 0) return env->NewStringUTF("");
    if (!mtmd_support_vision(ctx->mctx)) {
        LOGE("caption: loaded projector has no vision tower");
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(jRgb);
    if (n < w * h * 3) return env->NewStringUTF("");
    std::vector<unsigned char> rgb((size_t) n);
    env->GetByteArrayRegion(jRgb, 0, n, reinterpret_cast<jbyte *>(rgb.data()));

    llama_memory_clear(llama_get_memory(ctx->lctx), true);

    mtmd_bitmap * image = mtmd_bitmap_init((uint32_t) w, (uint32_t) h, rgb.data());
    if (image == nullptr) return env->NewStringUTF("");

    // The chat template is the CALLER's job, because it is model-specific: SmolVLM wants
    // "<|im_start|>User: ...<end_of_utterance>", Qwen3-VL wants chatml — and a wrong template does not
    // error, it just degrades output quality silently. The caller writes "{MEDIA}" where the image
    // belongs; it is replaced with mtmd's marker here so Kotlin never needs the native constant.
    std::string prompt = jstr(env, jPrompt);
    const std::string placeholder = "{MEDIA}";
    const size_t at = prompt.find(placeholder);
    if (at != std::string::npos) {
        prompt.replace(at, placeholder.size(), mtmd_default_marker());
    } else {
        prompt = std::string(mtmd_default_marker()) + prompt;
    }

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    mtmd_input_text text{};
    text.text          = prompt.c_str();
    text.text_len      = prompt.size();
    text.add_special   = true;
    text.parse_special = true;

    const mtmd_bitmap * bitmaps[1] = { image };
    const int32_t tok = mtmd_tokenize(ctx->mctx, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(image);
    if (tok != 0) {
        LOGE("caption: tokenize failed (%d)", tok);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("");
    }

    const auto t0 = std::chrono::steady_clock::now();
    llama_pos n_past = 0;
    const int32_t ev = mtmd_helper_eval_chunks(
            ctx->mctx, ctx->lctx, chunks, /*n_past=*/0, /*seq_id=*/0,
            /*n_batch=*/kNBatch, /*logits_last=*/true, &n_past);
    mtmd_input_chunks_free(chunks);
    if (ev != 0) {
        LOGE("caption: eval failed (%d)", ev);
        return env->NewStringUTF("");
    }
    const auto t1 = std::chrono::steady_clock::now();

    const llama_vocab * vocab = llama_model_get_vocab(ctx->model);
    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    const int cap = maxTokens > 0 ? maxTokens : 24;
    std::string out;
    char piece[512];
    int n_generated = 0;

    for (int i = 0; i < cap; ++i) {
        const float * logits = llama_get_logits_ith(ctx->lctx, -1);
        if (logits == nullptr) break;
        llama_token best = 0;
        float best_logit = logits[0];
        for (int32_t t = 1; t < n_vocab; ++t) {
            if (logits[t] > best_logit) { best_logit = logits[t]; best = t; }
        }
        if (llama_vocab_is_eog(vocab, best)) break;
        const int32_t np = llama_token_to_piece(vocab, best, piece, sizeof(piece), 0, /*special=*/false);
        if (np > 0) out.append(piece, np);
        llama_batch batch = llama_batch_get_one(&best, 1);
        if (llama_decode(ctx->lctx, batch) != 0) break;
        n_generated++;
    }

    const auto t2 = std::chrono::steady_clock::now();
    const auto ms = [](auto a, auto b) {
        return (long) std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count();
    };
    LOGI("caption: %dx%d prefill=%ldms decode=%ldms tokens=%d", w, h, ms(t0, t1), ms(t1, t2), n_generated);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_detectedLanguage(JNIEnv * env, jobject, jlong handle) {
    auto * ctx = (AsrContext *) (intptr_t) handle;
    // Qwen3-ASR does not expose a discrete language id through mtmd; language is implicit in the
    // produced text. Reported as unknown so the Kotlin side keeps auto-detect behaviour.
    return env->NewStringUTF(ctx ? ctx->last_lang.c_str() : "");
}

JNIEXPORT jboolean JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_usingGpu(JNIEnv *, jobject, jlong handle) {
    auto * ctx = (AsrContext *) (intptr_t) handle;
    return ctx && ctx->gpu ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_ai_rightone_finderplus_speech_AsrNative_free(JNIEnv *, jobject, jlong handle) {
    auto * ctx = (AsrContext *) (intptr_t) handle;
    delete ctx;
}

} // extern "C"
