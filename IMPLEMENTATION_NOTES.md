# Armored Llama — implementation notes

Native **Kotlin + Jetpack Compose** build of the `llama.cpp` Android monitor, recreated from the
design handoff (`Android app monitoring tool (3).zip → design_handoff_llamacpp_monitor/`).

**Chosen layout: A · Console** (RAM hero card → 4-up stat row → 8-core grid → log feed).
Layouts B and C were alternatives and are not built.

## Status
Everything visible matches the hi-fi spec (dark + light tokens, typography, radii, the drawer and
all three sub-screens). **Device telemetry — CPU %, per-core MHz, memory and temperature — now reads
real `/proc` + `sysfs` data**, with the eased random walk kept as a per-band fallback when a source is
hidden (SELinux, emulator, offline core). The core grid is sized to the real CPU and bars scale by
`cpuinfo_max_freq`. **The server now really runs**: Start/Stop launch a bundled or downloaded
`llama-server` via a foreground service, real logs stream into the feed, and model files download from
Hugging Face. **Throughput (tok/s + prompt-processing) is parsed live from the server log and shown as
two stat tiles, and the Settings flags now reach the launch.** GPU telemetry was dropped because the
bundled build is CPU-only.

The real reads live in `device/`: pure parsers in `ProcParsers.kt` and IO in `DeviceTelemetry.kt`
(over a `FileSource` seam so the logic is unit-tested without a device). The server runtime lives in
`server/`: pure `IniStore`/`ArgsBuilder`/`DownloadMath` (host-JVM tested) plus Android glue
`ConfigRepository`/`ModelDownloader`/`LogBus`/`LlamaServerService`. `MonitorViewModel.tick()` merges
real telemetry over mock; logs/status come from the service.

> Fonts: `ui/theme/Type.kt` currently maps to the platform sans/monospace families. Drop
> `Space Grotesk` + `JetBrains Mono` `.ttf`s into `res/font/` and update that file for full fidelity.

## Where things live
| Concern | File |
|---|---|
| Design tokens (dark/light) | `ui/theme/Color.kt` |
| Theme + typography | `ui/theme/Theme.kt`, `ui/theme/Type.kt` |
| State model | `model/MonitorState.kt` |
| State holder (telemetry + service/download wiring) | `MonitorViewModel.kt` |
| Server runtime (config/args/download/logs/service) | `server/` |
| Top bar (Start/Stop, status) | `ui/components/TopBar.kt` |
| Ring / sparkline / bars | `ui/components/Charts.kt` |
| Log window | `ui/components/LogWindow.kt` |
| Console dashboard (layout A) | `ui/console/ConsoleDashboard.kt` |
| Drawer + sub-panel host | `ui/menu/MenuOverlay.kt` |
| Steppers / toggles | `ui/menu/MenuControls.kt` |
| Settings / Update / HF panels | `ui/menu/SubPanels.kt` |
| Root composition | `ui/MonitorScreen.kt`, `MainActivity.kt` |

## Wiring Status
1. ✅ **Process control** — done. `MonitorViewModel.toggleRunning()` starts/stops
   `server/LlamaServerService` (foreground, declared in the manifest), which execs the bundled
   `libllamaserver.so` from `nativeLibraryDir` with `LD_LIBRARY_PATH` set.
2. ✅ **Server logs** — done. The service streams real stdout/stderr into `server/LogBus`, which the
   ViewModel collects into `state.logs` (also tee'd to `logs/server.log`). Mock generator removed.
3. ✅ **CPU % + per-core MHz** — done. `DeviceTelemetry.cpuPercent()`/`coreMhz()` from `/proc/stat`
   + `cpufreq/scaling_cur_freq`; core grid sized to the real CPU; bars scale by `metrics.maxCoreMhz`
   (`cpuinfo_max_freq`). Falls back to the mock walk when sysfs is hidden.
4. ✅ **Memory** — done. `DeviceTelemetry.memory()` from `/proc/meminfo` (`MemTotal`/`MemAvailable`).
5. ✅ **Temperature** — done. `DeviceTelemetry.temperature()` scans `thermal_zone*/temp`, preferring
   a CPU/SoC zone (`selectTemperature`).
6. ✖ **GPU** — removed. The bundled llama.cpp build is CPU-only, so GPU metrics were misleading. The
   GPU% stat tile was replaced by a **PP** (prompt-processing t/s) tile; the `gpu*` metric fields,
   `DeviceTelemetry.gpuPercent()` and `parsePercent` were deleted.
7. ✅ **Throughput** — done. `server/ThroughputParser` parses the server's live `print_timing`
   lines (`tg` for gen tok/s, `prompt processing …/X tokens per second` for pp, plus the final
   `eval time` snapshots) off `LogBus.raw` (uncapped); zeroed on `all slots are idle` and on stop.
   The dashboard shows two tiles: **TOK/S** (generation) and **PP** (prompt processing).
8. ◐ **Settings → launch flags** — done for the exposed controls. `updateSettings` persists
   `ctx/threads/port` + `flash_attn/cont_batching/mlock/useDraft/useMmproj` to `config.ini`, seeded
   back on startup and applied at launch via `server/ArgsBuilder` (`--flash-attn on|off`,
   `--cont-batching`/`--no-cont-batching`, `--mlock`, `--model-draft`, `--mmproj`). GPU-layers
   control removed (CPU-only build). Changes apply on the next Stop→Start (argv is built at process
   launch); Start flushes settings synchronously to avoid racing the service config read.
9. ✅ **Update llama.cpp** — done. `MonitorViewModel.checkForUpdate()` reads GitHub Releases for
   `ggml-org/llama.cpp`; `downloadUpdate()` downloads the android-arm64 tarball, `RuntimeBinaries`
   extracts `llama-server` plus `*.so` into `filesDir/llama/<tag>/`, records it active, and prunes
   older downloaded versions. `LlamaServerService` runs `RuntimeBinaries.activeExecutable()`, so a
   downloaded active binary wins and a missing/invalid install falls back to bundled `b9775`. The
   Update panel also exposes "Remove downloaded runtime" to clear app-storage runtimes and return to
   the bundled fallback.
10. ✅ **Download model** — done. The HF panel shows the configured model when search is blank, and
    calls the Hugging Face API for GGUF search queries. Results resolve each repo to a primary `.gguf`
    file, show repo/file/quant/size where available, and `Get` updates `config.ini` before downloading
    via `server/ModelDownloader` (resumable). Generic search results are text-only by default; the
    curated Gemma profile keeps its draft/mmproj companion files.

## Build
Open in Android Studio (the project uses AGP 9.2.1 + the block `compileSdk { … }` DSL). The Compose
stack lives in `gradle/libs.versions.toml`, `build.gradle.kts`, and `app/build.gradle.kts`.

AGP 9 compiles Kotlin via **built-in support**, so there is no `org.jetbrains.kotlin.android`
plugin — applying it double-registers the `kotlin` extension and fails configuration. Kotlin is
configured through `kotlin { compilerOptions { … } }` (not the old `android.kotlinOptions{}`), and
`kotlin = 2.2.10`. Verified from the CLI: `./gradlew :app:assembleDebug` and
`:app:testDebugUnitTest` both pass.

### Bundled llama-server binary
The `fetchLlamaServer` Gradle task (in `app/build.gradle.kts`, run automatically before every build)
downloads llama.cpp release **b9775** android-arm64 and stages all its `*.so` plus the `llama-server`
executable (renamed `libllamaserver.so`) into `app/src/main/jniLibs/arm64-v8a/`. These are
**git-ignored** and re-fetched on a clean checkout. Because that build is dynamically linked, the
service uses it as the fallback runtime when no downloaded active binary is installed. Downloaded
updates live under app storage (`filesDir/llama/<tag>/`) and run with `LD_LIBRARY_PATH` pointed at
that version directory. To pin a different bundled build, change `llamaRelease` in
`app/build.gradle.kts` and keep `RuntimeBinaries.BUNDLED_TAG` in sync. **Note:** the experimental
flags in the seeded `config.ini`
(`--spec-type draft-mtp`, `--spec-draft-*`, `--tools all`) require a build that supports them; the
server's own acceptance/errors show up live in the log window.

### Manual end-to-end check (needs an arm64 device)
Install on an arm64 phone, download the gemma-4 model from the Hugging Face panel, then tap Start: the
log window shows the `exec:` line, the server boot output, and the "listening" line; Stop kills it
(log shows `server exited`).
