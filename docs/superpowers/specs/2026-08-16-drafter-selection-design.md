# Drafter Selection, Drafter Management & Chat Reload — Design

**Date:** 2026-08-16
**Status:** Approved (brainstorming)

## Problem

Three related gaps in on-device model + chat management:

1. **No way to pick a drafter for a model.** After the dspark crash fix, `switchedConfig` sets
   `draftFile = ""` for every non-curated model — only the hardcoded curated gemma default gets its MTP
   draft. But a single drafter is reusable across *versions of the same base model* (the
   `mtp-gemma-4-E2B-it.gguf` MTP draft pairs with the curated gemma, the Hi-Fi quant, and SmartEdge —
   all `gemma-4-E2B` derivatives). Today, using a drafter with an arbitrary model requires hand-editing
   `config.ini`.
2. **Drafters are invisible and undeletable.** `primaryModels` filters companions out of the
   "On this phone" list and `deleteModel` only removes primary models (+ curated companions), so draft
   files can neither be seen nor deleted in the UI — they silently consume storage.
3. **Chat gets stuck after a server restart.** `ChatWebViewHolder.obtain` only reloads when the *URL*
   changes. A restart (model switch, Stop/Start) keeps the same `127.0.0.1:<port>` URL, so the retained
   WebView holds its stale page/connection and won't send new requests to the fresh backend.

## Goals

- Choose a drafter (from all draft files on disk) for any installed model; remember it per model.
- See every drafter on disk with its size, and delete drafters to reclaim space.
- The Chat WebView reloads automatically when the server returns to RUNNING, plus a manual reload button.

## Non-Goals

- No GGUF-metadata / architecture compatibility check — the user picks from all drafters on disk and owns
  compatibility (a mismatched drafter fails to load; a warning states this).
- No change to `spec_type`: a chosen drafter always uses `draft-mtp` (what the on-device gemma MTP
  drafters need).
- No auto-download of drafters here (that is the existing companion-download path); this feature is about
  selecting, managing, and deleting drafters already on disk.
- No repo-based auto-derivation of drafters (the dspark crash showed why arbitrary auto-wiring is unsafe);
  drafter wiring is now an explicit, remembered per-model choice.

## Design

### 1. Data model & persistence

Add a per-model drafter map to `LlamaConfig`, serialized like the existing `[library]`:

```kotlin
// LlamaConfig.kt — new field (last param, default empty)
val drafters: Map<String, String> = emptyMap(),  // modelFile -> draftFile ("" = explicit none)
```

`IniStore.toIni` writes a `[drafters]` section (`LinkedHashMap(drafters)`); `llamaConfigFromIni` reads
`drafters = ini["drafters"].orEmpty()`. Empty map → no section (existing `writeIni` skips empty sections),
so old configs stay backward-compatible.

**Precedence** — a pure helper `draftForModel` resolves a model's drafter:

```kotlin
/** The drafter file for [modelFile]: an explicit [drafters] choice ("" = none) wins; else the
 *  curated default's own draft for the curated model; else none. */
fun draftForModel(cfg: LlamaConfig, modelFile: String): String {
    cfg.drafters[modelFile]?.let { return it }          // explicit choice ("" = none) wins
    if (modelFile == LlamaConfig().modelFile) return LlamaConfig().draftFile  // curated fallback
    return ""
}
```

### 2. `switchedConfig` uses the precedence

`switchedConfig(cfg, file)` keeps its curated-default branch but, for **all** models, resolves the
drafter via `draftForModel(cfg, file)` instead of hardcoding `""`:

```kotlin
fun switchedConfig(cfg: LlamaConfig, file: String): LlamaConfig {
    val d = LlamaConfig()
    val draft = draftForModel(cfg, file)
    val next = if (file == d.modelFile) {
        cfg.copy(repo = d.repo, modelFile = d.modelFile, draftFile = draft, mmprojFile = d.mmprojFile)
    } else {
        val repo = cfg.library[file] ?: ""
        cfg.copy(repo = repo, modelFile = file, draftFile = draft, mmprojFile = mmprojForRepo(cfg.library, repo, file))
    }
    return next.copy(useDraft = next.draftFile.isNotBlank(), useMmproj = next.mmprojFile.isNotBlank())
}
```

(The `mmproj` derivation is unchanged; `spec_type` in `[server]` stays `draft-mtp`.)

### 3. Set a drafter (ViewModel)

`fun setDrafter(modelFile: String, draftFile: String)` (draftFile `""` = none):

1. Load config, write `drafters = cfg.drafters + (modelFile to draftFile)`, save.
2. If `modelFile` is the **active** model, also set `config.draftFile = draftFile` / `useDraft` and, if the
   server is running, auto-restart to apply (`pendingRestart = true; LlamaServerService.stop(...)`) —
   consistent with `switchModel`. Otherwise it applies on next Start.
3. Refresh `state.models` via `localModels(...)`.

`localModels` populates each `ModelEntry.draftFile` from `draftForModel(cfg, name)` so cards show the
current pairing.

### 4. Drafter selection UI (per-model, in the Download panel)

Each installed model card in "On this phone" gains a **`Drafter: <name|none> ▾`** row. Tapping opens a
chooser (dialog) listing **(none)** + every draft file on disk (`draftersOnDisk`), each with its size;
the current choice is checked. Selecting calls `onSetDrafter(model.file, chosen)`. The chooser shows the
warning: *"A mismatched drafter won't load — the server will fail to start."* The row appears on the
active model's card too, so its drafter can be changed/disabled (auto-restart if running).

### 5. Drafters on disk (management, in the Download panel)

Below the model list, a **`─ Drafters on disk ─`** section (shown only when the blank-search local list
is visible and at least one drafter exists) lists every `isDraftFile` GGUF in the models dir with its
size and a 🗑 delete (confirm dialog). `fun deleteDrafter(file: String)`:

1. Refuse if it is the **active** model's current draft *and the server is running* (log + no-op),
   mirroring the active-model delete guard.
2. Delete the file; prune it from `[drafters]` (`drafters = cfg.drafters.filterValues { it != file }`)
   and from `[library]`.
3. If it was `config.draftFile`, clear `config.draftFile = ""` / `useDraft = false`.
4. Save; refresh `localModels`.

A pure `draftersOnDisk(files: List<Pair<String,Long>>): List<Pair<String,Long>>` filters
`isDraftFile` GGUFs (parallel to `primaryModels`).

### 6. Chat reload

- **Epoch signal.** Add `serverEpoch: Int = 0` to `MonitorUiState`. In the existing
  `LlamaServerService.status.collect` block, increment it on each transition **into** RUNNING
  (track the previous status; `prev != RUNNING && s == RUNNING` → `serverEpoch + 1`).
- **Auto-reload.** `ChatWebViewHolder.obtain(context, url, epoch)` gains an epoch parameter and a
  `loadedEpoch` field. Logic: if `loadedUrl != url` → (re)load and set `loadedEpoch = epoch` (existing
  deferred-size load). Else if the view already loaded and `epoch != loadedEpoch` → `reload()` and set
  `loadedEpoch = epoch`. So a restart makes the retained WebView reload against the fresh backend the next
  time Chat is composed, or immediately if it is open. `ChatPanel` passes `state.serverEpoch`.
- **Manual reload.** `PanelHeader` gains an optional trailing action slot; the Chat panel supplies a
  **↻** button calling `ChatWebViewHolder.reload()` (which reloads `webView`, guarded if null).

### Files touched

- `server/LlamaConfig.kt` — `drafters` field.
- `server/IniStore.kt` — `[drafters]` round-trip.
- `server/ModelLibrary.kt` — `draftForModel`, `switchedConfig` update, `draftersOnDisk`.
- `MonitorViewModel.kt` — `setDrafter`, `deleteDrafter`, `serverEpoch` increment, `localModels` drafter
  population.
- `model/MonitorState.kt` — `MonitorUiState.serverEpoch`; `ModelEntry` already has `draftFile`.
- `ui/menu/MenuCallbacks.kt` — `onSetDrafter`, `onDeleteDrafter`.
- `ui/menu/SubPanels.kt` — per-model Drafter row + chooser dialog; "Drafters on disk" section + delete.
- `ui/menu/MenuOverlay.kt` (`PanelHeader`) — optional trailing action.
- `ui/chat/ChatWebViewHolder.kt` — epoch-aware `obtain`, `reload()`.
- `ui/chat/ChatPanel.kt` — pass `serverEpoch`, add reload button.
- `ui/MonitorScreen.kt` — wire new callbacks.

## Testing

Pure-logic unit tests (host JVM):

- `draftForModel`: explicit draft wins; explicit `""` = none (even for the curated model); curated
  fallback when unset; arbitrary model unset → `""`.
- `switchedConfig`: wires the resolved drafter for arbitrary + curated models; `useDraft` follows.
- `[drafters]` INI round-trip (write → read); empty map writes no section; absent section → empty map.
- `deleteDrafter` pruning: removes the file from `[drafters]` values and `[library]`, and clears
  `config.draftFile` when it matched (test the pure config transform).
- `draftersOnDisk`: keeps `isDraftFile` GGUFs, drops primaries / mmproj / non-gguf.

On-device verification: pick `mtp-gemma-4-E2B-it.gguf` as the Hi-Fi model's drafter → server restarts →
speculative decoding active in the log; change to (none) → restarts without `--model-draft`; delete an
unused drafter → gone from disk and the list; open Chat, switch models → the WebView reloads and sends to
the new backend; tap ↻ → reloads.

## Known limitations

- Compatibility is the user's responsibility (all drafters offered for any model); a mismatch fails the
  server start, surfaced in the log and by the empty-state (Chat gated on RUNNING).
- Deleting the curated model still removes its bundled draft+mmproj via the existing `companionsOf` path;
  a drafter shared via `[drafters]` is only deleted through the explicit "Drafters on disk" delete.
