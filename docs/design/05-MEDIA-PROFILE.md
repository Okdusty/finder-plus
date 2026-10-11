# 05 · The AI-Revision Media Profile + Tap-to-Clipboard

This formalizes the refined concept: **finder+ is an AI-assisted gallery finder.** Every media file is
"revised" by the on-device models into one long, high-precision **searchable text profile**. Those
words are what you type to navigate straight to the specific photo, video moment, or recording — and a
single **tap copies that content to the clipboard**.

## 1. The media profile ("AI revision")

For each item, the engine consolidates the output of every pass into one text document:

```
IMG_2231.jpg
Tags: dog, sofa, living room, pet, indoor, screenshot?=no, category:animals
Text: HAPPY BIRTHDAY MAYA            ← OCR
Transcript:                          ← (A/V only) full multilingual transcript
Location: Kadıköy, İstanbul
Album: Camera
```

- Stored in **`media_profile(item_id, text, updated_at)`** (1:1 with the item, cascade-deleted).
- Assembled by `ProfileBuilder.assemble(...)` in `engine-index`, rebuilt inside the same transaction
  that completes any text-producing pass — so it is always consistent with committed results and
  inherits the engine's crash-safety.
- It is the human/AI-readable description of what the media *contains* — the unit of "advanced local
  SEO." Empty sections are omitted, so a freshly-labelled photo already has a useful profile before its
  transcript (if any) exists (PARTIAL-state searchability).

### Relationship to FTS
The **FTS5 table stays the ranking index** (weighted columns: name > tags > OCR ≈ transcript > place >
album, with BM25 + snippets). The profile is the **consolidated, displayable/uncopyable-loss text** —
the same signals in one blob — used for: the result snippet ("why this matched"), the clipboard
payload, and a substring/`LIKE` fallback. Both are rebuilt together in `ItemFinalizer.rebuildSearch()`.

## 2. Navigation precision

A query resolves to a *location inside* content, not just a file:

- **Images** → the file, with the matched OCR fragment highlighted.
- **Video/Audio** → the file **plus the timestamp** of the matching transcript segment
  (`segment.start_ms`), so "the voicemail about the rent" jumps to the moment it's said, and
  "Open in gallery" seeks there.
- Vector legs (CLIP image / transcript embeddings) add semantic recall on top of exact keywords.

## 3. Tap-to-clipboard — the **actual media**, never its metadata

Tapping a result copies **the media itself** to the system clipboard, so pasting into a chat, editor or
document yields the picture (not a description of it).

How: the original bytes are copied verbatim into `cache/clip/` and handed out through a
**FileProvider** (`${applicationId}.clips`), with the clip's MIME set from the item
(`image/jpeg`, …). Copying rather than re-encoding preserves the original pixels byte-for-byte, and the
provider means the pasting app needs no media permission.

```kotlin
ClipData(ClipDescription(name, arrayOf(mime)), ClipData.Item(fileProviderUri))
```

Extracted text is deliberately **not** on the clipboard — a metadata blob is useless when you meant to
paste a photo. Text is available explicitly via `ClipboardWriter.copyText(...)` (long-press → Copy
text). Long-press also opens in the gallery, seeking to the matched A/V moment.

*Verified on-device:* tapping a 2.4 MB result staged `cache/clip/20241124_144016.jpg` at exactly
2,399,871 bytes with JPEG magic `ff d8 ff`, and Android showed its own "Copied to clipboard"
confirmation — i.e. real image content, not text.

## 4. Data-flow summary

```
passes → tag / document / segment rows ──┐
                                         ├─► ItemFinalizer.rebuildSearch(itemId)  (one txn)
                                         │      ├─ FTS5 row      (ranked keyword search)
                                         │      └─ media_profile (consolidated AI-revision text)
query ─► hybrid search (FTS + vectors) ──┴─► SearchResult{ hits } ─► tap ─► clipboard (the media)
```

The profile is the through-line: it's what the models write, what search reads, and what the clipboard
copies — the whole point of finder+ as an AI-revision gallery finder.
