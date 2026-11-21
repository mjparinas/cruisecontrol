# Cruise Control (aiauto)

Android app that runs Claude coding tasks on GitHub repos from Android Auto. See README.md for features.

## Layout
- core/: plain Kotlin (JVM 17). Managed Agents client, GitHub REST calls, task engine, voice commands, sync.
  Tests live in core/src/test/kotlin/dev/aiauto/core (kotlin.test, JUnit Platform).
- app/: Android app (package dev.aiauto). Phone UI is Jetpack Compose in app/src/main/java/dev/aiauto/ui
  (task chat is TaskScreen.kt), car UI in car/, notifications in notify/, storage and sync in data/.
  The app module has no unit or UI tests; testable logic belongs in core.
- docs/FEATURE_IDEAS.md, CHANGELOG.md (written per release, no Unreleased section).

## Build and test
- Full: ./gradlew :core:test :app:assembleDebug (needs JDK 17 and Android SDK API 35).
- In the cloud sandbox the Android Gradle plugin can't be downloaded and there's no SDK, so the root build fails
  to configure. To run core tests, copy core/, gradle/ and gradlew to a scratch dir with a settings file that only
  includes :core and a root build file declaring kotlin("jvm") and kotlin("plugin.serialization") 2.1.0, then run
  ./gradlew :core:test.

## Conventions
- Kotlin, 4-space indent, long lines are fine; short comments only where logic isn't obvious.
- User-facing text and docs use plain, short sentences.
