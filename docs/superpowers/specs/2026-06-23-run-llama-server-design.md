# Design: Download models + run a bundled llama-server (INI config, live logs)

**Date:** 2026-06-23
**Status:** Approved (brainstorming) — pending implementation plan
**Component area:** `app/.../server/`, `MonitorViewModel`, manifest + build config

## 1. Summary

Replace the mock process/log/download seams (WIRE THIS #1, #2, #8, #9→repurposed, #10→scoped)
with a real pipeline that:

1. **Bundles** an arm64 `llama-server` binary in the APK and executes it from the
   (read-only, executable) native-lib dir — works on **non-rooted** stock devices.
2. **Downloads** the gemma-4 GGUF model files from Hugging Face into app storage.
3. Stores the full launch configuration in a single, reusable, editable **INI file**.
4. Runs the server in a **foreground Service** and streams its **real stdout/stderr** into the
   existing log window.

The app is **binary-agnostic**: it only builds an argument list from the INI and execs whatever
`libllamaserver.so` is present. The user supplies a tested arm64 build that supports the configured
flags (they have confirmed one works).

## 2. Goals / Non-goals

**Goals**
- Tap **Start** → real `llama-server` process launches with the configured flags; **Stop** → it dies.
- Download the 3 gemma-4 files with visible progress; "installed" = present on disk at expected size.
- Launch config persisted in `config.ini`, seeded with the gemma-4 profile, editable and reused
  across restarts.
- Live server logs in the log window; also tee'd to a rolling file.

**Non-goals (kept as-is / mock / deferred)**
- Generic Hugging Face search UI (#10 search).
- GitHub binary auto-update / download of the binary itself (#9) — the binary is bundled.
- GPU telemetry (#6) and throughput parsing (#7).
- Multi-profile INI (single active config only).
- Building llama.cpp from source (user provides the `.so`).

## 3. Constraints & key decisions

- **Non-rooted execution.** Android (targetSdk 36) forbids executing a downloaded binary from
  app-writable storage. The only no-root path is to ship the executable as a `jniLibs` `.so` and run
  it from `applicationInfo.nativeLibraryDir`. Requires `useLegacyPackaging = true` (so the `.so` is
  extracted to disk) and `android:extractNativeLibs="true"`.
- **Binary:** mainline llama.cpp release **b9775** android-arm64, **dynamically linked**. A Gradle
  `fetchLlamaServer` task downloads it and stages all `*.so` + the `llama-server` exec (renamed
  `libllamaserver.so`) into `app/src/main/jniLibs/arm64-v8a/` (git-ignored, re-fetchable). The service
  execs with `LD_LIBRARY_PATH=nativeLibraryDir` so the shared-lib deps resolve.
- **Model storage:** app-specific external dir `getExternalFilesDir("models")`
  (`Android/data/com.iguar.armedllama/files/models`) — no runtime permission, user-visible in a file
  manager, large enough for ~3.2 GB.
- **Single INI** at `getExternalFilesDir(null)/config.ini`, seeded on first run.
- **Flags carried verbatim** even if a given binary would reject them; acceptance/errors surface live
  in the logs.

## 4. INI format (`config.ini`)

```ini
[server]
host = 0.0.0.0
port = 8080
ctx = 8192
threads = 4
no_mmap = true
tools = all
spec_type = draft-mtp
spec_draft_n_max = 4
spec_draft_p_min = 0.6
extra_args =

[model]
repo = unsloth/gemma-4-E2B-it-qat-mobile-GGUF
model = gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf
draft = mtp-gemma-4-E2B-it.gguf
mmproj = mmproj-F16.gguf
```

`extra_args` is a free-form space-separated escape hatch for anything not modeled.

## 5. Components

### 5.1 `server/LlamaConfig.kt` (pure)
Data class mirroring the INI: server fields + model file names + `extraArgs`. No Android deps.

### 5.2 `server/IniStore.kt`
- `parseIni(text): Map<String, Map<String,String>>` and `writeIni(map): String` — **pure**, testable.
- `LlamaConfig.fromIni(...)` / `toIni()` mapping.
- `ConfigRepository(context)` — loads `config.ini` (seeding defaults if absent) and saves it. Thin IO
  wrapper over the pure functions.

### 5.3 `server/ArgsBuilder.kt` (pure)
`buildArgs(config, binaryPath, modelsDir): List<String>` → the full argv. Emits a flag only when its
field is set/non-empty; resolves `model/draft/mmproj` to absolute paths under `modelsDir`; appends
tokenized `extra_args`. **Unit-tested** against the gemma-4 config (exact argv asserted).

### 5.4 `server/ModelDownloader.kt`
- `hfUrl(repo, file): String` = `https://huggingface.co/<repo>/resolve/main/<file>` — **pure**.
- `resumeOffset(existingBytes, totalBytes): Long?` and completeness check — **pure**, tested.
- `download(file, onProgress)` — `HttpURLConnection` with `Range` resume, writes to `models/`, IO
  dispatcher. (No new networking dependency; injectable connection-opener for testability.)
- "installed" = local size == `Content-Length` (HEAD), recorded per file.

### 5.5 `server/LlamaServerService.kt` (foreground Service)
- Lifecycle: `start` → validate binary + model files exist → `ProcessBuilder(args)` with
  `redirectErrorStream(true)`, working dir = models dir, **`LD_LIBRARY_PATH=nativeLibraryDir`** (so the
  dynamically-linked server finds its `.so` deps) → start process → promote to foreground with a
  notification → spawn a reader thread piping stdout lines to `LogBus`.
- `stop` → `process.destroy()` (then `destroyForcibly()` after grace) → stop foreground/self.
- On unexpected exit: emit exit code to `LogBus`, broadcast status → stopped.
- Declared with `foregroundServiceType="specialUse"`.

### 5.6 `server/LogBus.kt`
Process-wide `MutableStateFlow<List<LogLine>>` (capped) the service writes and the UI observes; tee
to rolling `logs/server.log`. Replaces `MonitorViewModel.nextLogLine()` and the mock log appends.

### 5.7 `MonitorViewModel` wiring
- `toggleRunning()` → `startService`/`stopService` (+ status from a service callback/flow).
- Logs sourced from `LogBus` (remove mock log generation; keep CPU/mem/temp telemetry).
- `downloadModel()` → real `ModelDownloader` per file with progress into state.
- Settings panel ↔ `ConfigRepository` (load/save `config.ini`).
- New UI state: `serverStatus` (Stopped/Starting/Running/Error+msg) and per-file download state.

## 6. Manifest / build changes

- `app/build.gradle.kts`: `android { packaging { jniLibs { useLegacyPackaging = true } } }`.
- `AndroidManifest.xml`:
  - `<uses-permission INTERNET>`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
    `POST_NOTIFICATIONS` (Android 13+).
  - `<application android:extractNativeLibs="true">`.
  - `<service android:name=".server.LlamaServerService" android:foregroundServiceType="specialUse" android:exported="false">`
    (+ the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property).
- New dir `app/src/main/jniLibs/arm64-v8a/` with `README` describing the binary slot.

## 7. Data flow

1. First launch → `ConfigRepository` seeds `config.ini` (gemma-4 profile).
2. User downloads → 3 files fetched to `models/` with progress → marked installed.
3. **Start** → service validates binary + files → `ArgsBuilder` → exec → logs stream → status Running
   ("listening on 0.0.0.0:8080" appears from the server's own output).
4. **Stop** → process killed, foreground dropped, status Stopped.

## 8. Error handling

| Condition | Behavior |
|---|---|
| Binary missing in `nativeLibraryDir` | Status Error; log "server binary not bundled"; Start no-ops. |
| Model file(s) missing | Status Error; log which file; prompt to download. |
| Download interrupted | Resume via `Range` on retry; surfaced on the model row. |
| Process exits unexpectedly | Status Stopped; exit code + tail logged. |
| Port in use / model load failure / unknown flag | Surfaced verbatim from server stderr in the log window. |
| `POST_NOTIFICATIONS` denied (A13+) | Service still runs; request rationale once. |

## 9. Testing

Host-JVM unit tests (Gradle `testDebugUnitTest`, now working):
- `IniStore`: parse/write round-trip; missing-section/garbage handling; `LlamaConfig` ↔ INI.
- `ArgsBuilder`: exact argv for the gemma-4 config; omission of empty fields; `extra_args` tokenizing.
- `ModelDownloader`: `hfUrl` building; `resumeOffset`/completeness math.

Not unit-tested (thin glue, needs a device): `LlamaServerService` exec, notification, `LogBus` tee.
Manual verification once the binary `.so` is in place: Start → server boots → logs stream → Stop.

## 10. File-by-file change list

**New:** `server/LlamaConfig.kt`, `server/IniStore.kt`, `server/ArgsBuilder.kt`,
`server/ModelDownloader.kt`, `server/LlamaServerService.kt`, `server/LogBus.kt`;
tests `IniStoreTest`, `ArgsBuilderTest`, `ModelDownloaderTest`;
`jniLibs/arm64-v8a/README`.
**Modified:** `MonitorViewModel.kt`, `model/MonitorState.kt` (status + download/config state),
`ui/menu/SubPanels.kt` (download + settings wiring), `AndroidManifest.xml`, `app/build.gradle.kts`.

## 11. Risks / open items

- The `gemma-4` arch / `--spec-type draft-mtp` support depends on the bundled build (b9775). User has
  tested this release with these flags; any rejection/load error surfaces live in the logs regardless.
- Large downloads (~3.2 GB) on metered networks — out of scope to gate; resumable.
- `useLegacyPackaging=true` slightly enlarges the APK (binary stored uncompressed-on-disk at runtime).
- Repo is **not** a git repository yet, so this spec is written but not committed.
