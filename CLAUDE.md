# Cruise Control (aiauto)

Android app that lets you talk to Claude from Android Auto and run coding tasks on your GitHub repos through
Claude Managed Agents. The README explains the features for users.

## Where things live

- `core/` is plain Kotlin with no Android code, and holds all the logic and its tests.
  - `ClaudeAgents.kt`: Managed Agents client. `SYSTEM_PROMPT` is the coding agent's prompt, and `firstPrompt` is the
    first message of every task (branch rules, "read README.md, CLAUDE.md and AGENTS.md first", the request).
    Saved agents are only rebuilt when the Settings profile changes, so instructions that must reach every task
    go in `firstPrompt`, not `SYSTEM_PROMPT`.
  - `Assistant.kt`: the spoken car chat (Messages API with tools like start_coding_task).
  - `TaskEngine.kt`: one session per task; `AgentProfile.kt`: models, effort and standing rules.
  - `GitHub.kt`: GitHub REST calls.
- `app/` is the Android app: phone UI in `ui/` (Compose), car UI in `car/` (Car App Library).

## Build and test

JDK 17 and Android SDK 35. `./gradlew :core:test` runs the unit tests (all in `core/src/test`);
`./gradlew :core:test :app:assembleDebug` is what CI runs.

## Conventions

- Text the car reads aloud is plain sentences: no markdown or lists.
- Tests use kotlin.test with descriptive camelCase names and fakes rather than mocks.
- User-facing changes go in the README; CHANGELOG.md has entries per release.
