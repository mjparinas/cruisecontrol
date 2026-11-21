# Cruise Control (aiauto)

Android app that talks to Claude Managed Agents from Android Auto: pick a GitHub repo, speak a request, Claude works
in the cloud and opens a pull request. See README.md for the user-facing behaviour.

## Layout

- core/: plain Kotlin, no Android. Agents client, GitHub REST, task engine, voice command parsing, settings search
  (SettingsSearch.kt). Unit tests in core/src/test (kotlin.test).
- app/: Android app, package dev.aiauto. Phone UI is Jetpack Compose in app/src/main/java/dev/aiauto/ui; car UI
  (Android for Cars App Library) in car/; stores and sync in data/; notifications and speech in notify/.
- Settings screen: ui/SettingsScreen.kt with extra sections in ui/SettingsSections.kt. Shared rows, cards and headers
  live in ui/Design.kt. Each section is wrapped in SearchableSection(name); when you add or rename a setting row,
  update SettingsSearch.CATALOGUE in core to match its title.
- The app module has no unit tests; put testable logic in core.

## Build and test

- CI runs: ./gradlew :core:test :app:assembleDebug
- The app needs an Android SDK (compileSdk 35) via ANDROID_HOME. If none is installed, download the command-line
  tools from dl.google.com and use sdkmanager to install platforms;android-35 and build-tools;35.0.0.

## Conventions

- Plain, short user-facing copy. Small, focused changes. Update README.md when behaviour changes. CHANGELOG.md is
  only updated when a release is cut.
