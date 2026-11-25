# AGENTS.md

This file is for anyone, human or AI agent, who changes this repository. It records the product, development and
engineering decisions the owner has made for Cruise Control, so new work follows them instead of undoing them. The
README explains how to use the app; this file explains why it is built the way it is.

## Keep this file in sync

The code is the source of truth. This file must always describe the repository as it is on `main`.

- Every change that makes, changes or reverses a decision below updates this file in the same pull request: edit
  the section it belongs to and add a line to the decision log at the end.
- If you find this file disagrees with the code, fix this file in your change (or the code, if the code is the
  mistake) and say so in the pull request. Never leave the two out of step.
- Commit messages and old pull requests can be wrong about the final state (for example, PR 52 describes OkHttp 5,
  but OkHttp stayed on 4.12.0 until PR 69). Check the code, not the history.
- `AgentsDocTest` in `core/src/test/kotlin/dev/aiauto/core/AgentsDocTest.kt` fails the build when the versions and
  numbers below drift from the build files and code, or when a path named here no longer exists. CI runs it on
  every push and pull request, including ones that only touch this file.
- `CLAUDE.md` holds short repository notes (layout, build, conventions) that every task reads first, and imports
  this file so Claude agents load these rules too. Decisions and the reasons for them go here, not there.

## Product decisions

What the app is:

- Cruise Control (renamed from AIAuto in PR 34) lets the owner talk to Claude from Android Auto. You pick one of your
  GitHub repos, say what you want, and Claude makes the change in the cloud on a new branch, runs the tests and the
  app opens a pull request. Updates come back as messages read aloud in the car.
- It is a personal app installed from GitHub releases, not the Play Store. Play doesn't accept general AI apps for
  Android Auto, so the full car screen needs Android Auto's Unknown sources setting.
- The plan for Play (`docs/PLAY_STORE_RELEASE.md`) doesn't pass the app off as a messaging app: car messaging apps
  must be peer-to-peer, and misrepresenting the app puts the developer account at risk. Instead the car build goes
  to Play's internal and closed testing tracks, where car review doesn't block, and production gets a phone-only
  build without Android Auto support. To reach further honestly, the plan grows the closed track, keeps production
  hands-free over Bluetooth, considers real peer-to-peer messaging, and waits for new car categories.
- The applicationId, package (`dev.aiauto`) and signing key never change, so every release installs over the last
  one. The rename only changed labels, text and the APK name.

Driving safety comes first:

- Everything Claude says is written to be heard: plain sentences, no markdown, lists, code or URLs, and nothing that
  asks the driver to look at a screen. `SpokenStatus` cleans agent text before it is shown or spoken.
- Tool calls that the agent's permission check flags for review are declined automatically, because nobody can
  review them while driving. The driver hears that a step was skipped and the task carries on. Once the car has
  reported zero speed for 10 seconds (parked mode, `app/src/main/java/dev/aiauto/car/ParkedWatcher.kt`), such a step
  waits for Approve or Deny on the car's task screen instead, and is denied as soon as the car moves or disconnects.
  An unknown speed, or no speed permission, counts as moving.
- Voice input sends as soon as the driver stops talking on the app's own car screen. The car screen opens on one big
  Talk button and starts listening when it opens. Android Auto's messaging view always asks for Send, which apps
  can't turn off, so the app explains how to avoid it. That view also has no button for starting a chat, so the
  Talk to Claude conversation posted when the phone connects is the way in, and dismissing it during a drive puts it
  straight back (`core/src/main/kotlin/dev/aiauto/core/CarChat.kt`). New task requests on the phone, car screen,
  Google Assistant shortcut and Android Auto messaging reply are read back and require a separate confirmation before
  work starts. The messaging reply's pending confirmation is held in memory and expires after 60 seconds, so a
  process restart safely drops it without starting the task. The phone app is voice first: the New task screen and the
  task chat have no text box, only a floating microphone at the bottom right (`MicButton`) that listens in the app
  with `VoiceInput`, turns into a Stop button while listening, and sends when the person stops talking. Keep
  listening until Stop (off by default) makes pauses not send, both on the car screen and on the phone, and Stop
  sends instead. Until Stop, recognizer errors restart listening too (up to 5 in a row), since
  it often fails after silence or while restarting, and an error must never send.
- Steering-wheel buttons are an experiment behind a setting, off by default: while the car screen shows, a media
  session (`app/src/main/java/dev/aiauto/car/WheelButtons.kt`) turns play or pause into Talk and next or previous
  into repeating the last update. It stays off by default because a media session can make Android Auto treat the
  app as a media app.
- `VoiceInput` plays a rising tone when a go at listening starts and a falling one when it ends, not on each
  restart until Stop. The car chat plays a soft cue 3 seconds into a slow answer and every 5 seconds after, at most
  6 times. Tones use the assistant audio attributes, like the microphone, so music ducks. Their shape and timing
  live in `core/src/main/kotlin/dev/aiauto/core/Tones.kt` so they are tested; there is no switch to turn them off.
- Nothing is read aloud while the microphone is listening, so the car never talks over the driver or hears itself.
  `Speaker` holds new speech (up to 5 lines, oldest dropped) from when `VoiceInput` starts until it ends, across
  restarts, and then reads it. `core/src/main/kotlin/dev/aiauto/core/SpeechHold.kt` holds the logic.
- The car's task screen shows only the latest two lines, each cut to a glance of whole sentences, or one line while a
  step waits for approval.
- The full history of a task, or of every task in a repo, is on the car's History screens
  (`app/src/main/java/dev/aiauto/car/TaskHistoryScreen.kt`, logic in `core/src/main/kotlin/dev/aiauto/core/RepoHistory.kt`).
  They use the standard list template with up to ten rows, each cut to a glance, and deliver the rest by voice:
  tap a row to hear it, or Read all for everything. Paging back to older rows only works while parked, so the driver
  never scrolls through text on the move. Repo history includes archived tasks and the last 100 tasks cleared on this
  device. Cleared history stays local and does not sync. Opening a task's History fills older messages trimmed from a
  synced copy by listing the Claude session's events.
- Short replies (status, stop, merge it, fix it, yes, no, repeat that, quiet, undo that, start over) are handled by the app without starting a Claude turn.
  Anything longer goes to Claude. Repeat and Quiet work in the car chat too.
- A merge the driver asks for always asks for confirmation first, and the car assistant only merges on a yes in a
  later turn. The app only merges on its own when Merge when checks pass is on. Failing checks, conflicting pull
  requests and checks the token can't read are never merged.

Claude and cost:

- Coding tasks default to Opus 5.5 at high effort. The car chat defaults to Opus 5.5 at low effort so answers come
  back quickly. Both can be changed in Settings, along with web search and standing rules.
- Settings are either global or per repo, and don't overlap. Global: keys, model, effort, web search, standing rules,
  everything in the car, theme, daily and monthly limits, and sync. Per repo (`RepoSettings`, set by tapping a repo): merge when
  checks pass, the two automatic fixes, release on merge, and rules for that repo. A repo without saved settings uses
  the defaults; updating from global switches copies the old values onto every saved repo once.
- The search box at the top of Settings filters sections and rows by name, section or related words listed in
  `SettingsSearch.CATALOGUE` (core). Rows the catalogue doesn't list, like each saved repo, show with their section.
- A repo's rules go in `ClaudeAgents.firstPrompt`, not the agent, so changing them never makes a new agent and they
  only reach that repo's tasks. Standing rules stay global and in the agent.
- Changing the model, effort, web search or standing rules makes a new agent for the next task. Running tasks keep
  the agent they started with.
- Every task asks Claude to read `CLAUDE.md` at the repo root first instead of exploring the whole repo again, and
  to create it or fix it when it's missing or out of date, in the same pull request (PR 66). Before anything else it
  also reads `README.md` and `AGENTS.md` when the repo has them, so it follows the project's own docs (PR 82).
- The car chat can use an OpenAI model (through the Responses API) or a Gemini model (through Google's
  OpenAI-compatible endpoint) when the owner adds that provider's key; each key is optional. Coding tasks stay on
  Claude because they need Managed Agents, which has no equivalent elsewhere. The other providers' chat costs count
  toward the daily and monthly limits at list price, using a model's later price when a launch discount is about to end.
- Web search is off by default and only applies to coding tasks. With it on, tasks run in an environment with open
  internet access; otherwise the sandbox only reaches package registries.
- A daily spending limit (default $20) stops Claude when the day's total reaches it. An optional monthly limit (off
  by default) adds up every day's spend in the calendar month and stops Claude when that reaches it. Both apply at
  once, to tasks and the car chat, and the tighter one is also set as each session's budget on Anthropic's side so
  it holds even when the phone loses signal. Costs are list prices. Passing 80% of either limit is said once and
  shown as an alert (`SpendWarning`), the same point where the home screen's bar turns amber.
- Claude writes in Canadian spelling (colour, centre, cancelled, organize) in app text, spoken answers, commit
  messages and pull requests (PR 54).

Pull requests and automation:

- After a pull request opens, the app watches its checks for 3 hours, announces the result and, with the repo's Merge
  when checks pass on (the default), turns on GitHub's auto-merge or merges once every check is green. If a coding
  turn finishes without a pull request (usually because GitHub was unreachable), the app keeps trying to open one for
  the same three-hour window, including when the network comes back.
- Fix merge conflicts automatically and Fix failing checks automatically are per repo and on by default. After 3
  automatic fixes on a task the app goes back to asking, so it can never loop.
- Alerts for failed checks, questions from Claude and stuck tasks (no update for 15 minutes) are always shown, even
  in quiet mode, and can be answered by voice.
- Release when a PR is merged is per repo and off by default. When on, the app publishes the next patch version on
  that repo for pull requests merged after the switch was turned on.

Tasks, repos and sync:

- One task is one Managed Agents session on one repo. Tasks that need a reply are listed first, and new tasks start
  on the last-used repo.
- On the phone, swiping a task card left archives it with undo, and swiping right clears it after asking (PR 65,
  directions swapped later). On the Archived tab, swiping right clears an archived task after asking; there's no
  left swipe there.
  Archived tasks are hidden from the car task list and the voice assistant, but remain in car repo history.
  Clearing a task keeps its history on this device, capped at the most recently cleared 100 tasks; it is not synced.
- Repos are added from the list of repos the token can push to, several at once, by typing owner/name, or by saying
  the name to the car assistant. Removing a repo needs a confirmation, leaves running tasks alone and changes nothing
  on GitHub.
- A new task can start in any repo the token can push to, not only saved ones (PR 83). The phone and car pickers and
  the car chat use `RepoRef.choices`: repos with tasks first, newest task first, then saved repos, then the rest in
  GitHub's most recently pushed order. The token's list is cached on the device so the car picker opens without
  waiting on GitHub; it isn't synced, and saved repos still decide what Projects shows and what syncs.
- Pictures shared into the app (up to four, scaled to 1568 pixels on the longest side, sent as JPEG) go with the
  next task's first prompt. They're kept in a file on the device that started the task until that prompt is sent,
  never in the synced task, so sync stays small and pictures never leave that phone except to Claude.
- Tasks can be saved for later (`Task.startAtMillis`, `core/src/main/kotlin/dev/aiauto/core/StartLater.kt`): on the
  phone's confirmation, or from the car chat with `start_in_minutes`, which the model works out from the local time
  in its context and the driver confirms. They wait on the device that made them, don't count as active, and start
  from an inexact alarm (`app/src/main/java/dev/aiauto/notify/StartLaterAlarm.kt`), since exact alarms need a
  special permission and a few minutes late doesn't matter. The alarm is set again when the app starts, including
  after the phone restarts. A task can wait at most a week.
- The car assistant can list open issues and start a task from a numbered issue. It reads the issue body, requires
  a separate yes, and the task's pull request closes that issue when merged. Review comments on task pull requests
  are announced once; replying includes the comment and the driver's response in the prompt to Claude.
- Sync across devices is opt-in and lives in a private repo named cruise-control-sync on the token owner's account.
  It never holds the API keys, GitHub token, voice or agent setup. Each device's own spend for today and this month
  syncs as its own entry (`core/src/main/kotlin/dev/aiauto/core/SpendLedger.kt`), and the limits add every
  device's entries together, so they hold across devices. A device only ever adds to its own entry, so merging
  keeps the larger total and never counts spend twice. Sync refuses to write to a public
  repo. It replaced a secret gist (PR 58) because anyone with a gist's link can read it.
- Synced tasks keep their last 60 messages. A task runs on the device that started it until you reply to it or
  retry it from another device. The newer side wins for tasks and settings, and removals sync through tombstones.
  Per-repo settings sync as one setting with the rest.
- A Google Assistant shortcut (App Actions custom intent in `app/src/main/res/xml/shortcuts.xml`) opens New task with
  the request read back and waits for a separate yes before starting. `VoiceShortcut` takes the repo from the start or end of
  the request, from the same choices as the pickers (saved repos and every repo the token can push to), and otherwise
  uses the last-used repo. Google only registers it for apps on the Play Console, so it waits on the Play plan for
  installs from GitHub releases, and Gemini may not pick up custom App Actions intents at all.
- The car chat remembers the last 20 exchanges across drives, with a switch to turn it off and a Forget button.
- When the car first becomes parked with unfinished tasks, the phone gets one reminder notification.
- A car chat question that fails before any tool runs is kept on the phone (up to 5, for 30 minutes) and answered
  aloud when the phone can get through again: no signal, a timeout, a rate limit, or a brief server error. A turn
  that already ran a tool is never queued, so nothing such as a task start can happen twice. The queue lives in
  memory, not on disk, since it only matters on a drive. A validated network also kicks a sync.
- A briefing of what happened since the last drive (up to a day back) is read when the phone connects to the car.
  It ends with today's spend and this month's, measured against the monthly limit when one is set.

Look and accessibility:

- The phone UI uses a warm paper-and-clay theme with System, Light or Dark mode and five accents (Clay, Ocean,
  Forest, Plum, Slate). Every screen shares the grouped card style from Settings
  (`app/src/main/java/dev/aiauto/ui/Design.kt`).
- Text and accents meet WCAG AA contrast (4.5:1 for text, 3:1 for outlines), touch targets are at least 48 dp, and
  TalkBack gets labels and custom actions for anything done by swiping.
- The app icon is a cruise control speedometer (PR 38).
- Home and widget spending summaries show both today's and this month's totals, with each configured limit. On the
  home screen they sit side by side on one compact card (`SpendMeter` in core), each with a bar towards its limit
  that turns amber from 80% and red at the limit, instead of two stacked section headers.
- The phone task list searches active and archived tasks by request, message, repo or pull request number. Its
  search box is the same pill as Settings' (`SearchField` in `app/src/main/java/dev/aiauto/ui/Components.kt`).
- The welcome-back briefing also checks recent issue and pull request activity and failed main-branch checks across saved repos.
- Important phone task, pull request, template and shared-request text wraps instead of being cut off; template Start
  and Delete are separate controls.

## Engineering decisions

Structure:

- `core/` is plain Kotlin with no Android dependency, so the engine is unit tested on the JVM. It holds the Managed
  Agents client, the GitHub REST calls, the task engine, sync, voice parsing and spoken text.
- `app/` is the Android app: Jetpack Compose on the phone, the Android for Cars App Library in the car, messaging
  notifications that Android Auto reads aloud, a foreground service, and a home-screen widget.
- `TaskEngine` keeps the task lifecycle and the Claude session. It delegates to `TaskJournal` (task state and
  messages), `SpendingLimit` (the daily and monthly limits and session budget) and `PullRequests` (opening, watching and
  merging), split out in PR 20 to keep each part small.
- Interfaces like `AgentClient`, `GitHub` and `TaskStore` exist so tests can use fakes. The real GitHub client is
  tested against canned HTTP responses through an OkHttp interceptor.

Claude:

- Coding work runs as Claude Managed Agents sessions through the official Anthropic Java SDK. There is no public API
  for claude.ai projects, so these sessions don't appear in the Claude app.
- The repo is mounted in the session at /workspace/repo with the user's GitHub token, which Anthropic's proxy adds
  to git requests so code in the sandbox never sees it. The agent pushes a branch; the app opens the pull request.
- Instructions every task needs go in `ClaudeAgents.firstPrompt`, sent with each task, rather than the system
  prompt baked into the agent, so agents already saved on a phone pick them up without being remade.
- The car chat uses the Messages API directly with a small set of tools (start a task, reply to a task, add a repo,
  list issues, start a task from an issue, get status, stop a task, merge a pull request, close or reopen a pull
  request, repeat and quiet). The tools are described once in `Assistant` and sent in each provider's format by
  `core/src/main/kotlin/dev/aiauto/core/ChatProviders.kt`, which keeps each conversation in that provider's own
  message format so tool calls, OpenAI's encrypted reasoning and Gemini's thought signatures go back exactly as they
  came. OpenAI requests are sent with store off.
- Claude car chat requests turn on automatic prompt caching (a top-level `cache_control` with the default 5-minute
  lifetime), so tools, system prompt and earlier turns are read from the cache on each turn. A drive's back and
  forth is well inside five minutes, so the 1-hour lifetime, which costs twice input to write, isn't worth it.
  Cache writes are priced at 1.25 times input and reads at each model's cache hit price (`ModelChoice`).
- Streams are reconnected by the engine, and past events are listed after reconnecting so nothing is reported
  twice and turns that ended offline are caught up. Agent messages that arrived while the phone was offline are
  spoken from history on reconnect, not only the final stop reason. Task history can also fetch older messages from
  the Claude session, filling gaps in the 60-message synced copy. Tasks cut off by a lost connection retry every
  30 seconds for up to two hours.
- Parked Approve or Deny is sent with short retries; if it never reaches Claude, the choice is put back on the
  screen so the driver can try again instead of leaving the session waiting.
- The GitHub client uses connect, read, write and call timeouts and retries transient 408/429/5xx replies a few
  times with the same backoff as the engine, so a one-shot blip doesn't strand a pull request or check refresh. It
  also lists open issues and polls pull-request review comments, paging back to the last-seen comment.

Reliability on the phone:

- A foreground service with a partial wake lock keeps tasks running after Android Auto disconnects or the screen
  turns off. The app asks once to ignore battery optimization. Android 15's data-sync timeout stops the service
  cleanly instead of crashing.
- The car app service declares both the MESSAGING and IOT categories, so Android Auto opens the app's own screen.
  Release builds trust Android Auto's known signing keys plus the Android Auto actually installed from Play or
  preinstalled.
- Unexpected errors fail the task instead of leaving it stuck on Working, and background-thread errors are logged
  instead of crashing the app. Every caught error goes through `AppLog` (`app/src/main/java/dev/aiauto/AppLog.kt`),
  which also keeps the last 50 on the phone for Settings' Recent errors screen (`ErrorLog` in core), since Android's
  own log can't be read on the phone. They never sync or leave the phone, and keys and tokens are blanked out.

Security:

- The Claude, OpenAI and Gemini API keys and the GitHub token are encrypted with an Android Keystore key
  (`app/src/main/java/dev/aiauto/data/Secrets.kt`). The OpenAI and Gemini keys are only sent to their provider's
  own chat endpoint.
- Updates are only accepted from APKs that GitHub serves over HTTPS, and a downloaded APK must be signed with the
  installed app's key before the install prompt opens. Updates come from the mjparinas/cruisecontrol releases.
- The update check lists the recent releases instead of reading GitHub's latest one, and offers the highest version
  with an APK. The app's release on merge publishes each release before the Release workflow attaches its APK, so
  the workflow turns it back into a draft while it builds and publishes it once the APK is attached
  (`ReleaseWorkflowTest` checks this). A failed build leaves the release a draft.
- The shortcut's action reaches the exported `MainActivity`, so any app could send it. Android stops background
  apps from opening activities, the request can only target repos the token can already push to, and the spending
  limits still apply. The task request is read back and does not start until the user confirms it on screen.
- The GitHub token is only sent to api.github.com, and to gist.githubusercontent.com when moving an old sync gist,
  never to wherever a URL in a response points.

## Development process

- Work happens on a branch and lands through a pull request. Claude agents name branches claude/... and never open
  the pull request themselves; the app does.
- Every change adds or updates tests and runs them. Keep each change small and focused, match the existing style,
  and update the README and docs the change affects, including this file.
- Build and test with JDK 17 and the Android SDK (API 37.2):
  `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`. The Build workflow
  (`.github/workflows/build.yml`) runs that on every push and pull request, cancels superseded runs, uploads the
  debug APK, then runs Android Lint and prints its text report in the log.
- Android Lint only reports for now (`abortOnError = false` in `app/build.gradle.kts`) until the issues it found in
  existing code are fixed.
- Releases come from the Release workflow (`.github/workflows/release.yml`), started from the Actions tab or by
  pushing a tag like v0.8.0. Run from the Actions tab with the version left empty, it releases the next patch after
  the latest tag, picked by `.github/scripts/next-version.sh` with the same rule as `Versions.nextPatch`, so
  routine releases need no typing; hand-cut minor releases still enter their version. The version name comes from the tag and the version code from the run number, so
  nothing is bumped in code. Releases are signed with the key in the repo secrets. `CHANGELOG.md` gets an entry per
  release, newest first, and the README's release example names the newest one (`ChangelogTest` checks both).
  Release when a PR is merged is on for this repo, so merges publish patch tags between hand-cut minor releases.
- Feature ideas live in `docs/FEATURE_IDEAS.md`. The Play Store release plan lives in `docs/PLAY_STORE_RELEASE.md`.

## Pinned versions

Each line is checked against the build files by `AgentsDocTest`. When you change a version, change it here too and
say why if the reason below no longer holds.

- Gradle wrapper: 9.9.2
- Android Gradle Plugin: 9.5.2
- Kotlin: 2.4.21
- JDK: 17
- compileSdk: 37.2
- targetSdk: 35
- minSdk: 26
- anthropic-java: 3.12.0
- OkHttp: 5.5.1
- kotlinx-serialization-json: 1.13.0
- kotlinx-coroutines: 1.11.0
- Compose BOM: 2026.11.01
- Car App Library: 1.7.0

Why these are where they are (PR 69): everything was moved to its latest stable release. Android Gradle Plugin 9 has
Kotlin built in, so the app has no kotlin-android plugin and names kotlin-test-junit for its unit tests. Compose
needs compileSdk 37.2. targetSdk stays on 35 so the app's runtime behaviour doesn't change; raising it is a separate
decision. CI uses setup-gradle v5, not v6, because v6 puts its caching under Gradle's proprietary Terms of Use, which
upgrading accepts; that's the owner's call. Car App 1.7 deprecates the title and header setters the car screens use;
they still work, and moving to `Header` is left for a separate change.

## Numbers the code depends on

Each line is checked against the code by `AgentsDocTest`.

- Default coding model: Opus 5.5
- Default coding effort: High
- Default car chat effort: Low
- Daily spending limit default (USD): 20
- Pull request watch window (hours): 3
- Automatic fixes per task: 3
- Stuck task alert (minutes): 15
- Car chat exchanges remembered: 20
- Synced messages per task: 60
- Car history rows: 10
- Cleared task histories kept locally: 100
- Issue body included in a task (characters): 12000
- Sync repo: cruise-control-sync
- Update source: mjparinas/cruisecontrol
- Repository notes file: CLAUDE.md

## Decision log

Newest first. Earlier work (before PR 13) isn't in the history; add older decisions here if they come up.

- Screenshots shared into the app go with the next task, kept only on that device. Tasks can be saved for later
  from the phone's confirmation or by voice, and start from an inexact alarm.
- Spending limits hold across synced devices: each device's own daily and monthly totals sync and are added up,
  reversing "the total stays per device" from PR 73. Passing 80% of a limit is said once. Caught errors are kept on
  the phone for a Recent errors screen with Copy.
- "Fix it" and automatic check fixes include the last lines of each failed Actions job's log when the token has
  Actions: read, and fall back to check names only when it doesn't. The car chat can close or reopen a pull request
  with the same two-turn confirmation as merge.
- Completing the missing-feature review: task starts now read back and require confirmation across phone, car,
  shortcut and messaging replies; car chat can repeat or go quiet; issues can start tasks and review comments can
  prompt fixes; car repo history includes archived and recently cleared tasks, with older Claude messages fetched
  when available; the car lists ten rows and parked cars get one unfinished-task reminder.
- Full conversation history per task and per repo on the car, on list screens of at most ten glanceable rows, with
  Read all for the rest and older pages only while parked. Archived tasks stay off the car task list. The car repo screen shows
  four pull requests instead of five to make room for Task history, and a task's Clear moves to its History screen
  when the task screen's two strip actions are taken.

- Bug review: Claude's question from an earlier turn no longer counts for a later turn; GitHub auto-merge isn't
  reported as merged; spoken repo names match a capitalized "Slash"; the Accent colour row uses Canadian spelling.
  The Security section now says the shortcut targets any repo the token can push to, matching the code.
- v0.8.2 cut with the home screen search box restyle as its only note.
- The home screen's task search box uses the same rounded pill as the Settings search, with a clear button, instead
  of an outlined field with a floating label.
- v0.8.1 cut from the notes merged since the v0.8.0 tag (PRs 110, 111 and 113) plus PR 112.
- The Google Assistant shortcut finds any repo the token can push to, like the pickers since PR 83, instead of only
  saved repos, and its phrases name Cruise Control rather than Claude ("ask Cruise Control to ..."). The docs now say
  Gemini may not pick up custom App Actions intents.
- The Talk to Claude conversation in Android Auto's messaging view comes back when dismissed during a drive, since
  that view has no button for starting a chat and apps can't add one.
- The home screen's today and this month spend moved from two widely spaced headers onto one card, side by side,
  with limit bars.
- Bug fixes: VoiceCommand treats negated yes-phrases as No; Ask follow-ups keep read-only rules and refuse write
  shortcuts; conflict handling always blocks the failing-checks question; car merge preview requires a non-draft with
  readable checks; colliding pull request numbers ask which repo; SyncMerge keeps max spend and known PR fields and
  drops replaced progress lines; a new parked approval denies the previous tool id.
- Resilience: retry opening a missing pull request after a finished turn, replay missed agent messages from session
  history on reconnect, restore parked Approve/Deny when confirm fails, queue car chat on transient chat errors (not
  only offline), kick sync when the network returns, and give the GitHub client timeouts plus brief retries.
- Accessibility review: task, pull request, template and shared-request text wraps; template actions are separate.
- Ask mode uses a read-only session prompt, and new pull requests announce their title and diff counts. The welcome-back
  briefing checks saved repos for changed issues, pull requests and failed main-branch checks. The phone now
  has a diff review list, a setup checklist, shared-request handling, task history search, launcher and Quick Settings
  entry points, a widget microphone, task notification actions, and monthly spend on the home screen and widget.
- Clickable settings and project rows expose button actions to screen readers; repository settings rows show a delete icon, status text enforces 4.5:1 contrast, and accent swatch checkmarks use a contrasting shade.
- Feature ideas checked against the code again: held speech while listening, the steering-wheel experiment and
  "undo that" and "start over" moved from Still missing to Already built.
- The welcome-back briefing says this month's spend, and the monthly limit when one is set, after today's. The
  home screen and widget now show both totals.
- PR 93: Parked mode. While the car is parked, steps the agent's safety check flags wait for Approve or Deny on the
  car screen instead of being declined; driving off denies any still waiting.
- PR 92: A search box at the top of Settings filters the page as you type. Pull request and repo-rule words find
  the Repositories section, since those settings live behind each repo.
- A release without an APK is no use, so the Release workflow drafts a release the app published on merge until its
  APK is attached, instead of leaving it public while the APK builds.
- PR 100: The Release workflow's version is optional. Left empty, it releases the next patch after the latest tag,
  using a shell script that mirrors `Versions.nextPatch` and is tested in the Build workflow.
- PR 98: The Claude car chat uses automatic prompt caching with the 5-minute lifetime, and its cost counts cache
  writes and reads at their own prices instead of the base input price.
- The phone app is voice first: the prompt box with its text field and Send button is gone from the New task screen
  and the task chat, replaced by a floating microphone at the bottom right that follows Keep listening until Stop.
  Google's speech dialog is no longer used, so the microphone can always turn into a Stop button.
- PR 84: Spoken updates wait while the microphone is listening and are read once it stops.
- v0.8.0 was still untagged after PR 79, so everything merged since went into its changelog entry instead of a new
  version, and `ChangelogTest` now also checks that every version entry has notes.
- PR 83: Every repo the token can push to is offered for new tasks, most recently used first, in the phone and car
  pickers and the car chat. Saving a repo is no longer needed to start a task there.
- PR 90: A task isn't shown as Done while its open pull request's checks are running or not yet read. Its status
  pill says Checks running in amber, the colour already used for pending checks (`Task.checksRunning`).
- PR 89: Listening and thinking tones, so the driver can tell the microphone opened or closed, or that Claude is
  still answering, without looking at the screen.
- PR 88: Steering-wheel buttons, an experimental setting, off by default, where the wheel's play or pause
  starts Talk and next or previous repeats the last update, through a media session that's only active then.
- PR 87: Car chat questions asked with no signal are queued in `Assistant` and answered when the network comes back,
  instead of failing.
- PR 86: Google Assistant shortcut that starts a task straight away, picking the repo from the request. It needs
  the app on the Play Console to work.
- PR 82: Every task reads the repo's README.md, CLAUDE.md and AGENTS.md before anything else, asked for in the
  first prompt.
- PR 81: Keep listening until Stop restarts on recognizer errors instead of stopping or sending, so silence at
  the start or a hiccup between pauses no longer ends it.
- PR 80: Swipe directions on task cards swapped: swipe left archives, swipe right clears after asking, on both tabs.
- PR 78: The update check offers the highest recent release with an APK, not just GitHub's latest release, which
  often has no APK yet right after a merge.
- PR 77: The car chat can use OpenAI or Gemini models with their own optional keys. Coding tasks stay on Claude.
- PR 76: Per-repo settings. The pull request switches moved from global to each repo, and each repo can
  have its own rules for Claude, sent in the first prompt. Global and per-repo settings don't overlap.
- PR 75: Play Store plan: the car build goes only to Play's internal and closed testing, production gets a
  phone-only build, and the app is never described as a messaging app to pass review. Nothing is built yet.
- PR 74: The phone app's mic button follows Keep listening until Stop too, listening in the app with the same
  `VoiceInput` as the car and a Stop button, instead of Google's speech dialog.
- PR 73: An optional monthly spending limit, off by default, on top of the daily one. Both apply at once and the
  monthly total and limit live alongside the daily ones; the limit syncs, the total stays per device.
- PR 71: Archived task cards can be cleared by swiping left, after asking.
- PR 69: Every dependency moved to its latest stable release, including Gradle 9, Android Gradle Plugin 9,
  compileSdk 37.2, OkHttp 5 and Car App 1.7. targetSdk stays 35 and setup-gradle stays on v5.
- PR 68: Added this file and `AgentsDocTest` to keep a checked record of decisions, imported from `CLAUDE.md`.
  The Build workflow now also runs when this file changes.
- PR 66: Each task reads and maintains `CLAUDE.md` repository notes, asked for in the first prompt.
- PR 64: Feature ideas brought up to date with what's built and what's still missing.
- PR 65: Swipe right archives a task card, swipe left clears it after asking. App unit tests run in CI.
- PR 62: Saved repos can be removed in Settings, and removals sync across devices.
- PR 61, 63: Direct tests for the web search switch and the daily spending limit.
- PR 60: The task screen shows only the repo name, without the owner.
- PR 59: Accessibility tweaks: prompt box outline at 3:1, 48 dp swatches, less text on the car screen.
- PR 58: Device sync moved from a secret gist into a private repo.
- PR 57: Fixed synced clears coming back, a car New chat freeze, Android 15's service timeout and chat scrolling.
- PR 56: Update downloads must come from GitHub over HTTPS and match the installed app's signing key.
- PR 55: AA contrast for accents and status colours, and TalkBack labels.
- PR 54: Canadian spelling in app text and in what Claude writes.
- PR 53: Android Lint added to CI, report-only for now.
- PR 52: Dependency upgrades, stopping short of Android Gradle Plugin 9, compileSdk 36 and OkHttp 5 (done in PR 69).
- PR 51: The real GitHub client and the project view are tested against canned HTTP responses.
- PR 50: Sync of repos, tasks, settings and car chat memory across devices.
- PR 49: Task templates on the phone and in the car.
- PR 48: Home-screen widget with running tasks and today's spend.
- PR 47: Settings' grouped card style used on every screen.
- PR 46: Alerts for failed checks, questions and stuck tasks, answerable by voice.
- PR 45: Listen when the car screen opens, a welcome-back briefing, and memory across drives.
- PR 44: Merge, stop and status by voice in the car, with merges confirmed in a later turn.
- PR 42: Model, effort, web search, standing rules and voice settings.
- PR 41: Repos are picked from the token's GitHub account, several at once.
- PR 40: Settings restyled in a grouped card style.
- PR 37, 38: App icon changed to the prompt sign, then to the cruise control speedometer.
- PR 34: Renamed the app to Cruise Control, keeping the package and signing key.
- PR 32: The car's Talk screen listens for a reply after each answer, so no tap is needed.
- PR 31, 35: Check for updates in Settings, with the result shown as a toast.
- PR 30: System, Light or Dark mode and five accent colours.
- PR 28: Optional release when a pull request from the app is merged.
- PR 27: The repo delete button was removed from Settings (brought back with a confirmation in PR 62).
- PR 25: Automatic fixes for merge conflicts and failing checks, capped at 3 per task.
- PR 24: Add a repo by saying its name to the car assistant.
- PR 23: Tasks that need a reply come first, merge results are reported, and new tasks start on the last-used repo.
- PR 21: Voice commands, merge by voice and spoken CI results.
- PR 20: TaskEngine split into TaskJournal, SpendingLimit and PullRequests.
- PR 19, 22: Tasks can be archived, including by swiping.
- PR 18: Releases are signed and versioned from the tag.
- PR 17: Tasks cut off by a lost connection keep retrying, and crashes are guarded against.
- PR 16: Quiet mode announces only each task's result.
- PR 15: A pull request is watched again after a follow-up turn pushes a fix.
- PR 14: The car screen opens on one Talk button, and tasks keep running after Android Auto disconnects.
- PR 13: Faster CI, spoken prompts sent right away, copy chat.
