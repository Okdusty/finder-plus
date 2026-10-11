# 09 · People, Objects and the VLM Tier

Two gaps drove this work: search could not identify **objects** well, and could not identify **people**
at all. The answer is a tiered classification stack — cheapest signal over everything, expensive
generation only where it is worth it.

## Why not "just run a VLM over the gallery"

A VLM is the obvious idea and the wrong default. Verified numbers for the mobile-class Gemma 4 models
(both real, both runnable on the llama.cpp/`mtmd` runtime already shipping in this app):

| Model | Decoder (q4_0) | Vision/audio projector | Total |
|---|---|---|---|
| `gemma-4-E2B-it` | 3,350 MB | 987 MB | **~4.3 GB** |
| `gemma-4-E4B-it` | 5,155 MB | 992 MB | **~6.1 GB** |

At roughly 5–20 s per image, a full 3,578-photo sweep is **5–20 hours** of sustained heavy compute —
precisely the thermal/battery failure mode that had to be fixed in `07-BATTERY-POLICY.md`. So the VLM is
an **opt-in deep pass on a bounded subset, charging + idle only**, using **E2B** (the lighter tier).

## NPU: not available on this device

Asked and answered with evidence, not assumption:

- The LiteRT Qwen3-ASR repo ships per-SoC NPU builds for **MediaTek MT6xxx** and **Qualcomm SM8xxx**
  only — **no Exynos variant at all**.
- `litert-community/gemma-4-E4B-it-litert-lm` ships **no NPU variants**, only `.litertlm`/`.task`.
- **NNAPI**, the only generic route to Samsung's NPU, is **deprecated in Android 15** — the OS on this
  phone. Samsung's ENN SDK is partner-gated, not public.

**Conclusion: the Exynos 2400 NPU is not reachable for this app.** The usable accelerator is the GPU
(Vulkan via ggml, already wired behind `-PfinderVulkan=ON`), with CPU (Cortex-X4 + NEON/i8mm) as the
dependable baseline.

## People: clustering + a name, not a model's guess

A VLM cannot know the people in *your* gallery, and for public figures it produces confident wrong
answers. The mechanism that actually works — and the one Google/Apple Photos use:

```
face detection (ML Kit, bundled, no download)
        │  boxes + attributes stored immediately
        ▼
face embedding (pluggable model)  ──►  incremental clustering (cosine ≥ 0.62)
        │                                        │
        ▼                                        ▼
   face rows                              person = cluster
                                                 │  user names it once
                                                 ▼
                                    "Ayşe" becomes a search term
```

- **`FACES` pass** (cheap tier, priority 10) runs ML Kit face detection and immediately emits
  *searchable* tags with no model download at all: `people`, `one person`, `two people`,
  `group of people`, `portrait`, `smiling`.
- Each face's **box is persisted now** (`face` table), so identity embeddings can be **backfilled**
  later without re-detecting every photo — the ledger's backfill mechanism already handles this.
- **`FaceEmbedder` is an interface, not a committed model.** The on-device face-embedding landscape is
  thin: the ONNX ArcFace/MobileFaceNet repos on HF are unvetted community uploads (0 downloads), so the
  model is a deliberate, replaceable choice rather than something baked in.
- **`FaceClusterer`** assigns each embedding to the nearest cluster centroid or starts a new one,
  updating centroids as a running mean — no global re-clustering pass, so it fits the same per-item
  budget as everything else. The threshold is deliberately conservative (0.62): over-splitting is a
  minor annoyance the user can merge, whereas merging two people produces confidently wrong results.
- Identity stays **entirely local and user-controlled**. Clusters are anonymous until named.

## Objects

`OBJECTS` pass (ML Kit object detection, bundled) adds multi-object tags plus a
`multiple objects` marker when three or more are present. Its classifier is coarse by design — it
contributes *breadth*, while precision comes from the labeler, OCR and (next) CLIP embeddings.

## Verified on device (S24+, after migration)

A rescan exercised schema migration 1→2 and backfilled both new passes onto the existing index:

```
items PRESERVED : 4,866          ← migration kept the whole index; nothing re-indexed from scratch
wu DONE         : 20,288 / 26,447
pass->count     : {... 7(OBJECTS): 3,515, 8(FACES): 3,515}   ← both backfilled onto existing photos
faces detected  : 130 (and climbing)
OBJECT tags     : 1,553
people tags     : people 280 · one person 45 · portrait 21 · smiling 18 · two people 10 · group 8
```

And they are searchable immediately:

```
FTS "portrait"          -> 21 hits
FTS "smiling"           -> 18 hits
FTS "one person"        -> 45 hits
FTS "group of people"   -> 8  hits
FTS "dog"               -> 188 hits   (was 49 before object detection widened coverage)
```

A sample AI-revision profile now reads:
`Tags: Eyelash Flesh Mouth Skin one person portrait other people | Album: gizli`

## Gemma 4 identity claims: propose, don't assert

Gemma 4 *can* recognise world-famous people and read a lot of other insight out of an image (landmark,
brand, event, activity). We want that signal. What we must not do is write it straight into the index,
for two reasons that are properties of the model rather than of our code:

- it will name a public figure **confidently and sometimes wrongly**, and
- it **cannot** know anyone private, so for most of a personal gallery there is no right answer.

A wrong name written as fact is worse than no name: search returns confidently incorrect results and
the user has no idea which labels to trust.

So identity flows through a three-party split:

| Party | Supplies |
|---|---|
| **Gemma 4** | the knowledge — *who is famous*, plus landmarks/brands/events |
| **Face clustering** | the reach — *every photo of that same person* |
| **The user** | the truth — *yes, that is them* |

Mechanically:

1. The VLM returns a structured block (`VlmInsightParser.PROMPT`) where the `PEOPLE:` line is only to be
   emitted for figures it is **certain** about, and omitted otherwise. Asking for certainty explicitly,
   and allowing omission, measurably reduces confabulation versus an open "who is this?".
2. Output is filtered before it is trusted: hedges (`unknown`, `a man`, `possibly`, …) are dropped, and
   a claimed name must actually *look* like a name — 1–4 capitalised words, no sentence punctuation.
   This rejects the descriptions models fall back on when they do not recognise anyone.
3. A surviving name becomes a **proposal on the face cluster**, stored in `person.proposed_name` —
   separate from the confirmed `person.name`, so a guess can never masquerade as verified identity.
4. `proposal_votes` increments only while successive photos agree on the same name and **resets when a
   different name appears**, so one hallucination cannot out-vote consistent agreement.
5. The user confirms once (`confirmProposal`) and clustering propagates that name to **every** photo of
   the person — including photos the VLM never looked at, which is the real payoff of pairing the two.

Non-identity insight (`SCENE`, `ACTIVITY`, `OBJECTS`, `PLACES`, `CAPTION`) carries no such risk and is
indexed directly, tagged `TagSource.VLM` so model-generated labels keep explicit provenance and can be
ranked differently, shown as "AI suggested", or purged wholesale if a model proves unreliable — without
disturbing the deterministic signals.

## Migration policy

The people tables were added via an explicit `Migration(1, 2)` rather than destructive fallback,
because a gallery index represents hours of on-device AI work. **An app update must never throw that
away** — the same principle that made the `media_generation` bug (which silently re-indexed everything)
the most serious defect found so far.

## Status

- **Done and verified:** OBJECTS pass, FACES pass (detection + attributes + tags + persisted boxes),
  `face`/`person` schema with migration, `FaceClusterer`, backfill onto the existing index, search
  integration.
- **Pluggable, not yet chosen:** the face-embedding model (needed before clusters can form).
- **Designed, not built:** the `CAPTION` VLM tier (Gemma 4 E2B via the existing `mtmd` runtime), gated
  to charging + idle over an opt-in subset.
- **Still open from earlier work:** CLIP embeddings remain stubbed, and end-to-end Qwen3-ASR
  transcription is unproven (the model loads — 2.1 GB RSS, 420% CPU — but no transcript has been
  produced yet; TRANSCRIBE sits behind KEYFRAMES in the queue).
