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
  - `TaskEngine.kt` runs tasks: one session per task, prompts, the event stream, reconnects, the spending limit.
  - `ClaudeAgents.kt` is the Managed Agents client. Its companion holds `SYSTEM_PROMPT` (baked into the agent)
    and `firstPrompt` (sent with each task). The agent is only remade when `AgentProfile` changes, so new
    per-task instructions belong in `firstPrompt`.
  - `AgentProfile.kt` has the model, effort and standing rules picked in Settings. `Models.kt` has `Task`,
    `RepoRef`, `AgentSetup` and friends.
  - `GitHub.kt` is the REST client; `PullRequests.kt` follows checks, merges and auto-fixes; `Projects.kt` is
    the per-repo status view.
  - `Assistant.kt` is the spoken car chat; `VoiceCommand.kt` and `VoiceControls.kt` handle short spoken
    replies; `SpokenStatus.kt` turns text into speech-friendly sentences; `Briefing.kt` is the drive briefing.
  - `Sync.kt` merges state between devices; `Updates.kt` checks for new app releases.
- `app/` is the Android app, package `dev.aiauto`. `Graph.kt` wires everything together. `ui/` is the
  Compose phone UI, `car/` is the Car App Library UI, `notify/` holds notifications and text-to-speech,
  `data/` holds the stores, encrypted secrets and sync, `widget/` is the home-screen widget.
- `docs/FEATURE_IDEAS.md` lists ideas; `docs/PLAY_STORE_RELEASE.md` is the plan for Google Play; `CHANGELOG.md` has
  release notes.

## Build and test

- `./gradlew :core:test` runs the core unit tests and needs only a JDK. The few app tests need the Android SDK.
- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` is what CI runs (needs the Android SDK,
  API 37.2). Lint only reports.
- CI skips pushes that only change Markdown files, except AGENTS.md: `AgentsDocTest` checks it against the code.

## Conventions

- Tests live in `core/src/test/kotlin/dev/aiauto/core`, one file per area, using kotlin.test with fakes for
  `AgentClient`, `EngineConfig` and `TaskStore`. Logic that needs testing goes in `core`, not `app`.
- Short KDoc on public things, written as plain sentences. Comments explain why.
- App text, messages and commits use Canadian spelling and plain, speakable sentences.
- Update the README when a user-visible behaviour changes.
