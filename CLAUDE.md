# Cruise Control (aiauto)

Android Auto app that sends spoken coding requests to Claude Managed Agents sessions and opens GitHub pull requests.

## Layout

- `core/`: plain Kotlin, all the logic and its tests. `TaskEngine.kt` runs tasks; `VoiceCommand.kt` parses short
  spoken replies (status, stop, merge, fix, undo, start over, yes, no) that `TaskEngine.prompt` handles without
  Claude; `PendingAction` in `Models.kt` holds a yes-or-no question waiting on the driver. `VoiceControls.kt` and
  `Assistant.kt` are the car chat side. Tests live in `core/src/test/kotlin/dev/aiauto/core` with in-file fakes.
- `app/`: Android app (Compose phone UI, Car App Library UI, notifications).
- `README.md` documents user-facing features (see "Voice commands"); `CHANGELOG.md` has an Unreleased section;
  `docs/FEATURE_IDEAS.md` tracks ideas and which are built.

## Build and test

- `./gradlew :core:test` runs the tests (JDK 17+, needs network the first time; `--offline` fails without a cache).
- `./gradlew :core:test :app:assembleDebug` is what CI runs; the app build needs the Android SDK (API 35).

## Conventions

- Spoken strings are plain sentences. Canadian spelling in docs and commits.
- Keep changes small; update README, CHANGELOG and tests with each feature.
