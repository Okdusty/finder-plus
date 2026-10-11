# Your blazing fast gallery finder

**Finder+** indexes an Android gallery on the phone and makes it searchable by what is inside the files
rather than by filename. Every image, video and audio file is read once and turned into a block of
text: OCR, a caption and object names from Florence-2, face groups, a CLIP vector, and a speech
transcript for anything with audio.<br>
<div align="center">
  <b>In action shot</b><br>
  <sub><i>results that are neither supervised nor hand-labelled</i></sub><br><br>
<img width="380" height="760" alt="Screenshot_20261011_120609_finder+ dev" src="https://github.com/user-attachments/assets/4109cd29-f31b-46f5-a6e6-eb2486977cdf" />
</div>

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

6. **Photos were analysed on their side, and OCR never read a word.** The shared decoder's docs
   promised EXIF rotation that no caller applied, so every photo stored with a rotation flag reached
   the models sideways; and PP-OCRv5's detector output (`[1, 1, H, W]`) was read as 3-D, so OCR failed
   on every image. A label audit (`tools/label-audit`) also found the detector giving one region
   several names (per-class suppression) and asserting whole-frame boxes for objects outside its 80
   classes. Photos are now decoded upright, suppression runs across classes, and doubtful detections
   are put to the user (see *Label questions*).

## Installing models

No model weights ship in the APK, so the **Models** screen (pop-up → *Models*) is what turns content
search on:

- **Quick setup.** One tap installs the essentials (≈255 MB: EmbeddingGemma 2 text search, OCR,
  labels, objects, faces); a second adds visual search and speech (≈1 GB total). The pop-up offers
  the same one-tap setup until the essentials are in.
- **Safe downloads.** Every file is fetched over HTTPS from a pinned upstream commit and refused unless
  its SHA-256 matches. Downloads check free space first, resume after a dropped connection, and run in
  a background queue with a progress notification, so leaving the screen cancels nothing.
- **Companions come along.** A model's tokenizer, projector or second tower installs with it and is
  only removed when nothing else needs it.
- **Takes effect immediately.** Newly installed models are picked up on the next item, without a
  restart, and parked work for them resumes.

Still missing:

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
- **Cheap before expensive.** Passes run in priority tiers: METADATA, OCR and FACES; KEYFRAMES;
  IMAGE_EMBED and CONCEPTS; OBJECTS (describing); TRANSCRIBE; TEXT_EMBED. The cheap ones share the
  first tier so items become fully text-searchable progressively instead of the
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
   every table. The schema uses FTS4 now, which has no `bm25()`; the keyword leg computes BM25 itself
   from FTS4's `matchinfo` statistics, with length normalization so a long transcript does not
   outrank a short caption by containing more words.
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
| EmbeddingGemma 2 (text, q4) + tokenizer | 174 MB + 32 MB | Multilingual search by meaning across captions, OCR, labels, transcripts | Apache-2.0 |
| Qwen3-ASR 0.6B Q8 + projector | 805 MB + 214 MB | Speech, 30 languages | Apache-2.0 |
| Qwen3-ASR 1.7B Q8 + projector | 2.17 GB + 356 MB | Speech, best accuracy | Apache-2.0 |
| Whisper small q5_1 | 190 MB | Speech, the fast path | MIT |
| Whisper large-v3-turbo q5_0 | 574 MB | Speech, best Whisper tier | MIT |
| CLIP ViT-B/16 image + text | 345 MB + 254 MB | Semantic image search and zero-shot labels | MIT |
| Florence-2 large (int8) | 832 MB | A caption and named objects with boxes, open vocabulary | MIT |
| Florence-2 base (int8) | 277 MB | The same at less detail, several times faster | MIT |
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
app            widget, search pop-up, review sheet, Models and Private folders screens, Hilt DI
engine-index   orchestrator, 11 pass handlers, WorkManager slices, model residency, resume/stop
engine-search  query parsing, spelling, BM25, per-item vector relevance, RRF rank fusion
ai-vision      ONNX CLIP towers, PP-OCR, YuNet faces, region rules, the describe-model interface
ai-speech      finder_asr JNI over whisper.cpp and llama.cpp mtmd, VAD gate, model manager
ai-text        EmbeddingGemma 2 embedder, Florence-2 describer, pure-Kotlin Hugging Face BPE tokenizer
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

## Search by meaning: EmbeddingGemma 2

Text search runs through Google DeepMind's **EmbeddingGemma 2** (the 270M text tower of the 740M
multimodal model) via ONNX Runtime. Each item's summary, labels, OCR and place become one
vector, and every minute of transcript gets its own; queries are embedded with the model's query
prompt and fused with keyword and CLIP results. Because the space is multilingual, a query in one
language matches labels, OCR and transcripts in another with no word in common.

- **Tokenizer:** a pure-Kotlin port of Hugging Face's BPE with byte fallback, verified token-for-token
  against `tokenizers` on a 151-case multilingual corpus (`HfBpeTokenizerTest`).
- **Weights:** 4-bit, 0.989 mean cosine to fp32 and 3.7x faster than 8-bit on the host.
- **Vectors:** Matryoshka-truncated to 256 dims (≈98.5% of full quality, a third of the scan).

## Private folders: encryption and recovery

Folders marked private are moved out of the gallery into app-private storage as encrypted `.fpv`
files, and stay searchable inside the app.

- **Authenticated encryption (FPV2).** AES-256-GCM in 64 KiB chunks with a per-file key: tampering,
  truncation or the wrong key fail loudly instead of decrypting to garbage, and video stays seekable.
  Files hidden by older versions (FPV1, AES-CTR) still open, and *Upgrade* converts them.
- **Nothing is deleted until it is proven.** A file is encrypted to a temp file, decrypted back in full
  and hash-matched before the original is removed. Restore never overwrites an existing file, and falls
  back to `Download/finder+ restored/` when the original folder cannot be written.
- **Self-describing.** Each file carries its original path inside the encryption, so *Restore all* also
  recovers files the index has lost track of. Index resets never touch hidden files.
- **Recovery keys that are proved.** The key file (PBKDF2-SHA256, 600k rounds) is checked to open the
  vault before it is called saved, can be tested any time, and is tracked per key, so replacing the key
  asks for a new one. A phone that lost its key shows the vault as *locked* and unlocks from the file;
  it never quietly creates a new key.
- **Crash-safe key rotation.** A new key becomes active only after a recovery file covering it has
  been saved and opened again; files are then re-encrypted one atomic rename at a time, and an old key
  is retired only when no file needs it. Every file opens under some key at every moment.

## Fair ranking between photos and videos

A video has a vector per keyframe, so judging it by its best keyframe gives it many chances to look
like any query: on real queries the best of ~10 keyframes scored about 0.8 standard deviations above a
single frame by chance alone, and the visual leg, which always returned its nearest 100 vectors, filled
results with unrelated videos. The visual leg now judges each item by its best vector minus the lift
that many vectors give by chance (measured on each query's own scores), and keeps only items that rise
above where the best of that many unrelated items would land by chance (sqrt(2 ln M) standard
deviations for M items). A video with one genuinely matching moment still ranks; a video that merely
has many frames no longer does. The text leg is judged the same way, since a long recording has a
vector per minute of transcript.

Speech and on-screen text are kept apart: text read off a video's keyframes is its OCR, each line
counted once, not part of its transcript. Mixed together, text that stayed on screen for many frames
was repeated in the transcript once per frame, and long videos matched words they barely showed.

## Captions and object names

Florence-2 writes a caption and finds objects in one read. Two rules keep the background out:

- **Only what the caption is about is named.** The caption's phrases are located in the image, and an
  object is kept only when its box matches one of them (IoU ≥ 0.5). Parts and background the caption
  does not mention drop out. With no located phrase, nothing is dropped.
- **The caption sentence has its own keyword index.** A sentence also names the background, so it is
  not part of the item's tags, summary or meaning vector. It is searched for queries of several words,
  which it can match whole, or when nothing else matched; only then does a partial match count. BM25
  orders its matches. The caption is shown under each result.

A video is described from its most typical keyframe, the one whose CLIP vector is closest to the mean
of all of them, and the frame's time is kept with its names and caption. A result found by a name or
the caption opens there, and in the review sheet a video's tile shows that frame; holding it plays the
video from there to the end.

## Label questions

Object names come from the describe model and can be wrong with high confidence, for example when an
object it has no word for fills the frame. Rather than patch individual labels, each one is checked
against evidence that does not depend on its name, and doubtful ones go to the review sheet:

- **Low confidence (optional, off by default):** the probability the model gave the name is below 0.40,
  or below the cut learned from the user's answers for that label. Always recorded; asked only when
  the user turns it on in the review sheet's Assist screen. These stay searchable while asked.
- **Models disagree:** CLIP, shown a crop of the box, ranks the name outside its top 3 among every
  object name used in the index.
- **Outlier from its label:** the photo's CLIP vector is unusually far from the other photos with the
  label (robust z-score below −2.5).
- **Your answers say no:** once a name has at least 5 yes and 5 no answers, a small model over the
  CLIP vectors of the detected regions (the user's answers on the 3 most similar regions, yes and no
  weighted equally) predicts the user's answer for every other detection with that name. It is used only when the answers prove separable: with each answer held out it
  gets yes and no right at least 75% of the time on average, and it beats all of 19 refits on
  shuffled answers (p ≤ 0.05). A predicted no holds the label for a
  vote; a confident yes (0.9 or more) drops the question. It is refitted whenever the answers change.
- **Whole frame:** the box covers at least 90% of the image.

Everything except low confidence is evidence against the label, so it is held out of search until answered. Answers are
stored apart from the tags: a re-index never asks again, a rejected label never comes back, and each
label's confidence cut is relearned from its answers. Questions with the most evidence against them
come first. `tools/label-audit` measures the effect of a change against a frozen baseline.

## Settings

Every setting is declared once, with its default, range and description, in `core-model`
(`SettingsCatalog`), and the Settings screen is generated from that list. A default is always the
behaviour the app had before the setting existed. Low-level controls sit behind an Advanced switch.
Where a number limits something, −1 turns the limit off; where it sizes something, −1 lets the app
decide.

- **Heat, processing, memory:** the heat limits (in every mode, Full speed included), threads, batch
  length, a battery floor, "only while charging", a free-memory floor, one model in memory at a time,
  and which describe model size to use. Unloading is real: the coordinator closes the describe, CLIP
  image and speech models, which only indexing uses.
- **Acceleration:** processor or NNAPI per model family, a processor-only switch for everything, the
  GPU for the speech and judge models, and drawing the screens without the GPU.
- **Opening and schedule:** which app plays or shows each kind of media, and automatic indexing on a
  timer, on new media and on charging, plus sweeping folders set to hide automatically as soon as new
  files appear.
- **What is read:** each pass can be switched off per media kind. What was already read stays; turning
  a pass back on fills in at the next index. Advanced: keyframes per video, sampling rate, duplicate
  frames, the describe frame, analysis size, speech language and window, the caption-grounding overlap.
- **Search and deep search:** each kind of match, its weight in the merge, BM25 k1 and b, when captions
  and meaning search run, fair ranking, typo correction and pick history. Deep search asks a
  vision-language model (on the phone, or the review assistant's service) about the top results with an
  editable question, and reorders them by its score.
- **Review and learning:** every doubt threshold, the answer model's test, and the reward each search
  action gives. Questions can be asked one name over many photos, or one photo with all its names to
  choose best first; the order is stored as group-relative rewards (`name_ranking`) for training.
- **Labels and models:** rules that rename, merge or hide labels, which label sources search uses, and
  the meaning vector size. Meaning vectors of different sizes still compare during re-embedding, since
  the model is Matryoshka-trained.
- **Folders and files:** folders to skip or to index first, file types to skip, and minimum image size
  and video or recording length. Skipped media leaves the index at the next scan; preferred folders
  go first within each step of indexing.
- **Test on this phone:** each model that can use NNAPI runs on a few of the user's photos and texts on
  both the processor and the accelerator, and keeps the accelerator only where it ran the whole model
  (no fallback), gave the same output (cosine ≥ 0.99) and was at least 10% faster. Nothing is assumed
  from the chip, so it behaves the same on any phone.
- **Diagnostics:** time per item for each pass (median and 90th percentile), model load times, every
  pause with the battery, heat and memory readings at that moment, how each batch ended, and a log
  export with the app's own log.
- **Data:** back up and restore the user's own work (labels, answers, rankings, votes, rules, settings,
  history, private-folder rules; never vault keys), export the whole index as one SQLite file, export
  rankings, answers and votes as JSON Lines for training, run any pass again, reset the settings, or
  reset the index (typed confirmation).

Votes and label edits made in the search pop-up are held, and shown as made, until **Save changes**
or the next index run applies them; nothing about ranking or labels changes before that, and nothing
is shown for them after. Copying, opening and previewing still count as picks at once.

## Free up space

*Clean up* in the pop-up (or long-press the app icon → *Free up space*) shows where storage goes and
what can safely go:

- **Duplicates:** byte-for-byte identical copies. Files are grouped by size, then by a hash of their
  first and last 64 KiB, and only then by a full SHA-256, so most file contents are never read;
  fingerprints are cached, so a second visit is instant. One copy of each set is kept —
  the camera original, else the copy outside download/messenger folders, else the oldest — and the
  extra copies come preselected.
- **Look-alikes:** the same photo resized, re-sent or shot in a burst, found from the CLIP vectors the
  index already holds (locality-sensitive hashing, then exact cosine ≥ 0.96). Never preselected; the
  sharpest copy is marked to keep.
- **Largest:** every photo, video and audio file by size.

Selection never takes the last copy of anything, and removal goes through Android's own trash
confirmation: files can be restored for 30 days.

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
