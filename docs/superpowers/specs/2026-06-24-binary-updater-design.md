# Design: #9 — Real in-app llama.cpp binary updater (targetSdk-28 approach)

**Date:** 2026-06-24
**Status:** Approved (brainstorming) — pending implementation plan
**Component area:** `app/.../server/`, `LlamaServerService`, `MonitorViewModel`, `SubPanels`,
`AndroidManifest.xml`, `app/build.gradle.kts`

## 1. Summary

Replace the mock "Update llama.cpp" screen (WIRE THIS #9) with a **real** self-updater that downloads
the latest `ggml-org/llama.cpp` android-arm64 release and runs it — on a **non-rooted** device.

The enabling decision: **drop `targetSdkVersion` to 28**. Android's write-xor-execute rule and the
SELinux `untrusted_app` policy that forbid executing a downloaded binary from app-writable storage are
keyed on `targetSdkVersion`, not the device version. At target ≤ 28 the app runs in the legacy
`untrusted_app_27` domain, which permits `execute_no_trans` on its own data dir (this is exactly how
Termux runs downloaded binaries). At the current target 36 this is impossible.

The bundled build-time binary (`b9775` in `nativeLibraryDir`) is **kept as a fallback**, so a broken or
incompatible release can never leave the app unable to run anything (Approach 1 of the brainstorm).

## 2. Goals / Non-goals

**Goals**
- Migrate the app to `targetSdk = 28` and adjust the foreground-service / permission layer accordingly.
- "Check for updates" shows the real latest release (tag, date, notes) vs the active version.
- "Download & install" fetches the release's android-arm64 tar.gz, extracts the executable + its `.so`
  deps into app storage, marks it active; the next server start execs it.
- The bundled binary remains a guaranteed-working fallback.

**Non-goals (YAGNI / deferred)**
- Auto-check on launch (manual "Check" button only).
- Version pinning / keeping multiple downloaded versions / a version picker (latest-downloaded is
  active; older downloaded versions are deleted after a successful newer install).
- Downloading the app's own APK / Play-style updates.
- Any change to model management (that is #10, a separate spec).

## 3. Constraints & key decisions

- **targetSdk 28, compileSdk 36, minSdk 26.** Compile against the latest SDK, target the old one — the
  standard Termux-style setup. `compileSdk` stays 36 so existing Compose/AGP-9 code is unaffected.
- **Feasibility is gated by an on-device spike (Task 1, go/no-go).** If this specific OnePlus /
  OxygenOS build denies exec from `filesDir` at target 28 (SELinux), the feature is infeasible and we
  revert to a version-checker. Termux's track record on OnePlus indicates it will work, but we verify
  before building the feature.
- **Downloaded binary is dynamically linked** (same as the bundled b9775): the tarball's `.so` deps are
  extracted alongside the executable and the process runs with `LD_LIBRARY_PATH` set to that dir.
- **Bundled fallback retained.** The `fetchLlamaServer` Gradle task and `nativeLibraryDir` exec path
  stay; `activeExecutable()` falls back to them when no valid downloaded version is active.
- **GitHub API unauthenticated** (60 req/hr/IP — ample for manual checks); a `User-Agent` header is
  required and sent.

## 4. targetSdk-28 migration (foundational)

| Change | Reason |
|---|---|
| `defaultConfig.targetSdk = 28` (build.gradle.kts) | Enter the legacy SELinux domain that permits data-dir exec. `compileSdk` unchanged. |
| Remove `android:foregroundServiceType="specialUse"` from the `<service>` | FGS types are only enforced for target ≥ 34. |
| Remove `FOREGROUND_SERVICE_SPECIAL_USE` permission + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | Same. |
| Remove `POST_NOTIFICATIONS` permission | Runtime notification permission only enforced for target ≥ 33; posts freely at 28. |
| `LlamaServerService.startForegroundCompat` → always 2-arg `startForeground(id, notif)` | The 3-arg type overload is only needed for FGS-type enforcement (target 34+). |
| **Keep:** `FOREGROUND_SERVICE` perm, `extractNativeLibs=true`, `useLegacyPackaging=true`, the notification channel | Still required; the bundled-binary exec path is unchanged. No scoped-storage impact — the app uses only `getExternalFilesDir`/`getFilesDir`. |

## 5. Components

### 5.1 `server/ReleaseParser.kt` (pure, unit-tested)
- `data class ReleaseInfo(tag: String, date: String, notes: String, arm64AssetUrl: String?)`.
- `parseLatestRelease(json: String): ReleaseInfo?` — from a GitHub `releases/latest` payload
  (`tag_name`, `published_at`, `body`, `assets[].name` / `.browser_download_url`). Uses `org.json`.
- `androidArm64AssetName(tag: String): String` = `llama-<tag>-bin-android-arm64.tar.gz`.
- `parseBuildNumber(tag: String): Int?` — integer after the leading `b` (`"b9775" → 9775`).
- `isNewer(latestTag: String, currentTag: String): Boolean` — compares build numbers.

### 5.2 `server/GithubReleases.kt` (Android glue)
- `suspend fun latest(): ReleaseInfo?` — `HttpURLConnection` GET
  `https://api.github.com/repos/ggml-org/llama.cpp/releases/latest`, `User-Agent` set, `Dispatchers.IO`,
  parsed via `ReleaseParser`. Distinguishes network error vs rate-limit (HTTP 403) for messaging.

### 5.3 `server/RuntimeBinaries.kt` (Android glue)
- `data class ExecTarget(val execPath: String, val libDir: String)`.
- `fun activeExecutable(): ExecTarget` — if an active downloaded version exists and its executable is
  present, return `filesDir/llama/<tag>/llama-server` + that dir; else the bundled
  `nativeLibraryDir/libllamaserver.so` + `nativeLibraryDir`.
- `fun activeTag(): String` — the active downloaded tag, or `BUNDLED_TAG` ("b9775").
- `suspend fun install(tag, tarGzFile)` — extract `llama-server` + all `*.so` into
  `filesDir/llama/<tag>/`, `Os.chmod(exec, 0b111_000_000)` (0700), write the tag to
  `filesDir/llama/active.txt`, then delete other `filesDir/llama/<other-tag>/` dirs.
- `const val BUNDLED_TAG = "b9775"` (kept in sync with `llamaRelease` in build.gradle.kts).

### 5.4 `server/UpdateDownloader.kt` (Android glue)
- New file, reuses the resumable `HttpURLConnection` pattern from `ModelDownloader`: downloads the
  asset URL into `filesDir/llama/dl/<file>` with progress, then hands the file to
  `RuntimeBinaries.install`.
- **Tar extraction:** Android has `java.util.zip.GZIPInputStream` but **no** built-in tar reader (the
  bundled-binary Gradle task uses Gradle's `tarTree`, which is build-time only). Decision: a **minimal
  pure tar reader** (`server/TarReader.kt`, ~a USTAR header parser: 512-byte blocks, name at offset 0,
  size octal at offset 124, type flag at 156) rather than adding Apache Commons Compress — pure and
  unit-tested against a small fixture.

### 5.5 `LlamaServerService` (modified)
- Replace the hardcoded `File(applicationInfo.nativeLibraryDir, "libllamaserver.so")` and
  `LD_LIBRARY_PATH = applicationInfo.nativeLibraryDir` with `RuntimeBinaries(this).activeExecutable()`
  → `ExecTarget.execPath` and `LD_LIBRARY_PATH = ExecTarget.libDir`.
- Existing binary-exists validation now checks `ExecTarget.execPath`.

### 5.6 `MonitorViewModel` (modified)
- Replace `startDeploy()` with `checkForUpdate()` (calls `GithubReleases.latest()`, updates state with
  the result + whether newer than `RuntimeBinaries.activeTag()`) and `downloadUpdate()` (downloads +
  installs with progress into state).

### 5.7 `model/MonitorState` (modified)
- Replace the mock `Release` / `ReleaseState` with a real update UI state: the fetched `ReleaseInfo`
  (nullable until checked), a status enum (`IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, DOWNLOADING,
  INSTALLED, ERROR`), progress, active/bundled tag, and an error message.

### 5.8 `ui/menu/SubPanels.ReleasePanel` (modified)
- "Check for updates" button → shows the active version, and after a check: latest tag, date, notes,
  and up-to-date vs update-available; "Download & install" with progress; "Installed — restart server
  to apply" terminal state.

## 6. Data flow

1. Open panel → shows active version (`RuntimeBinaries.activeTag()`).
2. **Check** → `GithubReleases.latest()` → status `UP_TO_DATE` or `UPDATE_AVAILABLE` (+ tag/date/notes).
3. **Download & install** → resumable download of `arm64AssetUrl` → `RuntimeBinaries.install(tag, file)`
   (extract, chmod, set active, prune old) → status `INSTALLED`.
4. **Restart server** → `LlamaServerService` execs `activeExecutable()` (the new binary) with
   `LD_LIBRARY_PATH` = its dir. The server's own build line in the log confirms the running version.

## 7. Error handling

| Condition | Behavior |
|---|---|
| No network | Status ERROR, message "couldn't reach GitHub". |
| GitHub rate-limited (HTTP 403) | Status ERROR, message "GitHub rate-limited, try later". |
| Release has no android-arm64 asset | Status ERROR, "no arm64 build in `<tag>`". |
| Download interrupted | Resume via `Range` on retry (existing pattern). |
| Extract / chmod / exec failure | Keep the previous active version; surface the error; server still runs the fallback. |
| Downloaded binary missing/corrupt at launch | `activeExecutable()` falls back to the bundled binary. |
| **Exec denied at target 28 (SELinux)** | Caught by the Task-1 spike **before** the feature is built; if it fails, revert to a version-checker. |

## 8. Testing

- **Pure host-JVM (Gradle `testDebugUnitTest`):** `ReleaseParser` (parse a real `releases/latest` JSON
  fixture → tag/date/notes/asset URL; asset-name construction; `parseBuildNumber`; `isNewer`); the
  minimal tar reader (extract a small fixture, verify file names + bytes).
- **Task 1 — on-device exec spike (go/no-go):** with `targetSdk = 28`, copy a binary into `filesDir`,
  `chmod +x`, exec it on the OnePlus, confirm it runs (not EACCES/SELinux-denied). Gate the rest of the
  work on this passing.
- **Manual E2E (device):** Check → Download & install → restart server → confirm the server's build/
  version line in the log shows the newly-installed tag; then verify the fallback by removing the
  downloaded dir and confirming the bundled `b9775` runs.

## 9. File-by-file change list

**New:** `server/ReleaseParser.kt`, `server/GithubReleases.kt`, `server/RuntimeBinaries.kt`,
`server/UpdateDownloader.kt`, `server/TarReader.kt` (pure); tests `ReleaseParserTest`, `TarReaderTest`.
**Modified:** `server/LlamaServerService.kt`, `MonitorViewModel.kt`, `model/MonitorState.kt`,
`ui/menu/SubPanels.kt` (ReleasePanel), `AndroidManifest.xml`, `app/build.gradle.kts`.

## 10. Risks / open items

- **targetSdk-28 regression.** The app permanently runs in Android-9 compatibility mode. Acceptable for
  a sideloaded personal app; not for Play Store (out of scope). No functional regression identified
  beyond the FGS/permission simplifications in §4.
- **Spike is a hard gate.** If exec-from-`filesDir` is denied on the device, the feature is dropped.
- **Storage.** Bundled (~tens of MB) + one downloaded version. Old downloaded versions are pruned.
- **Bundled-tag drift.** `RuntimeBinaries.BUNDLED_TAG` must track `llamaRelease` in build.gradle.kts
  (both currently `b9775`); noted as a maintenance coupling.