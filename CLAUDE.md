# CLAUDE.md

Cruise Control: an Android and Android Auto app that sends spoken coding requests to Claude Managed Agents, which work
on a GitHub repo, push a branch, and the app opens and watches the pull request. See README.md for the user-facing story.

## Layout

- `core/`: plain Kotlin (JVM 17). Models (`Models.kt`: Task, TaskStatus, Checks), TaskEngine, GitHub client,
  SpokenStatus (short speakable strings), voice commands, sync. All unit tests live in `core/src/test`.
- `app/`: Android app. Phone UI is Jetpack Compose in `app/.../ui` (StatusPill and Pill in Components.kt, shared
  look in Design.kt). Car UI in `app/.../car` (TasksScreen.statusLabel is reused by the widget and the car).
  The app module has no tests, so put any logic worth testing in core and keep the UI thin.
- `docs/FEATURE_IDEAS.md`, `CHANGELOG.md` (only updated when a release is cut).

## Build and test

- `./gradlew :core:test` runs the tests. Don't use `--offline` on a fresh machine; Gradle needs to download plugins.
- The app module needs the Android SDK, which the cloud sandbox doesn't have, so it can't be compiled there.
- CI: `.github/workflows/build.yml`.

## Conventions

- Canadian spelling in user-facing text, comments and commits (colour, centre).
- KDoc one-liners on model properties; match the existing style and keep changes small.
- User-facing strings are short and speakable (they're read aloud while driving).
