# Cruise Control

Talk to Claude from Android Auto. Pick one of your GitHub repos, say what you want changed, and Claude does the
work in the cloud. It makes the change on a new branch, runs the tests, and opens a pull request. Updates come back
as messages that the car reads aloud.

## How it works

```
 Phone / Android Auto                       Anthropic (Claude Managed Agents)          GitHub
 ─────────────────────                      ─────────────────────────────────          ──────
 You speak a request  ──▶ new session ──▶   Claude works in a cloud sandbox   ──push──▶ branch
 Updates read aloud   ◀── event stream ◀──  with your repo cloned
 "PR 12 is open"      ◀────────────────────────────────── app opens the PR ──────────▶ pull request
```

- **`core/`** is plain Kotlin. It has the Managed Agents client (official Anthropic Java SDK), the GitHub REST
  calls, and the task engine that keeps one session per task.
- **`app/`** is the Android app:
  - The phone UI uses Jetpack Compose with a warm paper-and-clay theme.
  - The car UI uses the Android for Cars App Library. It opens on one big **Talk** button; the task list,
    task details and repo picker are behind **Tasks** in the corner.
  - Each task is posted as a messaging notification. Android Auto reads these aloud and accepts voice replies.

Tap **Talk** on the car screen and say anything. Claude (Opus 5.5, low effort for quick answers) replies out loud
through the car speakers, then listens for your reply, which is sent as soon as you stop talking. Say nothing to end
the conversation. Claude can also start a coding task in one of your saved repos or answer a running task when you
ask it to. You can also add a repo by voice: say something like "add my repo aiauto" and Claude finds it among the
repos your GitHub token can push to and saves it. **New chat** starts the conversation over. Each answer counts toward the daily spending limit.

If the car only shows Cruise Control's messaging view ("No new messages during this drive"), Android Auto is hiding the
app screen, usually because **Unknown sources** is off (see setup below). You can still talk to Claude there: each
time the phone connects to the car, a **Talk to Claude** conversation appears. Tap it, reply by voice, and Claude's
answer arrives in the same conversation and is read aloud. In this view Android Auto asks you to confirm with a
**Send** button after you stop talking; apps can't turn that off. The app screen doesn't ask: **Talk** and **Reply**
send your words as soon as you stop speaking, so turn on **Unknown sources** to talk without pressing anything.

Voice input also works inside tasks: open **Tasks**, then tap **Talk to Claude** at the top of the list (or **New task**),
pick a repo if you have more than one, and speak your request. To answer Claude, open a task and tap **Reply**, then
speak. The app uses the car's microphone when Android Auto supports it, and the phone's otherwise. If voice
doesn't work, you can fall back to replying to the Cruise Control message through Android Auto's messaging flow.

**Projects** shows each repo straight from GitHub: whether its main branch is passing, the last change, and its
open pull requests with their checks. Tap a pull request to hear its checks or merge it (the app asks first).

When a task's pull request opens, the app follows its checks and tells you when they pass or fail. With
**Merge when checks pass** on (the default), it turns on GitHub's auto-merge if the repo allows it, so GitHub merges
even with your phone off. Otherwise the app merges once every check is green, as long as it's still running. Failing
checks, or checks your token can't read, are never merged.

Two more switches in Settings, both on by default, send problems straight back to Claude. **Fix merge conflicts
automatically** has Claude merge the latest main branch into its pull request, resolve the conflicts and push. **Fix
failing checks automatically** does the same for failing tests, builds or any other CI check. The app tells you each
time it does this. After three automatic fixes on a task it goes back to asking you, so it can't loop forever. With
either switch off, the app asks first, and you can say yes or "fix it".

You can **Stop** a working task, **Clear** one, or clear every finished task at once. On the phone's task list,
swipe a card right to archive it (with undo) or left to clear it (the app asks first). Each task shows what it has
cost so far, and a **daily limit** (default $20, set in Settings) stops Claude when the day's total reaches it.
The limit is also set as the session's budget on Anthropic's side, so it holds even if the phone loses signal.

While you're in the car, the app reads Claude's messages and short progress notes like "Building or testing"
aloud, ducking your music while it talks. You can turn this off in Settings.

## Voice commands

When you reply to a task, the app handles a few short phrases itself without waiting for Claude:

- **"Status"** tells you where the task is.
- **"Stop"** interrupts Claude.
- **"Merge it"** refreshes the pull request's checks and asks you to confirm. Say **"yes"** to merge or **"no"** to
  leave it. If checks failed, it won't merge. It offers to have Claude fix them instead.
- **"Fix it"** sends the names of the failing checks to Claude. When the app announces failed checks, you can also
  just say **"yes"**.

Anything longer goes to Claude as usual, so "merge the two settings screens" is still a coding request.

## Setup

1. **Get the APK.** Download `cruise-control-vX.Y.Z.apk` from the latest
   [release](https://github.com/mjparinas/cruisecontrol/releases) and install it on your phone. You'll need to allow
   installs from that source. Every push also builds an APK, which you can find under the **Build** run's
   Artifacts in the Actions tab.
2. **Claude API key.** Create one at [platform.claude.com](https://platform.claude.com). Usage is billed to that
   account. Tasks use Claude Opus 5.5 by default, and each task is a Managed Agents session.
3. **GitHub token.** Create a [fine-grained token](https://github.com/settings/personal-access-tokens/new) for
   the repos you want to use. Set **Contents** and **Pull requests** to read and write, and **Checks** and
   **Commit statuses** to read. For sync across devices, the token also needs the private `cruise-control-sync` repo
   (see below). To let GitHub merge on its own, turn on **Allow auto-merge** in the repo's settings.
4. **Settings in the app.** Paste both, then add repos as `owner/name`. To remove a repo, tap it and confirm. Its
   running tasks keep going and nothing changes on GitHub. The key and token are encrypted with the
   Android Keystore. The GitHub token is also sent to Anthropic for each session, so the sandbox can clone and
   push. Anthropic's proxy adds the token to git requests outside the sandbox, so code running in the sandbox
   never sees it.
5. **Android Auto.** Apps installed outside the Play Store only show up in the car after you allow them:
   - On the phone, open Android Auto settings.
   - Tap **Version** ten times to unlock developer settings.
   - In the ⋮ menu, open **Developer settings** and turn on **Unknown sources**.

   Then disconnect and reconnect the phone. Without this step the app's icon doesn't appear in the car at all.
   Messaging notifications work without it.
6. **Background work.** The first time you open the app it asks to ignore battery optimization. Say yes: otherwise
   Android (Samsung phones especially) may stop Claude's tasks soon after you unplug from the car. You can change it
   later under Settings > Apps > Cruise Control > Battery > Unrestricted.

## Claude and voice settings

- **Model and effort.** Settings > Claude picks the model (Opus 5.5, Sonnet 5.5 or Haiku 5.5) and effort for coding
  tasks, and separately for the spoken car chat, which defaults to low effort so answers come back quickly.
- **Allow web search.** Lets Claude search the web and read pages while it works. Its workspace then runs with open
  internet access instead of only package registries. It applies to coding tasks only; the car chat doesn't search
  the web.
- **Standing rules.** Tick common rules, like always adding tests or leaving CI files alone, and add your own, one
  per line. They're added to Claude's instructions for every task.
- **Repository notes.** Each task asks Claude to read `CLAUDE.md` at the root of the repo first, so it doesn't have
  to look through the whole repo again every time. If the file is missing, Claude writes it, and it fixes the file
  when it's out of date, so the notes build up across tasks and arrive in the same pull request as the change. Edit
  or delete it like any other file.
- Changing any of these makes a new agent for the next task. Tasks already running keep the agent they started with.
- **Listen when opened** (on by default): the car screen starts listening as soon as it opens, so saying
  "Hey Google, open Cruise Control", or pressing the steering-wheel voice button and saying that, is enough to start
  talking. Android Auto doesn't let other apps take over the voice button itself.
- **Keep listening until Stop** (off by default): pauses don't end what you're saying. The car screen keeps listening
  through them and joins your words until you tap **Stop**, which sends them. It works on **Talk**, **New task** and
  **Reply**. With it on, saying nothing no longer ends the conversation; tap **Stop** instead. The phone's own mic
  button still uses Google's speech dialog, which stops at the first pause.
- **Briefing when you get in** (on by default): when the phone connects to the car, Claude reads what happened
  since your last drive, up to a day back: merged pull requests, finished and failed tasks, questions waiting for
  you and what you've spent today. If the car screen is open it reads it there, then listens.
- **Remember past drives** (on by default): the car chat keeps your last 20 exchanges on the phone and gives them
  to Claude at the start of the next drive. **Forget past drives** clears them.
- **Speaking speed and voice** are under In the car. The voice list shows the phone's text-to-speech voices for its
  language, and a sample plays when you pick one.

## Sync across devices

Turn on **Sync across devices** in Settings on each phone or tablet. They share the file `cruise-control-sync.json` in a
private repo named `cruise-control-sync` on the GitHub account of the token, which holds your repos, tasks (with their last 60
messages), settings and the car chat's memory. The API key, GitHub token, voice, spending total and agent setup stay
on each device. The app makes the repo the first time if the token has **Administration: read and write**.
Otherwise make an empty private repo with that name yourself. Either way, give the token **Contents: read and write**
on it. Sync stops if the repo is ever made public. Earlier versions kept sync in a secret gist, which anyone with its
link can read; the app moves that gist into the repo and then deletes it.

The app syncs every three minutes while it runs, 20 seconds after a task changes, when it opens, and from **Sync
now**. A task keeps running on the device that started it: other devices show it but don't follow its session or
merge its pull request, until you reply to it or retry it from one of them, which moves it there. When both
devices changed a task, the newer one wins and messages from both are kept. Settings work the same way as a whole.
A repo removed on one device is removed on the others too, unless one of them added it again later.

## Building locally

Requires JDK 17 and the Android SDK (API 37.2).

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

Before changing anything, read [AGENTS.md](AGENTS.md). It records the product, development and engineering
decisions behind the app, and every change that affects one of them updates it in the same pull request. A core test
checks its versions, numbers and paths against the code, so the build fails if it drifts.

## Making a release

In the Actions tab, open **Release**, click **Run workflow** and enter the next version, like `v0.8.0`. You can
also push a tag like that. Either way, the workflow tests the code, builds a release APK and publishes a GitHub
release with the APK attached. The version name comes from the tag and the version code from the run number, so
there's nothing to bump in the code first.

Releases are signed with the key stored in the `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` repo secrets, so each release installs over the last one. If those
secrets are missing, the workflow falls back to a throwaway debug key and warns that the APK won't install over
earlier releases.

## Releasing when a PR is merged

Turn on **Release when a PR is merged** in the app's Settings. The app keeps watching the pull requests it opened
for a few hours. When one is merged, it publishes a GitHub release on that repo with the next patch
version (for example `v1.2.3` becomes `v1.2.4`, or `v0.1.0` if the repo has no version tags) and generated notes,
then tells you in the car. Only PRs merged after you turn the toggle on get a release. The GitHub token already
has the Contents permission this needs.

## Limits worth knowing

- There's no public API for your claude.ai or Claude app projects, so this app runs its own agent sessions through
  the Claude API. They don't appear in the Claude app.
- Google Play doesn't accept general AI or productivity apps for Android Auto. The full car UI is for personal
  installs and Play's internal testing track.
- Costs are list prices as each session reports them, so they can differ a little from your invoice. The daily
  total is counted on this phone and resets at local midnight.
- The app's own merge-when-green only works while the app is alive, for up to three hours after a pull request
  opens. GitHub's auto-merge has no such limit, but it only waits for checks the repo marks as required.
- While you're driving, the agent declines any step that its safety check flags for review. You'll hear that it
  skipped something, and the task continues.
