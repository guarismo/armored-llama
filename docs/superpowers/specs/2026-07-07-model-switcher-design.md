# Design: Local model switcher — "On this phone" in the HF panel

**Date:** 2026-07-07
**Status:** Approved
**Component area:** `server/` (pure logic + config), `MonitorViewModel`, `model/MonitorState.kt`,
`ui/menu/SubPanels.kt` (HfPanel), `ui/menu/MenuCallbacks.kt`

## 1. Summary

Downloaded models are invisible: the HF panel's blank-search state shows only the *configured*
model, so a user with two models on disk (e.g. Qwen3.5-4B and gemma-4-E2B) cannot switch back
without re-searching Hugging Face. Fix: when search is blank, the HF panel lists **all primary
GGUFs in the models dir** — active one badged, tap to switch (with auto-restart if the server is
running), delete with confirmation on non-active rows.

Chosen approach: extend the HF panel's blank state (over a separate "My models" panel or a
Settings dropdown) — model management lives where models already live, reusing the existing
`ModelEntry` row UI, fit estimator, and state plumbing. No new `Panel`.

## 2. Goals / Non-goals

**Goals**
- Blank search → "On this phone": every primary `.gguf` in the models dir with name, quant,
  real on-disk size, fit badge; the configured model marked **ACTIVE**.
- Tap a non-active model → switch the config to it; if the server is running, it auto-restarts
  with the new model (one tap does the whole thing).
- Delete a non-active model (with confirm dialog); the curated gemma's companions (draft +
  mmproj) are deleted with it.
- Switching to the curated gemma restores its draft/mmproj profile; switching to any other
  model clears companions.

**Non-goals (YAGNI)**
- No per-model saved settings (ctx/threads stay global).
- No multi-profile curation beyond the built-in gemma default (the `[library]` section lays
  groundwork, but only `file → repo` is recorded).
- No download management (pause/resume UI) changes; search behavior unchanged.
- Deleting the ACTIVE model is not possible (no icon shown) — switch first.

## 3. Components

### 3.1 `server/ModelLibrary.kt` (new, pure, unit-tested)
- `fun isCompanionFile(name: String): Boolean` — moved from `HfModels` (single source; HfModels
  calls it here). Rule unchanged: lowercase name contains `mmproj`, `draft`, or `mtp`.
- `fun primaryModels(files: List<Pair<String, Long>>): List<Pair<String, Long>>` — filters to
  `.gguf` (case-insensitive), non-companion; input is (fileName, sizeBytes).
- `fun switchedConfig(cfg: LlamaConfig, file: String): LlamaConfig` — the switch decision:
  - `file == LlamaConfig().modelFile` (curated gemma) → restore the built-in profile:
    `repo`, `modelFile`, `draftFile`, `mmprojFile` from `LlamaConfig()` defaults.
  - otherwise → `modelFile = file`, `repo = cfg.library[file] ?: ""`, `draftFile = ""`,
    `mmprojFile = ""`.
  - In both cases: `useDraft = draftFile.isNotBlank()`, `useMmproj = mmprojFile.isNotBlank()`.
    All server settings (`ctx`, `threads`, caches, …) and `library` pass through unchanged.
- `fun companionsOf(file: String): List<String>` — files deleted along with [file]: for the
  curated gemma model returns its `draftFile` + `mmprojFile` (from `LlamaConfig()` defaults);
  for any other file returns empty.

### 3.2 `LlamaConfig` + `IniStore` (modified)
- `LlamaConfig` gains `val library: Map<String, String> = emptyMap()` — downloaded-file → repo.
- `IniStore` persists it as a `[library]` section (`<file> = <repo>` lines) and parses it back;
  absent section → empty map (backward compatible with existing config.ini files).
- `MonitorViewModel.downloadModel` records `library + (file → repo)` when a download starts, so
  future downloads are attributable. (Existing files: gemma is the built-in profile and needs
  no entry; other pre-feature files show repo "" until re-downloaded — display-only impact.)

### 3.3 `MonitorViewModel` (modified)
- `localModels(): List<ModelEntry>` — replaces `seedModels` as the blank-search source: lists
  `modelsDir` files, `primaryModels(...)` filter, builds entries with real sizes
  (`length()`), fit via `estimateModelFit` (live settings, companions per `switchedConfig`
  preview), `state = ACTIVE` for `cfg.modelFile`, else `INSTALLED`.
- `switchModel(file: String)`:
  - if `state.serverStatus == STARTING` → log hint "wait for the server to finish starting",
    no-op.
  - persist `switchedConfig(configRepo.load(), file)` synchronously (same race-guard as Start);
    update UI state (modelFile label, models list, settings toggles).
  - if RUNNING → log `switching model → <file>`, set `pendingRestart = true`, stop the service;
    the existing status-flow collector starts the service again on the STOPPED transition and
    clears the flag.
  - if STOPPED → config-only switch (next Start uses it).
- `deleteModel(file: String)` — refuses the active file (defensive; UI shows no icon); deletes
  the file plus `companionsOf(file)` from `modelsDir`; refreshes the models list.
- `MenuCallbacks` gains `onSwitchModel: (String) -> Unit` and `onDeleteModel: (String) -> Unit`.

### 3.4 `model/MonitorState.kt` (modified)
- `ModelState` gains `ACTIVE` (rendering: the row's existing badge slot shows "ACTIVE").

### 3.5 `ui/menu/SubPanels.kt` — HfPanel (modified)
- Blank-search state: section header "On this phone" above the local list; rows reuse the
  existing model-row composable. ACTIVE row: badge, no tap action, no delete icon. Non-active
  rows: tap → `onSwitchModel(file)`; trailing delete icon → Material3 `AlertDialog`
  ("Delete <file>? Frees ~<size> GB." / Delete / Cancel) → `onDeleteModel(file)`.
- Non-blank search: unchanged (HF results with Get).

## 4. Data flow

1. Open HF panel (blank) → `localModels()` → "On this phone" rows, ACTIVE badged.
2. Tap non-active row → `switchModel` → config persisted → (if running) stop → STOPPED
   transition → auto-start with new model → ACTIVE badge moves; top-bar model label updates.
3. Delete icon → dialog confirm → file (+ gemma companions if applicable) removed → list
   refreshes.

## 5. Error handling

| Condition | Behavior |
|---|---|
| Switch while STARTING | Log hint, no-op (no mid-boot restart). |
| Switch while RUNNING | Auto-restart via pendingRestart on the status flow; if the new model fails to load, the service's existing error path surfaces it in the log; user can switch back the same way. |
| Delete the active model | No icon in UI; `deleteModel` also refuses defensively (log line). |
| File vanished between list and tap | Service pre-check ("missing model file(s)") already covers start; `switchModel` persists config regardless — list refresh shows reality. |
| Pre-feature files with unknown repo | `repo = ""` (display-only); fit/size/switch all work from the local file. |

## 6. Testing

- **Pure host-JVM (`ModelLibraryTest`):** `primaryModels` filtering (companions out, non-gguf
  out, case-insensitive); `switchedConfig` gemma-restore, plain-clear (+library repo lookup,
  toggles follow files, settings pass through); `companionsOf` gemma vs other.
- **`IniStoreTest`:** `[library]` section round-trip; absent section → empty map.
- **Manual on-device:** switch Qwen→gemma while running (auto-restart; draft acceptance line
  reappears in log), gemma→Qwen back; delete non-active model frees storage and disappears
  from the list; ACTIVE row has no delete icon.

## 7. File-by-file change list

**New:** `server/ModelLibrary.kt`, test `ModelLibraryTest.kt`.
**Modified:** `server/LlamaConfig.kt`, `server/IniStore.kt` (+ `IniStoreTest`),
`server/HfModels.kt` (use `ModelLibrary.isCompanionFile`), `MonitorViewModel.kt`,
`model/MonitorState.kt`, `ui/menu/SubPanels.kt`, `ui/menu/MenuCallbacks.kt`,
`ui/MonitorScreen.kt` (wire the two new callbacks).

## 8. Risks / notes

- **Restart sequencing** rides the existing service status flow (StateFlow of
  STOPPED/STARTING/RUNNING/ERROR); the pendingRestart flag must be cleared on ERROR too, so a
  failed stop can't leave a surprise start pending.
- The curated-gemma special case is keyed on the exact default `modelFile` name from
  `LlamaConfig()`; if the bundled profile changes, the key follows it automatically.
- Builds on whatever branch order lands first: this feature is independent of the pending
  `feat/chat-webview` branch (different files except `MenuCallbacks`/`MonitorScreen` touch
  points — trivial merge).
