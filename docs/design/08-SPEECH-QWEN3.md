# 08 · Speech: Qwen3-ASR via llama.cpp

## Why this document exists

Transcription was reported as "failing on multi-language speech". The on-device evidence said something
different and more important:

```
TRANSCRIBE (pass 5) unit states:  state 0 (PENDING) -> 1348
distinct TRANSCRIBE errors:       (none)
transcript documents produced:    0
native libs in APK:               libmlkit*.so, libonnxruntime*.so   ← no speech library at all
```

All 1,348 transcription units were still **PENDING** — never attempted, zero errors. Speech was an
interface stub with no model and no native code. So the failure was **absence, not model quality**, and
"switch to a better model" first required building the feature.

## Engine choice: Qwen3-ASR, not Whisper

| | Whisper (small/base) | **Qwen3-ASR 0.6B / 1.7B** |
|---|---|---|
| Turkish quality | weak below `small`; `tiny`/`base` unusable | **officially supported** (one of 30 languages) |
| Runtime | whisper.cpp | **llama.cpp `mtmd`** — official support (`tools/mtmd/models/qwen3a.cpp`) |
| Weights | ggml | GGUF from `ggml-org/Qwen3-ASR-{0.6B,1.7B}-GGUF` |
| GPU | Vulkan/OpenCL via ggml | same ggml backends |
| License | MIT | Apache-2.0 |

Qwen3-ASR's language list explicitly includes **`tr`**, alongside en, de, fr, es, ru, ar, zh, ja, ko and
20 more — which is exactly the multi-language case that was failing. Both sizes are officially
supported by llama.cpp's multimodal audio path, so no bespoke model plumbing is needed.

### Published artifact sizes (verified)

| Model | Decoder | Audio projector (`mmproj`) | Total |
|---|---|---|---|
| Qwen3-ASR-0.6B Q8_0 | 805 MB | 214 MB | **~1.0 GB** |
| Qwen3-ASR-1.7B Q8_0 | 2,165 MB | 356 MB | **~2.5 GB** |

**0.6B is the default.** 1.7B is selectable for best accuracy, but it is an autoregressive 1.7B decoder:
expect well below real-time on the Exynos 2400, so transcribing a 1,348-file corpus with it is an
overnight, on-charger job. The catalog states this in the model's `note` rather than hiding it.

## Architecture

```
PcmDecoder ──30 s window──► Vad.hasSpeech()? ──no──► skip (advance checkpoint, no model run)
                                   │yes
                                   ▼
                       AsrNative.transcribe(pcm)          [JNI]
                                   │
              llama.cpp: mtmd_bitmap_init_from_audio → mtmd_tokenize
                       → mtmd_helper_eval_chunks → greedy decode
                                   │
                     Segment(startMs, endMs, text) ──► committed with the checkpoint
```

- **`ai-speech/src/main/cpp/finder_asr.cpp`** — JNI bridge. One llama context per file, KV cache cleared
  per window so windows are independent. Greedy (argmax) decoding, which is both correct for
  transcription and the cheapest option.
- **`Qwen3SpeechRecognizer`** — drives windows, owns the resume cursor, honours cooperative stop at
  window boundaries, and degrades to `PassOutcome.SKIPPED` when models are absent.
- **`ai-speech/src/main/cpp/CMakeLists.txt`** — builds only `llama`, `ggml` and `mtmd`
  (`LLAMA_BUILD_MTMD=ON`, tools/examples/tests/server off), arm64-v8a only.

## VAD: battery *and* quality

Every window passes an energy + zero-crossing VAD before the model sees it. Two distinct wins:

1. **Battery** — most gallery video audio is silence, music or ambience. Those windows never reach the
   decoder at all.
2. **Correctness** — sequence-to-sequence ASR models (Whisper and Qwen3-ASR alike) reliably
   *hallucinate* fluent text on silence. Dropping non-speech windows eliminates that failure mode
   rather than post-filtering it.

## GPU acceleration

`AsrConfig.accelerator` defaults to `GPU_VULKAN`, passed through to `n_gpu_layers` and
`mtmd_context_params.use_gpu`. The Vulkan backend is a **build flag** (`-PfinderVulkan=ON`) because
compiling its shaders lengthens the build and Xclipse 940 driver maturity varies. This is safe by
construction: ggml keeps layers on the CPU when no usable Vulkan device exists, so requesting GPU never
fails hard — it just may not help. CPU inference uses the Cortex-X4/A720 cores with NEON + i8mm.

## Skip, don't fail

Speech models are large and optional, so a missing model must not poison the ledger. `TranscribePassHandler`
returns `SKIPPED` when `recognizer.isReady()` is false; the ledger records state 5 (SKIPPED), which:

- does not consume retry attempts or mark the item FAILED,
- does not block the item's FTS/profile rebuild (so images and metadata stay fully searchable),
- can be revived in bulk by `WorkUnitDao.requeueSkipped(pass)` once a download completes.

`AsrNative.available()` never throws on a missing `.so` — it degrades to "speech unavailable".

## Build notes (things that actually blocked Android)

- **`MTMD_VIDEO=OFF` is required.** mtmd's video path shells out to an ffmpeg binary through
  `vendor/sheredom/subprocess.h`, which needs `posix_spawn` (not in bionic at minSdk 26) and
  `posix_spawn_file_actions_addchdir_np` (absent on Android entirely). This was the *only* thing that
  failed the build; core `llama`/`ggml` compiled cleanly. We feed PCM, so video is irrelevant.
- **`GGML_OPENMP=OFF`** — libomp is not in the NDK sysroot.
- **`LLAMA_BUILD_MTMD=ON` with `LLAMA_BUILD_TOOLS=OFF`** builds the `mtmd` library without the CLI
  binaries (llama.cpp adds `tools/mtmd` on its own in that configuration).
- Built with **NDK r29** for **arm64-v8a only**. That also let the app drop the x86/x86_64/armeabi-v7a
  copies of ML Kit and ONNX Runtime that were dead weight on an Exynos device.

## Status

**Built and packaged.** `libfinder_asr.so` compiles and the APK now ships the whole runtime:

```
lib/arm64-v8a/libfinder_asr.so     38 KB   (JNI bridge)
lib/arm64-v8a/libmtmd.so          1.0 MB   (multimodal/audio)
lib/arm64-v8a/libllama.so         2.9 MB
lib/arm64-v8a/libggml*.so         1.8 MB
```

Implemented: model catalog with real URLs/sizes, resumable download manager, VAD, engine abstraction,
JNI bridge over `mtmd_bitmap_init_from_audio` → `mtmd_tokenize` → `mtmd_helper_eval_chunks` → greedy
decode, CMake native build, skip-don't-fail semantics, and the GPU flag.

**Not yet measured on device**: transcription accuracy and throughput on real Turkish + English clips.
That needs the ~1 GB model download plus a connected phone, and is the next concrete step. Until a
model is installed, `TranscribePassHandler` reports SKIPPED and the rest of the gallery indexes normally.


## Measured: GPU vs CPU, and why the CPU boost does nothing here

Same audio file, same four 30-second windows, cooled to ~36 C before each run.

| backend | cpuset | ms / 30 s window | notes |
|---|---|---|---|
| Vulkan GPU | `moderate` (little cores) | **51,435** | baseline |
| Vulkan GPU | boost applied once, reverted | 53,401 | boost did not hold |
| Vulkan GPU | **`top-app` held all run** | **52,299** | 36.5 -> 39.7 C, ended one thermal tier higher |
| ggml CPU-only | `foreground` -> `moderate` -> `abnormal` | 71,000 / 72,700 / **148,000** | degrades as the platform demotes it |

Three conclusions:

1. **The CPU boost buys nothing for speech.** 52.3 s boosted against 51.4 s unboosted is inside
   run-to-run noise, while the boosted run ended 0.7 C hotter and one thermal tier higher. That is the
   signature of a GPU-bound pass: ggml offloads all 29 layers to Vulkan, so faster CPU cores only spin
   waiting on the GPU. `IndexOrchestrator` therefore skips the boost when a pass declares
   `RequiredModel.ASR` — heat is the scarce resource, and spending it where it buys nothing is worse
   than not boosting at all.
2. **Vulkan is worth roughly 1.4x on paper and much more in practice.** 52 s versus 71-72 s
   steady-state, but the CPU path *degrades* as the phone heats — 148 s on the third window once
   Samsung moved it to the `abnormal` cpuset. GPU work is not scheduled on CPU cores, so it is immune
   to that demotion and held ~52 s throughout. Stability is the bigger win.
3. **Speech is intrinsically slow here regardless.** 52 s to transcribe 30 s of audio is 1.7x slower
   than real time, for a 0.6B model on the best backend available. That is the number any future
   generative-model plan has to start from.


## Where the 50 seconds actually goes

Instrumenting the JNI decode loop settled it:

```
window: prefill=47446ms  decode=2669ms  tokens=84 (31.5 tok/s)  n_past=497
```

**Prefill is 94.6% of the window.** Text generation is not the problem at all — 31.5 tok/s is healthy,
and the 84 tokens emitted are nowhere near the 512-token cap, so there is no repetition loop to fix.
The cost is the Qwen3-ASR audio encoder, and on the Xclipse 940 through ggml-vulkan it is simply slow.

### Two ASR contexts at once is catastrophic

Found by accident, while an `AsrProbeWorker` run overlapped a live index. Both loaded their own ~1 GB
context and hammered the same GPU:

```
single context : decode 31.5 / 31.7 / 29.3 tok/s
two contexts   : decode 7.5 / 6.3 / 3.8 / 2.2 / 1.3 / 0.9 / 0.3 tok/s
```

Decode fell by up to **100x**, and the per-window figure went from ~50 s to 110 s. Nothing errored — it
just looked like the model being slow.

The cause was scope: `ModelCoordinator` enforces "one heavy model resident at a time", but it was
constructed *inside* `IndexEngine.create`, so only the indexing passes held it. Anything else — the ASR
probe, the encoder benchmark — took no lock at all. It is now a process-wide `@Singleton` that the probe
acquires too.

### Right-sizing the context did not help

One 30 s window uses ~497 positions (~413 audio + ~84 generated), yet the context was 8192 and the batch
2048 — which reserved a **1,323 MiB** Vulkan compute buffer. Cutting these to 1024/512 measured
**45.3 s vs 43.7 s** mean prefill over two clean windows each, from the same thermal tier: no
improvement, inside run-to-run noise. (Later windows in that log are unusable — they overlapped a second
ASR context, see above.)
The smaller sizes were kept anyway, since they free ~1 GB of GPU memory at no measured cost, but the
compute buffer was **not** the bottleneck. Worth recording precisely because it was the obvious guess.

### What is left

Silent windows are already skipped by VAD before the encoder runs, so the remaining work is real speech.
Sizing it: **964 files, 6.4 hours of audio**, of which **760 files are under 30 s** — one window each.
The cost is therefore dominated by file *count*, not length, which rules out capping per-file duration as
a lever. Reducing per-window encoder cost would need a different model or backend, not tuning.


## The cost is linear, which closes the tuning space

| window | audio tokens | prefill | decode |
|---|---|---|---|
| 30 s | 497 | 47,446 ms | 2,669 ms (31.5 tok/s) |
| 60 s | 988 | 96,676 ms | 5,783 ms (32.0 tok/s) |

Tokens x1.99, prefill x2.04. **Prefill is exactly linear in audio duration** — about **1.6 s of compute
per 1 s of audio** — so there is no fixed per-call overhead to amortize with longer windows. The earlier
anomaly that suggested otherwise (497 tokens at 47.4 s versus 518 at 40.0 s) was first-window warm-up
within a freshly created context, not a fixed cost.

30 s windows are therefore kept: same throughput, but finer resume granularity and usable per-window
timestamps.

### Why there is no fast path on this device

`VK_KHR_cooperative_matrix` is **not exposed** by the driver:

```
Samsung Xclipse 940 | driver 24.3.9 | api 1.3.279 | 153 extensions
present: VK_KHR_shader_float16_int8, VK_KHR_16bit_storage, VK_KHR_8bit_storage,
         VK_KHR_shader_integer_dot_product, VK_EXT_subgroup_size_control,
         VK_KHR_shader_subgroup_extended_types, VK_KHR_buffer_device_address
absent : VK_KHR_cooperative_matrix, VK_NV_cooperative_matrix(2)
```

So ggml runs matmul on plain FP16/FP32 shaders with no matrix-core path. Xclipse 940 is RDNA3-derived
and desktop RDNA3 *does* expose coopmat under RADV, so this is a driver gap rather than missing silicon
— and not something an app can work around.

### Everything measured and rejected

| attempt | result |
|---|---|
| Bigger context/batch (8192/2048 -> 1024/512) | no change (43.7 -> 45.3 s, noise) |
| Longer windows (30 s -> 60 s) | exactly linear, no gain |
| CPU big-core boost (`top-app`) | no gain, +0.7 C, one thermal tier higher |
| CPU-only ggml, 4 threads | 71 s/window (worse than 52 s GPU) |
| CPU-only ggml, 8 threads + boost | >3 min 45 s for one window — far worse |
| Smaller quantization | none published below Q8_0 |
| Stricter VAD | 368 of 391 completed files yield real text; nothing to skip |

The remaining lever is the model, not its configuration.


## Whisper was integrated, and it does not make this faster

whisper.cpp is wired in as a second, selectable engine (`WhisperSpeechRecognizer`, `SpeechEngine`,
`DelegatingSpeechRecognizer`). It shares llama.cpp's ggml — whisper.cpp guards its own copy behind
`if (NOT TARGET ggml)`, so adding it *after* llama.cpp reuses the same Vulkan backend instead of building
a second native stack. Cost: one 437 KB `libwhisper.so`.

Measured on the same file, same 30 s windows:

| engine | steady-state per 30 s window |
|---|---|
| Qwen3-ASR 0.6B Q8_0 | 47.4 s |
| **Whisper small q5_1** | **47.3 s (1.58x realtime)** |

**No speedup.** The bottleneck was never the model — it is an audio encoder running on a GPU with no
cooperative-matrix path, and Whisper's encoder is comparable work. Swapping encoders of similar size
hits the same wall. Window 1 measures ~79 s because Vulkan compiles its pipelines on first use.

### What Whisper does win

- **Segmentation.** Whisper emits its own per-utterance boundaries: 5-8 segments per window with real
  timestamps, against Qwen3's single span covering the whole 30 s. That is a direct improvement to the
  app's actual purpose — "match @ 0:11" now lands on *"And I'm Alex."* rather than on the start of a
  chunk. It also punctuates and capitalizes properly.
- **Size**: 190 MB versus 1,019 MB (805 model + 214 projector).
- **Load time**: 0.3 s versus several seconds.

### Why Qwen3-ASR remains the default

Whisper's only claimed advantage was speed, and speed is identical. Qwen3-ASR was chosen for Turkish
quality, and Whisper's Turkish output has **not** been verified on this gallery — so switching the active
engine would trade a measured unknown for no measured gain. Whisper stays installed and selectable so the
two can be A/B'd on real files (`TEST_ASR --el item <id>` targets one specific item, which is what makes
the comparison meaningful).

### The alternatives that were ruled out

- **`nvidia/nemotron-3.5-asr-streaming-0.6b`** — ships only `.nemo` + `.safetensors`, `library: nemo`. No
  ONNX, no GGUF. Would require NeMo->ONNX conversion plus implementing cache-aware streaming transducer
  decoding and a new native stack.
- **`nvidia/parakeet-tdt-0.6b-v3`** — whisper.cpp *does* now ship a `parakeet_*` API and a pre-converted
  `ggml-org/parakeet-GGUF` exists, so conversion was not the blocker. Its 25 supported languages are
  explicitly enumerated and **Turkish is not among them**, which is disqualifying for this gallery — the
  mirror image of why Whisper was originally passed over.
