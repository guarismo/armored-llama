# Quant Picker for Model Download — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick any GGUF quant within a Hugging Face repo (headlining the best-fitting one) and download auto-wired vision/draft companions, instead of the search collapsing each repo to one file.

**Architecture:** Parsing (`HfModels`) becomes pure and returns *all* primary quants + companions per repo from the single tree fetch it already makes. The ViewModel computes per-quant RAM fit, picks a headline (largest that fits), and populates an expandable `ModelEntry`. Companions are persisted by extending the existing `[library]` file→repo map (no new config field) and derived by repo when switching/downloading. The UI adds an expand toggle with per-quant and per-companion Get buttons.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.coroutines, org.json, JUnit4 (host-JVM unit tests via `./gradlew testDebugUnitTest`). Android app package `com.iguar.armoredllama`, targetSdk 28, CPU-only llama.cpp.

## Global Constraints

- Parsing stays **pure**: `HfModels` never reads free-RAM or computes fit; fit lives in the ViewModel.
- **No new config field.** Companion persistence reuses `LlamaConfig.library: Map<String,String>` (file → repo) and its existing `[library]` INI serialization.
- **Headline quant** = the largest quant whose `ModelFit.level` is `FITS` or `TIGHT`; if none qualify, the smallest quant (by `sizeGB`).
- **Expanded quant rows** are ordered **smallest → largest** by `sizeGB`.
- Companions (`mmproj`=VISION; `draft`/`mtp`/`dspark`=DRAFT) show **size only, no fit badge**. Companion-token detection is single-source in `ModelLibrary.kt` via `isVisionFile`/`isDraftFile`, with `isCompanionFile = isVisionFile || isDraftFile`. (`dspark` is Bonsai's DSpark speculative drafter — confirmed a draft model.)
- Downloading a companion does **not** auto-restart the server (unlike a model switch); it logs `restart to apply <vision|draft>` when it wires into the active model.
- Deleting a primary does **not** cascade-delete shared companions (`companionsOf` unchanged).
- The local "On this phone" list is unchanged — installed files stay one row each (empty `quants`/`companions`).
- Existing quant label for the curated default (`…-Q2_K_XL.gguf`) becomes `Q2_K_XL` once `quantFrom` is consolidated (the extended list recognizes it).

---

### Task 1: Parse every quant + companions; consolidate `quantFrom` (pure)

Make `HfModels` return all primary quants and companions per repo, add the shared extended `quantFrom`, and wire the search panel to headline the best-fitting quant.

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt` (add shared `quantFrom`)
- Modify: `app/src/main/java/com/iguar/armoredllama/server/HfModels.kt` (new result types, `parseRepo`, `searchRepos`; remove `parseCandidate`/`search`/`HfModelCandidate`/`fileRank`/private `quantFrom`)
- Modify: `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt` (`QuantOption`/`CompanionOption`, `ModelEntry.quants`/`companions`, `pickHeadline`)
- Modify: `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt` (rewrite `updateHfQuery` mapping; delete private `quantFrom`)
- Test: `app/src/test/java/com/iguar/armoredllama/server/HfModelsTest.kt` (replace with `parseRepo` tests)
- Test: `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt` (add `quantFrom` cases)
- Test: `app/src/test/java/com/iguar/armoredllama/model/HeadlineTest.kt` (new — `pickHeadline`)

**Interfaces:**
- Produces (server package):
  - `enum class CompanionKind { VISION, DRAFT }`
  - `data class QuantFile(val file: String, val quant: String, val sizeGB: Float)`
  - `data class CompanionFile(val file: String, val kind: CompanionKind, val quant: String, val sizeGB: Float)`
  - `data class HfRepoResult(val repo: String, val name: String, val quants: List<QuantFile>, val companions: List<CompanionFile>)`
  - `internal fun HfModels.parseRepo(repo: String, treeJson: String): HfRepoResult?`
  - `suspend fun HfModels.searchRepos(query: String, limit: Int = 8): List<HfRepoResult>`
  - `fun quantFrom(name: String): String` (top-level in `ModelLibrary.kt`)
- Produces (model package):
  - `data class QuantOption(val file: String, val quant: String, val sizeGB: Float, val fit: ModelFit)`
  - `data class CompanionOption(val file: String, val kind: CompanionKind, val quant: String, val sizeGB: Float)`
  - `ModelEntry.quants: List<QuantOption>`, `ModelEntry.companions: List<CompanionOption>` (default `emptyList()`)
  - `fun pickHeadline(quants: List<QuantOption>): QuantOption?`
- Consumes: `estimateModelFit(...)` and `ModelFit`/`ModelFitLevel` (unchanged), `isCompanionFile(name)` (unchanged).

- [ ] **Step 1: Write the failing `quantFrom` tests**

Add to `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt`:

```kotlin
    @Test fun quantFrom_recognizesLowBitAndKQuants() {
        assertEquals("Q1_0", quantFrom("Bonsai-27B-Q1_0.gguf"))
        assertEquals("IQ2_XXS", quantFrom("model-IQ2_XXS.gguf"))
        assertEquals("IQ1_S", quantFrom("model-IQ1_S.gguf"))
        assertEquals("Q2_K_S", quantFrom("model-Q2_K_S.gguf"))
        assertEquals("Q2_K_XL", quantFrom("gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf"))
        assertEquals("Q4_K_M", quantFrom("model-Q4_K_M.gguf"))
        assertEquals("BF16", quantFrom("model-mmproj-BF16.gguf"))
        assertEquals("GGUF", quantFrom("model-unknownquant.gguf"))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.ModelLibraryTest"`
Expected: FAIL — `quantFrom` is unresolved (currently private inside `HfModels`).

- [ ] **Step 3: Add the shared `quantFrom` to `ModelLibrary.kt`**

Append to `app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt`:

```kotlin
/**
 * The quant label from a GGUF filename. Longest/most-specific tokens first so `Q4_K_M` wins over
 * a bare `Q4` and `Q2_K_XL` over `Q2_K`. Unrecognized → "GGUF".
 */
fun quantFrom(name: String): String {
    val upper = name.uppercase()
    return QUANT_TOKENS.firstOrNull { it in upper } ?: "GGUF"
}

private val QUANT_TOKENS = listOf(
    "IQ4_XS", "IQ4_NL", "IQ3_XXS", "IQ3_XS", "IQ3_M", "IQ3_S",
    "IQ2_XXS", "IQ2_XS", "IQ2_M", "IQ2_S", "IQ1_M", "IQ1_S",
    "Q5_K_M", "Q5_K_S", "Q4_K_M", "Q4_K_S", "Q3_K_L", "Q3_K_M", "Q3_K_S",
    "Q2_K_XL", "Q2_K_S", "Q2_K",
    "Q8_0", "Q6_K", "Q5_1", "Q5_0", "Q4_1", "Q4_0", "Q1_0",
    "BF16", "FP16", "F16", "F32",
)
```

- [ ] **Step 4: Remove the private `quantFrom` from `HfModels.kt`**

Delete the private `quantFrom` (lines ~110-114) in `app/src/main/java/com/iguar/armoredllama/server/HfModels.kt`. Same-package call sites resolve to the new top-level one. (The old `fileRank`/`parseCandidate` will be removed in Step 8.)

- [ ] **Step 5: Remove the private `quantFrom` from `MonitorViewModel.kt` and import the shared one**

In `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt`, delete the private `quantFrom` (lines ~626-631). Add the import near the other server imports:

```kotlin
import com.iguar.armoredllama.server.quantFrom
```

- [ ] **Step 6: Run the `quantFrom` tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.ModelLibraryTest"`
Expected: PASS (all cases, including the existing `switchedConfig`/`primaryModels` tests).

- [ ] **Step 7: Write the failing `parseRepo` tests**

Replace the body of `app/src/test/java/com/iguar/armoredllama/server/HfModelsTest.kt` with:

```kotlin
package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfModelsTest {

    private val bonsai = """
        [
          {"type":"file","path":"Bonsai-27B-F16.gguf","size":53808280640},
          {"type":"file","path":"Bonsai-27B-Q1_0.gguf","size":3803452480},
          {"type":"file","path":"Bonsai-27B-dspark-Q4_1.gguf","size":1787468768},
          {"type":"file","path":"Bonsai-27B-dspark-bf16.gguf","size":7291885792},
          {"type":"file","path":"Bonsai-27B-mmproj-BF16.gguf","size":931145760},
          {"type":"file","path":"Bonsai-27B-mmproj-Q8_0.gguf","size":629246880},
          {"type":"file","path":"README.md","size":21877}
        ]
    """.trimIndent()

    @Test fun parseRepo_returnsAllPrimaryQuantsSmallestFirst() {
        val r = HfModels.parseRepo("prism-ml/Bonsai-27B-gguf", bonsai)!!

        assertEquals(listOf("Bonsai-27B-Q1_0.gguf", "Bonsai-27B-F16.gguf"), r.quants.map { it.file })
        assertEquals("Q1_0", r.quants[0].quant)
        assertEquals(3803452480f / (1024f * 1024f * 1024f), r.quants[0].sizeGB, 0.01f)
    }

    @Test fun parseRepo_classifiesCompanionsAndKeepsThemOutOfQuants() {
        val r = HfModels.parseRepo("prism-ml/Bonsai-27B-gguf", bonsai)!!

        assertTrue(r.quants.none { isCompanionFile(it.file) })
        val vision = r.companions.filter { it.kind == CompanionKind.VISION }.map { it.file }
        val draft = r.companions.filter { it.kind == CompanionKind.DRAFT }.map { it.file }
        assertEquals(listOf("Bonsai-27B-mmproj-Q8_0.gguf", "Bonsai-27B-mmproj-BF16.gguf"), vision)
        assertEquals(listOf("Bonsai-27B-dspark-Q4_1.gguf", "Bonsai-27B-dspark-bf16.gguf"), draft)
    }

    @Test fun parseRepo_singleGgufHasOneQuantNoCompanions() {
        val tree = """[{"type":"file","path":"model-Q4_K_M.gguf","size":2000000000}]"""

        val r = HfModels.parseRepo("acme/model-GGUF", tree)!!

        assertEquals(1, r.quants.size)
        assertEquals("model-Q4_K_M.gguf", r.quants[0].file)
        assertTrue(r.companions.isEmpty())
    }

    @Test fun parseRepo_nullWhenNoPrimaryQuant() {
        val tree = """
            [
              {"type":"file","path":"mmproj-F16.gguf","size":500000000},
              {"type":"file","path":"README.md","size":10}
            ]
        """.trimIndent()

        assertNull(HfModels.parseRepo("acme/x", tree))
    }
}
```

- [ ] **Step 8: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.HfModelsTest"`
Expected: FAIL — `parseRepo`, `HfRepoResult`, `CompanionKind` unresolved.

- [ ] **Step 9: Rewrite `HfModels.kt` to the repo-result API**

Replace `app/src/main/java/com/iguar/armoredllama/server/HfModels.kt` with:

```kotlin
package com.iguar.armoredllama.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

enum class CompanionKind { VISION, DRAFT }

data class QuantFile(val file: String, val quant: String, val sizeGB: Float)
data class CompanionFile(val file: String, val kind: CompanionKind, val quant: String, val sizeGB: Float)

/** One Hugging Face repo's downloadable GGUFs: primary quants (smallest→largest) and companions. */
data class HfRepoResult(
    val repo: String,
    val name: String,
    val quants: List<QuantFile>,
    val companions: List<CompanionFile>,
)

object HfModels {
    private const val MAX_CONCURRENT_RESOLVES = 5

    suspend fun searchRepos(query: String, limit: Int = 8): List<HfRepoResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim().ifBlank { "GGUF" }, "UTF-8")
        val url = "https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=${limit * 2}"
        val repos = getJsonArray(url)
            .mapNotNull { it.optString("id").ifBlank { it.optString("modelId") }.ifBlank { null } }
            .distinct()
            .take(limit * 2)
        // Resolve repos concurrently (bounded); each repoResult() is a blocking round-trip. awaitAll
        // preserves the download-rank order.
        val gate = Semaphore(MAX_CONCURRENT_RESOLVES)
        val resolved = coroutineScope {
            repos.map { repo ->
                async { gate.withPermit { runCatching { repoResult(repo) }.getOrNull() } }
            }.awaitAll()
        }
        resolved.filterNotNull().take(limit)
    }

    private fun repoResult(repo: String): HfRepoResult? {
        // The tree endpoint carries per-file `size` for every GGUF in one fetch.
        val tree = get("https://huggingface.co/api/models/${repo.encodePath()}/tree/main?recursive=true")
        return parseRepo(repo, tree)
    }

    /** Pure: split a HF /tree/main payload into primary quants + companions. Null if no primary GGUF. */
    internal fun parseRepo(repo: String, treeJson: String): HfRepoResult? {
        val entries = JSONArray(treeJson)
        val ggufs = (0 until entries.length())
            .mapNotNull { entries.optJSONObject(it)?.toHfFile() }
            .filter { it.name.endsWith(".gguf", ignoreCase = true) }
        fun gb(f: HfFile): Float = f.sizeBytes?.let { it.toFloat() / (1024f * 1024f * 1024f) } ?: 0f

        val quants = ggufs.filterNot { isCompanionFile(it.name) }
            .map { QuantFile(it.name, quantFrom(it.name), gb(it)) }
            .sortedBy { it.sizeGB }
        if (quants.isEmpty()) return null

        val companions = ggufs.filter { isCompanionFile(it.name) }
            .map { f ->
                val kind = if (isVisionFile(f.name)) CompanionKind.VISION else CompanionKind.DRAFT
                CompanionFile(f.name, kind, quantFrom(f.name), gb(f))
            }
            .sortedBy { it.sizeGB }

        return HfRepoResult(repo = repo, name = repo.substringAfterLast('/'), quants = quants, companions = companions)
    }

    private fun getJsonArray(url: String): List<JSONObject> {
        val arr = JSONArray(get(url))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "ArmoredLlama")
            setRequestProperty("Accept", "application/json")
            connect()
        }
        try {
            if (conn.responseCode != 200) error("Hugging Face returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun String.encodePath(): String =
        split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    private data class HfFile(val name: String, val sizeBytes: Long?)

    private fun JSONObject.toHfFile(): HfFile? {
        val name = optString("path").ifBlank { return null }
        val size = optLong("size", 0L).takeIf { it > 0L }
        return HfFile(name, size)
    }
}
```

- [ ] **Step 10: Run the `parseRepo` tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.HfModelsTest"`
Expected: PASS (all four).

- [ ] **Step 11: Write the failing `pickHeadline` test**

Create `app/src/test/java/com/iguar/armoredllama/model/HeadlineTest.kt`:

```kotlin
package com.iguar.armoredllama.model

import com.iguar.armoredllama.server.ModelFit
import com.iguar.armoredllama.server.ModelFitLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadlineTest {

    private fun q(file: String, sizeGB: Float, level: ModelFitLevel) =
        QuantOption(file, "Q", sizeGB, ModelFit(level, sizeGB, 8f))

    @Test fun pickHeadline_largestThatFits() {
        val quants = listOf(
            q("q1", 3f, ModelFitLevel.FITS),
            q("q2", 5f, ModelFitLevel.TIGHT),
            q("q3", 50f, ModelFitLevel.TOO_LARGE),
        )
        assertEquals("q2", pickHeadline(quants)!!.file)
    }

    @Test fun pickHeadline_fallsBackToSmallestWhenNoneFit() {
        val quants = listOf(
            q("big", 50f, ModelFitLevel.TOO_LARGE),
            q("small", 20f, ModelFitLevel.TOO_LARGE),
        )
        assertEquals("small", pickHeadline(quants)!!.file)
    }

    @Test fun pickHeadline_nullOnEmpty() {
        assertNull(pickHeadline(emptyList()))
    }
}
```

- [ ] **Step 12: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.model.HeadlineTest"`
Expected: FAIL — `QuantOption`, `pickHeadline` unresolved.

- [ ] **Step 13: Add `QuantOption`/`CompanionOption`, `ModelEntry` fields, and `pickHeadline`**

In `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt`, add the import:

```kotlin
import com.iguar.armoredllama.server.CompanionKind
```

Add these data classes and helper near `ModelEntry` (top-level in the file):

```kotlin
/** One downloadable quant of a repo, with its per-file RAM fit. */
data class QuantOption(val file: String, val quant: String, val sizeGB: Float, val fit: ModelFit)

/** One downloadable companion (vision/draft) of a repo. Size only — fit is about the primary. */
data class CompanionOption(val file: String, val kind: CompanionKind, val quant: String, val sizeGB: Float)

/**
 * The recommended quant to headline: the largest whose fit is FITS/TIGHT, else the smallest.
 * Null when [quants] is empty.
 */
fun pickHeadline(quants: List<QuantOption>): QuantOption? =
    quants.filter { it.fit.level == ModelFitLevel.FITS || it.fit.level == ModelFitLevel.TIGHT }
        .maxByOrNull { it.sizeGB }
        ?: quants.minByOrNull { it.sizeGB }
```

Add the import for `ModelFitLevel` at the top if not present:

```kotlin
import com.iguar.armoredllama.server.ModelFitLevel
```

Add two fields to `ModelEntry` (after `freedGB`):

```kotlin
    val quants: List<QuantOption> = emptyList(),      // repo's quants (empty for local rows)
    val companions: List<CompanionOption> = emptyList(),
```

- [ ] **Step 14: Run the `pickHeadline` test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.model.HeadlineTest"`
Expected: PASS.

- [ ] **Step 15: Rewrite `updateHfQuery`'s search mapping to headline the best-fit quant**

In `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt`, replace the search branch of `updateHfQuery` (the `try` block that calls `HfModels.search(q)`) with the mapping below. Keep the surrounding `delay(350)`, `hfLoading`, and `catch` unchanged.

```kotlin
                val freeRamMB = state.metrics.ramFree
                val settings = state.settings
                val results = HfModels.searchRepos(q).map { repo ->
                    val quantOptions = repo.quants.map { qf ->
                        QuantOption(
                            file = qf.file,
                            quant = qf.quant,
                            sizeGB = qf.sizeGB,
                            fit = estimateModelFit(
                                modelSizeGB = qf.sizeGB,
                                freeRamMB = freeRamMB,
                                ctx = settings.ctx,
                                cacheTypeK = settings.cacheTypeK,
                                cacheTypeV = settings.cacheTypeV,
                                flashAttn = settings.flashAttn,
                            ),
                        )
                    }
                    val companionOptions = repo.companions.map {
                        CompanionOption(it.file, it.kind, it.quant, it.sizeGB)
                    }
                    val headline = pickHeadline(quantOptions) ?: quantOptions.first()
                    val installed = downloader.localSize(headline.file) > 0L
                    ModelEntry(
                        id = repo.repo,
                        repo = repo.repo,
                        name = repo.name,
                        file = headline.file,
                        quant = headline.quant,
                        sizeGB = headline.sizeGB,
                        fit = headline.fit,
                        state = if (installed) ModelState.INSTALLED else ModelState.IDLE,
                        quants = quantOptions,
                        companions = companionOptions,
                    )
                }.sortedWith(compareBy<ModelEntry> { fitRank(it.fit.level) }.thenBy { it.sizeGB })
                state = state.copy(
                    models = results,
                    hfLoading = false,
                    hfError = if (results.isEmpty()) "No GGUF models found." else null,
                )
```

Add the model-package imports near the top of `MonitorViewModel.kt` if not already present:

```kotlin
import com.iguar.armoredllama.model.QuantOption
import com.iguar.armoredllama.model.CompanionOption
import com.iguar.armoredllama.model.pickHeadline
```

- [ ] **Step 16: Build to verify the app compiles and run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (no reference to the removed `HfModels.search`/`parseCandidate`/`HfModelCandidate`/`fileRank` remains — grep to confirm: `git grep -n "parseCandidate\|HfModelCandidate\|fileRank"` returns nothing).

- [ ] **Step 17: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/HfModels.kt \
        app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt \
        app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt \
        app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt \
        app/src/test/java/com/iguar/armoredllama/server/HfModelsTest.kt \
        app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt \
        app/src/test/java/com/iguar/armoredllama/model/HeadlineTest.kt
git commit -m "feat(hf): parse every quant + companions per repo; headline best-fit quant"
```

---

### Task 2: Derive companions by repo from `[library]` (pure)

Teach `ModelLibrary` to find a repo's downloaded vision/draft companions, and have `switchedConfig` wire them for arbitrary models.

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt`
- Test: `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt`

**Interfaces:**
- Produces: `fun companionFilesForRepo(library: Map<String, String>, repo: String, self: String): Pair<String, String>` returning `(draftFile, mmprojFile)` ("" when absent).
- Consumes: `LlamaConfig.library` (unchanged shape), `isCompanionFile` (unchanged).
- Changes: `switchedConfig` non-default branch now derives companions by repo (curated-default branch unchanged).

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt`:

```kotlin
    @Test fun companionFilesForRepo_findsDraftAndMmprojExcludingSelf() {
        val library = mapOf(
            "Bonsai-27B-Q1_0.gguf" to "prism-ml/Bonsai-27B-gguf",
            "Bonsai-27B-mmproj-Q8_0.gguf" to "prism-ml/Bonsai-27B-gguf",
            "Bonsai-27B-dspark-Q4_1.gguf" to "prism-ml/Bonsai-27B-gguf",
            "Other-Q4_K_M.gguf" to "acme/other-GGUF",
        )

        val (draft, mmproj) = companionFilesForRepo(library, "prism-ml/Bonsai-27B-gguf", "Bonsai-27B-Q1_0.gguf")

        assertEquals("Bonsai-27B-dspark-Q4_1.gguf", draft)
        assertEquals("Bonsai-27B-mmproj-Q8_0.gguf", mmproj)
    }

    @Test fun companionFilesForRepo_blankWhenRepoBlankOrNoCompanions() {
        assertEquals("" to "", companionFilesForRepo(emptyMap(), "", "x.gguf"))
        val lib = mapOf("Other-Q4_K_M.gguf" to "acme/other-GGUF")
        assertEquals("" to "", companionFilesForRepo(lib, "acme/other-GGUF", "Other-Q4_K_M.gguf"))
    }

    @Test fun switchedConfig_wiresCompanionsFromLibraryByRepo() {
        val cfg = LlamaConfig(
            library = mapOf(
                "Bonsai-27B-Q1_0.gguf" to "prism-ml/Bonsai-27B-gguf",
                "Bonsai-27B-mmproj-Q8_0.gguf" to "prism-ml/Bonsai-27B-gguf",
            ),
        )

        val next = switchedConfig(cfg, "Bonsai-27B-Q1_0.gguf")

        assertEquals("prism-ml/Bonsai-27B-gguf", next.repo)
        assertEquals("Bonsai-27B-Q1_0.gguf", next.modelFile)
        assertEquals("", next.draftFile)
        assertEquals("Bonsai-27B-mmproj-Q8_0.gguf", next.mmprojFile)
        assertEquals(false, next.useDraft)
        assertEquals(true, next.useMmproj)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.ModelLibraryTest"`
Expected: FAIL — `companionFilesForRepo` unresolved; `switchedConfig_wiresCompanionsFromLibraryByRepo` fails (mmproj is currently cleared).

- [ ] **Step 3: Add `companionFilesForRepo` and update `switchedConfig`**

In `app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt`, add:

```kotlin
/**
 * The (draft, mmproj) companion files recorded in [library] for [repo], excluding [self].
 * Blank strings when [repo] is blank or the repo has no companion of that kind. When a repo has
 * several of one kind, the first (map-insertion order) is used.
 */
fun companionFilesForRepo(library: Map<String, String>, repo: String, self: String): Pair<String, String> {
    if (repo.isBlank()) return "" to ""
    val siblings = library.filterValues { it == repo }.keys.filter { it != self && isCompanionFile(it) }
    val draft = siblings.firstOrNull { isDraftFile(it) } ?: ""
    val mmproj = siblings.firstOrNull { isVisionFile(it) } ?: ""
    return draft to mmproj
}
```

Replace the non-default branch of `switchedConfig` (the `else` that currently clears companions) so the whole function reads:

```kotlin
fun switchedConfig(cfg: LlamaConfig, file: String): LlamaConfig {
    val d = LlamaConfig()
    val next = if (file == d.modelFile) {
        cfg.copy(repo = d.repo, modelFile = d.modelFile, draftFile = d.draftFile, mmprojFile = d.mmprojFile)
    } else {
        val repo = cfg.library[file] ?: ""
        val (draft, mmproj) = companionFilesForRepo(cfg.library, repo, file)
        cfg.copy(repo = repo, modelFile = file, draftFile = draft, mmprojFile = mmproj)
    }
    return next.copy(useDraft = next.draftFile.isNotBlank(), useMmproj = next.mmprojFile.isNotBlank())
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.iguar.armoredllama.server.ModelLibraryTest"`
Expected: PASS (new cases plus the existing `switchedConfig_clearsCompanionsForOtherModels`, which still passes because an empty/companion-less library yields `"" to ""`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/ModelLibrary.kt \
        app/src/test/java/com/iguar/armoredllama/server/ModelLibraryTest.kt
git commit -m "feat(library): derive vision/draft companions by repo for model switching"
```

---

### Task 3: Download by repo+file; companion-aware primary download; `downloadCompanion`

Change the download entry points so a specific file can be fetched, wire companions into config, and add companion downloads. UI still shows only the headline card (expandable rows come in Task 4).

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuCallbacks.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/MonitorScreen.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt` (headline Get call only)
- Modify: `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt` (`ModelEntry.downloadingFile`)

**Interfaces:**
- Produces:
  - `MenuCallbacks.onDownloadModel: (repo: String, file: String) -> Unit`
  - `MenuCallbacks.onDownloadCompanion: (repo: String, file: String, kind: CompanionKind) -> Unit`
  - `MonitorViewModel.downloadModel(repo: String, file: String)`
  - `MonitorViewModel.downloadCompanion(repo: String, file: String, kind: CompanionKind)`
  - `ModelEntry.downloadingFile: String?` (the file the DOWNLOADING state/progress refers to; null = headline `file`)
- Consumes: `companionFilesForRepo(...)` (Task 2), `downloader.download/localSize` (unchanged), `LlamaConfig` defaults.

- [ ] **Step 1: Add `downloadingFile` to `ModelEntry`**

In `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt`, add to `ModelEntry` (after `progress`):

```kotlin
    val downloadingFile: String? = null, // which file the DOWNLOADING state/progress refers to (null = headline)
```

- [ ] **Step 2: Change the callback signatures**

Replace `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuCallbacks.kt` with:

```kotlin
package com.iguar.armoredllama.ui.menu

import com.iguar.armoredllama.model.ServerSettings
import com.iguar.armoredllama.server.CompanionKind

/** The actions the menu sub-panels can invoke, forwarded to the ViewModel. */
data class MenuCallbacks(
    val onUpdateSettings: ((ServerSettings) -> ServerSettings) -> Unit,
    val onCheckUpdate: () -> Unit,
    val onDownloadUpdate: () -> Unit,
    val onRemoveDownloadedUpdate: () -> Unit,
    val onUpdateHfQuery: (String) -> Unit,
    val onDownloadModel: (String, String) -> Unit,
    val onDownloadCompanion: (String, String, CompanionKind) -> Unit,
    val onSwitchModel: (String) -> Unit,
    val onDeleteModel: (String) -> Unit,
)
```

- [ ] **Step 3: Rewrite `downloadModel` and add `downloadCompanion` in the ViewModel**

In `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt`, replace the existing `fun downloadModel(id: String)` with the two functions below. Add the imports `com.iguar.armoredllama.server.CompanionKind` and `com.iguar.armoredllama.server.companionFilesForRepo` near the other server imports.

```kotlin
    fun downloadModel(repo: String, file: String) {
        val entry = state.models.firstOrNull { it.id == repo }
        if (entry?.downloadingFile != null) return
        val d = LlamaConfig()
        // Curated default brings its bundled companions; any other repo derives them from [library].
        val (draft, mmproj) = if (repo == d.repo && file == d.modelFile) {
            d.draftFile to d.mmprojFile
        } else {
            val prevLib = configRepo.load().library
            companionFilesForRepo(prevLib, repo, file)
        }
        val prev = configRepo.load()
        val config = prev.copy(
            repo = repo,
            modelFile = file,
            draftFile = draft,
            mmprojFile = mmproj,
            useDraft = draft.isNotBlank(),
            useMmproj = mmproj.isNotBlank(),
            library = prev.library + (file to repo),
        )
        configRepo.save(config)
        state = state.copy(
            modelFile = file.substringBeforeLast(".").takeUnless { it.isBlank() } ?: file,
            settings = state.settings.copy(useDraft = config.useDraft, useMmproj = config.useMmproj),
        )
        // Only fetch files not already on disk (curated companions download; derived ones are present).
        val files = listOf(config.modelFile, config.draftFile, config.mmprojFile)
            .filter { it.isNotBlank() && downloader.localSize(it) <= 0L }
        val fit = entry?.quants?.firstOrNull { it.file == file }?.fit ?: entry?.fit
        if (fit != null && (fit.level == ModelFitLevel.TIGHT || fit.level == ModelFitLevel.TOO_LARGE)) {
            LogBus.append("RAM warning before download: ${fit.label.lowercase()} for $file; ${fit.detail}")
        }
        viewModelScope.launch {
            if (files.isEmpty()) {
                updateModel(repo) { it.copy(state = ModelState.INSTALLED, downloadingFile = null, progress = 1f) }
                return@launch
            }
            updateModel(repo) { it.copy(state = ModelState.DOWNLOADING, downloadingFile = file, progress = 0f) }
            try {
                files.forEachIndexed { idx, f ->
                    downloader.download(config.repo, f) { written, total ->
                        val frac = if (total > 0) written.toFloat() / total else 0f
                        val overall = (idx + frac) / files.size
                        updateModel(repo) { it.copy(progress = overall.coerceIn(0f, 1f)) }
                    }
                }
                updateModel(repo) { it.copy(state = ModelState.INSTALLED, downloadingFile = null, progress = 1f) }
                LogBus.append("download complete: ${config.repo}")
            } catch (e: Exception) {
                updateModel(repo) { it.copy(state = ModelState.IDLE, downloadingFile = null) }
                LogBus.append("download failed: ${e.message}")
            }
        }
    }

    // (`entry` above is `state.models.firstOrNull { it.id == repo }`, reused for the fit warning.)

    /** Download a vision/draft companion and record it in [library]; wire it if its repo is active. */
    fun downloadCompanion(repo: String, file: String, kind: CompanionKind) {
        val entry = state.models.firstOrNull { it.id == repo }
        if (entry?.downloadingFile != null) return
        viewModelScope.launch {
            updateModel(repo) { it.copy(downloadingFile = file, progress = 0f) }
            try {
                downloader.download(repo, file) { written, total ->
                    val frac = if (total > 0) written.toFloat() / total else 0f
                    updateModel(repo) { it.copy(progress = frac.coerceIn(0f, 1f)) }
                }
                val cfg = configRepo.load()
                var next = cfg.copy(library = cfg.library + (file to repo))
                // If this companion belongs to the currently active model's repo, wire it for next launch.
                if (repo == cfg.repo) {
                    next = when (kind) {
                        CompanionKind.VISION -> next.copy(mmprojFile = file, useMmproj = true)
                        CompanionKind.DRAFT -> next.copy(draftFile = file, useDraft = true)
                    }
                    LogBus.append("restart to apply ${if (kind == CompanionKind.VISION) "vision" else "draft"}")
                }
                configRepo.save(next)
                updateModel(repo) { it.copy(downloadingFile = null, progress = 1f) }
                if (repo == cfg.repo) {
                    state = state.copy(settings = state.settings.copy(useDraft = next.useDraft, useMmproj = next.useMmproj))
                }
                LogBus.append("companion downloaded: $file")
            } catch (e: Exception) {
                updateModel(repo) { it.copy(downloadingFile = null) }
                LogBus.append("companion download failed: ${e.message}")
            }
        }
    }
```

- [ ] **Step 4: Update the headline Get call in `SubPanels.kt`**

In `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt`, change the `ModelCard` invocation in `HfPanel` so the headline Get passes repo+file:

```kotlin
                    key(model.file) {
                        ModelCard(
                            model = model,
                            onGet = { callbacks.onDownloadModel(model.repo, model.file) },
                            onSwitch = if (local) ({ callbacks.onSwitchModel(model.file) }) else null,
                            onDelete = if (local) ({ callbacks.onDeleteModel(model.file) }) else null,
                        )
                    }
```

- [ ] **Step 5: Wire the new callbacks in `MonitorScreen.kt`**

In `app/src/main/java/com/iguar/armoredllama/ui/MonitorScreen.kt`, update the `MenuCallbacks(...)` construction:

```kotlin
                onDownloadModel = vm::downloadModel,
                onDownloadCompanion = vm::downloadCompanion,
```

- [ ] **Step 6: Build and run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. (No callers of the old single-arg `onDownloadModel`/`downloadModel(id)` remain — grep: `git grep -n "onDownloadModel(model.id)"` returns nothing.)

- [ ] **Step 7: On-device verification (headline download + curated companions)**

Install and drive the app (server can be stopped). Verify:
- Search "Bonsai" → the card headlines `Q1_0` with a `Fits` badge (not the 53.8 GB `F16`).
- Tapping Get on the headline starts a `Q1_0` download (progress on the card; log shows `download complete`).
- Regression: blank-search "On this phone" list still switches/deletes; downloading the curated **gemma** seed entry still fetches its `mtp` draft + `mmproj` companions (config `[library]` gains the primary; `use_draft`/`use_mmproj` set).

Run: `./gradlew installDebug` then exercise via the UI (or adb). Capture a screenshot showing the `Q1_0 · Fits` headline.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/ui/menu/MenuCallbacks.kt \
        app/src/main/java/com/iguar/armoredllama/ui/MonitorScreen.kt \
        app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt \
        app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt \
        app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt
git commit -m "feat(download): fetch by repo+file; auto-wire vision/draft companion downloads"
```

---

### Task 4: Expandable model card — quant rows + companion rows

Add the `▸ N quants` toggle that reveals every quant (smallest→largest) and the companion rows, each with its own Get and per-file progress.

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt`

**Interfaces:**
- Consumes: `ModelEntry.quants`/`companions`/`downloadingFile` (Tasks 1, 3), `callbacks.onDownloadModel(repo, file)` / `onDownloadCompanion(repo, file, kind)` (Task 3), `QuantOption`/`CompanionOption`/`CompanionKind`, `ModelFitLevel`.

- [ ] **Step 1: Add the expand toggle and rows to `ModelCard`**

This is a Compose UI change verified on-device (no unit test). In `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt`:

Add imports (note: `Column`, `ModelFitLevel`, `roundToInt` are already imported in this file — do **not** re-add them; `MenuCallbacks` is in the same package):

```kotlin
import com.iguar.armoredllama.model.QuantOption
import com.iguar.armoredllama.model.CompanionOption
import com.iguar.armoredllama.server.CompanionKind
```

Change `ModelCard`'s signature to receive the repo callbacks and wrap the current `Row` in a `Column` so expanded rows can sit beneath it. Replace the `ModelCard` composable header and the `HfPanel` call site as follows.

`HfPanel` call site (replace the `key(model.file){…}` block from Task 3):

```kotlin
                    key(model.file) {
                        ModelCard(
                            model = model,
                            local = local,
                            onDownloadModel = callbacks.onDownloadModel,
                            onDownloadCompanion = callbacks.onDownloadCompanion,
                            onSwitch = if (local) ({ callbacks.onSwitchModel(model.file) }) else null,
                            onDelete = if (local) ({ callbacks.onDeleteModel(model.file) }) else null,
                        )
                    }
```

`ModelCard` — new signature and body wrapper (keep the existing header `Row` content intact as the first child; the `onGet` used inside becomes `{ onDownloadModel(model.repo, model.file) }`):

```kotlin
@Composable
private fun ModelCard(
    model: ModelEntry,
    local: Boolean,
    onDownloadModel: (String, String) -> Unit,
    onDownloadCompanion: (String, String, CompanionKind) -> Unit,
    onSwitch: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val c = MonitorTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val onGet = { onDownloadModel(model.repo, model.file) }
    val extraQuants = model.quants.size > 1
    val canExpand = !local && (extraQuants || model.companions.isNotEmpty())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .panel(c.panel, c.border, 14.dp)
            .padding(14.dp),
    ) {
        // --- existing header Row goes here unchanged, except its `onGet` uses the local `onGet` above ---
        // (the Row that shows name, FitBadge, repo·quant·size, file, fit.detail, and the state button)

        if (canExpand) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (expanded) "▾ hide quants" else "▸ ${model.quants.size} quants",
                style = MonitorType.monoCaption,
                color = c.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            model.quants.forEach { q ->
                QuantRow(model = model, quant = q, onGet = { onDownloadModel(model.repo, q.file) })
            }
            if (model.companions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("─ Companions ─", style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.height(6.dp))
                model.companions.forEach { comp ->
                    CompanionRow(
                        model = model,
                        companion = comp,
                        onGet = { onDownloadCompanion(model.repo, comp.file, comp.kind) },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        // --- existing AlertDialog unchanged ---
    }
}
```

> Note: the existing `ModelCard` body is a single `Row` whose modifier is
> `.fillMaxWidth().panel(c.panel, c.border, 14.dp).padding(14.dp)`. Move that panel styling to the new
> outer `Column` (shown above) and make the inner header a plain
> `Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { … }` holding
> the existing children verbatim (name/`FitBadge`/`repo · quant · size`/file/`fit.detail` + the
> `when (model.state)` state button). The state button's `ModelState.IDLE -> SmallButton("Get", c.accent, onGet)`
> keeps using the `onGet` lambda defined above. The existing `AlertDialog` (`if (confirmDelete) { … }`)
> moves below the `Column`, unchanged.

- [ ] **Step 2: Add the `QuantRow` and `CompanionRow` composables**

Add to `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt`:

```kotlin
@Composable
private fun QuantRow(model: ModelEntry, quant: QuantOption, onGet: () -> Unit) {
    val c = MonitorTheme.colors
    val downloading = model.downloadingFile == quant.file
    val installedFile = model.state != ModelState.IDLE && model.file == quant.file
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(quant.quant, style = MonitorType.bodyLabel, color = c.text)
                Spacer(Modifier.width(8.dp))
                Text("%.1f GB".format(quant.sizeGB), style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.width(8.dp))
                FitPill(quant.fit.level, quant.fit.label)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            downloading -> Text("${(model.progress * 100).roundToInt()}%", style = MonitorType.monoCaption, color = c.accent)
            installedFile -> Text("Installed", style = MonitorType.monoCaption, color = c.good)
            else -> SmallButton("Get", c.accent, onGet)
        }
    }
}

@Composable
private fun CompanionRow(model: ModelEntry, companion: CompanionOption, onGet: () -> Unit) {
    val c = MonitorTheme.colors
    val downloading = model.downloadingFile == companion.file
    val icon = if (companion.kind == CompanionKind.VISION) "👁" else "⚡"
    val kindLabel = if (companion.kind == CompanionKind.VISION) "vision" else "draft"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$icon $kindLabel · ${companion.quant} · %.1f GB".format(companion.sizeGB),
            style = MonitorType.monoCaption, color = c.muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (downloading) {
            Text("${(model.progress * 100).roundToInt()}%", style = MonitorType.monoCaption, color = c.accent)
        } else {
            SmallButton("Get", c.accent, onGet)
        }
    }
}

@Composable
private fun FitPill(level: ModelFitLevel, label: String) {
    val c = MonitorTheme.colors
    val color = when (level) {
        ModelFitLevel.FITS -> c.good
        ModelFitLevel.TIGHT -> c.warn
        ModelFitLevel.TOO_LARGE -> c.bad
        ModelFitLevel.UNKNOWN -> c.muted
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, style = MonitorType.monoCaption, color = color, maxLines = 1)
    }
}
```

- [ ] **Step 3: Build and run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (UI change; no unit tests added).

- [ ] **Step 4: On-device verification (expand + per-file download + companion)**

Run: `./gradlew installDebug`, then:
- Search "Bonsai" → headline `Q1_0 · Fits`; a `▸ 2 quants` toggle appears.
- Tap the toggle → rows appear smallest→largest: `Q1_0 · 3.8 GB · Fits`, `F16 · 53.8 GB · Too large`; below `─ Companions ─`: `👁 vision · Q8_0 · 0.6 GB [Get]`, `👁 vision · BF16 · 0.9 GB [Get]`, `⚡ draft · Q4_1 · 1.8 GB [Get]`, `⚡ draft · BF16 · 7.3 GB [Get]`.
- Tap Get on `Q1_0` → progress shows on that row; completes to `Installed`.
- Tap Get on a `vision` companion → downloads; when Bonsai is the active model the log shows `restart to apply vision` and `config.ini` gains `mmproj = Bonsai-27B-mmproj-Q8_0.gguf` + `use_mmproj = true`.

Capture a screenshot of the expanded card.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt
git commit -m "feat(ui): expandable model card with quant + companion rows"
```

---

## Notes for the implementer

- Run tests from the repo root. On Windows use Git Bash: `./gradlew testDebugUnitTest`. The `--tests` filter scopes to one class while iterating.
- `./gradlew installDebug` kills the app process (and the foreground server). After reinstalling, tap **Start** and wait ~16 s for the model to load before testing Chat/streaming-dependent flows. Model-download and search flows do **not** require the server running.
- Debug builds expose the WebView devtools socket; not needed here.
- The device is a OnePlus 8T (1080×2400). `adb exec-out screencap -p > shot.png` for screenshots; prefix adb commands touching `/sdcard`, `/proc`, `/data` with `MSYS2_ARG_CONV_EXCL='*'` in Git Bash.
