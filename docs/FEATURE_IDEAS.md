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
- **Other AI providers for the car chat**: OpenAI and Gemini models, each with its own key in Settings.

### Coding tasks on other providers

The car chat can use OpenAI or Gemini, but coding tasks still need Claude, because each task is a Claude Managed
Agents session: a hosted sandbox with the repo mounted, the GitHub token kept out of reach, a session budget and an
event stream the engine follows. Running tasks on another provider would need an equivalent hosted runtime behind
the `AgentClient` interface, with the same budget and token protections, rather than a plain chat API.

## Still missing

### Easier in the car

- **Listening and thinking tones.** Today the only sign that the microphone opened, closed or that Claude is still
  thinking is text on the car screen. A short tone when `VoiceInput` starts and stops listening, and a soft cue when
  an answer takes more than a few seconds, would let the driver keep their eyes on the road. Use the same assistant
  audio attributes as the microphone so the tones duck music like speech does.
- **Read back a new task before it starts.** A task spoken from Tasks > New task starts as soon as the recognizer
  returns, so a misheard request still costs a session. Saying "Starting: add a dark mode toggle, in aiauto" with a
  few seconds to say "cancel" or tap Cancel would catch transcription slips. The read-back text and the cancel window
  belong in `core` so they can be tested.
- **Hold spoken updates while the microphone is open.** `ListenScreen` and `MicScreen` stop the speaker when they
  start listening, but a task message or progress note that arrives mid-sentence is read out straight away, talks over
  the driver and can end up in the transcript. `Speaker` could queue new speech while listening and play it after.
- **"Repeat that" and "quiet" in the car chat.** Task replies already handle both. The car chat could too: "repeat
  that" replaying its last answer, and "quiet" turning quiet mode on only for the rest of the drive.
- **Every repo reachable in the car, last used first.** `PickRepoScreen` lists the first six saved repos in the order
  they were added, so a seventh can't be picked in the car at all. Sorting by last used, and asking the host for its
  real list size with `ConstraintManager.getContentLimit` instead of a fixed six (also in `TasksScreen` and
  `TemplatesScreen`), would fix both.
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
- **Check the spoken text before it's sent.** On the phone, the mic button in the prompt box sends what the recognizer
  heard right away. Putting it in the box first, so you can fix a misheard word and tap Send, suits the phone, where
  you can look. The car keeps sending straight away.
- **Setup checklist.** Setup has six steps across three apps, and a missed one shows up later as a silent failure. A
  checklist in Settings could tick off what the app can check itself (API key works, token can push to each repo and
  read checks, notifications and microphone allowed, battery optimization off) and link to the fix for each one.
  Android Auto's Unknown sources can't be read, so that step stays a tip.
- **Share to Cruise Control.** Accept text and links from other apps' Share menu, such as a GitHub issue, a crash
  message or a Slack thread, and open New task with it filled in. It needs an `ACTION_SEND` intent filter on
  `MainActivity`.
- **Launcher shortcuts and a quick settings tile.** Long-pressing the app icon could offer New task, Talk to Claude
  and the last-used repo. A quick settings tile could start a task or switch read aloud on and off. Both are small
  and work on any phone, unlike the Google Assistant shortcut.
- **Buttons on the result notification.** When a task's pull request opens or its checks pass, the notification
  only offers Reply. Adding Open pull request and, when checks are green, Merge (with the same confirmation as in the
  app) would save opening the app for the most common next step.
