# Cruise Control (aiauto)

Android Auto app that runs Claude Managed Agents coding tasks on the user's GitHub repos and reads updates aloud.

## Layout

- core/ is plain Kotlin (JVM 17): ClaudeAgents (Managed Agents client), TaskEngine (task lifecycle, prompts,
  parked-mode approvals), TaskJournal, PullRequests, GitHub REST, Assistant (car chat), Models. Tests in
  core/src/test use fakes of AgentClient, GitHub and TaskStore.
- app/ is the Android app: car/ (Car App Library screens, ParkedWatcher reads car speed), ui/ (Compose phone UI),
  data/ (stores, secrets, sync), notify/ (notifications, speech), Graph.kt wires everything.
- docs/FEATURE_IDEAS.md is the feature wish list; mark items "Built" there when done. README documents features.

## Build and test

- Core tests: ./gradlew :core:test (no Android SDK needed).
- App build: needs JDK 17+ and Android SDK API 35 (ANDROID_HOME). ./gradlew :core:test :app:assembleDebug
  The sandbox has no SDK by default; installing cmdline-tools to /opt/sdk with platforms;android-35 works.

## Conventions

- Plain, short KDoc on public functions; user-facing strings are speakable sentences.
- Changing the AgentClient interface means updating the FakeClaude classes in three test files.
- Update README and docs/FEATURE_IDEAS.md with any feature change.
