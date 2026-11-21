# Feature ideas

Ideas for what Cruise Control could do next, based on what's already in the app (voice tasks, one Managed Agents
session per task, PRs opened automatically, updates read aloud). Ordered roughly by how much they'd improve the drive.

## Already built

These started on this list and are now in the app. See the README for how they work.

- **Merge by voice**, with a yes or no confirmation, and GitHub auto-merge when checks are still running.
- **CI results read aloud** after a pull request opens, with an offer to have Claude fix failures.
- **Quick commands**: "status", "stop", "merge it", "fix it", "yes" and "no" are handled by the app.
- **Repo by name** in the car chat, matched against saved repos so transcription slips still work.
- **Saved prompts**, built as task templates on the phone and in the car.
- **Welcome-back briefing** when the phone connects to the car.
- **Spend per task** and daily and monthly spending limits.
- **Resume after lost signal**: tasks cut off by a dropped connection restart when the network comes back.
- **Home screen widget** with running tasks and today's spend.

## Still missing

### Close the loop by voice

- **Failing log tail in "fix it".** Today "fix it" sends Claude only the names of the failing checks, so Claude has to
  find and rerun the failure itself. Fetching the last lines of the failed job's log (the Actions jobs and logs
  endpoints) and adding them to `TaskEngine.fixChecksPrompt` would make fixes faster and cheaper. It needs the token's
  **Actions: read** permission, and the prompt should fall back to names only when the log can't be read.
- **Review comments as prompts.** Watch the task's pull request for new review comments, alongside the checks that
  `PullRequests` already follows, and read them out: "Sam commented on PR 12: rename this function". Replying sends the
  comment and your reply to the same session. It needs a `GitHub.reviewComments()` call, a "last seen comment" field on
  the task so nothing is read twice, and a new alert kind.
- **"Undo that" and "Start over".** Two more quick commands in `VoiceCommand`. "Undo that" asks Claude to revert its
  last commit on the branch and push. "Start over" closes the pull request, deletes the branch and starts a fresh task
  with the original request. Both should confirm with yes or no first, like merging does.
- **Close or reopen a pull request by voice.** The car chat can merge but not close a pull request it got wrong. A
  close tool with the same two-turn confirmation as `MergeConfirmation` would cover it.

### Start work without typing

- **Issues to tasks.** "Fix issue 34 in aiauto" fetches the issue's title and body and starts a task with them, then
  adds "Closes #34" to the pull request body so GitHub closes the issue on merge. It needs `GitHub.issue()`, a
  `start_coding_task` option for an issue number in `Assistant`, and **Issues: read** on the token. A follow-up could read
  the newest open issues aloud so you can pick one.
- **Google Assistant shortcut.** An App Actions capability (`shortcuts.xml` with a custom intent) so "Hey Google, ask
  Cruise Control to add dark mode to aiauto" starts a task with no taps at all. **Listen when opened** gets close
  today, but still needs the app to open and hear the request a second time.
- **Queue a car chat request with no signal.** Coding tasks already resume after a dropped connection, but the car
  chat fails if there's no signal when you speak. Keeping the spoken request on the phone and sending it when the
  network returns, with "I'll send that when we're back online", would stop requests from being lost in tunnels and
  dead zones.

### Ask, don't change

- **Ask mode.** A read-only task type for questions like "how does the task engine reconnect?" or "what changed this
  week?". It runs a session whose prompt forbids commits and pushes, so no branch or pull request is made, and the
  answer is read aloud. The car chat can't do this today because it doesn't see the code.
- **Spoken diff summary.** When a pull request opens, read a one-sentence summary of the change, like "Added a toggle
  and two tests, 40 lines", so you know what you'll be reviewing later. The line counts come from the pull request
  itself; the sentence can come from Claude's last message or a short call to a small model.
- **Wider briefing.** The welcome-back briefing only covers tasks the app started. Adding a main branch that went red,
  new issues and pull requests from other people across saved repos would make it a real morning briefing. The
  data is already fetched for **Projects** by `GitHub.status()`.

### Safety and cost

- **Parked mode.** While driving, every step the agent's safety check flags is declined. Detecting that the car is
  parked (the Car App Library's car hardware or driving-state APIs) would let those steps wait for an approve or deny
  button on the car screen instead, so a task doesn't have to work around them.
- **Monthly spend in the briefing.** The monthly limit is built, but the welcome-back briefing, home screen and
  widget only mention today's spend. They could say how much of the month is used.

### Phone side

- **Review when you arrive.** A "Ready to review" list on the phone that shows the diff and the conversation
  together, with approve and merge buttons. It needs the pull request's files from GitHub and a simple diff view.
- **Microphone on the widget.** The widget opens the app, but has no button to speak a new task straight from the
  home screen.
- **Task history search.** Search past requests and pull requests across repos, including archived tasks, from the
  phone's task list.
