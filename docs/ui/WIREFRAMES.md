# finder+ · UI Wireframes

**Scope:** design only — this phase stops here. No Glance/Compose implementation. The build stops at the `UiState`/`ViewModel` contracts in `UI-CONTRACTS.md`, which these wireframes are drawn against.

**Surfaces (the only three):**
1. Home-screen **Widget** (Glance) — search entry + update button + live status.
2. **Search pop-up** (translucent dialog Activity) — query box + streaming results.
3. **Settings / onboarding** sheet (dialog-themed) — permissions, models, prefs, stats.

There is deliberately **no full app frame**. A result tap leaves the app entirely via `ACTION_VIEW` into the system gallery/player.

Legend: `[ ]` button · `(•)` icon · `▓` filled progress · `░` track · `«field»` dynamic value.

---

## 1. Widget

### 1.1 Size 4×1 — idle (default)
```
┌──────────────────────────────────────────────────────┐
│ (🔍)  Search your gallery…                    [ ⟳ ]   │
│ ─────────────────────────────────────────────────────│
│ (•) «8,900» indexed · updated «2h ago»                │
└──────────────────────────────────────────────────────┘
   ▲ tap anywhere on field → opens Search pop-up
                                          ▲ update button → enqueues incremental index
```

### 1.2 Size 4×1 — indexing in progress
```
┌──────────────────────────────────────────────────────┐
│ (🔍)  Search your gallery…                    [ ⏸ ]   │
│ ─────────────────────────────────────────────────────│
│ Indexing «1,240»/«8,900»  ▓▓▓▓▓▓░░░░░░░  «14%»  transcribing │
└──────────────────────────────────────────────────────┘
   the [⟳] becomes [⏸] (Stop) during a run · tapping it = cooperative stop
   status line binds to IndexProgress: «done»/«total», bar, currentPass label
```

### 1.3 Size 4×2 — expanded (with recent searches)
```
┌──────────────────────────────────────────────────────┐
│ (🔍)  Search your gallery…                    [ ⟳ ]   │
│ ─────────────────────────────────────────────────────│
│ (•) «8,900» indexed · «312» videos · «540» audio       │
│                                                        │
│ Recent:  [ beach ] [ invoice ] [ voicemail rent ]     │
│          [ dog ]   [ passport ]                        │
└──────────────────────────────────────────────────────┘
   recent chips → open pop-up pre-filled with that query
```

### 1.4 Size 4×1 — permission lost / partial access
```
┌──────────────────────────────────────────────────────┐
│ (⚠)  Media access needed to index            [ Fix ] │
│ ─────────────────────────────────────────────────────│
│ Partial access · only «120» photos visible            │
└──────────────────────────────────────────────────────┘
   [Fix] → Settings sheet → re-request permission / "Select more photos"
```

**Widget states** map 1:1 to `WidgetState` (`Idle`, `Indexing`, `Paused`, `NeedsPermission`, `PartialAccess`). The update/stop button reflects `RunStatus` (`⟳` when idle/done, `⏸` when running, `▶` when paused).

---

## 2. Search pop-up  (translucent dialog over the launcher/wallpaper)

### 2.1 Empty / focused (keyboard up)
```
        ╔══════════════════════════════════════════════╗   ← translucent scrim (wallpaper blurred behind)
        ║ (🔍) │ «cursor»                          (✕) ║   ← auto-focused field, ✕ dismiss
        ╟──────────────────────────────────────────────╢
        ║  Try:                                         ║
        ║   • "kids at the beach"                       ║
        ║   • "invoice from Vodafone"                   ║
        ║   • the voicemail about the rent              ║
        ║   • type:video last summer                    ║
        ║                                               ║
        ║  Recent:  [beach] [invoice] [dog] [passport]  ║
        ╚══════════════════════════════════════════════╝
        outside tap / back → dismiss · cold-open target < 400 ms
```

### 2.1b Live index status + category filters (implemented)

```
        ╔══════════════════════════════════════════════╗
        ║ (🔍) │ dog                                    ║
        ╟──────────────────────────────────────────────╢
        ║ 4,926 indexed · 🖼 3,578 · ▶ 1,301 · ♪ 47      ║ ← idle: what's searchable
        ║ Indexing 1,766 / 19,657 · 8% · reading text   ║ ← running: live count + phase
        ║ ▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ ║ ← determinate bar
        ║ Paused 34% · cooling down, resumes soon       ║ ← thermal/battery pause, stated plainly
        ╟──────────────────────────────────────────────╢
        ║ ( All ) ( 🖼 Photos ) ( ▶ Videos ) ( ♪ Audio ) ║ ← category filter chips
        ╚══════════════════════════════════════════════╝
```

Chips filter the result set by media kind (the active chip is accent-tinted); the status line always
explains what is not yet indexed, so an empty result is never ambiguous.

### 2.2 As-you-type — results streaming
```
        ╔══════════════════════════════════════════════╗
        ║ (🔍) │ dog on the sofa                    (✕) ║
        ╟──────────────────────────────────────────────╢
        ║  Photos  «7»                                  ║
        ║  ┌────────┐┌────────┐┌────────┐┌────────┐     ║
        ║  │▓▓▓▓▓▓▓▓││▓▓▓▓▓▓▓▓││▓▓▓▓▓▓▓▓││▓▓▓▓▓▓▓▓│     ║
        ║  │  IMG   ││  IMG   ││  IMG   ││  IMG   │     ║
        ║  │ ●92%   ││ ●88%   ││ ●71%   ││ ●63%   │     ║  ● = confidence dot (color scale)
        ║  └────────┘└────────┘└────────┘└────────┘     ║
        ║  Videos  «2»                                  ║
        ║  ┌───────────────────────┐┌───────────────┐   ║
        ║  │ ▶  clip_0421.mp4       ││ ▶ party.mp4   │   ║
        ║  │ ●81% · match @ 0:37    ││ ●59% @ 1:12   │   ║  ← A/V hit shows timestamp
        ║  │ "…the dog jumped on…"  ││               │   ║  ← transcript/OCR snippet
        ║  └───────────────────────┘└───────────────┘   ║
        ║  (streaming ⟳ vector results…)                ║  ← FTS paints first, vector legs stream in
        ╚══════════════════════════════════════════════╝
```

### 2.3 Result item anatomy
```
┌───────────────────────────────┐
│ ┌───────┐  clip_0421.mp4       │  ← display name (or date for photos)
│ │ thumb │  (▶) 0:42 · 12 MB    │  ← kind badge · duration/size
│ │ ●81%  │  match @ 0:37        │  ← confidence · A/V timestamp of the hit
│ └───────┘  "…the dog jumped…"  │  ← highlighted snippet from the AI-revision profile
└───────────────────────────────┘
  tap        → COPY TO CLIPBOARD  ➜ toast "Copied clip_0421.mp4"   (the small UI feature)
               one clip carries BOTH: the media file (URI) AND its extracted content text,
               so pasting into a chat gives the file, pasting into a text box gives the content.
  long-press → [ Open in gallery ] [ Copy text ] [ Copy file ] [ Share ] [ Show tags ] [ Location ]
```

> **Tap = copy** is the default (refined concept: results output directly to the clipboard). It's
> configurable in Settings (`Tap action: Copy ▸ / Open`), and "Open in gallery" (ACTION_VIEW, seek to
> the A/V timestamp) is always available on long-press.

### 2.4 No results
```
        ╔══════════════════════════════════════════════╗
        ║ (🔍) │ zxqwv                             (✕) ║
        ╟──────────────────────────────────────────────╢
        ║           (•)  No matches                     ║
        ║   Nothing indexed matches "zxqwv".            ║
        ║   «312» videos still awaiting transcription — ║  ← honest partial-index note
        ║   run update to finish.        [ Update now ] ║
        ╚══════════════════════════════════════════════╝
```

### 2.5 Search states → `SearchUiState`
`Empty(suggestions, recent)` · `Loading(partialResults)` · `Results(groups, streaming)` · `NoResults(reason)` · `NeedsIndex(pending)`.

---

## 3. Settings / onboarding sheet  (dialog-themed, minimal)

### 3.1 First-run onboarding (permission gate)
```
        ╔══════════════════════════════════════════════╗
        ║  finder+                                      ║
        ║  Find anything in your gallery — offline.     ║
        ║                                               ║
        ║  (1) Allow access to your media   [ Allow ]   ║  ← READ_MEDIA_* request
        ║  (2) Notifications for progress   [ Allow ]   ║  ← POST_NOTIFICATIONS
        ║  (3) Choose speech model          [ Base ▾ ]  ║  ← tiny/base/small selector
        ║                                               ║
        ║                       [ Build index ]         ║  ← enabled once (1) granted
        ║  Everything stays on your phone. No cloud.    ║
        ╚══════════════════════════════════════════════╝
```

### 3.2 Settings (post-onboarding)
```
        ╔══════════════════════════════════════════════╗
        ║  Settings                               (✕)   ║
        ╟──────────────────────────────────────────────╢
        ║  INDEX                                        ║
        ║   «8,900» files · «312» videos · «540» audio  ║
        ║   «41» failed             [ View ]            ║
        ║   [ Update now ]   [ Rebuild index ]          ║
        ║                                               ║
        ║  MODELS                          «footprint»  ║
        ║   Speech   ( ) tiny 75MB  (•) base 140MB      ║
        ║                          ( ) small 460MB      ║
        ║            «base installed»     [ Download ]  ║
        ║   Vision (CLIP)  «installed» 118MB  [ Delete ]║
        ║                                               ║
        ║  INDEXING                                     ║
        ║   Transcribe only while charging      [ ✓ ]   ║
        ║   Keyframes per video      [ 20 ▾ ]           ║
        ║   Index over cellular                 [   ]   ║  ← model download only
        ║                                               ║
        ║  RESULTS                                      ║
        ║   Tap action     (•) Copy to clipboard        ║  ← default
        ║                  ( ) Open in gallery          ║
        ║                                               ║
        ║  PRIVACY                                      ║
        ║   Encrypt index at rest               [   ]   ║
        ║   [ Wipe all data ]                           ║
        ╚══════════════════════════════════════════════╝
```

### 3.3 Model download (in progress)
```
        ║   Speech · base                               ║
        ║   Downloading  ▓▓▓▓▓▓▓▓▓░░░░  «68%» · 95/140MB ║
        ║   [ Cancel ]           verifying SHA-256…     ║
```

---

## 4. Update / indexing flow states

```
  IDLE ──[⟳ update]──► SCANNING ──diff──► RUNNING ──(cheap passes done)──► PARTIAL(searchable)
   ▲                      │                  │                                   │
   │                      │                  ├─[⏸ stop]──► STOPPED ──[⟳]──┐       │(heavy passes)
   │                      │                  │                            │       ▼
   └───────── DONE ◄──────┴──────────────────┴────────── resume ◄─────────┴──── all passes done
```

- **Where progress shows:** widget status line (§1.2) **and** a foreground-service notification (`Indexing «1,240»/«8,900» · «14%»` with `[Stop]` action). Both bind to the single `IndexProgress` flow.
- **Stop** is cooperative (see DB doc §7): the button flips instantly, the run persists its checkpoint, status → `STOPPED`. Next update resumes with no lost work.
- **Partial searchability:** as soon as cheap passes finish, results appear even while transcription continues; the pop-up footnotes how many items are still pending (§2.4).

---

## 5. Component & state inventory

| Surface | Component | Bound state | Dynamic fields |
|---|---|---|---|
| Widget | search field | `WidgetState` | placeholder |
| Widget | update/stop button | `RunStatus` | icon `⟳/⏸/▶` |
| Widget | status line | `IndexProgress` | done/total, %, currentPass, "updated Xago" |
| Widget | recent chips (4×2) | recent queries | labels |
| Pop-up | query field | `SearchUiState` | text, cursor |
| Pop-up | result grid | `Results.groups` | thumbnail, kind badge, confidence, snippet, timestamp |
| Pop-up | result tap | `SearchEffect.CopyToClipboard` | copies file URI + content text; toast confirm |
| Settings | tap-action toggle | `IndexPrefs.tapAction` | Copy (default) / Open |
| Pop-up | streaming spinner | `Results.streaming` | visible while vector legs resolve |
| Pop-up | empty/suggestions | `Empty` | suggestions, recent |
| Settings | index stats | `SettingsUiState.index` | counts, failed |
| Settings | model list | `SettingsUiState.models` | installed, size, download % |
| Settings | prefs toggles | `SettingsUiState.prefs` | charge-only, keyframe density, encrypt |

---

## 6. Visual design tokens

- **Translucency:** pop-up and settings float over a blurred/dimmed scrim (`Theme.Translucent`, ~72% dim, 24 dp corner radius, elevation 8). Never a fullscreen opaque frame.
- **Theme:** follow system light/dark. Surfaces use Material 3 dynamic color where available.
- **Confidence color scale** (the `●` dot / chip): `≥0.85` green · `0.65–0.85` amber · `<0.65` grey. Also shown as a numeric `%`.
- **Kind badges:** photo (no badge), video `▶`, audio `♪`.
- **Progress:** determinate bar for indexing and downloads; the widget bar is a thin 2 dp track.
- **Density:** result grid = 2 columns for A/V (wide cards with snippet), 4 columns for photos (thumbnail + confidence dot).
- **Motion:** results fade/slide in as legs stream; the update button's icon cross-fades on `RunStatus` change.

These wireframes plus `UI-CONTRACTS.md` are a complete, buildable UI specification — the next phase can implement Glance + Compose directly against them.
