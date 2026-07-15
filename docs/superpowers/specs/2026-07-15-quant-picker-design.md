# Quant Picker for Model Download — Design

**Date:** 2026-07-15
**Status:** Approved (brainstorming)

## Problem

The Hugging Face model search resolves **exactly one GGUF file per repo**. `HfModels.parseCandidate`
fetches the full repo tree, filters out companions (mmproj/draft), then picks a single "best" file by a
fixed quant ranking (`fileRank`). Two consequences:

1. Low-bit quants aren't recognized. `Q1_0` (and the `IQ1_*`/`IQ2_*`/… family) fall through to rank 10,
   so for `prism-ml/Bonsai-27B-gguf` the 53.8 GB `F16` (rank 6) wins over the 3.8 GB `Q1_0` — the only
   variant that fits an 8 GB phone.
2. Even if ranked, there is **no UI to reach a different quant**: one file per repo, one Get button.

The `Bonsai-27B` repo illustrates the shape we must handle:

| File | Size | Role |
|------|------|------|
| `Bonsai-27B-F16.gguf` | 53.8 GB | primary |
| `Bonsai-27B-Q1_0.gguf` | 3.8 GB | primary (fits) |
| `Bonsai-27B-dspark-Q4_1.gguf` | 1.8 GB | draft companion |
| `Bonsai-27B-dspark-bf16.gguf` | 7.3 GB | draft companion |
| `Bonsai-27B-mmproj-BF16.gguf` | 0.9 GB | vision companion |
| `Bonsai-27B-mmproj-Q8_0.gguf` | 0.6 GB | vision companion |

## Goals

- Surface **every primary quant** in a repo and let the user pick which to download.
- Auto-select a sensible **headline quant** (the largest that fits) so the common case needs no expansion.
- Recognize low-bit quant labels so they no longer display as "GGUF".
- Let the user download **vision (mmproj) and draft companions**, auto-wired so vision/speculative works.

## Non-Goals

- No change to the local "On this phone" list — installed files stay one row each.
- No repo-wide reference counting for companion deletion (see *Deletion* below).
- No recalibration of the RAM-fit estimate for arbitrary draft sizes (companions show size only, no fit
  badge).
- No manual companion-wiring UI beyond the Get buttons.

## Architecture

The change reuses the existing single tree fetch per repo — listing all quants costs **zero** extra
network calls. Parsing stays pure (`HfModels`, no RAM knowledge); fit/headline selection stays in the
ViewModel (which owns free-RAM and settings); the UI gains an expand toggle.

### 1. Parsing — `HfModels.kt`

`parseCandidate(repo, treeJson)` returns all three buckets instead of one file:

```kotlin
data class HfRepoResult(
    val repo: String,
    val name: String,                    // repo-derived display name
    val quants: List<QuantFile>,         // primary GGUFs, sorted SMALLEST → LARGEST
    val companions: List<CompanionFile>, // mmproj + draft, sorted smallest → largest
)
data class QuantFile(val file: String, val quant: String, val sizeGB: Float)
enum class CompanionKind { VISION, DRAFT }
data class CompanionFile(val file: String, val kind: CompanionKind, val quant: String, val sizeGB: Float)
```

Classification from filename (reuse `isCompanionFile`):
- `mmproj` in name → `VISION`
- `draft` or `mtp` in name → `DRAFT`
- otherwise → primary quant

`HfModels.search()` returns `List<HfRepoResult>` (one per repo). A repo with a single GGUF yields a
one-element `quants` list and empty `companions`. Repos with **zero** primary quants are dropped (as
today). `fileRank` is removed — ordering is by size, not quant quality.

### 2. Quant label recognition — `quantFrom`

Extend the recognized token list (longest-match-first so `Q4_K_M` beats `Q4`):

```
F32, F16, BF16, FP16,
Q8_0, Q6_K,
Q5_K_M, Q5_K_S, Q5_1, Q5_0,
Q4_K_M, Q4_K_S, Q4_1, Q4_0, IQ4_NL, IQ4_XS,
Q3_K_L, Q3_K_M, Q3_K_S, IQ3_M, IQ3_S, IQ3_XS, IQ3_XXS,
Q2_K_S, Q2_K, IQ2_M, IQ2_S, IQ2_XS, IQ2_XXS,
IQ1_M, IQ1_S, Q1_0
```

Unrecognized → "GGUF" (unchanged fallback). Label only; it does not drive selection.

### 3. ViewModel — headline + per-quant fit (`MonitorViewModel.updateHfQuery`)

For each `HfRepoResult`, compute `estimateModelFit` **per quant** using current free-RAM + settings
(exactly as the single-candidate path does today). Then:

- **Headline quant** = the **largest** quant whose fit level is `FITS` or `TIGHT`; if none qualify, the
  **smallest** quant. The headline supplies the card's `file`/`quant`/`sizeGB`/`fit`.
- The `ModelEntry` carries the full quant list and companion list for the expanded view.

`ModelEntry` gains:

```kotlin
val quants: List<QuantOption> = emptyList(),      // empty for local "On this phone" rows
val companions: List<CompanionOption> = emptyList(),
```

```kotlin
data class QuantOption(val file: String, val quant: String, val sizeGB: Float, val fit: ModelFit)
data class CompanionOption(val file: String, val kind: CompanionKind, val quant: String, val sizeGB: Float)
```

Quant rows in the entry are sorted **smallest → largest** (most-likely-to-fit first); the headline is
independently the largest-that-fits.

### 4. Companion persistence & wiring

**Persistence.** Extend the existing `library: Map<String,String>` (file → repo) to **also record
companion files → repo** when downloaded. No new config field, no serialization change — `[library]`
already round-trips arbitrary `file = repo` entries. A primary's companions are *derived by repo*.

**`switchedConfig(cfg, file)`** (in `ModelLibrary.kt`) is extended for the non-default branch: after
`repo = cfg.library[file]`, derive companions from the same repo when `repo` is non-blank —

```kotlin
val siblings = cfg.library.filterValues { it == repo }.keys
val draft  = siblings.firstOrNull { it != file && ("draft" in it.lowercase() || "mtp" in it.lowercase()) } ?: ""
val mmproj = siblings.firstOrNull { it != file && "mmproj" in it.lowercase() } ?: ""
cfg.copy(repo = repo, modelFile = file, draftFile = draft, mmprojFile = mmproj)
```

Then (unchanged) `useDraft = draftFile.isNotBlank()`, `useMmproj = mmprojFile.isNotBlank()`. The
curated-default branch is untouched. If a repo has two vision quants downloaded, `firstOrNull` picks one.

**Downloading a companion** (`MonitorViewModel`, new `downloadCompanion(repo, file, kind)`):
1. Download the file.
2. Record `file → repo` in `library`.
3. If the file's repo equals the **currently active** model's repo, set `config.mmprojFile`/`draftFile`
   + `useMmproj`/`useDraft` so the next server start uses it, and log
   `"restart to apply <vision|draft>"`. **No auto-restart** (less surprising than a model switch).

Downloading a primary quant (`downloadModel`) is unchanged in spirit: it writes repo + that file, with
`draftFile`/`mmprojFile` derived from any companions already in `library` for that repo (so a companion
downloaded earlier auto-wires when the primary lands).

### 5. UI — expandable card (`SubPanels.kt`)

- **Collapsed:** the headline quant — name, `repo · quant · size`, fit badge, Get (downloads the headline
  file).
- **Toggle:** `▸ N quants` when the repo has >1 quant *or* any companions. Local card state
  (`remember { mutableStateOf(false) }`, mirroring the existing delete-confirm flag). Hidden for a
  single-quant, companion-less repo.
- **Expanded quant rows** (smallest → largest): `quant · size · fit-badge · Get`. Each Get downloads that
  file.
- **Companions** (only if present): a `─ Companions ─` divider, then one row per companion —
  `👁 vision · Q8_0 · 0.6 GB · Get` / `⚡ draft · Q4_1 · 1.8 GB · Get`. **Size only, no fit badge.** Each
  Get calls `downloadCompanion`.

Download progress keys on the file, so the correct row shows its percentage.

### 6. Download-by-file wiring

`MenuCallbacks.onDownloadModel` changes from `(id: String)` to `(repo: String, file: String)` and a new
`onDownloadCompanion: (repo: String, file: String, kind: CompanionKind)` is added. `ModelCard`'s headline
and per-row Get buttons pass the specific file. `downloadModel(repo, file)` looks the entry up by
repo+file rather than by `id`.

## Deletion (known limitation)

Deleting a primary deletes only itself; shared companions (a repo's mmproj may pair with another
downloaded quant) are **not** cascade-deleted. `companionsOf` stays as-is (curated default only).
Orphaned companion files remain if a user deletes every quant of a repo — a minor storage cost, accepted
for this version. Companion files are excluded from the "On this phone" primary list, so they are not
individually deletable in the UI (unchanged).

## Testing (TDD)

Pure-logic unit tests (host JVM):

- **`parseCandidate`** on a Bonsai tree fixture → `quants` = [`Q1_0`, `F16`] sorted smallest→largest;
  `companions` = the two `mmproj` (VISION) + two `dspark` (DRAFT); no companion leaks into `quants`.
- **`parseCandidate`** on a single-GGUF repo → one quant, empty companions.
- **`quantFrom`** recognizes `Q1_0`, `IQ2_XXS`, `IQ1_S`, `Q2_K_S`; still returns `Q4_K_M` for a
  `…-Q4_K_M.gguf` name; `GGUF` for an unrecognized name.
- **Headline selection** (pure helper, e.g. `pickHeadline(quants, fits)`): picks the largest with
  `FITS`/`TIGHT`; falls back to the smallest when all are `TOO_LARGE`.
- **`switchedConfig`** derives draft + mmproj by repo from `library` for an arbitrary primary; clears them
  when the repo has no companions; curated-default path unchanged.

On-device verification: search "Bonsai" → headline row is `Q1_0 [FITS]`; expand → `F16` and the
`vision`/`draft` companion rows appear; Get on `Q1_0` downloads it; Get on a `vision` companion downloads
it and (when Bonsai is active) wires `mmproj` into config.

## Files Touched

- `server/HfModels.kt` — new result types, return all buckets, drop `fileRank`, extend `quantFrom`.
- `server/ModelLibrary.kt` — `switchedConfig` derives companions by repo.
- `model/MonitorState.kt` — `ModelEntry.quants`/`companions`, `QuantOption`/`CompanionOption`; expose
  `CompanionKind` (or import from server).
- `MonitorViewModel.kt` — per-quant fit, headline selection, `downloadModel(repo, file)`,
  `downloadCompanion(...)`, companion-aware primary download.
- `ui/menu/MenuCallbacks.kt` — signature changes / new callback.
- `ui/menu/SubPanels.kt` — expandable `ModelCard` with quant + companion rows.
- `ui/MonitorScreen.kt` — wire the new/changed callbacks.
- Tests under `app/src/test/...` for the pure logic above.
