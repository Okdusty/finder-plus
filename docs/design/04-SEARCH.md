# 04 · Hybrid Search ("internal SEO")

Search runs entirely against the local DB and models. It fuses **FTS5 keyword** ranking with **vector semantic** ranking so a file is findable both by the exact words in it (OCR, transcript, filename, place) and by a description of its content ("dog jumping into a lake") even when no tag says so.

## 1. Pipeline

```
raw query ─► QueryParser ─┬─► structured filters (kind/date/place)
                          └─► free text
                                 │
              ┌──────────────────┼─────────────────────┐
              ▼                  ▼                     ▼
        FTS5 BM25          CLIP text tower        Text embedder
        (keyword)          → cosine vs            → cosine vs
                             IMAGE_CLIP             TEXT_TRANSCRIPT
              └──────────────────┼─────────────────────┘
                                 ▼
                      RankFuser (RRF + boosts)
                                 ▼
                     grouped, confidence-scored SearchResults
```

## 2. Query parsing

```kotlin
interface QueryParser { fun parse(raw: String): SearchQuery }
```

Extracts, then strips, structured tokens; the remainder is free text:
- `type:video` / `type:photo` / `type:audio` → `kinds`
- date phrases: `today`, `last summer`, `2023`, `july` → `after`/`before` (offline resolver)
- quoted `"exact phrase"` → forces an FTS phrase match + boost
- place names matched against known `place` values → `place`
- everything else → `text`

## 3. FTS5 keyword leg

```sql
SELECT item_id,
       bm25(media_fts, 8.0, 4.0, 3.0, 3.0, 2.0, 1.0) AS rank,  -- weight name>tags>ocr≈transcript>place>bucket
       snippet(media_fts, 2, '[', ']', '…', 10) AS ocr_snip,
       snippet(media_fts, 3, '[', ']', '…', 10) AS tr_snip
  FROM media_fts
 WHERE media_fts MATCH :ftsQuery
 ORDER BY rank
 LIMIT 200;
```

- Tokenizer `unicode61 remove_diacritics 2` handles most scripts; a `trigram` companion column is the fallback for CJK/substring matching.
- `:ftsQuery` is built from `text` (prefix-expanded per token: `beach*`) plus any quoted phrases.
- `snippet()` yields the highlighted matched fragment → `SearchHit.snippet`.

## 4. Vector semantic leg

Two cosine searches against L2-normalized `embedding` blobs:
- query → **CLIP text tower** → cosine vs `IMAGE_CLIP` (finds visually-matching photos/frames with no tag).
- query → **text embedder** → cosine vs `TEXT_TRANSCRIPT` (semantic transcript match, cross-language).

```kotlin
interface VectorStore {
  suspend fun search(kind: EmbeddingKind, query: FloatArray, k: Int,
                     filterItemIds: LongArray? = null): List<VectorHit>
}
data class VectorHit(val itemId: Long, val sourceRef: Int, val score: Float)  // score = cosine
```

- **Now**: brute-force cosine over `float32` blobs with a SIMD-friendly loop; fine to ~50k vectors. Optionally pre-filter by the FTS candidate set or by date/kind to shrink the scan.
- **Upgrade path**: `sqlite-vec` extension or an on-disk HNSW index behind the same `VectorStore` interface — no caller change.
- A/V vector hits carry `source_ref` → mapped back to a `segment.start_ms` so the result shows the **timestamp** of the match.

## 5. Fusion & confidence

```kotlin
interface SearchEngine { fun search(raw: String): Flow<List<SearchResult>> }  // streams as legs resolve
interface RankFuser {
  fun fuse(fts: List<Scored>, clip: List<Scored>, transcript: List<Scored>, q: SearchQuery): List<SearchResult>
}
```

- **Reciprocal Rank Fusion**: `score(item) = Σ_leg w_leg / (k + rank_leg(item))`, `k≈60`. Robust to the legs' different score scales.
- **Boosts**: exact-phrase FTS hit ×1.5; recency decay on `date_taken`; kind match from parser.
- **Confidence** (0–1, shown as a chip/color): normalized fused score, lifted when ≥2 legs agree on the same item.
- **Grouping**: results grouped by `MediaKind`; within a group, ranked; A/V items annotated with their best `SearchHit` timestamp.
- **Streaming**: FTS returns in ~ms → first results paint immediately; vector legs stream in and re-rank (debounced), matching the as-you-type pop-up.

## 6. Result contract (from core-model)

```kotlin
data class SearchResult(
  val item: MediaItem, val score: Float, val confidence: Float,
  val hits: List<SearchHit>,        // matched snippets + A/V timestamps
  val thumbnailUri: String,
)
```

Tapping a result fires `ACTION_VIEW` into the system gallery/player (with a seek extra for A/V timestamp hits where the player supports it).
