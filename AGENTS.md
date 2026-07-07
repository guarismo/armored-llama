# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android project. The app module lives in `app/`, with production Kotlin sources under `app/src/main/java/com/iguar/armoredllama/`. UI code is organized under `ui/`, runtime/server logic under `server/`, device parsing under `device/`, and shared state under `model/`. Android resources are in `app/src/main/res/`; bundled native runtime files are staged in `app/src/main/jniLibs/arm64-v8a/`. Unit tests live in `app/src/test/java/...`, and instrumentation tests live in `app/src/androidTest/java/...`. Design notes and implementation plans are kept in `docs/superpowers/`.

## Build, Test, and Development Commands

Use the Gradle wrapper from the repository root:

- `.\gradlew.bat assembleDebug` builds a debug APK and runs the `preBuild` task that stages the pinned llama.cpp arm64 server.
- `.\gradlew.bat test` runs local JVM unit tests.
- `.\gradlew.bat connectedAndroidTest` runs instrumentation tests on a connected device or emulator.
- `.\gradlew.bat clean` removes generated build outputs.

On Unix-like shells, use `./gradlew` with the same task names.

## Coding Style & Naming Conventions

The codebase is Kotlin-first with Jetpack Compose. Use 4-space indentation, idiomatic Kotlin naming, and keep packages under `com.iguar.armoredllama`. Compose functions and screen-level components use PascalCase, such as `MonitorScreen`; state, parser, and service classes also use PascalCase. Test classes should mirror the subject under test, for example `ArgsBuilderTest` for `ArgsBuilder`.

## Testing Guidelines

Local tests use JUnit 4 and are placed in `app/src/test/java`. Prefer focused parser, configuration, and service-helper tests that do not require Android framework state. Instrumentation tests use AndroidX test dependencies in `app/src/androidTest/java`. Add or update tests when changing command-line argument construction, release parsing, runtime binary handling, download math, telemetry parsing, or persistence behavior.

## Commit & Pull Request Guidelines

Recent history uses Conventional Commit-style subjects with scopes, for example `feat(update): ...`, `fix(update): ...`, and `refactor(service): ...`. Keep subjects imperative and scoped to the affected area. Pull requests should describe the behavior change, list test commands run, link related issues or design docs when applicable, and include screenshots or screen recordings for visible UI changes.

## Security & Configuration Tips

Do not commit machine-specific secrets or SDK paths. `local.properties` is environment-specific and should stay local. Be careful when changing the pinned llama.cpp release or native packaging settings, because `fetchLlamaServer` downloads and stages executable native artifacts into `jniLibs`.
