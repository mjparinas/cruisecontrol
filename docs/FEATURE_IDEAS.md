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
- **Other AI providers for the car chat**: OpenAI and Gemini models, each with its own key in Settings.
- **Every repo available, most recently used first**: the phone and car repo pickers and the car chat offer every
  repo the GitHub token can push to, not only saved ones, with repos you've used most recently at the top.
- **Parked mode**: once the car stands still, steps the agent's safety check flags wait for Approve or Deny on the
  car screen instead of being declined.
- **Google Assistant shortcut**: "Hey Google, start a task to ... on Cruise Control" starts a task with no taps,
  once the app is uploaded to the Play Console.
- **Listening and thinking tones**: a tone when the microphone opens and closes, and a soft cue while an answer is slow.

### Coding tasks on other providers

The car chat can use OpenAI or Gemini, but coding tasks still need Claude, because each task is a Claude Managed
Agents session: a hosted sandbox with the repo mounted, the GitHub token kept out of reach, a session budget and an
event stream the engine follows. Running tasks on another provider would need an equivalent hosted runtime behind
the `AgentClient` interface, with the same budget and token protections, rather than a plain chat API.

## Still missing

### Easier in the car

- **Read back a new task before it starts.** A task spoken from Tasks > New task starts as soon as the recognizer
  returns, so a misheard request still costs a session. Saying "Starting: add a dark mode toggle, in aiauto" with a
  few seconds to say "cancel" or tap Cancel would catch transcription slips. The read-back text and the cancel window
  belong in `core` so they can be tested.
- **Hold spoken updates while the microphone is open.** `ListenScreen` and `MicScreen` stop the speaker when they
  start listening, but a task message or progress note that arrives mid-sentence is read out straight away, talks over
  the driver and can end up in the transcript. `Speaker` could queue new speech while listening and play it after.
- **"Repeat that" and "quiet" in the car chat.** Task replies already handle both. The car chat could too: "repeat
  that" replaying its last answer, and "quiet" turning quiet mode on only for the rest of the drive.
- **More than six rows in car lists.** `PickRepoScreen` now lists every repo last used first, but still shows only
  the first six, so an older repo can't be picked in the car. Asking the host for its real list size with
  `ConstraintManager.getContentLimit` instead of a fixed six (also in `TasksScreen` and `TemplatesScreen`) would
  fix that.
- **Hand-off when you park.** When the phone disconnects from the car, post one phone notification that sums up what
  is still going: "2 tasks still running, PR 12 is ready to review". Tapping it opens the task list, so nothing
  started on the drive is forgotten once you're out of the car.
- **Steering-wheel button (experiment).** Built behind the Steering-wheel buttons setting, off by default: play or
  pause starts Talk, next or previous repeats the last update. Still to learn: whether cars send the buttons to it
  reliably, and whether a media browser service is needed for that.

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

- **Monthly spend on the home screen and widget.** The welcome-back briefing now says the month's spend and limit,
  but the home screen and widget only mention today's spend. They could say how much of the month is used.

### Phone side

- **Review when you arrive.** A "Ready to review" list on the phone that shows the diff and the conversation
  together, with approve and merge buttons. It needs the pull request's files from GitHub and a simple diff view.
- **Microphone on the widget.** The widget opens the app, but has no button to speak a new task straight from the
  home screen.
- **Task history search.** Search past requests and pull requests across repos, including archived tasks, from the
  phone's task list.
- **Setup checklist.** Setup has six steps across three apps, and a missed one shows up later as a silent failure. A
  checklist in Settings could tick off what the app can check itself (API key works, token can push to each repo and
  read checks, notifications and microphone allowed, battery optimization off) and link to the fix for each one.
  Android Auto's Unknown sources can't be read, so that step stays a tip.
- **Share to Cruise Control.** Accept text and links from other apps' Share menu, such as a GitHub issue, a crash
  message or a Slack thread, and open New task with it filled in. It needs an `ACTION_SEND` intent filter on
  `MainActivity`.
- **Launcher shortcuts and a quick settings tile.** Long-pressing the app icon could offer New task, Talk to Claude
  and the last-used repo. A quick settings tile could start a task or switch read aloud on and off. Both are small
  and work on any phone, unlike the Google Assistant shortcut, which needs the app on Play.
- **Buttons on the result notification.** When a task's pull request opens or its checks pass, the notification
  only offers Reply. Adding Open pull request and, when checks are green, Merge (with the same confirmation as in the
  app) would save opening the app for the most common next step.
