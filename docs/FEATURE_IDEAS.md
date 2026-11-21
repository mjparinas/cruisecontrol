# Feature ideas

Ideas for what Cruise Control could do next, based on what's already in the app (voice tasks, one Managed Agents session per
task, PRs opened automatically, updates read aloud). Ordered roughly by how much they'd improve the drive.

## Close the loop by voice

Built: CI results read aloud, merging from the car, merge by voice with a yes or no confirmation, and quick
commands. See "Voice commands" in the README.

- **Merge by voice.** Reply "merge it" to a task whose PR is open. The app checks that CI is green and the PR is
  mergeable, reads back "Merging PR 12 into main, say yes to confirm", then calls the GitHub merge API. Needs
  `GitHub.merge()` and a small intent parser for replies before they go to Claude.
- **CI results read aloud.** After the PR opens, poll the check runs for the branch head and announce "Tests passed
  on PR 12" or "The build failed in the lint step". On failure, offer "Want Claude to fix it?" and send the failing
  log tail as the next prompt.
- **Review comments as prompts.** Watch the PR for new review comments and read them out: "Sam commented on PR 12:
  rename this function". Replying sends the comment plus your reply to the same session.
- **Quick commands.** Recognise short replies like "stop", "status", "undo that", "start over" locally instead of
  sending them to Claude. "Status" reads the latest progress line; "stop" calls `TaskEngine.stop`.

## Start work without typing

- **Issues to tasks.** "Fix issue 34 in aiauto" fetches the issue title and body and starts a task with them, then
  links the PR with "Closes #34".
- **Google Assistant shortcut.** An App Actions capability so "Hey Google, ask Cruise Control to add dark mode to aiauto"
  starts a task with no taps at all.
- **Repo by name in the request.** Let the first sentence choose the repo ("in aiauto, add a settings toggle"), with
  fuzzy matching against saved repos so transcription slips still work.
- **Saved prompts.** One-tap templates in the car such as "Bump dependencies", "Fix the failing build", "Write tests
  for the last change".

## Ask, don't change

- **Ask mode.** A read-only task type for questions like "how does the task engine reconnect?" or "what changed this
  week?". Uses a session prompt that forbids commits, so nothing gets pushed and no PR opens.
- **Morning briefing.** On the first drive of the day, read open PRs, failing builds and new issues across saved repos
  in two or three sentences.
- **Spoken diff summary.** When a PR opens, ask Claude for a one-sentence summary of the diff ("Added a toggle and two
  tests, 40 lines") so you know what you'll be reviewing later.

## Safety and cost

Built: parked mode, with Approve and Deny on the car screen. See "Parked mode" in the README.

- **Parked mode.** Detect when the car is parked (Car App Library's car hardware or driving-state APIs) and allow the
  steps that are currently declined, with an approve or deny button on the car screen.
- **Spend per task.** Show token usage and estimated cost on each task, plus a monthly cap in Settings that pauses new
  tasks when reached.
- **Offline queue.** If there's no signal when you speak a request, keep it and send it when the connection returns,
  instead of failing the task.

## Phone side

- **Review when you arrive.** A "Ready to review" list on the phone that shows the diff and the conversation together,
  with approve and merge buttons.
- **Home screen widget.** Current task status at a glance, with a microphone button to start a new task.
- **Task history search.** Search past requests and PRs across repos.
