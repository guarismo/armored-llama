# Armed Llama — implementation notes

Native **Kotlin + Jetpack Compose** build of the `llama.cpp` Android monitor, recreated from the
design handoff (`Android app monitoring tool (3).zip → design_handoff_llamacpp_monitor/`).

**Chosen layout: A · Console** (RAM hero card → 4-up stat row → 8-core grid → log feed).
Layouts B and C were alternatives and are not built.

## Status
Everything visible matches the hi-fi spec (dark + light tokens, typography, radii, the drawer and
all three sub-screens). **Device telemetry — CPU %, per-core MHz, memory and temperature (WIRE THIS
#3–5) — now reads real `/proc` + `sysfs` data**, with the eased random walk kept as a per-band
fallback when a source is hidden (SELinux, emulator, offline core). The core grid is sized to the
real CPU and bars scale by `cpuinfo_max_freq`. **The server now really runs**: Start/Stop launch a
bundled `llama-server` via a foreground service, its real logs stream into the feed, and the gemma-4
model downloads from Hugging Face. **Throughput (tok/s + prompt-processing) is parsed live from the
server log and shown as two stat tiles, and the Settings flags now reach the launch.** GPU telemetry
was dropped (the bundled build is CPU-only). The llama.cpp self-update (#9) remains mock. See the
"WIRE THIS" list below.

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

## ⚠️ WIRE THIS — mock → real, with the seam to replace
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
   `ctx/threads/port` + `flash_attn/cont_batching/mlock` to `config.ini`, seeded back on startup and
   applied at launch via `server/ArgsBuilder` (`--flash-attn on|off`, `--cont-batching`/
   `--no-cont-batching`, `--mlock`). GPU-layers control removed (CPU-only build). Changes apply on
   the next Stop→Start (argv is built at process launch). Still TODO: surface the model flags
   (mmproj/draft/spec) in the UI, and move the `config.ini` write off the main thread.
9. **Update llama.cpp** — `MonitorViewModel.startDeploy()`; GitHub Releases API for
   `ggml-org/llama.cpp`, download `android-arm64`, install, restart. (Still mock; the runtime binary
   is currently bundled at build time — see Build below.)
10. ◐ **Download model** — wired for the gemma-4 model. `MonitorViewModel.downloadModel()` pulls the
    configured GGUF files (model/draft/mmproj) from Hugging Face via `server/ModelDownloader`
    (resumable) into app storage; "Installed" = present on disk. Generic HF search UI still TODO.

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
service runs it with `LD_LIBRARY_PATH = nativeLibraryDir`. To pin a different build, change
`llamaRelease` in `app/build.gradle.kts`. **Note:** the experimental flags in the seeded `config.ini`
(`--spec-type draft-mtp`, `--spec-draft-*`, `--tools all`) require a build that supports them; the
server's own acceptance/errors show up live in the log window.

### Manual end-to-end check (needs an arm64 device)
Install on an arm64 phone, download the gemma-4 model from the Hugging Face panel, then tap Start: the
log window shows the `exec:` line, the server boot output, and the "listening" line; Stop kills it
(log shows `server exited`).
