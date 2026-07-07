# Run Bundled llama-server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the app download the gemma-4 GGUF model files, store a reusable INI launch config, and start/stop a bundled arm64 `llama-server` as a foreground service with its real logs streaming into the UI.

**Architecture:** A new `server/` package holds pure logic (INI parse/write, arg building, download math) plus Android glue (config repo, downloader IO, `LogBus`, foreground `Service`). The app is binary-agnostic: it execs whatever `libllamaserver.so` is in `jniLibs/arm64-v8a/` from the executable native-lib dir. `MonitorViewModel` is rewired to drive the service, real downloads, and `LogBus`-sourced logs; CPU/mem/temp telemetry already added earlier stays.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Lifecycle, Kotlin coroutines/Flow, `java.net.HttpURLConnection` (no new network dep), Android foreground Service, JUnit4 (host-JVM unit tests via Gradle).

## Current Status

Implemented. Start/Stop now controls `LlamaServerService`, logs stream through `LogBus`, settings
persist to `config.ini`, model downloads are resumable, and the HF panel now performs real GGUF search
against the Hugging Face API. The later binary-updater plan added GitHub Releases runtime updates and
bundled fallback handling.

## Global Constraints

- Package root: `com.iguar.armoredllama`; new code under `com.iguar.armoredllama.server`.
- minSdk 26, targetSdk 36, AGP 9 built-in Kotlin (no `kotlin.android` plugin), `jvmTarget = JVM_11`.
- Non-root: execute the binary from `applicationInfo.nativeLibraryDir` only. Requires `useLegacyPackaging = true` and `android:extractNativeLibs="true"`.
- Binary: mainline llama.cpp release **`b9775`** android-arm64
  (`https://github.com/ggml-org/llama.cpp/releases/download/b9775/llama-b9775-bin-android-arm64.tar.gz`),
  **dynamically linked**. Stage all release `*.so` + the `llama-server` exec renamed to
  `app/src/main/jniLibs/arm64-v8a/libllamaserver.so`. The service must run it with
  `LD_LIBRARY_PATH = applicationInfo.nativeLibraryDir` so deps (`libllama.so`, `libggml*.so`,
  `libmtmd.so`, `libllama-server-impl.so`, …) resolve.
- Model storage: `getExternalFilesDir("models")`. Config: `getExternalFilesDir(null)/config.ini`. Logs: `getExternalFilesDir("logs")/server.log`.
- HF repo: `unsloth/gemma-4-E2B-it-qat-mobile-GGUF`; files `gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf` (model), `mtp-gemma-4-E2B-it.gguf` (draft), `mmproj-F16.gguf` (mmproj).
- Default flags verbatim: `--spec-type draft-mtp --spec-draft-n-max 4 --spec-draft-p-min 0.6 --no-mmap --host 0.0.0.0 --port 8080 -c 8192 -t 4 --tools all`.
- Unit-test command (Windows, Git Bash or PowerShell): use the absolute wrapper path, e.g.
  `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:testDebugUnitTest --tests "<FQCN>"`.
- Compile/verify command: `... :app:assembleDebug` (expect `BUILD SUCCESSFUL`).

---

### Task 0: Initialize git (enables the commit steps)

**Files:** none (repo metadata only).

> If you prefer not to use git, skip this task and every `git` step below.

- [ ] **Step 1: Init and baseline commit**

```bash
cd /c/Users/iguar/Code/ArmoredLlama
git init
printf '\n# build harness\n/build/tdd-out/\n' >> .gitignore
git add -A
git commit -m "chore: baseline before llama-server runtime work"
```

---

### Task 1: `LlamaConfig` + INI parse/write/map (pure)

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/server/LlamaConfig.kt`
- Create: `app/src/main/java/com/iguar/armoredllama/server/IniStore.kt`
- Test: `app/src/test/java/com/iguar/armoredllama/server/IniStoreTest.kt`

**Interfaces:**
- Produces: `data class LlamaConfig(...)` (fields below); `fun parseIni(text:String): Map<String,Map<String,String>>`; `fun writeIni(data: Map<String,Map<String,String>>): String`; `fun LlamaConfig.toIni(): String`; `fun llamaConfigFromIni(text:String): LlamaConfig`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Test

class IniStoreTest {
    @Test fun parseIni_groupsKeysBySectionAndIgnoresCommentsAndBlanks() {
        val text = """
            ; a comment
            [server]
            host = 0.0.0.0
            port = 8080

            [model]
            repo = unsloth/gemma-4-E2B-it-qat-mobile-GGUF
        """.trimIndent()
        val ini = parseIni(text)
        assertEquals("0.0.0.0", ini["server"]!!["host"])
        assertEquals("8080", ini["server"]!!["port"])
        assertEquals("unsloth/gemma-4-E2B-it-qat-mobile-GGUF", ini["model"]!!["repo"])
    }

    @Test fun configRoundTripsThroughIni() {
        val config = LlamaConfig()
        val restored = llamaConfigFromIni(config.toIni())
        assertEquals(config, restored)
    }

    @Test fun llamaConfigFromIni_fallsBackToDefaultsForMissingKeys() {
        val cfg = llamaConfigFromIni("[server]\nport = 9090\n")
        assertEquals(9090, cfg.port)
        assertEquals(LlamaConfig().host, cfg.host)            // default kept
        assertEquals(LlamaConfig().modelFile, cfg.modelFile)  // default kept
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:testDebugUnitTest --tests "com.iguar.armoredllama.server.IniStoreTest"`
Expected: FAIL — unresolved references `parseIni`, `LlamaConfig`, etc.

- [ ] **Step 3: Write `LlamaConfig.kt`**

```kotlin
package com.iguar.armoredllama.server

/** Full llama-server launch configuration, persisted as config.ini. Pure (no Android deps). */
data class LlamaConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val ctx: Int = 8192,
    val threads: Int = 4,
    val noMmap: Boolean = true,
    val tools: String = "all",
    val specType: String = "draft-mtp",
    val specDraftNMax: Int = 4,
    val specDraftPMin: Float = 0.6f,
    val extraArgs: String = "",
    val repo: String = "unsloth/gemma-4-E2B-it-qat-mobile-GGUF",
    val modelFile: String = "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf",
    val draftFile: String = "mtp-gemma-4-E2B-it.gguf",
    val mmprojFile: String = "mmproj-F16.gguf",
)
```

- [ ] **Step 4: Write `IniStore.kt`**

```kotlin
package com.iguar.armoredllama.server

/** Minimal INI parse/write + LlamaConfig mapping. Pure; unit-tested on the host JVM. */

fun parseIni(text: String): Map<String, Map<String, String>> {
    val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
    var section = ""
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.substring(1, line.length - 1).trim()
            out.getOrPut(section) { LinkedHashMap() }
        } else {
            val idx = line.indexOf('=')
            if (idx > 0) {
                out.getOrPut(section) { LinkedHashMap() }[line.substring(0, idx).trim()] =
                    line.substring(idx + 1).trim()
            }
        }
    }
    return out
}

fun writeIni(data: Map<String, Map<String, String>>): String {
    val sb = StringBuilder()
    for ((section, entries) in data) {
        if (entries.isEmpty()) continue
        if (section.isNotEmpty()) sb.append('[').append(section).append("]\n")
        for ((k, v) in entries) sb.append(k).append(" = ").append(v).append('\n')
        sb.append('\n')
    }
    return sb.toString()
}

fun LlamaConfig.toIni(): String = writeIni(
    linkedMapOf(
        "server" to linkedMapOf(
            "host" to host,
            "port" to port.toString(),
            "ctx" to ctx.toString(),
            "threads" to threads.toString(),
            "no_mmap" to noMmap.toString(),
            "tools" to tools,
            "spec_type" to specType,
            "spec_draft_n_max" to specDraftNMax.toString(),
            "spec_draft_p_min" to specDraftPMin.toString(),
            "extra_args" to extraArgs,
        ),
        "model" to linkedMapOf(
            "repo" to repo,
            "model" to modelFile,
            "draft" to draftFile,
            "mmproj" to mmprojFile,
        ),
    ),
)

fun llamaConfigFromIni(text: String): LlamaConfig {
    val ini = parseIni(text)
    val s = ini["server"].orEmpty()
    val m = ini["model"].orEmpty()
    val d = LlamaConfig()
    return LlamaConfig(
        host = s["host"] ?: d.host,
        port = s["port"]?.toIntOrNull() ?: d.port,
        ctx = s["ctx"]?.toIntOrNull() ?: d.ctx,
        threads = s["threads"]?.toIntOrNull() ?: d.threads,
        noMmap = s["no_mmap"]?.toBooleanStrictOrNull() ?: d.noMmap,
        tools = s["tools"] ?: d.tools,
        specType = s["spec_type"] ?: d.specType,
        specDraftNMax = s["spec_draft_n_max"]?.toIntOrNull() ?: d.specDraftNMax,
        specDraftPMin = s["spec_draft_p_min"]?.toFloatOrNull() ?: d.specDraftPMin,
        extraArgs = s["extra_args"] ?: d.extraArgs,
        repo = m["repo"] ?: d.repo,
        modelFile = m["model"] ?: d.modelFile,
        draftFile = m["draft"] ?: d.draftFile,
        mmprojFile = m["mmproj"] ?: d.mmprojFile,
    )
}
```

Note: `extra_args` defaults to empty; `toIni()` writes `extra_args = ` and `llamaConfigFromIni` reads `""`, so the round trip holds.

- [ ] **Step 5: Run test to verify it passes**

Run: same command as Step 2. Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/LlamaConfig.kt app/src/main/java/com/iguar/armoredllama/server/IniStore.kt app/src/test/java/com/iguar/armoredllama/server/IniStoreTest.kt
git commit -m "feat(server): LlamaConfig + INI parse/write round-trip"
```

---

### Task 2: `ArgsBuilder` (pure)

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/server/ArgsBuilder.kt`
- Test: `app/src/test/java/com/iguar/armoredllama/server/ArgsBuilderTest.kt`

**Interfaces:**
- Consumes: `LlamaConfig` (Task 1).
- Produces: `fun buildArgs(config: LlamaConfig, binaryPath: String, modelsDir: String): List<String>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ArgsBuilderTest {
    @Test fun buildArgs_producesFullGemmaCommandLine() {
        val args = buildArgs(LlamaConfig(), "/lib/libllamaserver.so", "/models")
        assertEquals(
            listOf(
                "/lib/libllamaserver.so",
                "-m", "/models/gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf",
                "--model-draft", "/models/mtp-gemma-4-E2B-it.gguf",
                "--mmproj", "/models/mmproj-F16.gguf",
                "--spec-type", "draft-mtp",
                "--spec-draft-n-max", "4",
                "--spec-draft-p-min", "0.6",
                "--no-mmap",
                "--host", "0.0.0.0",
                "--port", "8080",
                "-c", "8192",
                "-t", "4",
                "--tools", "all",
            ),
            args,
        )
    }

    @Test fun buildArgs_omitsEmptyOptionalFieldsAndAppendsExtraArgs() {
        val cfg = LlamaConfig(draftFile = "", mmprojFile = "", specType = "", tools = "", noMmap = false, extraArgs = "--verbose --flash-attn")
        val args = buildArgs(cfg, "/b", "/m")
        assertEquals(false, args.contains("--model-draft"))
        assertEquals(false, args.contains("--mmproj"))
        assertEquals(false, args.contains("--spec-type"))
        assertEquals(false, args.contains("--tools"))
        assertEquals(false, args.contains("--no-mmap"))
        assertEquals(listOf("--verbose", "--flash-attn"), args.takeLast(2))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:testDebugUnitTest --tests "com.iguar.armoredllama.server.ArgsBuilderTest"`
Expected: FAIL — unresolved `buildArgs`.

- [ ] **Step 3: Write `ArgsBuilder.kt`**

```kotlin
package com.iguar.armoredllama.server

/** Turn a [LlamaConfig] into the llama-server argv. Pure; flags omitted when their field is blank. */
fun buildArgs(config: LlamaConfig, binaryPath: String, modelsDir: String): List<String> {
    fun path(f: String) = "$modelsDir/$f"
    val args = mutableListOf(binaryPath)
    args += listOf("-m", path(config.modelFile))
    if (config.draftFile.isNotBlank()) args += listOf("--model-draft", path(config.draftFile))
    if (config.mmprojFile.isNotBlank()) args += listOf("--mmproj", path(config.mmprojFile))
    if (config.specType.isNotBlank()) {
        args += listOf("--spec-type", config.specType)
        args += listOf("--spec-draft-n-max", config.specDraftNMax.toString())
        args += listOf("--spec-draft-p-min", config.specDraftPMin.toString())
    }
    if (config.noMmap) args += "--no-mmap"
    args += listOf("--host", config.host)
    args += listOf("--port", config.port.toString())
    args += listOf("-c", config.ctx.toString())
    args += listOf("-t", config.threads.toString())
    if (config.tools.isNotBlank()) args += listOf("--tools", config.tools)
    if (config.extraArgs.isNotBlank()) args += config.extraArgs.trim().split(Regex("\\s+"))
    return args
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: same as Step 2. Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/ArgsBuilder.kt app/src/test/java/com/iguar/armoredllama/server/ArgsBuilderTest.kt
git commit -m "feat(server): build llama-server argv from config"
```

---

### Task 3: Download URL + resume math (pure)

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/server/DownloadMath.kt`
- Test: `app/src/test/java/com/iguar/armoredllama/server/DownloadMathTest.kt`

**Interfaces:**
- Produces: `fun hfUrl(repo:String, file:String): String`; `fun resumeOffset(existingBytes:Long, totalBytes:Long): Long?`; `fun isComplete(existingBytes:Long, totalBytes:Long): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadMathTest {
    @Test fun hfUrl_buildsResolveMainUrl() {
        assertEquals(
            "https://huggingface.co/unsloth/gemma-4-E2B-it-qat-mobile-GGUF/resolve/main/mmproj-F16.gguf",
            hfUrl("unsloth/gemma-4-E2B-it-qat-mobile-GGUF", "mmproj-F16.gguf"),
        )
    }
    @Test fun resumeOffset_zeroWhenNothingDownloaded() = assertEquals(0L, resumeOffset(0, 100))
    @Test fun resumeOffset_partialReturnsExistingBytes() = assertEquals(40L, resumeOffset(40, 100))
    @Test fun resumeOffset_nullWhenAlreadyComplete() = assertNull(resumeOffset(100, 100))
    @Test fun isComplete_trueWhenSizesMatch() = assertEquals(true, isComplete(100, 100))
    @Test fun isComplete_falseWhenShortOrUnknownTotal() {
        assertEquals(false, isComplete(40, 100))
        assertEquals(false, isComplete(100, 0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:testDebugUnitTest --tests "com.iguar.armoredllama.server.DownloadMathTest"`
Expected: FAIL — unresolved `hfUrl`, etc.

- [ ] **Step 3: Write `DownloadMath.kt`**

```kotlin
package com.iguar.armoredllama.server

/** Pure helpers for resumable Hugging Face downloads. */

fun hfUrl(repo: String, file: String): String =
    "https://huggingface.co/$repo/resolve/main/$file"

/** Offset to resume from, or null if the file is already complete. */
fun resumeOffset(existingBytes: Long, totalBytes: Long): Long? = when {
    existingBytes <= 0L -> 0L
    isComplete(existingBytes, totalBytes) -> null
    else -> existingBytes
}

fun isComplete(existingBytes: Long, totalBytes: Long): Boolean =
    totalBytes > 0L && existingBytes >= totalBytes
```

- [ ] **Step 4: Run test to verify it passes**

Run: same as Step 2. Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/DownloadMath.kt app/src/test/java/com/iguar/armoredllama/server/DownloadMathTest.kt
git commit -m "feat(server): HF url + resume math"
```

---

### Task 4: `ConfigRepository` + `ModelDownloader` IO (Android glue)

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/server/ConfigRepository.kt`
- Create: `app/src/main/java/com/iguar/armoredllama/server/ModelDownloader.kt`

**Interfaces:**
- Consumes: `LlamaConfig`, `llamaConfigFromIni`, `toIni` (Task 1); `hfUrl`, `isComplete` (Task 3).
- Produces:
  - `class ConfigRepository(context: Context)` with `fun load(): LlamaConfig`, `fun save(config: LlamaConfig)`, `fun modelsDir(): File`, `fun logFile(): File`.
  - `class ModelDownloader(modelsDir: File)` with `suspend fun download(repo: String, file: String, onProgress: (Long, Long) -> Unit): File` and `fun localSize(file: String): Long`.

- [ ] **Step 1: Write `ConfigRepository.kt`**

```kotlin
package com.iguar.armoredllama.server

import android.content.Context
import java.io.File

/** Loads/saves config.ini (seeding defaults on first run) and resolves app storage dirs. */
class ConfigRepository(private val context: Context) {
    private fun base(): File = context.getExternalFilesDir(null) ?: context.filesDir
    private fun configFile(): File = File(base(), "config.ini")

    fun load(): LlamaConfig {
        val f = configFile()
        if (!f.exists()) {
            val def = LlamaConfig()
            save(def)
            return def
        }
        return llamaConfigFromIni(f.readText())
    }

    fun save(config: LlamaConfig) {
        val f = configFile()
        f.parentFile?.mkdirs()
        f.writeText(config.toIni())
    }

    fun modelsDir(): File = File(base(), "models").apply { mkdirs() }
    fun logFile(): File = File(base(), "logs/server.log")
}
```

- [ ] **Step 2: Write `ModelDownloader.kt`**

```kotlin
package com.iguar.armoredllama.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Resumable HF downloader writing into [modelsDir]. Reports (bytesWritten, totalBytes). */
class ModelDownloader(private val modelsDir: File) {

    fun localSize(file: String): Long = File(modelsDir, file).let { if (it.exists()) it.length() else 0L }

    suspend fun download(repo: String, file: String, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            modelsDir.mkdirs()
            val target = File(modelsDir, file)
            val existing = if (target.exists()) target.length() else 0L
            val conn = (URL(hfUrl(repo, file)).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            try {
                val partial = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                val len = conn.contentLengthLong.coerceAtLeast(0L)
                val total = if (partial) existing + len else len
                if (partial && isComplete(existing, total)) return@withContext target
                conn.inputStream.use { input ->
                    FileOutputStream(target, partial).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var written = if (partial) existing else 0L
                        var n = input.read(buf)
                        while (n >= 0) {
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                            n = input.read(buf)
                        }
                    }
                }
                target
            } finally {
                conn.disconnect()
            }
        }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/ConfigRepository.kt app/src/main/java/com/iguar/armoredllama/server/ModelDownloader.kt
git commit -m "feat(server): config repository + resumable model downloader"
```

---

### Task 5: `LogBus` (process-wide live log stream)

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/server/LogBus.kt`

**Interfaces:**
- Consumes: `com.iguar.armoredllama.model.LogLine`.
- Produces: `object LogBus` with `val lines: StateFlow<List<LogLine>>`, `fun append(body: String)`, `fun attachFile(file: File)`, `fun clear()`.

- [ ] **Step 1: Write `LogBus.kt`**

```kotlin
package com.iguar.armoredllama.server

import com.iguar.armoredllama.model.LOG_CAP
import com.iguar.armoredllama.model.LogLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/** Process-wide buffer for real server log lines; the UI observes [lines]. Also tees to a file. */
object LogBus {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    @Volatile private var logFile: File? = null

    fun attachFile(file: File) {
        logFile = file.apply { parentFile?.mkdirs() }
    }

    @Synchronized
    fun append(body: String) {
        val line = LogLine(stamp(), body)
        _lines.value = (_lines.value + line).takeLast(LOG_CAP)
        runCatching { logFile?.appendText("${line.time} $body\n") }
    }

    fun clear() { _lines.value = emptyList() }

    private fun stamp(): String {
        val t = System.currentTimeMillis()
        val s = (t / 1000) % 86400
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", s / 3600, (s % 3600) / 60, s % 60, t % 1000)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/LogBus.kt
git commit -m "feat(server): LogBus live log stream with file tee"
```

---

### Task 6: Bundle the b9775 llama-server + build/manifest config

**Files:**
- Modify: `.gitignore` (already done in pre-flight: `app/src/main/jniLibs/arm64-v8a/*.so`)
- Modify: `app/build.gradle.kts` (packaging + `fetchLlamaServer` task wired into preBuild)
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/jniLibs/arm64-v8a/README.md`

**Context:** Release `b9775` ships a prebuilt android-arm64 `llama-server` that is **dynamically
linked** — it needs `libllama.so`, `libggml*.so` (incl. runtime-dispatched `libggml-cpu-android_*`
variants), `libmtmd.so`, `libllama-common.so`, and `libllama-server-impl.so` beside it. Stage **all**
release `*.so` plus the `llama-server` exec renamed `libllamaserver.so` into `jniLibs/arm64-v8a/`.
Android extracts them to the executable `nativeLibraryDir`; the service sets `LD_LIBRARY_PATH` so the
linker finds the deps (Task 7). Staged binaries are git-ignored; the Gradle task re-fetches on a clean
checkout.

**Interfaces:** none (build/config only). Produces the runtime files the service execs in Task 7.

- [ ] **Step 1: Add packaging + fetch task to `app/build.gradle.kts`**

Inside the `android { ... }` block, after `buildFeatures { compose = true }`, add:

```kotlin
    packaging {
        jniLibs {
            // Extract the bundled llama-server .so to nativeLibraryDir so it can be exec'd.
            useLegacyPackaging = true
        }
    }
```

At the **top level** of the file (after the `android { }` block), add the fetch task:

```kotlin
// Stage the pinned llama.cpp arm64 server + its shared libs into jniLibs. Uses Gradle's built-in
// tarTree/gzip so no extra plugin is needed. Idempotent: skips if libllamaserver.so already present.
val llamaRelease = "b9775"
val llamaUrl =
    "https://github.com/ggml-org/llama.cpp/releases/download/$llamaRelease/llama-$llamaRelease-bin-android-arm64.tar.gz"
val jniArm64 = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

val fetchLlamaServer by tasks.registering {
    description = "Download + stage the llama.cpp $llamaRelease arm64 server into jniLibs"
    val marker = jniArm64.file("libllamaserver.so").asFile
    outputs.file(marker)
    doLast {
        if (marker.exists()) return@doLast
        val tarball = layout.buildDirectory.file("llama-dl/llama-$llamaRelease.tar.gz").get().asFile
        if (!tarball.exists()) {
            tarball.parentFile.mkdirs()
            java.net.URI(llamaUrl).toURL().openStream().use { input ->
                tarball.outputStream().use { input.copyTo(it) }
            }
        }
        copy {
            from(tarTree(resources.gzip(tarball)))
            into(jniArm64)
            include("**/*.so", "**/llama-server")
            eachFile { path = name } // flatten the llama-bXXXX/ prefix
            includeEmptyDirs = false
        }
        val server = jniArm64.file("llama-server").asFile
        if (server.exists()) server.renameTo(jniArm64.file("libllamaserver.so").asFile)
    }
}

tasks.named("preBuild") { dependsOn(fetchLlamaServer) }
```

- [ ] **Step 2: Update `AndroidManifest.xml`**

Replace the file contents with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:extractNativeLibs="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.ArmoredLlama">

        <service
            android:name=".server.LlamaServerService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Runs the on-device llama.cpp inference server" />
        </service>

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.ArmoredLlama">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 3: Create the binary-slot README**

`app/src/main/jniLibs/arm64-v8a/README.md`:

```markdown
# llama-server runtime binaries (auto-staged)

The `fetchLlamaServer` Gradle task downloads llama.cpp release **b9775** (android-arm64) and stages
here, before every build:

- `libllamaserver.so` — the `llama-server` executable (renamed so Android packages + extracts it to
  the executable `nativeLibraryDir`; the app execs `nativeLibraryDir/libllamaserver.so`).
- all release `*.so` deps (`libllama.so`, `libggml*.so` incl. `libggml-cpu-android_*` variants,
  `libmtmd.so`, `libllama-common.so`, `libllama-server-impl.so`, …). The service runs the executable
  with `LD_LIBRARY_PATH=nativeLibraryDir` so these resolve.

These `*.so` are git-ignored. To pin a different build, change `llamaRelease` in `app/build.gradle.kts`
or drop your own `libllamaserver.so` (+ its deps) here. Without them, Start shows
"server binary not bundled".
```

- [ ] **Step 4: Stage binaries and verify the APK contains them**

Run:
```bash
& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:fetchLlamaServer :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. Then verify the libs are packaged:
```bash
unzip -l "C:/Users/iguar/Code/ArmoredLlama/app/build/outputs/apk/debug/app-debug.apk" | grep -E 'lib/arm64-v8a/(libllamaserver|libllama|libmtmd|libggml)\.so'
```
Expected: lines listing `libllamaserver.so`, `libllama.so`, `libmtmd.so`, `libggml.so`.

- [ ] **Step 5: Commit** (the `.so` are git-ignored; only config + README are tracked)

```bash
git add .gitignore app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/jniLibs/arm64-v8a/README.md
git commit -m "build: stage b9775 llama-server into jniLibs, FGS perms, service decl"
```

---

### Task 7: `LlamaServerService` (foreground exec + log streaming)

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/server/LlamaServerService.kt`

**Interfaces:**
- Consumes: `ConfigRepository`, `buildArgs`, `LogBus`.
- Produces: `class LlamaServerService` with companion `enum class Status { STOPPED, STARTING, RUNNING, ERROR }`, `val status: StateFlow<Status>`, `fun start(context: Context)`, `fun stop(context: Context)`.

- [ ] **Step 1: Write `LlamaServerService.kt`**

```kotlin
package com.iguar.armoredllama.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Foreground service that owns the llama-server process and streams its output to [LogBus]. */
class LlamaServerService : Service() {

    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopServer(); stopSelfResult(startId) }
            else -> startServer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        if (process != null) return
        ensureChannel()
        startForegroundCompat("Starting llama-server…")
        statusFlow.value = Status.STARTING

        val repo = ConfigRepository(this)
        LogBus.attachFile(repo.logFile())
        val config = repo.load()
        val modelsDir = repo.modelsDir()
        val binary = File(applicationInfo.nativeLibraryDir, "libllamaserver.so")

        if (!binary.exists()) {
            fail("server binary not bundled (${binary.path}) — see jniLibs/arm64-v8a/README.md")
            return
        }
        val needed = listOf(config.modelFile, config.draftFile, config.mmprojFile).filter { it.isNotBlank() }
        val missing = needed.filter { !File(modelsDir, it).exists() }
        if (missing.isNotEmpty()) {
            fail("missing model file(s): ${missing.joinToString()} — download them first")
            return
        }

        val args = buildArgs(config, binary.path, modelsDir.path)
        LogBus.append("exec: ${args.joinToString(" ")}")
        try {
            val pb = ProcessBuilder(args).redirectErrorStream(true).directory(modelsDir)
            // The b9775 server is dynamically linked; its .so deps live in nativeLibraryDir.
            pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
            val p = pb.start()
            process = p
            statusFlow.value = Status.RUNNING
            startForegroundCompat("Running on ${config.host}:${config.port}")
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { LogBus.append(it) }
                }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                LogBus.append("server exited (code $code)")
                process = null
                statusFlow.value = Status.STOPPED
                stopSelf()
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            fail("failed to start: ${e.message}")
        }
    }

    private fun fail(message: String) {
        LogBus.append("error: $message")
        statusFlow.value = Status.ERROR
        process = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopServer() {
        process?.let { p ->
            p.destroy()
            runCatching { if (!p.waitForGrace()) p.destroyForcibly() }
        }
        process = null
        statusFlow.value = Status.STOPPED
        stopForegroundCompat()
    }

    private fun Process.waitForGrace(): Boolean =
        runCatching { waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Armored Llama")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "llama-server", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "llama_server"
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "com.iguar.armoredllama.START"
        private const val ACTION_STOP = "com.iguar.armoredllama.STOP"

        private val statusFlow = MutableStateFlow(Status.STOPPED)
        val status: StateFlow<Status> = statusFlow

        fun start(context: Context) {
            val i = Intent(context, LlamaServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, LlamaServerService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/server/LlamaServerService.kt
git commit -m "feat(server): foreground service execs llama-server, streams logs"
```

---

### Task 8: Rewire `MonitorViewModel` + state (service, downloads, real logs)

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt`

**Interfaces:**
- Consumes: `LlamaServerService`, `ConfigRepository`, `ModelDownloader`, `LogBus`, `LlamaConfig`.
- Produces: `MonitorViewModel` extends `AndroidViewModel`; `MonitorUiState` gains `serverStatus: LlamaServerService.Status` and download progress on `ModelEntry`.

- [ ] **Step 1: Extend `MonitorState.kt`**

In `MonitorUiState`, add a field (keep existing ones):

```kotlin
    val serverStatus: com.iguar.armoredllama.server.LlamaServerService.Status =
        com.iguar.armoredllama.server.LlamaServerService.Status.STOPPED,
```

`ModelEntry` already has `state`/`progress`; reuse them for download UI. No other model change.

- [ ] **Step 2: Convert `MonitorViewModel` to `AndroidViewModel` and wire real sources**

Replace the class declaration and `init`/log/action sections. Specifically:

Change imports — add:
```kotlin
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.iguar.armoredllama.server.ConfigRepository
import com.iguar.armoredllama.server.LlamaServerService
import com.iguar.armoredllama.server.LogBus
import com.iguar.armoredllama.server.ModelDownloader
import kotlinx.coroutines.flow.collect
```
and remove `import androidx.lifecycle.ViewModel`.

Change the class header from:
```kotlin
class MonitorViewModel : ViewModel() {
```
to:
```kotlin
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val configRepo = ConfigRepository(app)
    private val downloader = ModelDownloader(configRepo.modelsDir())
```

In `init { ... }`, after the existing core-count/maxCoreMhz setup and `startTicker()`, add collectors:
```kotlin
        // Real server logs replace the mock generator.
        viewModelScope.launch { LogBus.lines.collect { state = state.copy(logs = it) } }
        // Reflect service status into UI running state.
        viewModelScope.launch {
            LlamaServerService.status.collect { s ->
                state = state.copy(
                    serverStatus = s,
                    running = s == LlamaServerService.Status.RUNNING || s == LlamaServerService.Status.STARTING,
                )
            }
        }
```

Replace `toggleRunning()` body with:
```kotlin
    fun toggleRunning() {
        val ctx = getApplication<Application>()
        if (state.running) LlamaServerService.stop(ctx) else LlamaServerService.start(ctx)
    }
```

Replace `downloadModel(id)` body to do a real download of the config's files. Replace the method with:
```kotlin
    fun downloadModel(id: String) {
        val target = state.models.firstOrNull { it.id == id } ?: return
        if (target.state == ModelState.DOWNLOADING) return
        val config = configRepo.load()
        val files = listOf(config.modelFile, config.draftFile, config.mmprojFile).filter { it.isNotBlank() }
        viewModelScope.launch {
            updateModel(id) { it.copy(state = ModelState.DOWNLOADING, progress = 0f) }
            try {
                files.forEachIndexed { idx, file ->
                    downloader.download(config.repo, file) { written, total ->
                        val frac = if (total > 0) written.toFloat() / total else 0f
                        val overall = (idx + frac) / files.size
                        updateModel(id) { it.copy(progress = overall.coerceIn(0f, 1f)) }
                    }
                }
                updateModel(id) { it.copy(state = ModelState.INSTALLED, progress = 1f) }
                LogBus.append("download complete: ${config.repo}")
            } catch (e: Exception) {
                updateModel(id) { it.copy(state = ModelState.IDLE) }
                LogBus.append("download failed: ${e.message}")
            }
        }
    }
```

In `tick()`, **delete** the mock log block (the `if (running && tickCount % 3 == 0) { logs = appendLog(...) }` lines and the `var logs = state.logs` / `logs = ...` usage) and stop writing `logs` from tick — keep only `metrics`/`histories`:
```kotlin
        tickCount++
        state = state.copy(metrics = next, histories = histories)
```
Delete the now-unused `nextLogLine(...)` function and the `appendLog(...)` helper if no longer referenced. Keep `now()` only if still used elsewhere; otherwise delete.

Keep `updateSettings`, `updateHfQuery`, `startDeploy` as-is for now (out of scope), or have `updateSettings` persist via `configRepo.save(...)` in a later iteration.

- [ ] **Step 3: Update `MainActivity` factory usage**

`by viewModels()` already supplies an `Application` to `AndroidViewModel` via the default factory, so `MainActivity.kt` needs **no change**. Verify the existing line remains:
```kotlin
    private val viewModel: MonitorViewModel by viewModels()
```

- [ ] **Step 4: Verify it builds**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If the compiler flags an unused `nextLogLine`/`appendLog`/`now`, remove them.

- [ ] **Step 5: Run unit tests (no regressions)**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt
git commit -m "feat: drive llama-server service, real downloads, live logs from ViewModel"
```

---

### Task 9: Settings panel persists to `config.ini`

**Files:**
- Modify: `app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt` (settings load/save)
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt` (bind editable fields)

**Interfaces:**
- Consumes: `ConfigRepository`, `LlamaConfig`.
- Produces: `MonitorViewModel.updateSettings` persists changes; the Settings sub-panel reflects `port/ctx/threads` from `config.ini`.

- [ ] **Step 1: Load INI into the existing `ServerSettings` on init**

In `MonitorViewModel`, after `configRepo` is created, seed `settings` from the INI so the panel shows real values. Add to `init`:
```kotlin
        val cfg = configRepo.load()
        state = state.copy(
            settings = state.settings.copy(ctx = cfg.ctx, threads = cfg.threads, port = cfg.port),
        )
```

- [ ] **Step 2: Persist edits**

Replace `updateSettings` with:
```kotlin
    fun updateSettings(transform: (ServerSettings) -> ServerSettings) {
        val newSettings = transform(state.settings)
        state = state.copy(settings = newSettings)
        val cfg = configRepo.load().copy(
            ctx = newSettings.ctx,
            threads = newSettings.threads,
            port = newSettings.port,
        )
        configRepo.save(cfg)
    }
```

- [ ] **Step 3: Verify SubPanels still binds these fields**

Open `app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt`. Confirm the Settings panel already edits `ctx`, `threads`, `port` via `onUpdateSettings`. No code change needed if it does; if a field is missing a control, leave it — full settings UI is out of scope. (This step is verification only.)

- [ ] **Step 4: Verify it builds**

Run: `& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/MonitorViewModel.kt app/src/main/java/com/iguar/armoredllama/ui/menu/SubPanels.kt
git commit -m "feat: settings panel reads/writes config.ini"
```

---

### Task 10: Final verification + notes

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md` (update Wiring Status for process/log/settings/model work)

- [ ] **Step 1: Full build + tests**

Run:
```bash
& "C:\Users\iguar\Code\ArmoredLlama\gradlew.bat" -p "C:\Users\iguar\Code\ArmoredLlama" :app:assembleDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`; all `server` unit tests pass.

- [ ] **Step 2: Update `IMPLEMENTATION_NOTES.md`**

In the Wiring Status section, mark process control, real logs, and settings → `config.ini` as done.
Model downloads started as the configured Gemma profile and later grew into real GGUF search through
the Hugging Face API. Note the binary slot requirement.

- [ ] **Step 3: Manual device check (requires the `.so` in place)**

With `libllamaserver.so` (arm64) in `jniLibs/arm64-v8a/`, install on an arm64 device, download the model from the HF panel, tap Start. Expected: log window shows `exec: …` then the server's own boot output and "listening" line; Stop kills it (log shows `server exited`).

- [ ] **Step 4: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs: mark process/logs/settings wired; note binary slot"
```

---

## Self-Review

**Spec coverage:** §3 constraints → Tasks 6/7. §4 INI → Task 1. §5.1 LlamaConfig → Task 1. §5.2 IniStore/ConfigRepository → Tasks 1/4. §5.3 ArgsBuilder → Task 2. §5.4 ModelDownloader → Tasks 3/4. §5.5 Service → Task 7. §5.6 LogBus → Task 5. §5.7 ViewModel wiring → Task 8. §6 manifest/build → Task 6. §8 error handling → Task 7 (`fail`, exit-code, missing files/binary). §9 testing → Tasks 1–3 (pure). §10 file list → covered. All sections mapped.

**Placeholder scan:** No TBD/TODO; every code step shows full code; commands have expected output. Task 9 Step 3 is intentionally verification-only (no code), clearly stated.

**Type consistency:** `LlamaConfig` field names used identically across Tasks 1/2/4/8. `buildArgs(config, binaryPath, modelsDir)` signature consistent (Tasks 2/7). `LogBus.append/attachFile/lines` consistent (Tasks 5/7/8). `LlamaServerService.start/stop/status/Status` consistent (Tasks 7/8). `ConfigRepository.load/save/modelsDir/logFile` consistent (Tasks 4/7/8/9). `ModelDownloader.download(repo,file,onProgress)` consistent (Tasks 4/8).
