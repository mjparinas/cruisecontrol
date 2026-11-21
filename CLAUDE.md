# CLAUDE.md

Cruise Control: an Android Auto app that runs Claude coding tasks (Managed Agents sessions) on GitHub repos by voice,
opens pull requests and reads updates aloud.

## Layout

- `core/`: plain Kotlin logic (task engine, GitHub client, intents, sync) in `core/src/main/kotlin/dev/aiauto/core`,
  unit tests in `core/src/test/kotlin/dev/aiauto/core`.
- `app/`: Android app in `app/src/main/java/dev/aiauto` (`car` for the Car App Library screens, `ui`, `data`,
  `notify`, `update`, `widget`).
- `README.md`: user docs. `CHANGELOG.md`: release notes. `docs/FEATURE_IDEAS.md`: future ideas, grouped by theme,
  with a "Built:" line at the top of a section once its ideas ship.

## Build and test

JDK 17 and Android SDK 35: `./gradlew :core:test :app:assembleDebug` (same as CI in `.github/workflows/build.yml`).
CI skips Markdown-only changes, so doc-only edits have no tests to run.

## Conventions

- Canadian spelling in docs, commits and PR text.
- Docs are plain and short; feature ideas are one bold-titled bullet each.
