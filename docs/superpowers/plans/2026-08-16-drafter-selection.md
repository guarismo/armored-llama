# Drafter Selection, Management & Chat Reload — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick/remember a drafter per model, see and delete drafters on disk, and have the Chat WebView reload automatically when the server restarts (plus a manual reload button).

**Architecture:** A new `[drafters]` map (`modelFile → draftFile`) in `LlamaConfig`, resolved by a pure `draftForModel` precedence that flows through `switchedConfig`. The ViewModel gains `setDrafter`/`deleteDrafter` and a `serverEpoch` counter that increments on each transition into RUNNING; the retained Chat WebView reloads when that epoch advances. UI lives in the existing Download panel and Chat panel.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 host-JVM tests (`./gradlew testDebugUnitTest`). Android package `com.iguar.armoredllama`, targetSdk 28, CPU-only llama.cpp. Windows dev host; the Bash tool runs Git Bash, `./gradlew` works.

## Global Constraints

- The drafter picker offers **all** draft files on disk for **any** model; the user owns compatibility. Chooser warning text is exactly: `A mismatched drafter won't load — the server will fail to start.`
- `spec_type` is never changed by this feature (stays whatever `[server]` holds, i.e. `draft-mtp`).
- `[drafters]` maps `modelFile → draftFile`; a value of `""` means **explicit none**.
- Drafter precedence (`draftForModel`): explicit `[drafters]` entry (`""` = none) wins; else the curated default's own `draftFile` for the curated model; else `""`.
- Changing the drafter of the **active** model **auto-restarts** the server if it is running (consistent with `switchModel`); otherwise it applies on next Start.
- `serverEpoch` increments only on a transition **into** RUNNING (`prev != RUNNING && s == RUNNING`).
- Backward compatible: an empty `drafters` map writes no `[drafters]` section (existing `writeIni` skips empty sections); an absent section reads back as an empty map.
- Companion detection stays single-source: `isDraftFile`/`isVisionFile`/`isCompanionFile` in `ModelLibrary.kt`.

---

### Task 1: `[drafters]` config + drafter resolution/pruning (pure)

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/server/LlamaConfig.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/server/IniStore.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt`
- Test: `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt`
- Test: `app/src/test/java/com/iguar/armoredllama/server/IniStoreTest.kt`

**Interfaces:**
- Produces:
  - `LlamaConfig.drafters: Map<String, String>` (last constructor param, default `emptyMap()`)
  - `fun draftForModel(cfg: LlamaConfig, modelFile: String): String`
  - `fun draftersOnDisk(files: List<Pair<String, Long>>): List<Pair<String, Long>>`
  - `fun configAfterDrafterDelete(cfg: LlamaConfig, file: String): LlamaConfig`
  - `switchedConfig(cfg, file)` now wires the drafter via `draftForModel`
- Consumes: `isDraftFile`, `isVisionFile`, `mmprojForRepo` (unchanged), `LlamaConfig`.

- [ ] **Step 1: Write the failing tests (ModelLibraryTest)**

Append to `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt` (before the final closing `}`):

```kotlin
    @Test fun draftForModel_explicitChoiceWins_emptyMeansNone() {
        val cfg = LlamaConfig(drafters = mapOf(
            "HiFi.gguf" to "mtp-gemma-4-E2B-it.gguf",
            LlamaConfig().modelFile to "",   // explicitly disable the curated model's draft
        ))
        assertEquals("mtp-gemma-4-E2B-it.gguf", draftForModel(cfg, "HiFi.gguf"))
        assertEquals("", draftForModel(cfg, LlamaConfig().modelFile))   // explicit none overrides curated
    }

    @Test fun draftForModel_curatedFallback_andArbitraryNone() {
        val cfg = LlamaConfig()  // no [drafters] entries
        assertEquals(LlamaConfig().draftFile, draftForModel(cfg, LlamaConfig().modelFile)) // curated default
        assertEquals("", draftForModel(cfg, "Qwen3.5-4B-Q4_K_M.gguf"))                     // arbitrary → none
    }

    @Test fun switchedConfig_wiresChosenDrafterForArbitraryModel() {
        val cfg = LlamaConfig(
            library = mapOf("HiFi.gguf" to "fraQtl/Gemma-4-E2B-it-Hi-Fi-GGUF"),
            drafters = mapOf("HiFi.gguf" to "mtp-gemma-4-E2B-it.gguf"),
        )
        val next = switchedConfig(cfg, "HiFi.gguf")
        assertEquals("HiFi.gguf", next.modelFile)
        assertEquals("mtp-gemma-4-E2B-it.gguf", next.draftFile)
        assertEquals(true, next.useDraft)
    }

    @Test fun switchedConfig_curatedDrafterCanBeDisabled() {
        val cfg = LlamaConfig(drafters = mapOf(LlamaConfig().modelFile to ""))
        val next = switchedConfig(cfg, LlamaConfig().modelFile)
        assertEquals("", next.draftFile)
        assertEquals(false, next.useDraft)
    }

    @Test fun draftersOnDisk_keepsOnlyDraftGgufs() {
        val files = listOf(
            "mtp-gemma-4-E2B-it.gguf" to 59_234_176L,     // draft (mtp)
            "Bonsai-27B-dspark-Q4_1.gguf" to 1_787_468_768L, // draft (dspark)
            "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf" to 2_186_184_768L, // primary
            "mmproj-F16.gguf" to 985_654_080L,            // vision
            "notes.txt" to 10L,                           // not gguf
        )
        assertEquals(listOf(files[0], files[1]), draftersOnDisk(files))
    }

    @Test fun configAfterDrafterDelete_prunesMapsAndClearsActiveDraft() {
        val cfg = LlamaConfig(
            draftFile = "mtp-gemma-4-E2B-it.gguf", useDraft = true,
            library = mapOf("mtp-gemma-4-E2B-it.gguf" to "somerepo", "HiFi.gguf" to "repo2"),
            drafters = mapOf("HiFi.gguf" to "mtp-gemma-4-E2B-it.gguf", "Qwen.gguf" to ""),
        )
        val next = configAfterDrafterDelete(cfg, "mtp-gemma-4-E2B-it.gguf")
        assertEquals("", next.draftFile)                                   // active draft cleared
        assertEquals(false, next.useDraft)
        assertEquals(mapOf("Qwen.gguf" to ""), next.drafters)              // HiFi→mtp mapping pruned
        assertEquals(mapOf("HiFi.gguf" to "repo2"), next.library)          // library entry pruned
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.ModelLibraryTest"`
Expected: FAIL — `drafters`, `draftForModel`, `draftersOnDisk`, `configAfterDrafterDelete` unresolved.

- [ ] **Step 3: Add the `drafters` field to `LlamaConfig`**

In `app/src/main/java/com/iguar/armoredllama/server/LlamaConfig.kt`, add as the **last** constructor parameter (after `library`):

```kotlin
    // Per-model drafter choice (INI [drafters] section): modelFile -> draftFile ("" = explicit none).
    val drafters: Map<String, String> = emptyMap(),
```

- [ ] **Step 4: Add drafter logic to `ModelLibrary.kt`**

In `app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt`, add these functions (after `mmprojForRepo`):

```kotlin
/**
 * The drafter file for [modelFile]: an explicit [LlamaConfig.drafters] choice ("" = none) wins; else
 * the curated default's own draft for the curated model; else "". The user owns compatibility.
 */
fun draftForModel(cfg: LlamaConfig, modelFile: String): String {
    cfg.drafters[modelFile]?.let { return it }
    if (modelFile == LlamaConfig().modelFile) return LlamaConfig().draftFile
    return ""
}

/** Filter (fileName, sizeBytes) pairs down to draft/speculative .gguf files (draft/mtp/dspark). */
fun draftersOnDisk(files: List<Pair<String, Long>>): List<Pair<String, Long>> =
    files.filter { (name, _) -> name.endsWith(".gguf", ignoreCase = true) && isDraftFile(name) }

/**
 * The config after deleting drafter [file] from disk: prune it from [LlamaConfig.drafters] values and
 * from [LlamaConfig.library], and clear the active draft if [file] was in use.
 */
fun configAfterDrafterDelete(cfg: LlamaConfig, file: String): LlamaConfig {
    val pruned = cfg.copy(
        drafters = cfg.drafters.filterValues { it != file },
        library = cfg.library - file,
    )
    return if (pruned.draftFile == file) pruned.copy(draftFile = "", useDraft = false) else pruned
}
```

Then update `switchedConfig` to resolve the drafter via `draftForModel` (replace the whole function):

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

- [ ] **Step 5: Run the ModelLibrary tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.ModelLibraryTest"`
Expected: PASS (new cases + the existing `switchedConfig_*`/`companionsOf_*` cases — note `switchedConfig_clearsCompanionsForOtherModels` still passes because an arbitrary model with no `[drafters]` entry resolves to `draftFile = ""`).

- [ ] **Step 6: Write the failing INI round-trip test**

Add to `app/src/test/java/com/iguar/armoredllama/server/IniStoreTest.kt` (before the final `}`):

```kotlin
    @Test fun drafters_roundTripThroughIni() {
        val cfg = LlamaConfig(drafters = mapOf(
            "HiFi.gguf" to "mtp-gemma-4-E2B-it.gguf",
            "Qwen.gguf" to "",
        ))
        val back = llamaConfigFromIni(cfg.toIni())
        assertEquals(cfg.drafters, back.drafters)
    }

    @Test fun drafters_emptyMapWritesNoSection_absentReadsEmpty() {
        val ini = LlamaConfig().toIni()
        assertEquals(false, ini.contains("[drafters]"))
        assertEquals(emptyMap<String, String>(), llamaConfigFromIni(ini).drafters)
    }
```

- [ ] **Step 7: Run to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.IniStoreTest"`
Expected: FAIL — `drafters` not serialized (round-trip returns empty).

- [ ] **Step 8: Serialize `[drafters]` in `IniStore.kt`**

In `app/src/main/java/com/iguar/armoredllama/server/IniStore.kt`, in `toIni()` add a section after `"library"`:

```kotlin
        "library" to LinkedHashMap(library),
        "drafters" to LinkedHashMap(drafters),
```

And in `llamaConfigFromIni(...)` add after `library = ini["library"].orEmpty(),`:

```kotlin
        drafters = ini["drafters"].orEmpty(),
```

- [ ] **Step 9: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/LlamaConfig.kt \
        app/src/main/java/com/iguar/armoredllama/server/IniStore.kt \
        app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt \
        app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt \
        app/src/test/java/com/iguar/armoredllama/server/IniStoreTest.kt
git commit -m "feat(drafters): [drafters] config map + draftForModel precedence + pruning helpers"
```

---

### Task 2: ViewModel — setDrafter, deleteDrafter, drafter list, serverEpoch

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuCallbacks.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/MonitorScreen.kt`

**Interfaces:**
- Produces:
  - `data class DrafterFile(val file: String, val sizeGB: Float)` (model package)
  - `MonitorUiState.serverEpoch: Int`, `MonitorUiState.localDrafters: List<DrafterFile>`
  - `MonitorViewModel.setDrafter(modelFile: String, draftFile: String)`
  - `MonitorViewModel.deleteDrafter(file: String)`
  - `MenuCallbacks.onSetDrafter: (String, String) -> Unit`, `MenuCallbacks.onDeleteDrafter: (String) -> Unit`
- Consumes: `draftForModel`, `draftersOnDisk`, `configAfterDrafterDelete` (Task 1); `localModels`, `pendingRestart`, `LlamaServerService` (existing).

- [ ] **Step 1: Add state types**

In `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt`, add near `ModelEntry` (top-level):

```kotlin
/** A draft/speculative GGUF present on disk, for the drafter chooser + management list. */
data class DrafterFile(val file: String, val sizeGB: Float)
```

Add two fields to `MonitorUiState` (after `serverStatus`):

```kotlin
    val serverEpoch: Int = 0,                    // bumped on each transition into RUNNING (chat reload)
    val localDrafters: List<DrafterFile> = emptyList(),
```

- [ ] **Step 2: Increment `serverEpoch` on RUNNING transition**

In `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt`, add a field near `pendingRestart` (line ~76):

```kotlin
    private var lastStatus = LlamaServerService.Status.STOPPED
```

In the `LlamaServerService.status.collect { s ->` block (line ~137), change the `state = state.copy(...)` assignment to bump the epoch, and record `lastStatus`:

```kotlin
            LlamaServerService.status.collect { s ->
                val stopped = s == LlamaServerService.Status.STOPPED
                val becameRunning = lastStatus != LlamaServerService.Status.RUNNING &&
                    s == LlamaServerService.Status.RUNNING
                lastStatus = s
                state = state.copy(
                    serverStatus = s,
                    running = s == LlamaServerService.Status.RUNNING || s == LlamaServerService.Status.STARTING,
                    metrics = if (stopped) state.metrics.copy(tps = 0f, pp = 0f) else state.metrics,
                    serverEpoch = if (becameRunning) state.serverEpoch + 1 else state.serverEpoch,
                )
                if (stopped && pendingRestart) {
                    pendingRestart = false
                    toggleRunning()
                }
                if (s == LlamaServerService.Status.ERROR) pendingRestart = false
            }
```

- [ ] **Step 3: Add a private drafter-list helper**

In `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt`, add near `localModels` (after it, ~line 681):

```kotlin
    /** Draft files on disk, for the chooser + "Drafters on disk" list (alphabetical). */
    private fun localDrafterList(): List<DrafterFile> {
        val onDisk = configRepo.modelsDir().listFiles().orEmpty()
            .filter { it.isFile }.map { it.name to it.length() }
        return draftersOnDisk(onDisk)
            .map { (name, bytes) -> DrafterFile(name, bytes.toFloat() / (1024f * 1024f * 1024f)) }
            .sortedBy { it.file.lowercase() }
    }
```

Add the import near the other model/server imports at the top of the file:

```kotlin
import com.iguar.armoredllama.model.DrafterFile
import com.iguar.armoredllama.server.draftersOnDisk
import com.iguar.armoredllama.server.configAfterDrafterDelete
```

- [ ] **Step 4: Populate `localDrafters` wherever the local list is (re)built**

In `MonitorViewModel.kt`, at each site that sets `models = localModels(...)`, also set `localDrafters = localDrafterList()`. There are four existing sites — update each `state.copy(...)`:

1. `updateHfQuery` blank branch (`~line 364`):
```kotlin
            state = state.copy(models = localModels(state.metrics.ramFree, state.settings), localDrafters = localDrafterList(), hfLoading = false, hfError = null)
```
2. `updateHfQuery` catch fallback (`~line 400`):
```kotlin
                state = state.copy(
                    models = localModels(state.metrics.ramFree, state.settings),
                    localDrafters = localDrafterList(),
                    hfLoading = false,
                    hfError = "Hugging Face search failed: ${e.message}",
                )
```
3. `switchModel` (`~line 554`) — add `localDrafters = localDrafterList(),` inside its `state.copy(...)`.
4. `deleteModel` (`~line 576`):
```kotlin
        state = state.copy(models = localModels(state.metrics.ramFree, state.settings), localDrafters = localDrafterList())
```

- [ ] **Step 5: Add `setDrafter` and `deleteDrafter`**

In `MonitorViewModel.kt`, add after `deleteModel` (~line 577):

```kotlin
    /** Choose [draftFile] ("" = none) as the drafter for [modelFile]; auto-restart if it's the active model. */
    fun setDrafter(modelFile: String, draftFile: String) {
        val cfg = configRepo.load()
        val activeChange = modelFile == cfg.modelFile
        var next = cfg.copy(drafters = cfg.drafters + (modelFile to draftFile))
        if (activeChange) next = next.copy(draftFile = draftFile, useDraft = draftFile.isNotBlank())
        configRepo.save(next)
        state = state.copy(
            settings = if (activeChange) state.settings.copy(useDraft = next.useDraft) else state.settings,
            models = localModels(state.metrics.ramFree, state.settings),
            localDrafters = localDrafterList(),
        )
        if (activeChange && state.running) {
            LogBus.append("drafter → ${draftFile.ifBlank { "none" }} (restarting)")
            pendingRestart = true
            LlamaServerService.stop(getApplication())
        }
    }

    /** Delete a drafter file; refuse if it's the active drafter while running. */
    fun deleteDrafter(file: String) {
        val cfg = configRepo.load()
        if (file == cfg.draftFile && state.running) {
            LogBus.append("delete refused: $file is the active drafter — stop the server first")
            return
        }
        if (File(configRepo.modelsDir(), file).delete()) {
            LogBus.append("deleted drafter: $file")
            configRepo.save(configAfterDrafterDelete(cfg, file))
        }
        val saved = configRepo.load()
        state = state.copy(
            settings = state.settings.copy(useDraft = saved.useDraft),
            models = localModels(state.metrics.ramFree, state.settings),
            localDrafters = localDrafterList(),
        )
    }
```

- [ ] **Step 6: Add the callbacks**

In `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuCallbacks.kt`, add two fields (after `onDeleteModel`):

```kotlin
    val onSetDrafter: (String, String) -> Unit,
    val onDeleteDrafter: (String) -> Unit,
```

- [ ] **Step 7: Wire them in `MonitorScreen.kt`**

In `app/src/main/java/com/iguar/armoredllama/ui/MonitorScreen.kt`, add to the `MenuCallbacks(...)` construction (after `onDeleteModel = vm::deleteModel,`):

```kotlin
                onSetDrafter = vm::setDrafter,
                onDeleteDrafter = vm::deleteDrafter,
```

- [ ] **Step 8: Build + run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (this task is integration wiring; the pure logic is covered by Task 1). Confirm it compiles (the new `File` reference in `deleteDrafter` reuses the existing `java.io.File` import already present for `deleteModel`).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt \
        app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt \
        app/src/main/java/com/iguar/armoredllama/ui/menu/MenuCallbacks.kt \
        app/src/main/java/com/iguar/armoredllama/ui/MonitorScreen.kt
git commit -m "feat(drafters): ViewModel setDrafter/deleteDrafter + serverEpoch + drafter list"
```

---

### Task 3: Download panel UI — per-model drafter picker + Drafters-on-disk list

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt`

**Interfaces:**
- Consumes: `ModelEntry.draftFile` (already populated by `localModels` via `switchedConfig`), `MonitorUiState.localDrafters`, `DrafterFile`, `callbacks.onSetDrafter(modelFile, draftFile)`, `callbacks.onDeleteDrafter(file)`.

This is a Compose UI change verified on-device (no unit test).

- [ ] **Step 1: Add imports to `SubPanels.kt`**

Add (only those not already present):

```kotlin
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.iguar.armoredllama.model.DrafterFile
```

- [ ] **Step 2: Show the drafter row inside the local model card**

In `SubPanels.kt` `ModelCard`, the `ModelState.ACTIVE`/`INSTALLED` local rows are where drafter selection belongs. Pass the drafter info + callback into `ModelCard`. Update the `HfPanel` call site (the `key(model.file) { ModelCard(...) }` block, ~line 356) to also pass drafter data and render the "Drafters on disk" section after the loop:

```kotlin
                state.visibleModels.forEach { model ->
                    val local = state.hfQuery.isBlank() && model.state == ModelState.INSTALLED
                    key(model.file) {
                        ModelCard(
                            model = model,
                            local = local,
                            drafters = state.localDrafters,
                            onDownloadModel = callbacks.onDownloadModel,
                            onDownloadCompanion = callbacks.onDownloadCompanion,
                            onSwitch = if (local) ({ callbacks.onSwitchModel(model.file) }) else null,
                            onDelete = if (local) ({ callbacks.onDeleteModel(model.file) }) else null,
                            onSetDrafter = if (local || model.state == ModelState.ACTIVE) callbacks.onSetDrafter else null,
                        )
                    }
                }
                if (state.hfQuery.isBlank() && state.localDrafters.isNotEmpty()) {
                    DraftersOnDisk(drafters = state.localDrafters, onDelete = callbacks.onDeleteDrafter)
                }
```

- [ ] **Step 3: Extend `ModelCard` signature and render the Drafter row**

Change `ModelCard`'s signature to accept the drafter list + setter, and render a Drafter row for local/active cards. Add these params to the existing `ModelCard` composable:

```kotlin
    drafters: List<DrafterFile> = emptyList(),
    onSetDrafter: ((String, String) -> Unit)? = null,
```

Inside `ModelCard`, after the header `Row { ... }` (the block that shows name / fit / state button) and before the quant-expansion block, add:

```kotlin
        if (onSetDrafter != null) {
            var pickDrafter by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { pickDrafter = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Drafter: ", style = MonitorType.monoCaption, color = c.muted)
                Text(
                    model.draftFile.ifBlank { "none" }.substringBeforeLast("."),
                    style = MonitorType.monoCaption,
                    color = c.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose drafter", tint = c.accent, modifier = Modifier.size(16.dp))
            }
            if (pickDrafter) {
                DrafterChooser(
                    current = model.draftFile,
                    drafters = drafters,
                    onPick = { chosen -> pickDrafter = false; onSetDrafter(model.file, chosen) },
                    onDismiss = { pickDrafter = false },
                )
            }
        }
```

- [ ] **Step 4: Add the `DrafterChooser` dialog**

Add to `SubPanels.kt`:

```kotlin
@Composable
private fun DrafterChooser(
    current: String,
    drafters: List<DrafterFile>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MonitorTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose drafter") },
        text = {
            Column {
                DrafterChoiceRow(label = "none", selected = current.isBlank(), size = null) { onPick("") }
                drafters.forEach { d ->
                    DrafterChoiceRow(
                        label = d.file.substringBeforeLast("."),
                        selected = current == d.file,
                        size = d.sizeGB,
                    ) { onPick(d.file) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A mismatched drafter won't load — the server will fail to start.",
                    style = MonitorType.monoCaption,
                    color = c.warn,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DrafterChoiceRow(label: String, selected: Boolean, size: Float?, onClick: () -> Unit) {
    val c = MonitorTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A checkmark on the selected row; transparent (but space-reserving) on the others.
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = if (selected) c.good else androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MonitorType.bodyLabel, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (size != null) {
            Spacer(Modifier.width(8.dp))
            Text("%.1f GB".format(size), style = MonitorType.monoCaption, color = c.muted)
        }
    }
}
```

- [ ] **Step 5: Add the `DraftersOnDisk` management section**

Add to `SubPanels.kt`:

```kotlin
@Composable
private fun DraftersOnDisk(drafters: List<DrafterFile>, onDelete: (String) -> Unit) {
    val c = MonitorTheme.colors
    Spacer(Modifier.height(16.dp))
    Text("─ Drafters on disk ─", style = MonitorType.monoCaption, color = c.muted)
    Spacer(Modifier.height(8.dp))
    drafters.forEach { d ->
        key(d.file) {
            var confirm by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚡ ${d.file.substringBeforeLast(".")}", style = MonitorType.monoCaption, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("%.1f GB".format(d.sizeGB), style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${d.file}",
                    tint = c.bad,
                    modifier = Modifier.size(18.dp).clickable { confirm = true },
                )
            }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Delete drafter?") },
                    text = { Text("${d.file} — frees %.1f GB".format(d.sizeGB)) },
                    confirmButton = { TextButton(onClick = { confirm = false; onDelete(d.file) }) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                )
            }
        }
    }
}
```

- [ ] **Step 6: Build + run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (UI change; no new unit tests).

- [ ] **Step 7: On-device verification**

Run `./gradlew installDebug`. In the app → Download model 🤗 (blank search → "On this phone"):
- Each model card shows a `Drafter: <name|none> ▾` row. Tapping opens the chooser with **(none)** + every draft file (sizes shown) and the warning line; the current choice is checked.
- Pick `mtp-gemma-4-E2B-it` for the active Hi-Fi model → server restarts → the log shows `--model-draft …mtp…` and `speculative decoding context initialized`. Change to **(none)** → restarts without `--model-draft`.
- A `─ Drafters on disk ─` section lists the draft files with size + 🗑; deleting an *unused* one removes it from disk and the list (confirm dialog first).

Capture a screenshot of the expanded local card + drafters section.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt
git commit -m "feat(ui): per-model drafter picker + Drafters-on-disk management"
```

---

### Task 4: Chat reload — auto-reload on restart + manual button

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuOverlay.kt` (`PanelHeader`)
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/chat/ChatWebViewHolder.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/chat/ChatPanel.kt`

**Interfaces:**
- Consumes: `MonitorUiState.serverEpoch` (Task 2).
- Produces: `PanelHeader(title, onBack, action)` optional trailing slot; `ChatWebViewHolder.obtain(context, url, epoch)` + `onEpoch(epoch)` + `reload()`.

This is a Compose/WebView change verified on-device.

- [ ] **Step 1: Add an optional trailing action to `PanelHeader`**

In `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuOverlay.kt`, change `PanelHeader` to accept an optional trailing composable:

```kotlin
@Composable
fun PanelHeader(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    val c = MonitorTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
        }
        Spacer(Modifier.width(6.dp))
        Text(title, style = MonitorType.title, color = c.text)
        if (action != null) {
            Spacer(Modifier.weight(1f))
            action()
        }
    }
}
```

(Existing callers pass `(title, onBack)` and keep working — `action` defaults to null.)

- [ ] **Step 2: Make `ChatWebViewHolder` epoch-aware**

Replace `app/src/main/java/com/iguar/armoredllama/ui/chat/ChatWebViewHolder.kt`'s `obtain` + add `onEpoch`/`reload`. Add a field and change `obtain` to take an epoch, seeding `loadedEpoch` on a fresh URL load; add `onEpoch` (called from AndroidView `update`) and a public `reload`:

```kotlin
    private var webView: WebView? = null
    private var loadedUrl: String? = null
    private var loadedEpoch = 0

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun obtain(context: Context, url: String, epoch: Int): WebView {
        val view = webView ?: WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            webView = this
        }
        if (loadedUrl != url) {
            loadedUrl = url
            loadedEpoch = epoch      // seed: a fresh page is already current for this epoch
            loadWhenSized(view, url)
        }
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    /** Reload if the server has (re)started since the page loaded — keeps the UI on the fresh backend. */
    fun onEpoch(epoch: Int) {
        if (epoch != loadedEpoch) {
            loadedEpoch = epoch
            webView?.reload()
        }
    }

    /** Manual reload (Chat header button). */
    fun reload() {
        webView?.reload()
    }
```

Keep `loadWhenSized` and `destroy` as-is (in `destroy`, also reset `loadedEpoch = 0` for cleanliness):

```kotlin
    fun destroy() {
        webView?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        webView = null
        loadedUrl = null
        loadedEpoch = 0
    }
```

- [ ] **Step 3: Wire epoch + reload button in `ChatPanel`**

Replace `app/src/main/java/com/iguar/armoredllama/ui/chat/ChatPanel.kt` body:

```kotlin
package com.iguar.armoredllama.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.iguar.armoredllama.model.MonitorUiState
import com.iguar.armoredllama.ui.menu.PanelHeader
import com.iguar.armoredllama.ui.theme.MonitorTheme

@Composable
fun ChatPanel(state: MonitorUiState, onBack: () -> Unit) {
    val c = MonitorTheme.colors
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        PanelHeader("Chat", onBack) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Reload chat",
                tint = c.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { ChatWebViewHolder.reload() }
                    .size(28.dp),
            )
        }
        AndroidView(
            factory = { ctx -> ChatWebViewHolder.obtain(ctx, chatUrl(state.settings.port), state.serverEpoch) },
            update = { ChatWebViewHolder.onEpoch(state.serverEpoch) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
```

- [ ] **Step 4: Build + run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: On-device verification**

Run `./gradlew installDebug`. Start the server, open Chat and send a message (confirm it replies). Then:
- With Chat open, trigger a restart (switch models, or change the active model's drafter) → the WebView reloads on its own once the server is RUNNING again, and a new message reaches the fresh backend (no "stuck").
- Back out of Chat, restart the server, reopen Chat → it's reloaded (not stale).
- Tap the **↻** button in the Chat header → the page reloads.

Capture a screenshot of the Chat header showing the ↻ button.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/ui/menu/MenuOverlay.kt \
        app/src/main/java/com/iguar/armoredllama/ui/chat/ChatWebViewHolder.kt \
        app/src/main/java/com/iguar/armoredllama/ui/chat/ChatPanel.kt
git commit -m "fix(chat): reload WebView on server restart (serverEpoch) + manual reload button"
```

---

## Notes for the implementer

- Run tests from the repo root: `./gradlew testDebugUnitTest` (Git Bash on Windows). Use `--tests "<ClassFQN>"` to scope while iterating.
- `./gradlew installDebug` kills the app process (and the foreground server). After reinstalling, tap **Start** and wait ~15 s for the model to load before testing Chat/drafter-restart flows.
- The device is a OnePlus 8T (1080×2400). Screenshot with `adb exec-out screencap -p > shot.png`; prefix adb commands touching `/sdcard` with `MSYS2_ARG_CONV_EXCL='*'` in Git Bash. The app's server log is at `/sdcard/Android/data/com.iguar.armoredllama/files/logs/server.log`.
- `MonitorType.warn`/`c.warn`, `c.good`, `c.bad`, `c.accent`, `c.muted`, `c.text` are existing theme colors used across `SubPanels.kt`.
