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
real CPU and bars scale by `cpuinfo_max_freq`. **Logs, GPU, throughput, downloads and process state
are still mock.** See the "WIRE THIS" list below for what remains.

The real reads live in `device/`: pure parsers in `ProcParsers.kt` and IO in `DeviceTelemetry.kt`
(over a `FileSource` seam so the logic is unit-tested without a device — see
`app/src/test/.../device/`, 20 host-JVM tests). `MonitorViewModel.tick()` merges real over mock.

> Fonts: `ui/theme/Type.kt` currently maps to the platform sans/monospace families. Drop
> `Space Grotesk` + `JetBrains Mono` `.ttf`s into `res/font/` and update that file for full fidelity.

## Where things live
| Concern | File |
|---|---|
| Design tokens (dark/light) | `ui/theme/Color.kt` |
| Theme + typography | `ui/theme/Theme.kt`, `ui/theme/Type.kt` |
| State model | `model/MonitorState.kt` |
| State holder + mock producers | `MonitorViewModel.kt` |
| Top bar (Start/Stop, status) | `ui/components/TopBar.kt` |
| Ring / sparkline / bars | `ui/components/Charts.kt` |
| Log window | `ui/components/LogWindow.kt` |
| Console dashboard (layout A) | `ui/console/ConsoleDashboard.kt` |
| Drawer + sub-panel host | `ui/menu/MenuOverlay.kt` |
| Steppers / toggles | `ui/menu/MenuControls.kt` |
| Settings / Update / HF panels | `ui/menu/SubPanels.kt` |
| Root composition | `ui/MonitorScreen.kt`, `MainActivity.kt` |

## ⚠️ WIRE THIS — mock → real, with the seam to replace
1. **Process control** — `MonitorViewModel.toggleRunning()`; declare the foreground `Service` in
   `AndroidManifest.xml` (placeholder comment already there).
2. **Server logs** — `MonitorViewModel.nextLogLine()` / the `logs` list; stream real stdout/stderr.
3. ✅ **CPU % + per-core MHz** — done. `DeviceTelemetry.cpuPercent()`/`coreMhz()` from `/proc/stat`
   + `cpufreq/scaling_cur_freq`; core grid sized to the real CPU; bars scale by `metrics.maxCoreMhz`
   (`cpuinfo_max_freq`). Falls back to the mock walk when sysfs is hidden.
4. ✅ **Memory** — done. `DeviceTelemetry.memory()` from `/proc/meminfo` (`MemTotal`/`MemAvailable`).
5. ✅ **Temperature** — done. `DeviceTelemetry.temperature()` scans `thermal_zone*/temp`, preferring
   a CPU/SoC zone (`selectTemperature`).
6. **GPU % + mem** — `tick()` `gpu`/`gpuMem*`; vendor sysfs (Adreno `kgsl`). Hide if unreadable.
7. **Throughput** — `tick()` `tps` (gen) + `pp` (prompt); parse server timings / `/metrics`.
8. **Settings → launch flags** — `MonitorViewModel.updateSettings`; apply `-c/--threads/-ngl/-fa/
   --cont-batching/--mlock/--port` and persist.
9. **Update llama.cpp** — `MonitorViewModel.startDeploy()`; GitHub Releases API for
   `ggml-org/llama.cpp`, download `android-arm64`, install, restart.
10. **Download model** — `MonitorViewModel.downloadModel()` / `seedModels()`; HF API search + GGUF
    pull to app storage; "Installed" = present on disk.

## Build
Open in Android Studio (the project uses AGP 9.2.1 + the block `compileSdk { … }` DSL). The Compose
stack lives in `gradle/libs.versions.toml`, `build.gradle.kts`, and `app/build.gradle.kts`.

AGP 9 compiles Kotlin via **built-in support**, so there is no `org.jetbrains.kotlin.android`
plugin — applying it double-registers the `kotlin` extension and fails configuration. Kotlin is
configured through `kotlin { compilerOptions { … } }` (not the old `android.kotlinOptions{}`), and
`kotlin = 2.2.10`. Verified from the CLI: `./gradlew :app:assembleDebug` and
`:app:testDebugUnitTest` both pass.
