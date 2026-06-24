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
