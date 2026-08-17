# 11 — Self-tuning local classifier

How finder+ labels media: a frozen backbone that already knows the world, a zero-shot head that reads
that knowledge, and a personal head that learns this gallery. No model is trained, fine-tuned, or run
per-file by an LLM.

---

## 1. The shape of the problem

Three requirements that pull against each other:

1. **Know things on day one.** A fresh install must recognize *receipt*, *beach*, *chat screenshot*
   without being taught.
2. **Learn this gallery.** Naming one photo "Kerem" should find Kerem everywhere, like face
   recognition does.
3. **Stay small and fast.** No multi-GB model, no per-file language model. (Measured: Gemma-4 E2B
   captioning one 536×349 JPEG took **146.7 s** and emitted nothing. Abandoned.)

The resolution is that (1) and (2) are the *same operation* in the right representation. A CLIP-style
model puts images and text in one shared vector space. Once an item is a 512-d unit vector:

- a **concept name** becomes a vector (text tower) → recognition with zero examples,
- a **user's examples** become a vector (their mean) → recognition of personal concepts,
- and both are compared to the item by a single dot product.

So the backbone is frozen forever, and all "learning" is arithmetic over stored vectors.

```
                     ┌─────────────────────────────┐
   media ──────────► │  FROZEN BACKBONE            │  CLIP ViT-B/32, ONNX
                     │  image tower — never trains │  ~90M params, MIT
                     └──────────────┬──────────────┘
                                    │  512-d unit vector, stored once
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
    ┌───────────────────┐ ┌──────────────────┐ ┌────────────────────┐
    │ ZERO-SHOT HEAD    │ │ PERSONAL HEAD    │ │ ML Kit: OCR,       │
    │ text tower over a │ │ prototypes from  │ │ objects, faces     │
    │ ~300-concept list │ │ your labels      │ │ (independent)      │
    │ softmax, no train │ │ cosine, no train │ │                    │
    └─────────┬─────────┘ └────────┬─────────┘ └─────────┬──────────┘
              └────────────────────┴─────────────────────┘
                                    ▼
                        confidence band → HIGH: apply
                                          REVIEW: ask the user
                                          LOW: drop
                                    ▼
                        the answer updates the prototype
```

---

## 2. Why the text tower was the missing piece

The image tower alone cannot label anything — it produces a vector with no names attached. Until now
`OnnxClipTextEncoder` returned zeros, so:

- text→image search was dead,
- zero-shot labelling was impossible,
- and a new personal label had to start from nothing.

The blocker was not the model but the **tokenizer**: CLIP needs its own byte-pair encoding over a
49,408-entry vocabulary with a 77-token context. `ClipTokenizer` implements it (byte→printable
mapping, contraction-aware split, greedy merge by rank). It parses `vocab.json` by hand rather than via
`org.json`, because that class is a stub on the JVM and an untestable tokenizer is one whose subtle
breakage shows up months later as merely-mediocre search.

**Verified on device** — `finderClipProbe`, 3,489 image vectors, queries never stored as tags:

| query | top result |
|---|---|
| `a photo of a cat` | 0.2886 — profile: *"Shows cat, dog, ear, eyelash, fur…"* |
| `a screenshot of a text conversation` | 0.3150 — `Screenshot_20260124_192521_WhatsApp.jpg` |
| `a photo of food on a plate` | 0.2893 — *"Shows food, cuisine"* |
| `a selfie of a person smiling` | 0.3095 — *"Shows one person, portrait…"* |
| `a photo of a car` | 0.2868 — *"Shows bumper, car, vehicle, wheel, windshield"* |

Top-5 for every query was coherent, matched purely visually. The towers share a space.

---

## 3. The calibration trap

That probe produced the single most important number in this design:

> **text↔image cosine peaks around 0.31. image↔image cosine runs 0.6–0.9.**

The two heads are on **different scales**. The existing prototype threshold of `0.78` was set for
image↔image similarity. Applied to a text prior it rejects everything — an unmistakable match at 0.315
is less than half the cutoff. Applied the other way, a 0.3 threshold makes image prototypes fire on
everything.

This is exactly the failure that produces no error and no crash, just a feature that quietly never
works. So the heads are scored separately:

- **Taught labels** (≥1 exemplar) — cosine against the prototype, thresholded at `0.78`.
- **Seeded labels** (0 exemplars) — **softmax over the vocabulary** at CLIP's own trained inverse
  temperature (`logit_scale ≈ 100`), keeping concepts above `ZERO_SHOT_MIN_PROB = 0.12`. Zero-shot
  classification is defined relatively: what matters is that *cat* beats the other candidates, not that
  it clears an absolute number.

Mixing the two pools inside one softmax would let the high-scale image prototypes dominate the
normalization and flatten every genuine zero-shot match to near zero. They stay separate.

---

## 4. The text prior: how one example is enough

Each label stores `text_prior` — the CLIP text embedding of its own name — beside its exemplar mean.
Scoring uses a shrinkage blend:

```
α        = PRIOR_STRENGTH / (PRIOR_STRENGTH + n)      PRIOR_STRENGTH = 0.5
prototype = normalize(α · text_prior + (1 − α) · exemplar_mean)
```

| exemplars | prior weight | behaviour |
|---|---|---|
| 0 | 1.00 | pure world knowledge — usable immediately |
| 1 | 0.33 | mostly your example, still anchored |
| 5 | 0.09 | your version of the concept |
| 20 | 0.02 | fully personal |

Two properties this buys:

- **Cold start.** Label one photo "my bicycle" and the concept already generalizes, because it starts
  from CLIP's notion of *bicycle* rather than from a single point.
- **Robustness.** The anchor never fully disappears, so one mislabelled photo cannot redefine a class.

`PRIOR_STRENGTH` is deliberately < 1. Because a text vector only reaches ~0.3 cosine against *any*
image, giving it equal weight at `n=1` would drag the prototype off the image manifold and push scores
under the threshold — the calibration trap again, one level down.

Rejections still accumulate into a negative centroid; a candidate must beat its own negative to
survive.

---

## 5. Why retraining is free

Embeddings are stored, so nothing above ever re-reads media:

- 5,000 items × 512 floats × 4 B = **10 MB**
- re-labelling the whole gallery = 5,000 × 300 dot products ≈ 750 M FLOPs — **well under a second**

Every knob in this document — thresholds, prior strength, vocabulary contents — can therefore be
changed and applied retroactively to the entire gallery without touching a single file. That is the
main reason the design puts the expensive step (encoding) behind a durable store and keeps everything
else as arithmetic.

---

## 5b. Re-embedding throughput — what actually limits it

Measured while re-embedding ~3,500 images with ViT-B/16 on the SM-S926B. Every number here came from
instrumenting the pass, and two plausible fixes were measured and rejected.

| configuration | time per image | notes |
|---|---|---|
| fp32, 2 intra-op threads | **6.6 s** | starting point (decode 0.2-0.4 s, inference the rest) |
| fp32, XNNPACK EP | ~6.6 s | rejected — ran at ~56 % CPU, half a core |
| **fp32, 6 CPU intra-op threads** | **3.5 s** | current; 134 % CPU |
| int8 dynamic quantized (87 MB) | **8.5 s** | rejected — *slower* than fp32 |

Three findings worth keeping:

1. **Inference dominates, not I/O.** Decode is 140-460 ms; the forward pass is 3.3-3.7 s. Without the
   split, the obvious guess would have been slow MediaStore reads, which would have led to the wrong fix.
2. **int8 is a pessimization here.** ORT's dynamically-quantized `MatMulInteger` path lacks good kernels
   for these cores, and the per-op quantize/dequantize overhead outweighs the arithmetic saving. The
   4x-smaller file was tempting; the measurement settled it.
3. **The real ceiling is the scheduler.** The process sits in `cpuset:/background`, so Android confines
   it to the Cortex-A520 little cores at 1.96 GHz rather than the X4s at 3.21 GHz. An app cannot leave
   that cpuset. This — not thread count and not the model — is why a 17.5 GFLOP forward pass takes
   seconds, and it is a hard limit on any background CPU inference on this phone.

Session creation deserved its own fix: ORT re-optimizes the 345 MB graph on every load, which took long
enough that the pass sat in RUNNING until its lease expired and was retried from scratch three times,
never completing. `setOptimizedModelFilePath` writes the optimized graph once beside the model; later
loads read it with optimization disabled.

### The cpuset is worth 5x — and it is not ours to set

`ClipBenchWorker` times the encoder over real images without touching the database, which is what made
the following comparable. Same model, same threads, same device:

| provider | cpuset | cores | median / image | n |
|---|---|---|---|---|
| CPU | **`top-app`** (rooted boost) | 0-9 | **190 ms** | 40 |
| CPU | `foreground` | 0-8 | 434 ms | 12 |
| NNAPI (fp16, CPU_DISABLED) | `moderate` | 0-3 | 1,794 ms (min 443, max 2,126) | 10 |
| CPU | `moderate` / `background` | 0-3 | 2,320 ms | 12 |
| CPU, as the indexer actually ran | `background` | 0-3 | 3,500 ms | — |

- **The encoder was never slow; the scheduling was.** 190 ms against the 3,500 ms the indexer achieved
  is an **18x** gap, and none of it is about the model, the provider, or the thread count. Everything
  else in this section is a rounding error next to it. At 190 ms the whole 3,490-image gallery re-embeds
  in ~11 minutes rather than ~3.4 hours.
- **NNAPI was the right thing to try and still not the answer.** It is the only *non-root* way out of
  the cpuset, since NPU work is not scheduled on CPU cores at all, and on little cores it does beat the
  CPU path by ~1.3x. But a 443-2,126 ms spread says it is not reliably staying on the NPU, and it is
  deprecated as of API 35. Kept as a measured option (`--es ep nnapi`), never a default.

### Getting the boost, and keeping it

`CpuBooster` moves the process and every thread into `top-app` via `su`. Two non-obvious requirements:

1. **`su` must be invoked by absolute path.** Magisk installs it only as `/system_ext/bin/su` and
   `/debug_ramdisk/su`, neither of which is on an Android app's exec PATH — so `ProcessBuilder("su", …)`
   fails at exec *before Magisk is consulted*. That is indistinguishable from "root was denied", and it
   sent the first diagnosis down the wrong path: root had in fact already been granted.
2. **The boost must be re-applied continuously.** Samsung demotes a sustained background load into its
   `abnormal` cpuset — also cores 0-3 — within minutes, and the cpuset was observed reverting to
   `background` the instant a benchmark run ended. `ensureBoosted()` is therefore called per work unit:
   the check is one small file read, and the `su` exec only happens once we have actually been demoted.

Without root this is a no-op that logs `UNAVAILABLE` and changes nothing. Granting an app root is the
user's decision, so the app asks and accepts the answer.

### The trade-off this creates

Big cores heat the SoC faster. During this work the AP reached 46.3 °C and the platform reported thermal
status MODERATE, and the run had already been paused by the governor. So the boost buys **per-item
latency**, while **sustained throughput remains bounded by heat** — the duty-cycle governor in
`PowerPolicy` is what keeps that from becoming a dead battery or a killed process, and it should not be
loosened just because the cores got faster.

## 6. Encoder options considered

Current: **CLIP ViT-B/32** (`Qdrant/clip-ViT-B-32-{vision,text}`, MIT, fp32 ONNX, 512-d, 224 px).
Installed and verified. 352 MB vision + 254 MB text.

| model | zero-shot IN-1k | license | notes |
|---|---|---|---|
| CLIP ViT-B/32 *(previous)* | ~63 % | MIT | 49 patches. |
| **CLIP ViT-B/16** *(current)* | ~68 % | MIT | `Xenova/clip-vit-base-patch16`. 196 patches, same tokenizer, same 512-d, **345 MB vs 352 MB** — same parameter count, so 4× the spatial detail costs compute, not storage. |
| SigLIP 2 base/16 | ~78 % | Apache-2.0 | Strongest permissive option, but a **different tokenizer** (Gemma, 256k vocab) — `ClipTokenizer` would not apply. |
| MobileCLIP2-S2 | ≈ SigLIP B/16, 2.3× faster | **apple-amlr** | Technically ideal — `fastvit_mci2`, 256 px, 512-d, ONNX available. **See licence warning.** |
| MobileCLIP2-S4 | ≈ SigLIP-SO400M | apple-amlr | 1.29 GB vision tower. Too large. |

### Licence warning — MobileCLIP2

Apple's weights ship under the *Apple Machine Learning Research Model License*, which grants use
**exclusively for Research Purposes** and states this "does not include any commercial exploitation,
product development or use in any commercial product or service."

MobileCLIP2-S2 is the best model in the table on accuracy-per-millisecond, and its preprocessing
differs from CLIP's (mean `[0,0,0]`, std `[1,1,1]`, 256 px, bilinear — *not* the ImageNet
normalization `ClipPreprocess` applies). It is a fine choice for a personal build. It is not a safe
default for anything shipped under `ai.dusty.*`.

**Recommendation:** stay on ViT-B/32 now (working, verified, MIT); upgrade to **ViT-B/16 LAION-2B**
when spatial detail becomes the limit, since it is a drop-in swap needing only a pass-version bump to
re-embed.

---

## 7. Vulkan: where GPU offload is worth it

Measured, not assumed. `SpeechBackends.devices()` enumerates ggml's registered compute devices
directly, because ggml's documented behaviour on a missing or unusable Vulkan device is to fall back to
CPU **silently** — so "GPU enabled" is otherwise an assumption.

| workload | runtime | GPU offload? |
|---|---|---|
| **Qwen3-ASR** (~1,300 files pending) | llama.cpp / ggml | **Yes — the only real beneficiary.** Vulkan backend, `n_gpu_layers = 999`. |
| CLIP image embed | ONNX Runtime | **No.** ORT has no Vulkan EP. ~4.4 GFLOPs/image is not the bottleneck. |
| CLIP text embed | ONNX Runtime | No. Runs once per query / per seeded concept. |
| ML Kit OCR / objects / faces | ML Kit | Already GPU/NPU-accelerated internally. |

Vulkan is a **build flag** (`-PfinderVulkan=ON`), off by default, because shader compilation lengthens
the build considerably. Getting it to compile at all required four separate fixes:

1. **Host shader generator.** ggml runs `vulkan-shaders-gen` *during* the build. Under the NDK
   toolchain it is cross-compiled for arm64 and cannot execute on the build machine — which manifests
   as the build hanging, not as an error. Fixed by `host-toolchain.cmake` +
   `GGML_VULKAN_SHADERS_GEN_TOOLCHAIN`.
2. **API level.** `ggml-vulkan` calls `vkGetPhysicalDeviceFeatures2`, a Vulkan 1.1 entry point that
   Android's stub `libvulkan` only exports from **API 28**. The native build targets 28 while the app
   keeps `minSdk 26`.
3. **`vulkan.hpp` not found.** The NDK ships Vulkan's C headers but not the C++ bindings. Supplying
   them via `include_directories()` does *not* work: ggml creates backend targets through a helper that
   assigns `INCLUDE_DIRECTORIES` outright, discarding anything inherited.
4. **Do not put `/usr/include` on a cross-compile path.** The obvious fix for (3) poisons the build —
   glibc headers shadow bionic and CMake's first compiler probe fails with `pthread.h - not found`.
   The working form synthesizes a directory holding a single `vulkan` symlink, so `<vulkan/vulkan.hpp>`
   resolves and nothing else from the host leaks in.

### Runtime finding

The default (CPU-only) build reports exactly the silent fallback the probe exists to catch —
`gpu_requested=1` while every layer sits on CPU:

```
backend: CPU CPU 'CPU' 11207 MiB          backends: gpu=false
load_tensors: layer 0..28 assigned to device CPU
sched_reserve: CPU compute buffer size = 1203.01 MiB    graph splits = 1
```

With `-PfinderVulkan=ON`, the same probe confirms real GPU execution:

```
llama_prepare_model_devices: using device Vulkan0 (Samsung Xclipse 940) - 7843 MiB free
load_tensors: offloaded 29/29 layers to GPU
load_tensors:   Vulkan0 model buffer size =  604.15 MiB
load_tensors:   CPU_Mapped model buffer size = 157.65 MiB
sched_reserve:  Vulkan0 compute buffer size = 1323.07 MiB     graph splits = 2
```

**Cost:** `libggml-vulkan.so` is 51 MB of precompiled shaders, taking the debug APK from 97 MB to
149 MB. That is the trade for GPU speech; the flag stays opt-in partly for this reason.

A related bug was fixed in the same pass: the ggml log callback emitted **one logcat line per
character** (llama.cpp streams model-load progress as individual `.` fragments), flooding the ring
buffer and evicting the backend-selection lines. It now buffers to newline.

---

## 8. Reaching general-purpose breadth: the hierarchy

The goal is a model that recognizes what a general VLM recognizes — famous people, landmarks, brands,
the long tail of common objects — while staying personalizable. The obstacle is not model capacity;
CLIP already carries that knowledge from web pretraining. The obstacle is **softmax dilution**.

Probability mass is conserved. In a flat vocabulary, every concept added takes mass from every other,
so a 5,000-entry list is *worse* than a 300-entry one: the right answer competes with thousands of
irrelevant concepts and lands under any useful floor. "Just add more labels" degrades the system.

`ConceptVocabulary` therefore branches, and `ConceptClassifier` scores in two stages:

1. **Gate** — softmax over ~13 coarse domain phrases (*a photo of a person*, *a photo of a document*,
   *a screenshot of a screen*…). Cheap, and the domains are visually distinct enough to be reliable.
2. **Expand** — softmax over only the winning domains' concepts (`GATES_TO_EXPAND = 3`, gates below
   `MIN_GATE_PROB = 0.08` dropped).

Each softmax stays small and sharp; the reachable vocabulary is the union of all branches. Breadth
scales with the number of domains rather than trading against precision.

Domains: people, places, animals, food, vehicles, documents, screen, objects, events, art, nature,
photographic quality, **entities**.

### Named entities

`ConceptVocabulary.ENTITIES` holds landmarks, brand logos, fictional characters and public figures —
the "it just knows things" behaviour, obtained with no extra model. It is its own domain so identity
guesses never dilute the ordinary concept softmax.

**Stated plainly:** identity recognition from a 224 px whole-frame embedding is materially less
reliable than the rest of this vocabulary, and it degrades in the order landmarks → brands →
characters → faces. A confidently wrong name is the one output a user would call broken rather than
imperfect. The intended flow is therefore *suggest, user confirms*, and the durable path to naming
people remains face clustering plus the user's own names — exact where this is only likely.

#### Entities need an absolute floor, not a relative one

This was found on-device, not predicted. With only the relative threshold in place, a street photo of
buses produced:

```
entities  0.386  the great wall of china      ← ranked #1
vehicles  0.066  car
```

A softmax is forced to sum to 1, so it **always** crowns a winner — even over a set where nothing
fits. The Great Wall was simply the least-bad of ~100 entities. Relative confidence structurally
cannot express *"none of these"*.

Raw cosine can, because it is anchored: on this gallery a genuine text-image match measures ~0.29–0.32
and an unrelated pair sits near 0.20. Entities must therefore clear
`ENTITY_MIN_COSINE = 0.28` **in addition to** the softmax threshold. After the fix, both invented
entities disappear and `car` correctly leads its photo — with every other domain's output unchanged.

This generalizes: any bounded candidate set scored by softmax needs an absolute admission test, or it
will confidently answer questions whose true answer is "nothing".

#### Cross-domain scores must be joint

Also found on-device: concept scores from different domains were not comparable, because a conditional
probability ("given this is an event photo, is it a race?") ignores how likely the domain was. That put
*running race* (0.21) above *car* (0.19) on a photo of buses. Admission still uses the conditional —
each domain keeps its own sharp floor — but ranking uses `P(domain) × P(concept | domain)`.

### Personalization outranks the vocabulary

A taught label is scored on the user's exemplars and, when it fires, **displaces** the generic concept
for the same idea rather than appearing beside it with a second confidence. The shipped vocabulary is a
starting point the gallery progressively overwrites.

### SEO shaped by the gallery

`ConceptClassifier.tuneToGallery()` samples the gallery, records which concepts ever win, and deletes
seeded concepts that never do. This is not housekeeping — an unused concept keeps taking softmax mass
from the concepts that matter, so pruning *raises* confidence on real matches. Two invariants:

- Anything the user taught is never pruned, however rare. A label used once, on purpose, is the most
  valuable label in the database.
- Gate phrases are never pruned; they are structural, and losing one disables a whole branch.

The result is a vocabulary that starts general and converges on the shape of this particular gallery.

### Throughput

Seeding needs one text encode per (concept × prompt). At ~1 s each — ONNX Runtime per-call overhead on
a `[1,77]` input, not compute — the vocabulary would take hours. `ClipTextEncoder.encodeBatch` submits
`[N,77]` instead, 16 concepts × 3 prompts per call, which is what makes a vocabulary this size
practical. The implementation probes batch support once at runtime and falls back to single rows if the
export pins its batch axis, so correctness never depends on the guess.

Seeding runs via `VocabularySeedWorker` and is interruptible: a seeded label is skipped next run, so the
prototype table *is* the checkpoint — no cursor that could disagree with it.

---

## 9. Video: many frames, one answer

A video stores one embedding per keyframe, so labelling it is a question about a *set* of vectors. Three
reductions are possible and only one of them is a statement about the video.

| reduction | tags/video | failure |
|---|---|---|
| union of each frame's winners | **2.7** (ML Kit: 11.4) | 52% of them come from ≤1/10 of the frames |
| `softmax(mean vector)` | 0.6 | averaging cancels detail; heterogeneous clips label to nothing |
| **mean of per-frame posteriors** | 0.4 apply + 0.8 review | — |

Measured over 502 indexed videos, 5,484 frames.

**The union is not a tuning problem, it is the wrong reduction.** Twenty independent classifications of
twenty different moments, OR'd together, describe no single thing. One clip came back as
`cow, horse, shark, dog, turtle, screenshot of a video game` — six frames each winning something
different, every one of them individually above its confidence floor. The same clip under
mean-of-posteriors asserts nothing at all, because nothing describes it. Note what this means for model
choice: **a stronger backbone reproduces the failure exactly**, since the defect is in the aggregation.
This was originally observed with ML Kit's labeler and reproduced with CLIP.

So `P(concept | video) = mean_i P(concept | frame_i)` — a proper mixture over frames. It needs no
threshold of its own: a concept present throughout keeps its full score, one appearing in a single frame
is divided by the frame count and falls below the review band on its own. The bands calibrated for
photos (§4) transfer unchanged, and for a single-vector item the mean of one posterior *is* that
posterior — so photos are unaffected bit for bit.

Two heads aggregate differently, on purpose, because they answer different questions:

- **taught** labels ask *"is my cat in here?"* → **max** over frames. A video answers yes if any frame does.
- **zero-shot** concepts ask *"what is this?"* → **mean** over frames.

### Coarse category

Stage 1's gate posterior, averaged over frames, also yields the one word summarizing a video —
`MIN_CATEGORY_PROB = 0.45`, roughly 6× the 1/13 chance rate. Validation is external rather than
circular: the highest-scoring categories agree with what the filenames independently say
(`Screen_Recording_20250906_014656_Google.mp4` → `screen` at 0.96), while everything the floor rejects
sits at 0.18–0.22. It admits 263 of 502 videos. This matters because `IMAGE_LABEL` — which writes
`CATEGORY` for photos — never runs on a video, so this is video's only category source.

### What video no longer does

`KEYFRAMES` no longer runs the ML Kit labeler at all. Per-frame *labels* were the redundant half of that
pass: the per-frame **embedding** already finds the moment a dog appears, by open-vocabulary similarity
rather than a fixed 400-word list, so removing them costs no retrieval. Dropping the labeler also
removes one model invocation per frame, making video indexing faster. Per-frame OCR stays — recognized
text is a transcription, not a guess, and cannot mislead the way a classification can.

---

## 10. Open items

- **Region embeddings.** A 224 px whole-frame embedding gives a small face or a meme's text ~1 of 49
  patches. Faces are now cropped and embedded (`RegionEmbedder`); object boxes are detected and returned
  but not yet persisted or embedded, and 2×2 tiling is not implemented.
- **Review-queue UI.** `ReviewQueue` does uncertainty sampling and confidence banding; nothing surfaces
  its suggestions to the user. This is the gap that matters most now: the review band is where video's
  plausible-but-unconfirmed labels accumulate.
- **Cluster-naming UI.** `SimilarityClusterer` groups people and scenes; nothing lets the user name a group.
- **Backbone upgrade.** CLIP ViT-B/16 (68.3% IN-1k zero-shot) is the weakest link *for accuracy*, though
  not for the video-labelling defect above. SigLIP base-patch16-224 is Apache-2.0, ~76%, and the same
  vision-tower cost; its 32k SentencePiece vocabulary keeps the text tower small, unlike SigLIP 2's 256k
  Gemma vocabulary. Swapping invalidates every stored vector, so it is a re-embed of the whole gallery.
