# Cruise Control (aiauto)

Android and Android Auto app that sends voice coding requests to Claude Managed Agents and follows the GitHub pull requests.

## Layout
- core/: plain Kotlin. Models (Task, TaskStatus, list helpers like needsYouFirst and runningCount) in Models.kt, task engine, GitHub and Claude clients, voice commands. Tests in core/src/test/kotlin (kotlin.test).
- app/: Android app, package dev.aiauto. Phone UI in ui/ (Compose; HomeScreen.kt is the task list with Active and Archived tabs), car UI in car/, notifications in notify/, widget in widget/.
- README.md documents user-facing behaviour; CHANGELOG.md is per release.

## Build and test
- ./gradlew :core:test runs the unit tests (needs network the first time for plugins; --offline fails).
- CI also runs :app:assembleDebug, which needs an Android SDK; the sandbox has none, so app code can't be compiled locally.
- Put testable logic in core and keep app code thin, since only core has tests.

## Conventions
- Canadian spelling in text, docs and commits. Short KDoc comments on public helpers. Long lines (about 120 to 150 chars) are fine.
