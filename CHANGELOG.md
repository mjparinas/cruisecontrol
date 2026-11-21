# Changelog

## Unreleased

- Spoken updates wait while the microphone is listening and are read once it stops, so the car doesn't talk over
  you or hear itself.

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
