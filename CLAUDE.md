# CLAUDE.md

Cruise Control: an Android Auto app that runs Claude coding tasks (Managed Agents sessions) on GitHub repos by voice.

- `core/`: plain Kotlin. Managed Agents client, GitHub REST client (`GitHub.kt`), task engine, models, sync, car chat assistant. Tests in `core/src/test`.
- `app/`: Android app (package `dev.aiauto`). Compose phone UI in `ui/`, car UI (Car App Library) in `car/`, stores and sync in `data/`, notifications and speech in `notify/`, wiring in `Graph.kt`.
- Docs: `README.md` (user-facing behaviour), `CHANGELOG.md` (per release), `docs/FEATURE_IDEAS.md` (ideas list, grouped by theme, bold title then description, wrapped near 120 columns).
- Build and test: JDK 17 and Android SDK 35, `./gradlew :core:test :app:assembleDebug`. CI in `.github/workflows`.
- Conventions: plain, friendly prose in docs; Canadian spelling in messages, commits and PR text.
