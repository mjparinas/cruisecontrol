# Feature ideas

Ideas for what Cruise Control could do next, based on what's already in the app (voice tasks, one Managed Agents
session per task, PRs opened automatically, updates read aloud). Ordered roughly by how much they'd improve the drive.

## Already built

These started on this list and are now in the app. See the README for how they work.

- **Merge by voice**, with a yes or no confirmation, and GitHub auto-merge when checks are still running.
- **CI results read aloud** after a pull request opens, with an offer to have Claude fix failures.
- **Quick commands**: "status", "stop", "merge it", "fix it", "repeat that", "quiet", "undo that",
  "start over", "yes" and "no" are handled by the app.
- **Repo by name** in the car chat, matched against saved repos so transcription slips still work.
- **Saved prompts**, built as task templates on the phone and in the car.
- **Welcome-back briefing** when the phone connects to the car.
- **Spend per task** and daily and monthly spending limits.
- **Resume after lost signal**: tasks cut off by a dropped connection restart when the network comes back.
- **Queue a car chat request with no signal**: the car chat keeps a question asked offline and answers it when the
  network returns.
- **Home screen widget** with running tasks and today's spend.
- **Monthly spend on the home screen and widget**, alongside today's total and any configured limits.
- **Ask mode**, for read-only questions about a repository, with answers read aloud.
- **Spoken pull request summary**, including the pull request title and diff counts.
- **Wider welcome-back briefing**, including changed issues and pull requests across saved repos and failed main-branch checks.
- **Ready to review** phone screen with pull request diffs, conversation, approve and merge actions.
- **Task history search** across active and archived tasks.
- **Full history in the car**: History screens per task and per repo, read aloud with Read all, scrolling back only
  while parked.
- **Setup checklist** for keys, repo access, phone permissions and Android Auto's Unknown sources setting.
- **Share to Cruise Control** to start from text shared by another app.
- **Launcher shortcuts and Quick Settings tile** for New task, Talk to Claude, and the last-used repo.
- **Widget microphone** that opens New task and starts listening.
- **Pull request notification actions** to open the pull request and bring up merge confirmation when checks pass.
- **Other AI providers for the car chat**: OpenAI and Gemini models, each with its own key in Settings.
- **Every repo available, most recently used first**: the phone and car repo pickers and the car chat offer every
  repo the GitHub token can push to, not only saved ones, with repos you've used most recently at the top.
- **Parked mode**: once the car stands still, steps the agent's safety check flags wait for Approve or Deny on the
  car screen instead of being declined.
- **Google Assistant shortcut**: "Hey Google, start a task to ... on Cruise Control" starts a task with no taps,
  once the app is uploaded to the Play Console. Gemini may not pick up custom App Actions intents.
- **Listening and thinking tones**: a tone when the microphone opens and closes, and a soft cue while an answer is slow.
- **Spoken updates wait while the microphone is open**: `Speaker` holds new speech while `VoiceInput` listens and
  reads it once listening ends, so the car never talks over the driver.
- **Steering-wheel buttons (experiment)**: behind the Steering-wheel buttons setting, off by default, play or pause
  starts Talk and next or previous repeats the last update. Still to learn: whether cars send the buttons to it
  reliably, and whether a media browser service is needed for that.

### Coding tasks on other providers

The car chat can use OpenAI or Gemini, but coding tasks still need Claude, because each task is a Claude Managed
Agents session: a hosted sandbox with the repo mounted, the GitHub token kept out of reach, a session budget and an
event stream the engine follows. Running tasks on another provider would need an equivalent hosted runtime behind
the `AgentClient` interface, with the same budget and token protections, rather than a plain chat API.

## Still missing

### Not planned by the owner

- Read back a new task before starting.
- Add “repeat that” or “quiet” to car chat.
- Show more than six rows in car lists.
- Notify about unfinished work when parking.

### Message history beyond this phone

The car's History screens show what the phone keeps. Three gaps remain, from simplest to most work:

- **Archived tasks in the car's repo history.** One filter in `RepoHistory.tasks`, but it reverses the decision
  that archived tasks stay off the car, so it's the owner's call.
- **History from Claude's session.** Each task's Managed Agents session keeps every event, so an `AgentClient`
  call that lists them (the same list `ClaudeAgents.follow` already reads on reconnect) could fill in messages a
  synced copy trimmed to 60, or progress notes that were replaced. The first user message is the long first prompt,
  so it would show the task's request instead. Recommended next step if trimmed history turns out to matter.
- **Keeping history after a clear.** Clearing deletes the task. Keeping a read-only copy would need its own store
  and a limit on its size, and it would have to stay out of sync.

### Close the loop by voice

- **Failing log tail in "fix it".** Today "fix it" sends Claude only the names of the failing checks, so Claude has to
  find and rerun the failure itself. Fetching the last lines of the failed job's log (the Actions jobs and logs
  endpoints) and adding them to `TaskEngine.fixChecksPrompt` would make fixes faster and cheaper. It needs the token's
  **Actions: read** permission, and the prompt should fall back to names only when the log can't be read.
- **Review comments as prompts.** Watch the task's pull request for new review comments, alongside the checks that
  `PullRequests` already follows, and read them out: "Sam commented on PR 12: rename this function". Replying sends the
  comment and your reply to the same session. It needs a `GitHub.reviewComments()` call, a "last seen comment" field on
  the task so nothing is read twice, and a new alert kind.
- **Close or reopen a pull request by voice.** The car chat can merge but not close a pull request it got wrong. A
  close tool with the same two-turn confirmation as `MergeConfirmation` would cover it.

### Start work without typing

- **Issues to tasks.** "Fix issue 34 in aiauto" fetches the issue's title and body and starts a task with them, then
  adds "Closes #34" to the pull request body so GitHub closes the issue on merge. It needs `GitHub.issue()`, a
  `start_coding_task` option for an issue number in `Assistant`, and **Issues: read** on the token. A follow-up could read
  the newest open issues aloud so you can pick one.
