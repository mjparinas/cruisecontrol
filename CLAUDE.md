# Notes for Claude

Read this first. It saves exploring the whole repository on every task. Keep it short and fix it when it's wrong.

The product, engineering and development decisions behind the app live in AGENTS.md, imported here. Read it
before changing anything, and update it in the same pull request when a change affects a decision.

@AGENTS.md

## What this is

Cruise Control: an Android and Android Auto app that sends spoken coding requests to Claude Managed Agents sessions,
reads the updates aloud, opens the pull request and follows its checks. The README describes every feature.

## Layout

- `core/` is plain Kotlin (JVM 17) with all the logic and almost every unit test. Package `dev.aiauto.core`.
  - `TaskEngine.kt` runs tasks: one session per task, prompts, the event stream, reconnects, and the daily and
    monthly spending limits through `SpendingLimit.kt`.
  - `ClaudeAgents.kt` is the Managed Agents client. Its companion holds `SYSTEM_PROMPT` (baked into the agent)
    and `firstPrompt` (sent with each task, which starts by asking Claude to read README.md, CLAUDE.md and
    AGENTS.md). The agent is only remade when `AgentProfile` changes, so new per-task instructions belong in
    `firstPrompt`.
  - `AgentProfile.kt` has the model, effort and standing rules picked in Settings (global). `RepoSettings.kt` has
    the per-repo settings: pull request switches and repo rules, read through `EngineConfig.repoSettings(repo)`.
    `Models.kt` has `Task`, `RepoRef`, `AgentSetup` and friends.
  - `GitHub.kt` is the REST client; `PullRequests.kt` follows checks, merges and auto-fixes; `Projects.kt` is
    the per-repo status view.
  - `Assistant.kt` is the spoken car chat, including the queue of questions asked with no signal, which
    `Graph.watchNetwork` sends when the phone is back online. `ChatProviders.kt` has the car chat models and talks to Claude, OpenAI
    (Responses API) or Gemini (OpenAI-compatible endpoint); tests fake `ChatHttp`. `VoiceCommand.kt` and `VoiceControls.kt` handle short spoken
    replies; `SpokenStatus.kt` turns text into speech-friendly sentences; `Briefing.kt` is the drive briefing.
  - `Sync.kt` merges state between devices; `Updates.kt` checks for new app releases
    (the highest recent release with an APK) and picks release-on-merge tags.
- `app/` is the Android app, package `dev.aiauto`. `Graph.kt` wires everything together. `ui/` is the
  Compose phone UI, `car/` is the Car App Library UI (its `VoiceInput` also serves the phone's prompt box when
  Keep listening until Stop is on; `core/Dictation.kt` decides when it listens again, sends or gives up),
  `notify/` holds notifications, text-to-speech and `TonePlayer` (listening and thinking tones, made by `core/Tones.kt`),
  `data/` holds the stores, encrypted secrets and sync, `widget/` is the home-screen widget. `data/Stores.kt`
  `Settings` keeps every setting in SharedPreferences; `SYNCED_KEYS` lists the ones that sync.
- Settings screen: `ui/SettingsScreen.kt` and `ui/SettingsSections.kt`, built from rows in `ui/Design.kt`. Each section
  is wrapped in `SearchableSection(name)`; when you add or rename a setting row, update `SettingsSearch.CATALOGUE`
  in `core/SettingsSearch.kt` to match its title.
- `docs/FEATURE_IDEAS.md` lists ideas; `docs/PLAY_STORE_RELEASE.md` is the plan for Google Play; `CHANGELOG.md` has
  release notes.

## Build and test

- `./gradlew :core:test` runs the core unit tests and needs only a JDK. The few app tests need the Android SDK.
- The agent sandbox starts without an Android SDK (see below to install one). The first run downloads from Maven
  Central, which sometimes answers 429 Too Many Requests; just run it again.
- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` is what CI runs (needs the Android SDK,
  API 37.2). Lint only reports. Without an SDK, install the command-line tools from dl.google.com, run
  sdkmanager for `platforms;android-37.2` and set `ANDROID_HOME`. Maven Central may answer 429; just rerun.
- CI skips pushes that only change Markdown files, except AGENTS.md: `AgentsDocTest` checks it against the code.

## Releasing

- Cutting a release means moving CHANGELOG's Unreleased notes (plus any merged features they missed) into a version
  entry and pointing the README's release example at it; `ChangelogTest` checks both. After the pull request merges,
  the owner runs the Release workflow with that version. The agent can't push tags.
- Release when a PR is merged is on for this repo, so every merge publishes a patch tag. Check the real latest
  version with `git ls-remote --tags origin`. The local `main` branch can be stale: branch from `origin/main`.

## Conventions

- Tests live in `core/src/test/kotlin/dev/aiauto/core`, one file per area, using kotlin.test with fakes for
  `AgentClient`, `EngineConfig` and `TaskStore`. Logic that needs testing goes in `core`, not `app`.
- Short KDoc on public things, written as plain sentences. Comments explain why.
- App text, messages and commits use Canadian spelling and plain, speakable sentences.
- Update the README when a user-visible behaviour changes.
