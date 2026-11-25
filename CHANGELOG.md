# Changelog

## Unreleased

- Share screenshots or other pictures into Cruise Control, up to four, and they go with the next task you start, so
  Claude can see the bug you mean.
- Tasks for later: pick Later when confirming a task (in an hour, tonight or tomorrow morning), or ask the car
  assistant for it "tonight" or "in two hours". The task waits on the phone and starts on its own, with Start now
  and Stop on its screen.
- The daily and monthly spending limits count every synced device: each device's spend for today and this month
  now syncs, so two phones can't each spend the full limit.
- The app says once, and shows an alert, when spending passes 80% of the daily or monthly limit, before Claude
  stops.
- Settings has a Recent errors screen under About with the last 50 errors the app caught, and a Copy button for bug
  reports. Keys and tokens are blanked out, and nothing leaves the phone.
- "Fix it" and automatic check fixes send Claude the last lines of each failed Actions job's log when the GitHub
  token can read Actions, and still fall back to check names alone when it can't.
- The car chat can close a pull request without merging, or reopen one that was closed without merging, with the same
  two-turn confirmation as merge.
- Before a task starts, the app reads your request back and waits for a separate yes. This works from the phone,
  Android Auto's car screen and messaging reply, and the Google Assistant shortcut.
- The car assistant can list a repo's open issues and start work on an issue. The task includes its description, and
  the pull request closes it when merged. New pull request review comments are read aloud; replying sends the comment
  and your response to Claude together.
- The car assistant can repeat its last answer or turn on quiet mode without starting a model turn. Car lists now
  show up to ten rows, and the phone gets one reminder when the car first becomes parked with unfinished work.
- Car repo history includes archived tasks and up to 100 recently cleared task histories kept on this device only.
  When available, Claude's session history fills older messages trimmed from a synced task.
- The car has History screens for each task and each repo with the whole conversation, Claude's earlier messages
  included. Tap a message to hear it, or Read all to hear everything; up to ten rows are shown at a time and older
  messages scroll back only while parked.
- Bug fixes: a question Claude asked in an earlier turn no longer marks a finished turn as waiting on you or alerts
  again; turning on GitHub's auto-merge is no longer announced as merged; a spoken repo name with a capitalized
  "Slash" still finds the repo; the Accent colour setting uses Canadian spelling.

## v0.8.2

- The home screen's task search box now matches the Settings search: a rounded pill on the card colour with a
  clear button once you type.

## v0.8.1

- The Google Assistant shortcut now finds any repo your GitHub token can push to, not just saved ones, so it no
  longer says to add a repo first when you haven't saved any. Its phrases say Cruise Control instead of Claude, like
  "Hey Google, ask Cruise Control to add dark mode to aiauto".
- The home screen shows today's and this month's spend side by side on one tidy card, each with a bar towards its
  limit that turns amber near the limit and red once it's reached, instead of two widely spaced headers.
- Bug fixes: spoken answers like "do not merge" no longer count as yes; Ask sessions stay read-only on follow-ups;
  a new conflict isn't overwritten by failing checks; drafts and unread checks aren't offered as ready to merge;
  the same pull request number in two repos asks which one; sync keeps a pull request and spend the other device
  already had, and doesn't bring back replaced progress lines; a second parked approval denies the first so Claude
  isn't left waiting.
- Resilience: if a coding turn finishes without a pull request, the app keeps trying to open one for three hours and
  when the network returns. Missed Claude messages are spoken from session history after a reconnect. Parked Approve
  or Deny is retried, and put back on screen if it never reaches Claude. Car chat queues brief server errors and rate
  limits as well as no signal. Sync runs when the network comes back. GitHub calls use timeouts and short retries.
- The Talk to Claude conversation in Android Auto's messaging view stays there for the whole drive, since that view
  has no button to start a chat: dismiss it and it comes straight back, ready to tap and talk.

## v0.8.0

- Added Ask mode with a read-only repository prompt, spoken pull request summaries, a wider drive briefing, a Ready to review screen with
  diffs and approve or merge actions, task history search, a setup checklist, shared text requests, launcher and
  Quick Settings entry points, a widget microphone, pull request notification actions, and monthly spend on the
  home screen and widget.
- Accessibility: template Start and Delete have separate controls, and task, pull request and template text can wrap
  instead of being cut off in the phone UI. Status label text keeps AA contrast against its card.
- The welcome-back briefing says how much you've spent this month as well as today, and how that compares with
  your monthly limit when you've set one.
- Google Assistant shortcut: "Hey Google, start a task to add dark mode to aiauto on Cruise Control" starts a task
  with no taps. Name the repo at the start or end of the request, or it goes to the last-used one. Google only
  offers the shortcut once the app is on the Play Console.
- A release made when a pull request merges stays a draft until the Release workflow attaches its APK, so no public
  release is ever missing the APK. If the build fails, the release stays a draft.
- The Release workflow no longer insists on a version. Leave it empty and it releases the next patch version after
  the latest tag, the same way the app's release on merge does.
- Opening a task with a long conversation jumps straight to the latest message instead of scrolling down to it.
  New messages that arrive while it's open still scroll smoothly.
- Spoken updates wait while the microphone is listening and are read once it stops, so the car doesn't talk over
  you or hear itself.
- The car chat uses Claude's prompt caching, so each answer rereads the instructions and earlier turns from the
  cache instead of paying full price for them again. Its cost now counts cache writes and reads at their own prices.
- Parked mode: once the car has stood still for ten seconds, a step Claude's safety check flags waits for you to
  approve or deny it on the car's task screen, instead of being skipped. Driving off denies any step still waiting.
  The car screen asks once for the car speed permission it needs.
- A search box at the top of Settings shows only the settings that match what you type, by name, section or a
  related word like "colour" or "money". Searching for a pull request switch finds your repos, where those live now.
- An experimental Steering-wheel buttons switch in Settings, under In the car and off by default. While the car
  screen is open, the wheel's play or pause button starts Talk, and next or previous repeats the last update.

- The phone app is voice first. The New task screen and each task's chat drop the text box for a floating microphone
  at the bottom right. It turns into a Stop button while listening, sends as soon as you stop talking, and with Keep
  listening until Stop on, waits for Stop instead.
- Per-repo settings: tap a repo in Settings for its own Merge when checks pass, Fix merge conflicts automatically,
  Fix failing checks automatically and Release when a PR is merged switches, plus rules for Claude that apply only
  to that repo. These switches used to be global; on update, every saved repo keeps the values you had.
- Start a task in any repo your GitHub token can push to, without saving it in Settings first. The repo pickers on
  the phone and in the car, and the car chat, list those repos with the ones you used most recently at the top.
- An optional monthly spending limit, off until you set it in Settings, on top of the daily one. Whichever is
  reached first stops Claude and the car chat.
- The car chat can use OpenAI or Gemini models. Add an OpenAI or Gemini API key under Accounts in Settings, then
  pick the model under Model for the car chat. Coding tasks still run on Claude.
- The car chat no longer loses what you said when there's no signal. It tells you it'll send the question once
  you're back online, then answers it out loud as soon as the network returns, for up to 5 questions asked in the
  last 30 minutes.
- Listening and thinking tones: a short rising tone when the app starts listening, a falling one when it stops,
  and a soft tone every few seconds while Claude is still working on an answer in the car, so you can keep your
  eyes on the road.
- New voice commands for task replies: say "repeat that" to hear the last message again, "quiet" to turn on quiet
  mode without going to Settings, "undo that" to have Claude revert its last change, or "start over", which asks
  for a yes and then has Claude reset the branch and redo the original request from scratch.
- A new Keep listening until Stop setting lets the car's Talk, New task and Reply screens listen through pauses
  and send what you said only when you tap Stop. Silence at the start and hiccups from the speech recognizer just
  start listening again. The phone app's microphone follows it too.
- The Active tab on the phone's task list shows how many tasks are running right now, like Active (2).
- A task whose pull request's checks are still running says Checks running in amber instead of Done, so it doesn't
  look finished before the checks are.
- Swipe a task card left to archive it, with undo, or right to clear it after the app asks. On the Archived tab,
  swipe right to clear a task for good.
- Every coding task starts by reading the repo's README.md, CLAUDE.md and AGENTS.md, whichever exist, so Claude
  follows the project's own docs. Claude also creates or fixes the CLAUDE.md repository notes when they're missing
  or out of date, so it doesn't explore the whole repo every time.
- The update check finds new releases on its own. It used to read only GitHub's latest release, so right after a
  merge, while that release's APK was still building, it said you were up to date. It now looks at the recent
  releases and offers the highest version with an APK, comparing versions by number so v0.7.10 beats v0.7.9.
- Sync across devices now lives in a private repo named cruise-control-sync instead of a secret gist, which anyone
  with its link could read. The app moves an old sync gist into the repo and then deletes it. Give the GitHub token
  Contents: read and write on that repo, and Administration: read and write if you want the app to make it.
- Remove a saved repo from Settings, after confirming. Running tasks keep going, nothing changes on GitHub, and the
  removal syncs to your other devices.
- Updates are only accepted from APKs that GitHub serves over HTTPS, and a downloaded update must be signed with the
  installed app's key before the install prompt opens.
- Accessibility: accents and status colours meet AA contrast, TalkBack labels chat bubbles and offers Archive and
  Clear as actions on task cards, the prompt box has an outline, accent swatches are easier to tap and the task
  screen's top bar grows with large text.
- The car's task screen shows less text, and the phone's task screen shows only the repo name, without the owner.
- App text and what Claude writes use Canadian spelling.
- Fixed tasks cleared on one device and forgotten car chat memory coming back from sync, a freeze when starting a
  New chat in the car, a crash from Android 15's service timeout, and the task chat not scrolling to the last message.
- Every dependency is on its latest stable release, including Android Gradle Plugin 9 and OkHttp 5.

## v0.7.1

- New app icon again: a cruise control speedometer with the set-speed marker outside the dial, replacing the
  prompt sign from v0.7.0. It is used for the launcher icon (with a themed-icon version), the car screen and
  notifications.

## v0.7.0

- New app icon: a road-sign ring holding a terminal prompt, replacing the old sparkle. It is used for the launcher
  icon (with a themed-icon version), the car screen and notifications.

## v0.5.0

- Short spoken replies like status, stop, merge it, fix it, yes and no are handled by the app without starting a
  Claude turn.
- Merge a pull request by voice. The app checks for conflicts and GitHub Actions results and asks before merging.
- After a pull request opens, the app watches its CI runs, announces the result and offers to have Claude fix a
  failure.
- Optional Settings switches let the app fix merge conflicts and failing checks on its own, up to three times per
  task. Conflicting pull requests are never auto-merged.
- Tasks that need a reply are listed first, merge results are reported, and new tasks start on the last-used repo.
- Say a repo's name to the car assistant to add it.
- After Claude's answer is read aloud on the car's Talk screen, the app listens for your reply and sends it when you
  stop talking, so no tap is needed.
- Swipe a task card on the phone to archive it, with undo.
- The repository delete button is gone from Settings.

## v0.1.0

First release.

- Android Auto app that runs Claude coding tasks on your GitHub repos. Pick a repo, speak a request, and Claude
  makes the change on a new branch, runs the tests and opens a pull request.
- Task updates arrive as messaging notifications that Android Auto reads aloud. Reply by voice to continue a task.
- Car UI with a task list, task details and a repo picker.
- Phone UI for settings, repos and tasks. The Claude API key and GitHub token are encrypted with the Android Keystore.
- Build workflow that produces an APK on every push, and a release workflow that publishes the APK to GitHub
  releases from a tag or the Actions tab.
