# Local Model Switcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The HF panel's blank-search state lists every primary GGUF on the phone — active one badged, tap to switch (auto-restarting a running server), delete with confirmation.

**Architecture:** Pure switch/filter logic in a new `server/ModelLibrary.kt` (host-JVM tested); a `[library]` section in `config.ini` records file→repo; `MonitorViewModel` gains `localModels`/`switchModel`/`deleteModel` with a `pendingRestart` flag riding the existing service status flow; `HfPanel` renders the local list with Use/Delete affordances.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 `AlertDialog`). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-07-model-switcher-design.md`

## Global Constraints

- No new Gradle dependencies.
- Package namespace is `com.iguar.armoredllama` (NOT armedllama).
- Work on branch `feat/model-switcher` off `main` (baseline dc6b8ba). The unmerged `feat/chat-webview` branch is unrelated — do NOT touch it or merge it.
- Companion rule (unchanged, single source): lowercase name contains `mmproj`, `draft`, or `mtp`.
- The curated profile is `LlamaConfig()`'s defaults (gemma-4-E2B: repo `unsloth/gemma-4-E2B-it-qat-mobile-GGUF`, model `gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf`, draft `mtp-gemma-4-E2B-it.gguf`, mmproj `mmproj-F16.gguf`). Never hardcode those strings in new logic — always read them from `LlamaConfig()`.
- After any switch: `useDraft = draftFile.isNotBlank()`, `useMmproj = mmprojFile.isNotBlank()`.
- Switching while STARTING is refused with a log hint. Switching while RUNNING auto-restarts. Deleting the active model is refused (and the UI shows no delete affordance for it).
- `[library]` section in config.ini: one `<file> = <repo>` line per downloaded file; absent section parses to an empty map (backward compatible).

---

### Task 1: `ModelLibrary` (pure) + single-source companion rule

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt`
- Test: `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/server/HfModels.kt:81-84` (delete its private `isCompanionFile`)

**Interfaces:**
- Consumes: `LlamaConfig` (existing data class in the same package; its no-arg constructor IS the curated profile).
- Produces (package `com.iguar.armoredllama.server`, all top-level):
  - `fun isCompanionFile(name: String): Boolean`
  - `fun primaryModels(files: List<Pair<String, Long>>): List<Pair<String, Long>>`
  - `fun switchedConfig(cfg: LlamaConfig, file: String): LlamaConfig`
  - `fun companionsOf(file: String): List<String>`
  Tasks 3–4 rely on these exact names. NOTE: `switchedConfig` reads `cfg.library` which is added in Task 2 — in THIS task use `cfg.library` only if the field exists; it does not yet, so in this task `switchedConfig`'s non-curated branch sets `repo = ""` and Task 2 swaps in the library lookup. (See Step 4 code — it is written WITHOUT the library lookup; Task 2 upgrades it.)

- [ ] **Step 1: Create branch**

```bash
git checkout main && git checkout -b feat/model-switcher
```

- [ ] **Step 2: Write the failing test**

`app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt`:

```kotlin
package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLibraryTest {

    @Test fun primaryModels_filtersCompanionsAndNonGguf() {
        val files = listOf(
            "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf" to 2_186_184_768L,
            "Qwen3.5-4B-Q4_K_M.GGUF" to 2_740_937_888L, // case-insensitive extension
            "mtp-gemma-4-E2B-it.gguf" to 59_234_176L,   // companion: mtp
            "mmproj-F16.gguf" to 985_654_080L,          // companion: mmproj
            "notes.txt" to 10L,                         // not a gguf
        )

        assertEquals(listOf(files[0], files[1]), primaryModels(files))
    }

    @Test fun switchedConfig_restoresCuratedProfile() {
        val d = LlamaConfig()
        val qwen = d.copy(
            repo = "unsloth/Qwen3.5-4B-GGUF", modelFile = "Qwen3.5-4B-Q4_K_M.gguf",
            draftFile = "", mmprojFile = "", useDraft = false, useMmproj = false, ctx = 8192,
        )

        val back = switchedConfig(qwen, d.modelFile)

        assertEquals(d.repo, back.repo)
        assertEquals(d.modelFile, back.modelFile)
        assertEquals(d.draftFile, back.draftFile)
        assertEquals(d.mmprojFile, back.mmprojFile)
        assertEquals(true, back.useDraft)
        assertEquals(true, back.useMmproj)
        assertEquals(8192, back.ctx) // server settings pass through untouched
    }

    @Test fun switchedConfig_clearsCompanionsForOtherModels() {
        val next = switchedConfig(LlamaConfig(), "Qwen3.5-4B-Q4_K_M.gguf")

        assertEquals("Qwen3.5-4B-Q4_K_M.gguf", next.modelFile)
        assertEquals("", next.draftFile)
        assertEquals("", next.mmprojFile)
        assertEquals(false, next.useDraft)
        assertEquals(false, next.useMmproj)
    }

    @Test fun companionsOf_curatedTakesItsCompanions_othersNone() {
        val d = LlamaConfig()
        assertEquals(listOf(d.draftFile, d.mmprojFile), companionsOf(d.modelFile))
        assertEquals(emptyList<String>(), companionsOf("Qwen3.5-4B-Q4_K_M.gguf"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armoredllama.server.ModelLibraryTest"`
Expected: FAIL to compile — `unresolved reference: primaryModels` (etc.).

- [ ] **Step 4: Write the implementation**

`app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt`:

```kotlin
package com.iguar.armoredllama.server

/** Local model library logic: which files are primary models, and how switching rewrites config. */

/** Companion (non-primary) GGUF: draft/mtp speculative models and mmproj vision projectors. */
fun isCompanionFile(name: String): Boolean {
    val n = name.lowercase()
    return "mmproj" in n || "draft" in n || "mtp" in n
}

/** Filter (fileName, sizeBytes) pairs down to primary .gguf models. */
fun primaryModels(files: List<Pair<String, Long>>): List<Pair<String, Long>> =
    files.filter { (name, _) -> name.endsWith(".gguf", ignoreCase = true) && !isCompanionFile(name) }

/**
 * The config after switching to [file]. The curated default model restores its full profile
 * (repo + draft + mmproj from [LlamaConfig] defaults); any other file clears the companions.
 * Feature toggles follow the files; server settings pass through untouched.
 */
fun switchedConfig(cfg: LlamaConfig, file: String): LlamaConfig {
    val d = LlamaConfig()
    val next = if (file == d.modelFile) {
        cfg.copy(repo = d.repo, modelFile = d.modelFile, draftFile = d.draftFile, mmprojFile = d.mmprojFile)
    } else {
        cfg.copy(repo = "", modelFile = file, draftFile = "", mmprojFile = "")
    }
    return next.copy(useDraft = next.draftFile.isNotBlank(), useMmproj = next.mmprojFile.isNotBlank())
}

/** Files deleted along with [file]: the curated default takes its draft+mmproj; others nothing. */
fun companionsOf(file: String): List<String> {
    val d = LlamaConfig()
    return if (file == d.modelFile) listOf(d.draftFile, d.mmprojFile).filter { it.isNotBlank() } else emptyList()
}
```

Then in `app/src/main/java/com/iguar/armoredllama/server/HfModels.kt` DELETE these four lines (the
package-level function above replaces them; the call site at `filterNot { isCompanionFile(it.name) }`
resolves to it automatically since both are in `com.iguar.armoredllama.server`):

```kotlin
    private fun isCompanionFile(name: String): Boolean {
        val n = name.lowercase()
        return "mmproj" in n || "draft" in n || "mtp" in n
    }
```

- [ ] **Step 5: Run tests to verify green**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — ModelLibraryTest 4/4, HfModelsTest still green (companion rule unchanged).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt \
        app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt \
        app/src/main/java/com/iguar/armoredllama/server/HfModels.kt
git commit -m "feat(models): pure ModelLibrary — primary filter, switch config, companions"
```

---

### Task 2: `LlamaConfig.library` + `[library]` INI section + repo recording

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/server/LlamaConfig.kt:28` (add field)
- Modify: `app/src/main/java/com/iguar/armoredllama/server/IniStore.kt:36-97` (toIni/fromIni)
- Modify: `app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt` (switchedConfig uses library)
- Modify: `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt:404-412` (downloadModel records repo)
- Test: `app/src/test/java/com/iguar/armoredllama/server/IniStoreTest.kt` (add 2 tests)
- Test: `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt` (add 1 test)

**Interfaces:**
- Consumes: Task 1's `switchedConfig`.
- Produces: `LlamaConfig.library: Map<String, String>` (file → repo, default empty); `switchedConfig` non-curated branch now sets `repo = cfg.library[file] ?: ""`. Task 3 relies on both.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/iguar/armoredllama/server/IniStoreTest.kt` (inside the existing class):

```kotlin
    @Test fun libraryRoundTrips() {
        val cfg = LlamaConfig(library = mapOf("Qwen3.5-4B-Q4_K_M.gguf" to "unsloth/Qwen3.5-4B-GGUF"))
        val restored = llamaConfigFromIni(cfg.toIni())
        assertEquals(cfg.library, restored.library)
    }

    @Test fun missingLibrarySectionYieldsEmptyMap() {
        val restored = llamaConfigFromIni(LlamaConfig().toIni())
        assertEquals(emptyMap<String, String>(), restored.library)
    }
```

Append to `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt` (inside the class):

```kotlin
    @Test fun switchedConfig_looksUpRepoInLibrary() {
        val cfg = LlamaConfig(library = mapOf("Qwen3.5-4B-Q4_K_M.gguf" to "unsloth/Qwen3.5-4B-GGUF"))

        val next = switchedConfig(cfg, "Qwen3.5-4B-Q4_K_M.gguf")

        assertEquals("unsloth/Qwen3.5-4B-GGUF", next.repo)
        assertEquals(cfg.library, next.library) // library passes through
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armoredllama.server.IniStoreTest" --tests "com.iguar.armoredllama.server.ModelLibraryTest"`
Expected: FAIL to compile — `cannot find a parameter with this name: library`.

- [ ] **Step 3: Implement**

(a) `LlamaConfig.kt` — add as the LAST constructor parameter (after `useMmproj`):

```kotlin
    val useMmproj: Boolean = true,   // vision/multimodal: emit --mmproj (~1 GB; off for text-only)
    // Downloaded-file → repo bookkeeping (INI [library] section); display + switch-back metadata.
    val library: Map<String, String> = emptyMap(),
```

(b) `IniStore.kt` — in `LlamaConfig.toIni()` add a third section after `"model" to linkedMapOf(...)`:

```kotlin
        "library" to LinkedHashMap(library),
```

(`writeIni` already skips empty sections, so an empty library writes nothing — backward compatible.)

In `llamaConfigFromIni`, add before the closing paren of the `LlamaConfig(...)` call:

```kotlin
        library = ini["library"].orEmpty(),
```

(c) `ModelLibrary.kt` — change `switchedConfig`'s else-branch line from:

```kotlin
        cfg.copy(repo = "", modelFile = file, draftFile = "", mmprojFile = "")
```

to:

```kotlin
        cfg.copy(repo = cfg.library[file] ?: "", modelFile = file, draftFile = "", mmprojFile = "")
```

(d) `MonitorViewModel.kt` `downloadModel` — replace the config-build block:

```kotlin
        val config = configRepo.load().copy(
            repo = target.repo,
            modelFile = target.file,
            draftFile = target.draftFile,
            mmprojFile = target.mmprojFile,
            useDraft = target.draftFile.isNotBlank(),
            useMmproj = target.mmprojFile.isNotBlank(),
        )
```

with:

```kotlin
        val prev = configRepo.load()
        val config = prev.copy(
            repo = target.repo,
            modelFile = target.file,
            draftFile = target.draftFile,
            mmprojFile = target.mmprojFile,
            useDraft = target.draftFile.isNotBlank(),
            useMmproj = target.mmprojFile.isNotBlank(),
            // Remember where this file came from so the local list can show/switch it later.
            library = prev.library + (target.file to target.repo),
        )
```

- [ ] **Step 4: Run full suite green**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (new tests pass; existing IniStore/ModelLibrary tests untouched).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/LlamaConfig.kt \
        app/src/main/java/com/iguar/armoredllama/server/IniStore.kt \
        app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt \
        app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt \
        app/src/test/java/com/iguar/armoredllama/server/IniStoreTest.kt \
        app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt
git commit -m "feat(models): [library] config section records file->repo on download"
```

---

### Task 3: ViewModel — `localModels` / `switchModel` / `deleteModel` + auto-restart

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt` (state seed ~line 61, init status collector ~line 127, updateHfQuery blank/error paths ~lines 347+390, new functions after `downloadModel`)
- Modify: `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt:78` (ModelState.ACTIVE)
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt:410-428` (ModelCard `when` gains minimal ACTIVE branch — keeps the build green; full UI is Task 4)
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuCallbacks.kt` (2 new callbacks)
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/MonitorScreen.kt:49-56` (wire them)

**Interfaces:**
- Consumes: Task 1/2's `primaryModels`, `switchedConfig`, `companionsOf`, `LlamaConfig.library`.
- Produces: `MonitorViewModel.switchModel(file: String)`, `deleteModel(file: String)`; `MenuCallbacks.onSwitchModel: (String) -> Unit`, `onDeleteModel: (String) -> Unit`; `ModelState.ACTIVE`. Task 4's UI calls all of these.

- [ ] **Step 1: `ModelState.ACTIVE` + minimal render branch**

In `model/MonitorState.kt` change:

```kotlin
enum class ModelState { IDLE, DOWNLOADING, INSTALLED }
```

to:

```kotlin
enum class ModelState { IDLE, DOWNLOADING, INSTALLED, ACTIVE }
```

In `ui/menu/SubPanels.kt`, the `when (model.state)` inside `ModelCard` is now non-exhaustive.
Add a minimal branch after the `ModelState.INSTALLED` branch (Task 4 replaces this with the full
local-list UI):

```kotlin
            ModelState.ACTIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.good, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("ACTIVE", style = MonitorType.monoCaption, color = c.good)
            }
```

- [ ] **Step 2: `localModels()` replaces `seedModels` as the blank-search source**

In `MonitorViewModel.kt`, add AFTER the existing `seedModels` function (keep `seedModels` — it
becomes the fresh-install fallback):

```kotlin
    /**
     * Blank-search source: every primary GGUF on disk (active first). Falls back to the
     * configured-model seed entry when the configured model is not downloaded yet (fresh
     * install), so the curated default stays one tap away.
     */
    private fun localModels(
        freeRamMB: Float = 0f,
        settings: ServerSettings = ServerSettings(),
    ): List<ModelEntry> {
        val cfg = configRepo.load()
        val onDisk = configRepo.modelsDir().listFiles().orEmpty()
            .filter { it.isFile }
            .map { it.name to it.length() }
        val entries = primaryModels(onDisk).map { (name, sizeBytes) ->
            val sizeGB = sizeBytes.toFloat() / (1024f * 1024f * 1024f)
            val preview = switchedConfig(cfg, name) // what switching here would configure
            ModelEntry(
                id = name,
                repo = preview.repo.ifBlank { "local file" },
                name = name.substringBeforeLast(".").takeUnless { it.isBlank() } ?: name,
                file = name,
                quant = quantFrom(name),
                sizeGB = sizeGB,
                draftFile = preview.draftFile,
                mmprojFile = preview.mmprojFile,
                fit = estimateModelFit(
                    modelSizeGB = sizeGB,
                    freeRamMB = freeRamMB,
                    ctx = settings.ctx,
                    hasDraft = preview.draftFile.isNotBlank(),
                    hasMmproj = preview.mmprojFile.isNotBlank(),
                    cacheTypeK = settings.cacheTypeK,
                    cacheTypeV = settings.cacheTypeV,
                    flashAttn = settings.flashAttn,
                ),
                state = if (name == cfg.modelFile) ModelState.ACTIVE else ModelState.INSTALLED,
            )
        }.sortedByDescending { it.state == ModelState.ACTIVE }
        // Fresh install / configured model not downloaded: show the seed entry so it can be Got.
        return if (entries.none { it.state == ModelState.ACTIVE }) {
            seedModels(freeRamMB, settings) + entries
        } else entries
    }
```

Then swap the FOUR `seedModels(` call sites that feed the UI to `localModels(`:
1. `var state by mutableStateOf(MonitorUiState(models = seedModels()))` → `localModels()`
2. the `models = seedModels(` inside the `init` block's `state.copy(...)` → `localModels(`
3. `updateHfQuery` blank path: `state.copy(models = seedModels(state.metrics.ramFree, state.settings), ...)` → `localModels(`
4. `updateHfQuery` catch path: `models = seedModels(state.metrics.ramFree, state.settings),` → `localModels(`

(`seedModels` keeps its one remaining caller: `localModels` itself.)

- [ ] **Step 3: `switchModel` / `deleteModel` + pendingRestart**

Add a field next to the other Jobs (`private var hfSearchJob: Job? = null`):

```kotlin
    private var pendingRestart = false
```

In the `init` block's status collector, extend the body:

```kotlin
        viewModelScope.launch {
            LlamaServerService.status.collect { s ->
                val stopped = s == LlamaServerService.Status.STOPPED
                state = state.copy(
                    serverStatus = s,
                    running = s == LlamaServerService.Status.RUNNING || s == LlamaServerService.Status.STARTING,
                    metrics = if (stopped) state.metrics.copy(tps = 0f, pp = 0f) else state.metrics,
                )
                // Model switch requested while running: restart with the new config once stopped.
                if (stopped && pendingRestart) {
                    pendingRestart = false
                    toggleRunning()
                }
                if (s == LlamaServerService.Status.ERROR) pendingRestart = false
            }
        }
```

Add after `downloadModel` (and its `updateModel` helper):

```kotlin
    // Local model switching (blank-search list) ------------------------------------------------
    fun switchModel(file: String) {
        if (state.serverStatus == LlamaServerService.Status.STARTING) {
            LogBus.append("switch ignored: wait for the server to finish starting")
            return
        }
        val cfg = configRepo.load()
        if (file == cfg.modelFile) return
        val next = switchedConfig(cfg, file)
        configRepo.save(next) // synchronous: the restart below reads config.ini at launch
        state = state.copy(
            modelFile = next.modelFile.substringBeforeLast(".").takeUnless { it.isBlank() } ?: next.modelFile,
            settings = state.settings.copy(useDraft = next.useDraft, useMmproj = next.useMmproj),
            models = localModels(state.metrics.ramFree, state.settings),
        )
        if (state.running) {
            LogBus.append("switching model → $file")
            pendingRestart = true
            LlamaServerService.stop(getApplication())
        }
    }

    fun deleteModel(file: String) {
        val cfg = configRepo.load()
        if (file == cfg.modelFile) {
            LogBus.append("delete refused: $file is the active model — switch first")
            return
        }
        val dir = configRepo.modelsDir()
        val removed = (listOf(file) + companionsOf(file)).filter { File(dir, it).delete() }
        if (removed.isNotEmpty()) LogBus.append("deleted: ${removed.joinToString()}")
        state = state.copy(models = localModels(state.metrics.ramFree, state.settings))
    }
```

Add the imports:

```kotlin
import com.iguar.armoredllama.server.companionsOf
import com.iguar.armoredllama.server.primaryModels
import com.iguar.armoredllama.server.switchedConfig
```

- [ ] **Step 4: Callbacks + wiring**

`ui/menu/MenuCallbacks.kt` — full new content:

```kotlin
package com.iguar.armoredllama.ui.menu

import com.iguar.armoredllama.model.ServerSettings

/** The actions the menu sub-panels can invoke, forwarded to the ViewModel. */
data class MenuCallbacks(
    val onUpdateSettings: ((ServerSettings) -> ServerSettings) -> Unit,
    val onCheckUpdate: () -> Unit,
    val onDownloadUpdate: () -> Unit,
    val onRemoveDownloadedUpdate: () -> Unit,
    val onUpdateHfQuery: (String) -> Unit,
    val onDownloadModel: (String) -> Unit,
    val onSwitchModel: (String) -> Unit,
    val onDeleteModel: (String) -> Unit,
)
```

`ui/MonitorScreen.kt` — extend the `MenuCallbacks(...)` construction:

```kotlin
            callbacks = MenuCallbacks(
                onUpdateSettings = vm::updateSettings,
                onCheckUpdate = vm::checkForUpdate,
                onDownloadUpdate = vm::downloadUpdate,
                onRemoveDownloadedUpdate = vm::removeDownloadedUpdate,
                onUpdateHfQuery = vm::updateHfQuery,
                onDownloadModel = vm::downloadModel,
                onSwitchModel = vm::switchModel,
                onDeleteModel = vm::deleteModel,
            ),
```

- [ ] **Step 5: Build + full unit suite green**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt \
        app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt \
        app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt \
        app/src/main/java/com/iguar/armoredllama/ui/menu/MenuCallbacks.kt \
        app/src/main/java/com/iguar/armoredllama/ui/MonitorScreen.kt
git commit -m "feat(models): localModels list, switchModel with auto-restart, deleteModel"
```

---

### Task 4: HfPanel UI — "On this phone", Use / Delete with confirm

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt` (HfPanel ~line 290, ModelCard ~line 356)

**Interfaces:**
- Consumes: `MenuCallbacks.onSwitchModel` / `onDeleteModel` (Task 3), `ModelState.ACTIVE` (Task 3).
- Produces: final user-facing UI; nothing downstream.

- [ ] **Step 1: Section header in HfPanel**

In `HfPanel`, insert between `SearchField(...)` + `Spacer(Modifier.height(12.dp))` and the
`if (state.hfLoading)` line:

```kotlin
            if (state.hfQuery.isBlank()) {
                Text("On this phone", style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.height(8.dp))
            }
```

And change the row call inside `state.visibleModels.forEach { model ->` from:

```kotlin
                state.visibleModels.forEach { model ->
                    ModelCard(model) { callbacks.onDownloadModel(model.id) }
                }
```

to:

```kotlin
                state.visibleModels.forEach { model ->
                    val local = state.hfQuery.isBlank() && model.state == ModelState.INSTALLED
                    ModelCard(
                        model = model,
                        onGet = { callbacks.onDownloadModel(model.id) },
                        onSwitch = if (local) ({ callbacks.onSwitchModel(model.file) }) else null,
                        onDelete = if (local) ({ callbacks.onDeleteModel(model.file) }) else null,
                    )
                }
```

- [ ] **Step 2: ModelCard — Use / Delete affordances + confirm dialog**

Replace the `ModelCard` signature and its trailing `when` block (the info `Column` in the middle
is unchanged). New signature:

```kotlin
@Composable
private fun ModelCard(
    model: ModelEntry,
    onGet: () -> Unit,
    onSwitch: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val c = MonitorTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }
```

New trailing block (replaces the existing `when (model.state) { ... }`):

```kotlin
        when (model.state) {
            ModelState.IDLE -> SmallButton("Get", c.accent, onGet)
            ModelState.DOWNLOADING -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
                Text("${(model.progress * 100).roundToInt()}%", style = MonitorType.monoCaption, color = c.accent)
                Spacer(Modifier.height(4.dp))
                GradientBar(
                    fraction = model.progress,
                    startColor = c.accent,
                    endColor = c.accent2,
                    trackColor = c.ringTrack,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            ModelState.ACTIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.good, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("ACTIVE", style = MonitorType.monoCaption, color = c.good)
            }
            ModelState.INSTALLED -> if (onSwitch != null) {
                Column(horizontalAlignment = Alignment.End) {
                    SmallButton("Use", c.accent, onSwitch)
                    if (onDelete != null) {
                        Spacer(Modifier.height(6.dp))
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete ${model.file}",
                            tint = c.bad,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { confirmDelete = true },
                        )
                    }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.good, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Installed", style = MonitorType.monoCaption, color = c.good)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete model?") },
            text = {
                val size = if (model.sizeGB > 0f) " — frees %.1f GB".format(model.sizeGB) else ""
                Text(model.file + size)
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
```

(The closing brace before `if (confirmDelete)` ends the card's `Row`; the dialog sits outside it,
inside the composable.)

Add imports to `SubPanels.kt`:

```kotlin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

- [ ] **Step 3: Build + full unit suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Install and launch**

Run: `./gradlew :app:installDebug`
then: `adb shell monkey -p com.iguar.armoredllama -c android.intent.category.LAUNCHER 1`
Expected: Installed on 1 device; app launches.

- [ ] **Step 5: On-device manual verification (requires the user — agents cannot tap the screen)**

1. HF panel, blank search → "On this phone" lists gemma + Qwen with real sizes; the configured one shows ACTIVE (no delete icon); the other shows Use + delete icon.
2. With the server running on Qwen → tap Use on gemma → log shows `switching model → gemma…`, server stops and auto-starts; boot log shows the gemma exec line with `--model-draft`/`--mmproj` back; ACTIVE badge moved; top-bar model name updated.
3. Tap Use on Qwen → switches back (no draft/mmproj flags in the exec line).
4. Delete icon on the non-active model → dialog with size → Delete → row disappears; file gone (`adb shell ls`); Cancel leaves it.
5. Search "qwen" → HF results still show Get/Installed as before (no Use/Delete in search results).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt
git commit -m "feat(models): On-this-phone list with Use switch and confirmed delete"
```

---

## Final

After all tasks: whole-branch review, then `superpowers:finishing-a-development-branch`
(merge `feat/model-switcher` → `main`). Update `IMPLEMENTATION_NOTES.md` Wiring Status item 10
(model management now includes local switch/delete) during finish-up. Note: `MenuCallbacks.kt` /
`MonitorScreen.kt` will conflict trivially with the unmerged `feat/chat-webview` branch — whichever
merges second resolves by keeping both additions.
