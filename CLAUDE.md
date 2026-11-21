# Cruise Control (aiauto)

Android Auto app that runs Claude coding tasks on GitHub repos and reads updates aloud in the car.

## Layout

- `core/`: plain Kotlin (JVM) logic with unit tests in `core/src/test`: task engine, GitHub client, voice commands,
  spoken status text, sync, money, templates. Put testable logic here.
- `app/`: the Android app. `car/` holds the Android Auto screens (MicScreen is the car home, VoiceInput does speech
  to text), `notify/` holds notifications and the text-to-speech Speaker, `ui/` the phone Compose screens,
  `data/` settings and stores, `Graph.kt` wires everything together (`carContext.graph`).
- `docs/FEATURE_IDEAS.md`, `README.md`, `CHANGELOG.md` (add user-facing changes under "Unreleased").

## Build and test

- `./gradlew :core:test` runs the unit tests (JDK 17+, needs network for Gradle plugins; `--offline` fails).
- `./gradlew :app:assembleDebug` needs the Android SDK (API 35), which the agent sandbox doesn't have, so app code
  can't be compiled there; CI builds it.
- The app module has no tests; keep logic in `core` so it can be tested.

## Conventions

- Kotlin, 4-space indent, long lines (about 120), KDoc on classes and non-obvious functions.
- Short plain sentences in anything spoken or shown in the car.
- Speaker holds new speech while VoiceInput is listening (core `SpeechHold`), and releases it when listening ends.
