# Your blazing fast gallery finder

**Finder+** indexes an Android gallery on the phone and makes it searchable by what is inside the files
rather than by filename. Every image, video and audio file is read once and turned into a block of
text: ML Kit labels, OCR, YOLOX object classes, face groups, a CLIP vector, and a speech transcript
for anything with audio.<br>
<div align="center">
  <b>In action shot</b><br>
  <sub><i>please dont question my gallery. thanks.</i></sub><br><br>
<img width="360" height="720" alt="Screenshot_20260810_124224_finder+" src="https://github.com/user-attachments/assets/be92665e-c06c-4324-acc0-b39545a0698c" /></div>

There is no app screen to open. Search is a home-screen widget and a translucent pop-up over whatever
you were doing. Tapping a result puts the media URI and its extracted text on the clipboard as a
single clip.

<img width="230" height="157" alt="image" src="https://github.com/user-attachments/assets/0dfcf803-01fa-49c4-a94e-d52293dcdbf6" />

There's an accessibility preference that lets you bind `finder+ search` as a shortcut for much quicker access to your gallery.

## Download

Grab `app-debug.apk` from the [releases page](https://github.com/Okdusty/finder-plus/releases/tag/v0.1).

| | |
|---|---|
| ABI | arm64-v8a only (no x86, no armeabi-v7a) |
| Android | 8.0 / API 26 and up, built against API 35 |
| Signature | self-signed `CN=finderplus, O=dusty`, so Play Protect will warn about an unknown developer |

Modern phone recommended *(min 6GB)*, Vulkan acceleration is supported. 
Snapshot releases, do not expect reliable and power tiled app. 
Can be power hungry when phone is charging, During `Indexing...` or labeling sessions, it's recommended to keep an eye on your phone.

<img width="286" height="433" alt="image" src="https://github.com/user-attachments/assets/08a3937e-a05a-4402-be6b-2253796b5141" />

Then label it, as you want... You can customize it, CLIP will learn based on your labeling, and this will eventually teach the model to auto-label your gallery, locally/privately/wisely.  

<img width="264" height="186" alt="image" src="https://github.com/user-attachments/assets/fc880746-36e2-473e-b4fe-778cd03e6bc9" />

Separation by commas is recommended while labeling; it'll assist in adapting to your gallery quicker.<br>
Don't go into detail about the media; always keep it brief, don't use full sentences.

## What runs in the beta

Bundled ML Kit labeling and OCR make the gallery searchable the moment media permission is granted:

- Gallery inventory with incremental diff
- Image labels and OCR, on device, offline
- Category derivation (screenshot, people, document, food, nature)
- Per-item text profile, keyword search, and tap-to-clipboard
- The widget, pop-up, and indexer with a working Stop

## What is missing

The settings screen is a stub (`SettingsActivity.kt` renders nothing), and nothing else calls
`ModelManager.download()`. **In this build there is no way to install the downloadable models**, so
speech transcription, CLIP semantic search, object detection, face grouping and captions have no
weights to load and their passes fail per item and get marked FAILED. Images still index. The engine,
the JNI bridges and the download code underneath are all wired; the UI that starts them is not.

Also missing:

- Text embedding: `OnnxTextEmbedder.embed()` returns a zero vector, so the TEXT_EMBED leg contributes
  nothing to ranking
- Place labels: EXIF GPS is not read and there is no offline reverse geocoder (`Passes.kt`)

## The indexing engine

The engine is the part worth reading. It has to index tens of thousands of work units on a phone that
will kill it, so it is built to be stopped:

- **Resumable.** Progress is a ledger of `(item, pass)` units in SQLite. A process kill resumes from
  the last committed checkpoint. Worst case you lose one unit: one image, one video keyframe, or one
  30-second audio chunk.
- **Stoppable.** Stop is cooperative and lands within a unit, not at the end of the run.
- **Sub-item checkpointing.** A one-hour recording resumes mid-file; a video resumes at the next
  keyframe.
- **Idempotent.** Re-running a pass converges to the same rows.
- **Sliced.** WorkManager kills any job that runs past roughly 10 minutes, which killed every early
  run mid-flight. Indexing now runs in 4-minute slices that reschedule themselves.
- **Governed.** With the CPU pinned the AP hit 46 °C and the thermal governor throttled it. A
  `PowerPolicy` now inserts per-unit pauses and cool-downs scaled by temperature, charge state and
  battery level, and the slice thread runs at `THREAD_PRIORITY_BACKGROUND`.
- **Cheap before expensive.** The 11 passes are ordered by priority (METADATA, IMAGE_LABEL, OCR,
  KEYFRAMES, IMAGE_EMBED, TRANSCRIBE, TEXT_EMBED, OBJECTS, FACES, CONCEPTS, CAPTION). The three cheap
  ones share one priority tier so items become fully text-searchable progressively instead of the
  whole gallery finishing metadata before anything gets a label.
- **One heavy model resident at a time**, with `pipeline_version` driving selective re-index when a
  model changes.

Schema, state machine and the kill-point recovery matrix are in
[`docs/design/01-DB-ENGINE.md`](docs/design/01-DB-ENGINE.md).

## Design changes the real device forced

Every item here is a fix, not a plan. Full list with evidence in
[`docs/design/06-ONDEVICE-VERIFICATION.md`](docs/design/06-ONDEVICE-VERIFICATION.md).

1. **FTS5 does not exist on this phone.** Samsung's system SQLite ships without the FTS5 module, so
   `CREATE VIRTUAL TABLE … USING fts5` aborted and rolled back Room's entire `onCreate`, dropping
   every table. The schema uses FTS4 now. The cost is `bm25()`: the keyword leg orders by recency and
   relevance comes from RRF fusion with the vector legs.
2. **`execSQL` cannot run a PRAGMA that returns a row.** `journal_mode=WAL`, `busy_timeout`,
   `mmap_size` and `wal_autocheckpoint` all threw. WAL moved to the Room builder; the rest run through
   `query().close()`. This failed every single database open.
3. **MediaStore columns are per-table.** Images have no `DURATION`, audio has no `DATE_TAKEN`. One
   shared projection threw on the first row.
4. **Indexing looked stuck at 1,094 of 19,657 because it was deleting its own work.**
   `media_generation` was persisted as `0` for all 4,928 rows while MediaStore reported a real
   generation, so the differ flagged every item as changed on each run, purged it and re-enqueued.
   `done` collapsed from 1,781 to 368 when a new run started. Fixed, with three regression tests in
   `MediaDifferTest`.
5. **Per-unit waste.** Three `COUNT(*)` over 19,657 rows per unit became in-memory counters, each
   photo was decoded twice and now hits a single-entry cache, and the initial scan stopped issuing one
   MediaStore query per item (about 5,000 of them) in favour of one query per volume.

## Models

Optional downloads. Nothing ships in the APK except ML Kit. Sizes are the published artifact sizes;
the full catalog with URLs and reasoning lives in `core-model/.../Model.kt`.

| Model | Size | Job | Licence |
|---|---|---|---|
| Qwen3-ASR 0.6B Q8 + projector | 805 MB + 214 MB | Speech, 30 languages | Apache-2.0 |
| Qwen3-ASR 1.7B Q8 + projector | 2.17 GB + 356 MB | Speech, best accuracy | Apache-2.0 |
| Whisper small q5_1 | 190 MB | Speech, the fast path | MIT |
| Whisper large-v3-turbo q5_0 | 574 MB | Speech, best Whisper tier | MIT |
| CLIP ViT-B/16 image + text | 345 MB + 254 MB | Semantic image search and zero-shot labels | MIT |
| YOLOX-tiny | 20.4 MB | 80 COCO object classes with boxes | Apache-2.0 |
| SmolVLM-256M + projector | 175 MB + 104 MB | One-sentence captions | Apache-2.0 |
| Qwen3.5-4B + projector | 2.5 GB + 836 MB | Verifies weak labels and captions | Apache-2.0 |
| MobileFaceNet `w600k_mbf` | 13.6 MB | Groups the same person across photos | non-commercial |
| Silero VAD | 2.3 MB | Skips silent windows before ASR | MIT |

Three of these choices came out of measurements rather than preference:

**Speech is Qwen3-ASR, and also Whisper.** Qwen3-ASR was picked over Whisper's small tiers for its
broad 30-language coverage. Then it measured 1.6 seconds of compute per second of audio on the Exynos
2400, prefill-dominated and linear in length, which puts a 6.4-hour audio backlog at about 10 hours.
Whisper's encoder is far better optimised and keeps the same language coverage, so both engines ship
and the model picker chooses which native path runs. Parakeet was rejected for its narrower
25-language coverage, and Nemotron-ASR because it ships only NeMo checkpoints with no on-device
inference path.

**Faces need a face model.** CLIP crops did not separate identity on this gallery: the two most
similar face crops in the whole set scored 0.868 and 0.859 and were same-photo pairs of provably
different people. CLIP encodes appearance because it was trained on captions. MobileFaceNet is trained
with a margin loss to separate identity across pose and lighting, and 13.6 MB does what the 345 MB
general encoder could not do at all.

**Volume cannot tell you whether someone is talking.** Across 18 files with known transcripts, the
ones that produced nothing measured -17.2 dB mean volume and the ones that produced full transcripts
measured -17.1 dB. The quiet files were not quiet, they were music and laughter. Silero VAD, at
2.3 MB, skips about 20% of windows and stops the recognizer inventing sentences over music.

## Modules

```
app            widget, search pop-up, review sheet, Hilt DI, UI contracts (settings still a stub)
engine-index   orchestrator, 11 pass handlers, WorkManager slices, model residency, resume/stop
engine-search  query parsing, spelling, RRF rank fusion across the keyword and vector legs
ai-vision      ML Kit labeling/OCR, ONNX CLIP towers, YOLOX detector, MobileFaceNet embedder
ai-speech      finder_asr JNI over whisper.cpp and llama.cpp mtmd, VAD gate, model manager
ai-text        sentence embedder interface (implementation is a stub)
core-db        Room, FTS4, vector store, work-ledger DAOs
core-media     MediaStore reader, incremental diff, frame and PCM decoders
core-model     shared domain types and the model catalog (pure Kotlin)
```

## Build

JDK 17, Android SDK API 35, NDK 29.0.14206865, and CMake for the native ASR bridge. whisper.cpp and
llama.cpp are vendored under `ai-speech/src/main/cpp/`, so a clone builds without fetching submodules.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # needs keys/finderplus-release.jks
./gradlew test
```

Release signing reads `finderplus.store.password` and `finderplus.key.password` from
`~/.gradle/gradle.properties`. Neither the keystore nor the passwords are in this repository. An
unsigned-config build still works: the signing block falls back to empty credentials when the keystore
is absent.

## Privacy

Inference runs on the phone. Indexing works in airplane mode, which is the check that makes the claim
falsifiable. The `INTERNET` permission has exactly one caller, `FileModelManager.download()`, which
fetches model files from Hugging Face and GitHub. No media content, thumbnail, transcript or label
leaves the device.

## Documentation

| Doc | Contents |
|---|---|
| [`01-DB-ENGINE.md`](docs/design/01-DB-ENGINE.md) | Schema, ledger state machine, resume and stop protocols |
| [`02-ARCHITECTURE.md`](docs/design/02-ARCHITECTURE.md) | Modules, version catalog, DI, manifest |
| [`03-AI-PIPELINE.md`](docs/design/03-AI-PIPELINE.md) | Pass handlers and their checkpoint hooks |
| [`04-SEARCH.md`](docs/design/04-SEARCH.md) | Keyword plus vector search and RRF fusion |
| [`05-MEDIA-PROFILE.md`](docs/design/05-MEDIA-PROFILE.md) | The per-item text profile and tap-to-clipboard |
| [`06-ONDEVICE-VERIFICATION.md`](docs/design/06-ONDEVICE-VERIFICATION.md) | What was verified on an S24+ and the bugs it exposed |
| [`07-BATTERY-POLICY.md`](docs/design/07-BATTERY-POLICY.md) | Sliced indexing, thermal governor, status reporting |
| [`08-SPEECH-QWEN3.md`](docs/design/08-SPEECH-QWEN3.md) | Qwen3-ASR through llama.cpp, VAD, the GPU flag |
| [`09-PEOPLE-AND-VLM.md`](docs/design/09-PEOPLE-AND-VLM.md) | Face clustering, object detection, the VLM tier, why the NPU is unreachable |
| [`ui/WIREFRAMES.md`](docs/ui/WIREFRAMES.md) | Widget, pop-up and settings wireframes |
| [`ui/UI-CONTRACTS.md`](docs/ui/UI-CONTRACTS.md) | UiState and ViewModel contracts |

## Licence

The app is under the repository's [LICENSE](LICENSE). Vendored whisper.cpp and llama.cpp keep their
own MIT licences. The MobileFaceNet weights are released for non-commercial research use, which is
fine for a personal gallery and not safe to ship commercially.
