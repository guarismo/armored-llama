# llama.cpp Binary Updater Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the mock "Update llama.cpp" screen with a real self-updater that downloads the latest `ggml-org/llama.cpp` android-arm64 release and runs it on a non-rooted device, keeping the bundled `b9775` as fallback.

**Architecture:** Drop `targetSdk` to 28 so the app may `exec` a downloaded binary from `filesDir` (Termux approach). A pure `ReleaseParser` reads the GitHub Releases API; a pure `TarReader` extracts the release tarball; `RuntimeBinaries` resolves which binary the service runs (downloaded-active, bundled-fallback) and installs downloads; `LlamaServerService` execs whatever `RuntimeBinaries.activeExecutable()` returns.

**Tech Stack:** Kotlin, Jetpack Compose, AGP 9.2.1, `HttpURLConnection`, `org.json`, `java.util.zip.GZIPInputStream`, `android.system.Os.chmod`. Host-JVM JUnit for pure units.

## Current Status

Implemented. The app now checks GitHub Releases, downloads android-arm64 llama.cpp assets, installs
them under `filesDir/llama/<tag>/`, tracks the active tag, runs downloaded runtimes through
`RuntimeBinaries.activeExecutable()`, and falls back to bundled `b9775`. The Update panel also exposes
cleanup for downloaded runtimes. Device verification confirmed active downloaded tag `b9859` ran from
`files/llama/b9859/llama-server`.

## Global Constraints

- `targetSdk = 28`, `compileSdk = 36`, `minSdk = 26` (verbatim from spec §3). `compileSdk` unchanged at 36.
- Bundled binary tag is `b9775` (matches `llamaRelease` in `app/build.gradle.kts`); `RuntimeBinaries.BUNDLED_TAG` must equal it.
- Android arm64 asset name format: `llama-<tag>-bin-android-arm64.tar.gz`.
- llama.cpp tags are `b<number>`; "newer" = larger number.
- No new production runtime dependency (minimal pure tar reader instead of Apache Commons Compress). `org.json` is Android-framework-provided; add it as a **test-only** dependency for host-JVM parser tests.
- Downloaded binaries live in `filesDir/llama/<tag>/` (executable `llama-server` + its `*.so`), active tag recorded in `filesDir/llama/active.txt`. Downloads staged in `filesDir/llama/dl/`.
- Reuse the existing resumable `HttpURLConnection` pattern from `server/ModelDownloader.kt`.
- **Task 1 is a hard go/no-go gate.** If exec-from-`filesDir` at target 28 is denied on the device, STOP and escalate — the feature is infeasible and the fallback is a version-checker.

---

### Task 1: Feasibility spike — exec a binary from `filesDir` at targetSdk 28 (GO/NO-GO)

**Files:**
- Modify: `app/build.gradle.kts` (`defaultConfig { targetSdk = 28 }`)
- Modify (temporary): `app/src/main/java/com/iguar/armedllama/MonitorViewModel.kt` (add a one-shot spike call in `init`)

**Interfaces:**
- Consumes: the bundled `libllamaserver.so` + its `*.so` in `applicationInfo.nativeLibraryDir`.
- Produces: nothing permanent — this is a throwaway spike that either passes (proceed) or fails (abort). The temporary code is removed in Step 6.

This task is a spike, not TDD: the "test" is an on-device observation, because the thing under test is a device/OS capability, not our logic.

- [ ] **Step 1: Set targetSdk to 28**

In `app/build.gradle.kts`, inside `defaultConfig`, change:
```kotlin
        targetSdk = 36
```
to:
```kotlin
        targetSdk = 28
```
Leave `compileSdk` (36) and `minSdk` (26) unchanged.

- [ ] **Step 2: Add a temporary exec spike**

In `app/src/main/java/com/iguar/armedllama/MonitorViewModel.kt`, add this private function and call `spikeExec()` as the **last line** of the `init { }` block:

```kotlin
    // TEMPORARY SPIKE (Task 1) — remove after verifying exec+dlopen from filesDir at targetSdk 28.
    private fun spikeExec() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val libDir = java.io.File(app.applicationInfo.nativeLibraryDir)
                val spike = java.io.File(app.filesDir, "spike").apply { mkdirs() }
                // Copy the exec + all its .so into filesDir so we test BOTH exec- and dlopen-from-filesDir,
                // which is exactly the runtime condition the real feature relies on.
                libDir.listFiles()?.forEach { it.copyTo(java.io.File(spike, it.name), overwrite = true) }
                val exec = java.io.File(spike, "libllamaserver.so")
                android.system.Os.chmod(exec.path, 448) // 0700
                val pb = ProcessBuilder(exec.path, "--version").redirectErrorStream(true)
                pb.environment()["LD_LIBRARY_PATH"] = spike.path
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                LogBus.append("SPIKE exec+dlopen from filesDir: exit=$code out=${out.take(200)}")
            }.onFailure { LogBus.append("SPIKE FAILED: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }
```

Add imports if missing: `android.app.Application` is already imported; `LogBus` is already imported.

- [ ] **Step 3: Build and install**

Run: `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `BUILD SUCCESSFUL` and `Success`. (A lint warning about targetSdk < compileSdk is expected and fine.)

- [ ] **Step 4: Launch and read the spike result**

Run: `adb shell am start -n com.iguar.armedllama/.MainActivity` then, after ~3s,
`adb shell "cat /sdcard/Android/data/com.iguar.armedllama/files/logs/server.log | grep SPIKE"`

Expected (GO): a line like `SPIKE exec+dlopen from filesDir: exit=0 out=version: 9775 (…)`. The GO signal is that the process **executed at all** (any exit code with real output) — it proves exec+dlopen from `filesDir` is permitted.
Failure (NO-GO): `SPIKE FAILED: IOException: … EACCES`, `Permission denied`, or an SELinux `avc: denied` in `adb logcat` — the OS refused to exec/dlopen from `filesDir`.

- [ ] **Step 5: Decision gate**

If GO: continue to Step 6.
If NO-GO: **STOP.** Report the exact failure to the human. Do not proceed — the feature is infeasible on this device; revert `targetSdk` to 36 and escalate to fall back to a version-checker design.

- [ ] **Step 6: Remove the temporary spike, keep targetSdk 28**

Delete the `spikeExec()` function and its call in `init { }`. Keep `targetSdk = 28`.

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/iguar/armedllama/MonitorViewModel.kt
git commit -m "chore: targetSdk 28 (spike-confirmed exec from filesDir)"
```

---

### Task 2: targetSdk-28 manifest + foreground-service cleanup

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/iguar/armedllama/server/LlamaServerService.kt:109-116`

**Interfaces:**
- Consumes: nothing new.
- Produces: an app that starts its foreground service correctly at target 28 (plain 2-arg `startForeground`), with no target-34/33-only declarations.

No unit test — the deliverable is verified by starting the server on-device (Step 5).

- [ ] **Step 1: Remove target-34/33-only permissions from the manifest**

In `app/src/main/AndroidManifest.xml`, delete these two `<uses-permission>` lines:
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
Keep `INTERNET` and `FOREGROUND_SERVICE`.

- [ ] **Step 2: Drop the specialUse FGS type from the service**

In the same file, change the `<service>` element from:
```xml
        <service
            android:name=".server.LlamaServerService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="llama-server" />
        </service>
```
to:
```xml
        <service
            android:name=".server.LlamaServerService"
            android:exported="false" />
```

- [ ] **Step 3: Simplify `startForegroundCompat` to the 2-arg form**

In `LlamaServerService.kt`, replace:
```kotlin
    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
```
with:
```kotlin
    private fun startForegroundCompat(text: String) {
        // targetSdk 28: FGS types are not enforced, so the plain 2-arg form is correct.
        startForeground(NOTIF_ID, buildNotification(text))
    }
```
Then remove the now-unused import `import android.content.pm.ServiceInfo`.

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (no reference to `ServiceInfo` or removed permissions).

- [ ] **Step 5: Verify the server still starts on-device**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, launch the app, tap Start.
Expected: the log shows the `exec:` line and the server boots (foreground notification appears). Tap Stop; log shows `server exited`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/iguar/armedllama/server/LlamaServerService.kt
git commit -m "refactor(service): drop specialUse FGS + POST_NOTIFICATIONS for targetSdk 28"
```

---

### Task 3: `ReleaseParser` — parse GitHub releases/latest (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/iguar/armedllama/server/ReleaseParser.kt`
- Test: `app/src/test/java/com/iguar/armedllama/server/ReleaseParserTest.kt`
- Modify: `app/build.gradle.kts` (add `testImplementation("org.json:json:20240303")`)

**Interfaces:**
- Produces:
  - `data class ReleaseInfo(val tag: String, val date: String, val notes: String, val arm64AssetUrl: String?)`
  - `fun parseLatestRelease(json: String): ReleaseInfo?`
  - `fun androidArm64AssetName(tag: String): String`
  - `fun parseBuildNumber(tag: String): Int?`
  - `fun isNewer(latestTag: String, currentTag: String): Boolean`

- [ ] **Step 1: Add the test-only org.json dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block, next to `testImplementation(libs.junit)`, add:
```kotlin
    testImplementation("org.json:json:20240303")
```
(Android provides `org.json` at runtime; the host JVM test needs a real implementation.)

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/iguar/armedllama/server/ReleaseParserTest.kt`:
```kotlin
package com.iguar.armedllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseParserTest {

    private val json = """
        {
          "tag_name": "b9999",
          "published_at": "2026-06-20T11:22:33Z",
          "body": "- faster prompt processing\n- fix mlock",
          "assets": [
            {"name": "llama-b9999-bin-ubuntu-x64.zip", "browser_download_url": "https://x/ubuntu"},
            {"name": "llama-b9999-bin-android-arm64.tar.gz", "browser_download_url": "https://x/android"}
          ]
        }
    """.trimIndent()

    @Test fun parseLatestRelease_extractsTagDateNotesAndArm64Asset() {
        val r = parseLatestRelease(json)!!
        assertEquals("b9999", r.tag)
        assertEquals("2026-06-20", r.date)
        assertTrue(r.notes.contains("faster prompt processing"))
        assertEquals("https://x/android", r.arm64AssetUrl)
    }

    @Test fun parseLatestRelease_arm64AssetNullWhenAbsent() {
        val noAsset = """{"tag_name":"b9999","published_at":"2026-06-20T00:00:00Z","body":"x","assets":[]}"""
        assertNull(parseLatestRelease(noAsset)!!.arm64AssetUrl)
    }

    @Test fun parseLatestRelease_returnsNullForGarbage() {
        assertNull(parseLatestRelease("not json"))
    }

    @Test fun androidArm64AssetName_formatsTag() {
        assertEquals("llama-b9999-bin-android-arm64.tar.gz", androidArm64AssetName("b9999"))
    }

    @Test fun parseBuildNumber_stripsBPrefix() {
        assertEquals(9775, parseBuildNumber("b9775"))
        assertNull(parseBuildNumber("nightly"))
    }

    @Test fun isNewer_comparesBuildNumbers() {
        assertTrue(isNewer("b9999", "b9775"))
        assertFalse(isNewer("b9775", "b9775"))
        assertFalse(isNewer("b9000", "b9775"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armedllama.server.ReleaseParserTest"`
Expected: FAIL — `Unresolved reference: parseLatestRelease` (and the other functions).

- [ ] **Step 4: Implement `ReleaseParser.kt`**

Create `app/src/main/java/com/iguar/armedllama/server/ReleaseParser.kt`:
```kotlin
package com.iguar.armedllama.server

import org.json.JSONObject

/** Parsed GitHub `releases/latest` payload for ggml-org/llama.cpp. Pure; host-JVM tested. */
data class ReleaseInfo(
    val tag: String,
    val date: String,          // YYYY-MM-DD, from published_at
    val notes: String,
    val arm64AssetUrl: String?, // download URL of the android-arm64 tar.gz, or null if absent
)

/** Asset filename llama.cpp publishes for an android-arm64 build of a given tag. */
fun androidArm64AssetName(tag: String): String = "llama-$tag-bin-android-arm64.tar.gz"

/** The integer after the leading `b` in a llama.cpp tag (`"b9775" -> 9775`), or null. */
fun parseBuildNumber(tag: String): Int? = tag.trim().removePrefix("b").toIntOrNull()

/** True when [latestTag] has a higher build number than [currentTag]. */
fun isNewer(latestTag: String, currentTag: String): Boolean {
    val l = parseBuildNumber(latestTag) ?: return false
    val c = parseBuildNumber(currentTag) ?: return true
    return l > c
}

/** Parse a GitHub `releases/latest` JSON body. Returns null on malformed JSON or a missing tag. */
fun parseLatestRelease(json: String): ReleaseInfo? {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val tag = obj.optString("tag_name").ifBlank { return null }
    val date = obj.optString("published_at").take(10)
    val notes = obj.optString("body")
    val wanted = androidArm64AssetName(tag)
    var url: String? = null
    val assets = obj.optJSONArray("assets")
    if (assets != null) {
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == wanted) {
                url = a.optString("browser_download_url").ifBlank { null }
                break
            }
        }
    }
    return ReleaseInfo(tag = tag, date = date, notes = notes, arm64AssetUrl = url)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armedllama.server.ReleaseParserTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/iguar/armedllama/server/ReleaseParser.kt app/src/test/java/com/iguar/armedllama/server/ReleaseParserTest.kt
git commit -m "feat(update): pure ReleaseParser for GitHub releases/latest"
```

---

### Task 4: `TarReader` — minimal USTAR extractor (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/iguar/armedllama/server/TarReader.kt`
- Test: `app/src/test/java/com/iguar/armedllama/server/TarReaderTest.kt`

**Interfaces:**
- Produces:
  - `data class TarEntry(val name: String, val bytes: ByteArray)`
  - `fun readTar(input: java.io.InputStream): List<TarEntry>` — regular files only, directories skipped.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/iguar/armedllama/server/TarReaderTest.kt`:
```kotlin
package com.iguar.armedllama.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TarReaderTest {

    /** Build a minimal USTAR archive of (name -> bytes) regular-file entries. */
    private fun tarOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in files) {
            val h = ByteArray(512)
            name.toByteArray(Charsets.US_ASCII).copyInto(h, 0)
            val sizeOctal = data.size.toString(8).padStart(11, '0')
            sizeOctal.toByteArray(Charsets.US_ASCII).copyInto(h, 124)
            h[156] = '0'.code.toByte()                 // typeflag: regular file
            for (i in 148 until 156) h[i] = ' '.code.toByte() // checksum field (reader ignores it)
            out.write(h)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024)) // two zero blocks = end-of-archive
        return out.toByteArray()
    }

    @Test fun readTar_extractsRegularFilesWithBytes() {
        val a = "hello".toByteArray()
        val b = ByteArray(600) { (it % 7).toByte() } // spans two 512 blocks
        val tar = tarOf("llama-server" to a, "libggml.so" to b)

        val entries = readTar(ByteArrayInputStream(tar))

        assertEquals(2, entries.size)
        assertEquals("llama-server", entries[0].name)
        assertArrayEquals(a, entries[0].bytes)
        assertEquals("libggml.so", entries[1].name)
        assertArrayEquals(b, entries[1].bytes)
    }

    @Test fun readTar_emptyArchiveYieldsNoEntries() {
        assertEquals(0, readTar(ByteArrayInputStream(ByteArray(1024))).size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armedllama.server.TarReaderTest"`
Expected: FAIL — `Unresolved reference: readTar`.

- [ ] **Step 3: Implement `TarReader.kt`**

Create `app/src/main/java/com/iguar/armedllama/server/TarReader.kt`:
```kotlin
package com.iguar.armedllama.server

import java.io.InputStream

/** One extracted tar entry: relative [name] and raw [bytes]. */
class TarEntry(val name: String, val bytes: ByteArray)

/**
 * Minimal USTAR reader — enough to extract a llama.cpp release tarball. Regular files only
 * (directories / other types skipped). Header layout: name@0(100), size@124(12, octal),
 * typeflag@156(1). Caller wraps a `GZIPInputStream` around the .tar.gz before calling.
 */
fun readTar(input: InputStream): List<TarEntry> {
    val out = ArrayList<TarEntry>()
    val header = ByteArray(512)
    while (readFully(input, header)) {
        if (header.all { it == 0.toByte() }) break // end-of-archive marker
        val name = cString(header, 0, 100)
        val size = octal(header, 124, 12)
        val typeflag = header[156].toInt().toChar()
        val data = ByteArray(size.toInt())
        if (size > 0 && !readFully(input, data)) error("truncated tar entry: $name")
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) skipFully(input, pad)
        if (typeflag == '0' || typeflag == ' ') out.add(TarEntry(name, data))
    }
    return out
}

private fun readFully(input: InputStream, buf: ByteArray): Boolean {
    var n = 0
    while (n < buf.size) {
        val r = input.read(buf, n, buf.size - n)
        if (r < 0) return false
        n += r
    }
    return true
}

private fun skipFully(input: InputStream, count: Int) {
    var remaining = count.toLong()
    val scratch = ByteArray(minOf(count, 512))
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) { remaining -= skipped; continue }
        val r = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
        if (r < 0) return
        remaining -= r
    }
}

private fun cString(buf: ByteArray, off: Int, len: Int): String {
    var end = off
    val limit = off + len
    while (end < limit && buf[end] != 0.toByte()) end++
    return String(buf, off, end - off, Charsets.US_ASCII)
}

private fun octal(buf: ByteArray, off: Int, len: Int): Long {
    val s = cString(buf, off, len).trim()
    return if (s.isEmpty()) 0L else s.toLong(8)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armedllama.server.TarReaderTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armedllama/server/TarReader.kt app/src/test/java/com/iguar/armedllama/server/TarReaderTest.kt
git commit -m "feat(update): minimal pure USTAR reader"
```

---

### Task 5: `RuntimeBinaries` — resolve active/bundled binary + install downloads

**Files:**
- Create: `app/src/main/java/com/iguar/armedllama/server/RuntimeBinaries.kt`
- Test: `app/src/test/java/com/iguar/armedllama/server/RuntimeBinariesTest.kt`

**Interfaces:**
- Consumes: `readTar(InputStream): List<TarEntry>` (Task 4).
- Produces:
  - `data class ExecTarget(val execPath: String, val libDir: String)`
  - `class RuntimeBinaries(private val filesDir: File, private val bundledLibDir: File)`
    - `companion object { const val BUNDLED_TAG = "b9775"; const val BUNDLED_EXEC = "libllamaserver.so" }`
    - `fun activeTag(): String`
    - `fun activeExecutable(): ExecTarget`
    - `fun install(tag: String, tarGz: InputStream)` — extracts, chmods, records active, prunes others.
- The class takes `filesDir` and `bundledLibDir` as `File` params (not a `Context`) so its resolution + install logic is host-JVM testable. `chmod` is applied via an injectable hook defaulting to `android.system.Os.chmod`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/iguar/armedllama/server/RuntimeBinariesTest.kt`:
```kotlin
package com.iguar.armedllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class RuntimeBinariesTest {
    @get:Rule val tmp = TemporaryFolder()

    // Reuses the tar builder shape from TarReaderTest, gzipped (install() expects a .tar.gz stream).
    private fun tarGz(vararg files: Pair<String, ByteArray>): ByteArray {
        val raw = ByteArrayOutputStream()
        for ((name, data) in files) {
            val h = ByteArray(512)
            name.toByteArray(Charsets.US_ASCII).copyInto(h, 0)
            data.size.toString(8).padStart(11, '0').toByteArray(Charsets.US_ASCII).copyInto(h, 124)
            h[156] = '0'.code.toByte()
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            raw.write(h); raw.write(data); raw.write(ByteArray((512 - data.size % 512) % 512))
        }
        raw.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }

    private fun newBinaries(): RuntimeBinaries {
        val files = tmp.newFolder("files")
        val bundled = tmp.newFolder("lib")
        java.io.File(bundled, RuntimeBinaries.BUNDLED_EXEC).writeText("bundled")
        // no-op chmod so the test doesn't touch android.system.Os
        return RuntimeBinaries(files, bundled, chmod = { _, _ -> })
    }

    @Test fun activeExecutable_defaultsToBundledWhenNothingInstalled() {
        val rb = newBinaries()
        assertEquals(RuntimeBinaries.BUNDLED_TAG, rb.activeTag())
        assertTrue(rb.activeExecutable().execPath.endsWith(RuntimeBinaries.BUNDLED_EXEC))
    }

    @Test fun install_extractsExecAndSoAndBecomesActive() {
        val rb = newBinaries()
        rb.install("b9999", ByteArrayInputStream(tarGz(
            "llama-b9999-bin-android-arm64/llama-server" to "exe".toByteArray(),
            "llama-b9999-bin-android-arm64/libggml.so" to "so".toByteArray(),
        )))
        assertEquals("b9999", rb.activeTag())
        val target = rb.activeExecutable()
        assertTrue(target.execPath.endsWith("/b9999/llama-server"))
        assertEquals("exe", java.io.File(target.execPath).readText())
        assertEquals("so", java.io.File(target.libDir, "libggml.so").readText())
    }

    @Test fun install_prunesPreviousDownloadedVersion() {
        val rb = newBinaries()
        rb.install("b9000", ByteArrayInputStream(tarGz("x/llama-server" to "old".toByteArray())))
        rb.install("b9999", ByteArrayInputStream(tarGz("x/llama-server" to "new".toByteArray())))
        assertEquals("b9999", rb.activeTag())
        val activeDir = java.io.File(rb.activeExecutable().libDir)     // …/llama/b9999
        assertFalse(java.io.File(activeDir.parentFile, "b9000").exists()) // …/llama/b9000 pruned
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armedllama.server.RuntimeBinariesTest"`
Expected: FAIL — `Unresolved reference: RuntimeBinaries`.

- [ ] **Step 3: Implement `RuntimeBinaries.kt`**

Create `app/src/main/java/com/iguar/armedllama/server/RuntimeBinaries.kt`:
```kotlin
package com.iguar.armedllama.server

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Absolute path of the server executable to run + the dir holding its `.so` deps (for LD_LIBRARY_PATH). */
data class ExecTarget(val execPath: String, val libDir: String)

/**
 * Resolves which llama-server binary the service runs and installs downloaded updates.
 * Downloaded-active wins over the build-time bundled binary; a missing/invalid download falls back
 * to bundled. Takes plain [File] dirs (not a Context) so its logic is host-JVM testable; [chmod] is
 * injectable and defaults to android.system.Os.chmod on-device.
 */
class RuntimeBinaries(
    private val filesDir: File,
    private val bundledLibDir: File,
    private val chmod: (String, Int) -> Unit = { path, mode -> android.system.Os.chmod(path, mode) },
) {
    private val root = File(filesDir, "llama")
    private val activeFile = File(root, "active.txt")

    fun activeTag(): String {
        val tag = activeFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (tag.isBlank() || tag == BUNDLED_TAG) return BUNDLED_TAG
        return if (execFor(tag).exists()) tag else BUNDLED_TAG
    }

    fun activeExecutable(): ExecTarget {
        val tag = activeTag()
        if (tag == BUNDLED_TAG) {
            return ExecTarget(File(bundledLibDir, BUNDLED_EXEC).path, bundledLibDir.path)
        }
        val dir = File(root, tag)
        return ExecTarget(File(dir, EXEC_NAME).path, dir.path)
    }

    /** Extract [tarGz] into filesDir/llama/<tag>/, chmod the exec 0700, record active, prune others. */
    fun install(tag: String, tarGz: InputStream) {
        val dir = File(root, tag).apply { mkdirs() }
        GZIPInputStream(tarGz).use { gz ->
            for (entry in readTar(gz)) {
                val leaf = entry.name.substringAfterLast('/')
                if (leaf.isBlank()) continue
                val isExec = leaf == EXEC_NAME
                val isSo = leaf.endsWith(".so")
                if (!isExec && !isSo) continue
                File(dir, leaf).writeBytes(entry.bytes)
            }
        }
        chmod(File(dir, EXEC_NAME).path, 448) // 0700
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(tag)
        // Prune any other downloaded version dirs.
        root.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != tag) f.deleteRecursively()
        }
    }

    private fun execFor(tag: String) = File(File(root, tag), EXEC_NAME)

    companion object {
        const val BUNDLED_TAG = "b9775"                 // must match llamaRelease in build.gradle.kts
        const val BUNDLED_EXEC = "libllamaserver.so"    // exec name in nativeLibraryDir
        const val EXEC_NAME = "llama-server"            // exec name inside a release tarball
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armedllama.server.RuntimeBinariesTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armedllama/server/RuntimeBinaries.kt app/src/test/java/com/iguar/armedllama/server/RuntimeBinariesTest.kt
git commit -m "feat(update): RuntimeBinaries resolves active/bundled binary and installs updates"
```

---

### Task 6: `GithubReleases` — fetch releases/latest (Android glue)

**Files:**
- Create: `app/src/main/java/com/iguar/armedllama/server/GithubReleases.kt`

**Interfaces:**
- Consumes: `parseLatestRelease(String): ReleaseInfo?` (Task 3).
- Produces:
  - `sealed interface LatestResult { data class Ok(val info: ReleaseInfo): LatestResult; data class Err(val message: String): LatestResult }`
  - `object GithubReleases { suspend fun latest(): LatestResult }`

Thin network glue — not unit-tested (the parsing it delegates to is covered by Task 3). Verified in the Task 11 E2E.

- [ ] **Step 1: Implement `GithubReleases.kt`**

Create `app/src/main/java/com/iguar/armedllama/server/GithubReleases.kt`:
```kotlin
package com.iguar.armedllama.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Result of a releases/latest lookup: parsed info or a user-facing error message. */
sealed interface LatestResult {
    data class Ok(val info: ReleaseInfo) : LatestResult
    data class Err(val message: String) : LatestResult
}

/** Fetches the latest ggml-org/llama.cpp release from the GitHub API. */
object GithubReleases {
    private const val URL_LATEST = "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"

    suspend fun latest(): LatestResult = withContext(Dispatchers.IO) {
        val conn = runCatching {
            (URL(URL_LATEST).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "ArmedLlama")
                setRequestProperty("Accept", "application/vnd.github+json")
                connect()
            }
        }.getOrElse { return@withContext LatestResult.Err("couldn't reach GitHub: ${it.message}") }
        try {
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().readText()
                    val info = parseLatestRelease(body)
                        ?: return@withContext LatestResult.Err("couldn't parse GitHub response")
                    LatestResult.Ok(info)
                }
                403 -> LatestResult.Err("GitHub rate-limited, try again later")
                else -> LatestResult.Err("GitHub returned HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            LatestResult.Err("update check failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/iguar/armedllama/server/GithubReleases.kt
git commit -m "feat(update): GithubReleases fetches releases/latest"
```

---

### Task 7: `UpdateDownloader` — resumable asset download (Android glue)

**Files:**
- Create: `app/src/main/java/com/iguar/armedllama/server/UpdateDownloader.kt`

**Interfaces:**
- Produces:
  - `class UpdateDownloader(private val filesDir: File)`
    - `suspend fun download(url: String, onProgress: (Long, Long) -> Unit): File` — into `filesDir/llama/dl/`, resumable.

Thin glue mirroring `ModelDownloader`. Not unit-tested; verified in Task 11.

- [ ] **Step 1: Implement `UpdateDownloader.kt`**

Create `app/src/main/java/com/iguar/armedllama/server/UpdateDownloader.kt`:
```kotlin
package com.iguar.armedllama.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Resumable download of a release asset into filesDir/llama/dl/. Reports (bytesWritten, totalBytes). */
class UpdateDownloader(private val filesDir: File) {

    suspend fun download(url: String, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(filesDir, "llama/dl").apply { mkdirs() }
            val target = File(dir, url.substringAfterLast('/'))
            val existing = if (target.exists()) target.length() else 0L
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "ArmedLlama")
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            try {
                val partial = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                val len = conn.contentLengthLong.coerceAtLeast(0L)
                val total = if (partial) existing + len else len
                if (partial && total > 0L && existing >= total) return@withContext target
                conn.inputStream.use { input ->
                    FileOutputStream(target, partial).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var written = if (partial) existing else 0L
                        var n = input.read(buf)
                        while (n >= 0) {
                            out.write(buf, 0, n); written += n; onProgress(written, total)
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

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/iguar/armedllama/server/UpdateDownloader.kt
git commit -m "feat(update): resumable UpdateDownloader for release assets"
```

---

### Task 8: `LlamaServerService` runs the resolved active binary

**Files:**
- Modify: `app/src/main/java/com/iguar/armedllama/server/LlamaServerService.kt:40-86`

**Interfaces:**
- Consumes: `RuntimeBinaries(filesDir, File(nativeLibraryDir)).activeExecutable(): ExecTarget` (Task 5).

- [ ] **Step 1: Resolve the executable via RuntimeBinaries**

In `LlamaServerService.startServer`, replace:
```kotlin
        val binary = File(applicationInfo.nativeLibraryDir, "libllamaserver.so")

        if (!binary.exists()) {
            fail("server binary not bundled (${binary.path}) — see jniLibs/arm64-v8a/README.md")
            return
        }
```
with:
```kotlin
        val target = RuntimeBinaries(filesDir, File(applicationInfo.nativeLibraryDir)).activeExecutable()
        val binary = File(target.execPath)

        if (!binary.exists()) {
            fail("server binary missing (${binary.path}) — see jniLibs/arm64-v8a/README.md")
            return
        }
```

- [ ] **Step 2: Point the argv + LD_LIBRARY_PATH at the resolved target**

In the same method, the `buildArgs(...)` call already uses `binary.path` — leave it. Replace:
```kotlin
            pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
```
with:
```kotlin
            pb.environment()["LD_LIBRARY_PATH"] = target.libDir
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify the bundled path still runs on-device**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, launch, tap Start.
Expected: server boots from the bundled binary (no downloaded version installed yet → `activeTag()` = `b9775`), log shows the `exec:` line pointing at `…/lib/arm64/libllamaserver.so`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armedllama/server/LlamaServerService.kt
git commit -m "feat(update): service execs RuntimeBinaries.activeExecutable()"
```

---

### Task 9: ViewModel + state — real update check/download

**Files:**
- Modify: `app/src/main/java/com/iguar/armedllama/model/MonitorState.kt:63-79,95-108` (replace mock `Release`/`ReleaseState`)
- Modify: `app/src/main/java/com/iguar/armedllama/MonitorViewModel.kt:233-250` (replace `startDeploy`)

**Interfaces:**
- Consumes: `GithubReleases.latest()`, `UpdateDownloader(filesDir).download(...)`, `RuntimeBinaries(...).install(...)` + `.activeTag()`, `ReleaseInfo`, `isNewer`.
- Produces (state):
  - `enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, DOWNLOADING, INSTALLED, ERROR }`
  - `data class UpdateUi(val status, val activeTag, val latest: ReleaseInfo?, val progress: Float, val error: String?)`
  - `MonitorUiState.update: UpdateUi`
- Produces (VM): `fun checkForUpdate()`, `fun downloadUpdate()`.

- [ ] **Step 1: Replace the mock Release model in `MonitorState.kt`**

Delete the `ReleaseState` enum and the `Release` data class (the block currently at lines ~63-79). Add:
```kotlin
enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, DOWNLOADING, INSTALLED, ERROR }

/** Update-llama.cpp screen state (WIRE THIS #9). */
data class UpdateUi(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val activeTag: String = "b9775",
    val latest: com.iguar.armedllama.server.ReleaseInfo? = null,
    val progress: Float = 0f, // 0..1 during download
    val error: String? = null,
)
```
In `MonitorUiState`, replace the field `val release: Release = Release(),` with:
```kotlin
    val update: UpdateUi = UpdateUi(),
```

- [ ] **Step 2: Replace `startDeploy` in `MonitorViewModel.kt`**

Add fields near the other repos (after `private val downloader = ...`):
```kotlin
    private val updateDownloader = UpdateDownloader(app.filesDir)
    private val runtimeBinaries = RuntimeBinaries(app.filesDir, java.io.File(app.applicationInfo.nativeLibraryDir))
```
Add imports:
```kotlin
import com.iguar.armedllama.server.GithubReleases
import com.iguar.armedllama.server.LatestResult
import com.iguar.armedllama.server.RuntimeBinaries
import com.iguar.armedllama.server.UpdateDownloader
import com.iguar.armedllama.server.isNewer
import com.iguar.armedllama.model.UpdateStatus
```
Replace the whole `startDeploy()` + `setRelease()` block with:
```kotlin
    // Update llama.cpp (WIRE THIS #9) ----------------------------------------------------------
    fun checkForUpdate() {
        if (state.update.status == UpdateStatus.CHECKING || state.update.status == UpdateStatus.DOWNLOADING) return
        val active = runtimeBinaries.activeTag()
        setUpdate { it.copy(status = UpdateStatus.CHECKING, activeTag = active, error = null) }
        viewModelScope.launch {
            when (val r = GithubReleases.latest()) {
                is LatestResult.Ok -> {
                    val newer = isNewer(r.info.tag, active) && r.info.arm64AssetUrl != null
                    setUpdate {
                        it.copy(
                            status = if (newer) UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE,
                            latest = r.info,
                        )
                    }
                }
                is LatestResult.Err -> setUpdate { it.copy(status = UpdateStatus.ERROR, error = r.message) }
            }
        }
    }

    fun downloadUpdate() {
        val info = state.update.latest ?: return
        val url = info.arm64AssetUrl ?: return
        if (state.update.status == UpdateStatus.DOWNLOADING) return
        setUpdate { it.copy(status = UpdateStatus.DOWNLOADING, progress = 0f, error = null) }
        viewModelScope.launch {
            try {
                val file = updateDownloader.download(url) { written, total ->
                    val frac = if (total > 0) written.toFloat() / total else 0f
                    setUpdate { it.copy(progress = frac.coerceIn(0f, 1f)) }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runtimeBinaries.install(info.tag, file.inputStream())
                }
                setUpdate { it.copy(status = UpdateStatus.INSTALLED, activeTag = info.tag, progress = 1f) }
                LogBus.append("installed llama.cpp ${info.tag} — restart server to apply")
            } catch (e: Exception) {
                setUpdate { it.copy(status = UpdateStatus.ERROR, error = "install failed: ${e.message}") }
            }
        }
    }

    private fun setUpdate(transform: (com.iguar.armedllama.model.UpdateUi) -> com.iguar.armedllama.model.UpdateUi) {
        state = state.copy(update = transform(state.update))
    }
```
Remove the now-unused imports for `Release`/`ReleaseState` if present. Seed `activeTag` at startup: in `init { }`, after the existing `state = state.copy(...)`, add:
```kotlin
        state = state.copy(update = state.update.copy(activeTag = runtimeBinaries.activeTag()))
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (fix any leftover references to `Release`/`ReleaseState`/`state.release`).

- [ ] **Step 4: Run all unit tests (guard against regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all suites pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/iguar/armedllama/model/MonitorState.kt app/src/main/java/com/iguar/armedllama/MonitorViewModel.kt
git commit -m "feat(update): real checkForUpdate/downloadUpdate in the ViewModel"
```

---

### Task 10: `ReleasePanel` UI — real check/download

**Files:**
- Modify: `app/src/main/java/com/iguar/armedllama/ui/menu/SubPanels.kt` (`ReleasePanel`)
- Modify: `app/src/main/java/com/iguar/armedllama/ui/menu/MenuOverlay.kt` (drawer subtitle wording, if it references "latest release")
- Check: `MenuCallbacks` wiring in the menu host — replace `onStartDeploy` with `onCheckUpdate` + `onDownloadUpdate`.

**Interfaces:**
- Consumes: `state.update: UpdateUi`, `UpdateStatus`, VM `checkForUpdate()` / `downloadUpdate()`.

- [ ] **Step 1: Update the callbacks type**

Find the `MenuCallbacks` definition (grep: `onStartDeploy`). Replace the `onStartDeploy: () -> Unit` member with:
```kotlin
    val onCheckUpdate: () -> Unit,
    val onDownloadUpdate: () -> Unit,
```
Update the construction site (the composable that builds `MenuCallbacks`, likely in `MenuOverlay.kt` or `MonitorScreen.kt`) to pass `onCheckUpdate = viewModel::checkForUpdate, onDownloadUpdate = viewModel::downloadUpdate`.

- [ ] **Step 2: Rewrite `ReleasePanel`**

Replace the body of `ReleasePanel` in `SubPanels.kt` with:
```kotlin
@Composable
fun ReleasePanel(state: MonitorUiState, onBack: () -> Unit, callbacks: MenuCallbacks) {
    val c = MonitorTheme.colors
    val u = state.update
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHeader("Update llama.cpp", onBack)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().panel(c.panel, c.border, 16.dp).padding(16.dp)) {
                Text("Installed", style = MonitorType.monoCaption, color = c.muted)
                Spacer(Modifier.height(2.dp))
                Text(u.activeTag, style = MonitorType.heroNumber, color = c.text)
                Text("ggml-org/llama.cpp", style = MonitorType.monoCaption, color = c.muted)
            }

            val latest = u.latest
            if (latest != null) {
                Column(modifier = Modifier.fillMaxWidth().panel(c.panel, c.border, 16.dp).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(latest.tag, style = MonitorType.heroNumber, color = c.text)
                        Spacer(Modifier.width(8.dp))
                        val badge = if (u.status == UpdateStatus.UP_TO_DATE) "up to date" else "available"
                        val badgeColor = if (u.status == UpdateStatus.UP_TO_DATE) c.good else c.accent
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(badgeColor.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        ) { Text(badge, style = MonitorType.monoCaption, color = badgeColor) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(latest.date, style = MonitorType.monoCaption, color = c.muted)
                    if (latest.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(latest.notes.take(400), style = MonitorType.bodyLabel, color = c.text)
                    }
                }
            }

            if (u.status == UpdateStatus.DOWNLOADING) {
                GradientBar(
                    fraction = u.progress, startColor = c.accent, endColor = c.accent2, trackColor = c.ringTrack,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }
            if (u.status == UpdateStatus.ERROR && u.error != null) {
                Text(u.error, style = MonitorType.monoCaption, color = c.bad)
            }
            if (u.status == UpdateStatus.INSTALLED) {
                Text("Installed — Stop/Start the server to run ${u.activeTag}.", style = MonitorType.monoCaption, color = c.good)
            }

            val (label, action, enabled) = when (u.status) {
                UpdateStatus.CHECKING -> Triple("Checking…", callbacks.onCheckUpdate, false)
                UpdateStatus.UPDATE_AVAILABLE -> Triple("Download & install", callbacks.onDownloadUpdate, true)
                UpdateStatus.DOWNLOADING -> Triple("Downloading…", callbacks.onDownloadUpdate, false)
                UpdateStatus.INSTALLED -> Triple("Check again", callbacks.onCheckUpdate, true)
                else -> Triple("Check for updates", callbacks.onCheckUpdate, true)
            }
            PrimaryButton(label = label, enabled = enabled, color = c.accent, onClick = action)
        }
    }
}
```
Add any missing imports to `SubPanels.kt`: `com.iguar.armedllama.model.UpdateStatus`, `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.ui.draw.clip`, `androidx.compose.foundation.layout.Box` (most already present — add only those the compiler reports missing).

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/iguar/armedllama/ui/menu/SubPanels.kt app/src/main/java/com/iguar/armedllama/ui/menu/MenuOverlay.kt
git commit -m "feat(update): real Update llama.cpp panel (check/download/install)"
```

---

### Task 11: On-device end-to-end verification

**Files:** none (manual verification).

- [ ] **Step 1: Install and check for updates**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, open the drawer → Update llama.cpp → tap "Check for updates".
Expected: shows Installed `b9775`, latest tag/date/notes, and either "up to date" or "available".

- [ ] **Step 2: Download & install (if an update is available, or force by temporarily lowering BUNDLED_TAG)**

If "available", tap "Download & install"; watch the progress bar to completion → "Installed — Stop/Start the server".
(If GitHub's latest == b9775, temporarily set `RuntimeBinaries.BUNDLED_TAG` to an older tag like `b9000`, rebuild, to force an "available" state for the test, then restore it.)

- [ ] **Step 3: Restart the server on the downloaded binary**

Tap Stop, then Start. Read the log:
Run: `adb shell "cat /sdcard/Android/data/com.iguar.armedllama/files/logs/server.log | grep -E 'exec:|build' | tail -5"`
Expected: the `exec:` path now points at `…/files/llama/<tag>/llama-server` and the server's own build line shows the new tag.

- [ ] **Step 4: Verify the bundled fallback**

Run: `adb shell "run-as com.iguar.armedllama rm -rf files/llama"` then Stop/Start the server.
Expected: `activeTag()` falls back to `b9775`; the `exec:` line points back at `…/lib/arm64/libllamaserver.so` and the server boots.

- [ ] **Step 5: Final full test run + commit any doc update**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all pass. Update `IMPLEMENTATION_NOTES.md` #9 from mock to done, then:
```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs: mark #9 (llama.cpp self-update) wired"
```
