# Changelog

## Unreleased

- Opening a task with a long conversation jumps straight to the latest message instead of scrolling down to it.
  New messages that arrive while it's open still scroll smoothly.
- Spoken updates wait while the microphone is listening and are read once it stops, so the car doesn't talk over
  you or hear itself.
- The car chat uses Claude's prompt caching, so each answer rereads the instructions and earlier turns from the
  cache instead of paying full price for them again. Its cost now counts cache writes and reads at their own prices.

## v0.8.0

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
