# Cruise Control (aiauto)

Android app that lets you talk to Claude from Android Auto; Claude works on GitHub repos via Claude Managed Agents
and the app opens pull requests. See README.md for the user-facing description.

## Layout
- core/: plain Kotlin (Managed Agents client, GitHub REST, task engine). Tests in core/src/test/kotlin.
- app/: Android app (Compose phone UI, Android for Cars car UI, notifications).
- .github/workflows/build.yml: tests and debug APK on every push and PR.
- .github/workflows/release.yml: signed release APK and GitHub release, from a v* tag or Run workflow. With no
  version entered it uses .github/scripts/next-version.sh (next patch after the latest tag, same rule as
  Versions.nextPatch in core/.../Updates.kt). Keep the two in step; next-version-test.sh mirrors UpdatesTest.
- CHANGELOG.md, docs/FEATURE_IDEAS.md.

## Build and test
- JDK 17 and Android SDK 35: ./gradlew :core:test :app:assembleDebug
- Release script tests: .github/scripts/next-version-test.sh

## Conventions
- Plain, short user-facing text; README describes features in prose. Update README when behaviour changes.
- Commit messages are short imperative sentences; PRs are opened by the app, not by the agent.
