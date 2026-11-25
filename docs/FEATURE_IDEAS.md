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
- **Read back and confirm a task before starting** on the phone, car screen, Google Assistant shortcut and
  Android Auto messaging reply. A separate yes is required.
- **Repeat and quiet in car chat**: repeat the last answer or turn on quiet mode without starting a model turn.
- **More car list rows**: lists show up to ten rows, with history pages still gated on parked mode.
- **Parked-work reminder**: one phone notification when the car first becomes parked with unfinished tasks.
- **Issues to tasks**: list open issues or start a task from an issue; its body is included and the pull request says
  `Closes #number`.
- **Pull request review comments**: new comments are read aloud, and replying sends the comment and response to the
  same Claude session. The last seen comment id syncs between devices.
- **Deeper car history**: archived tasks appear in repo history; Claude's session fills older events trimmed from a
  synced copy; the last 100 cleared task histories remain available locally and do not sync.
- **Google Assistant shortcut**: "Hey Google, start a task to ... on Cruise Control" opens the request for a separate
  confirmation before starting, once the app is uploaded to the Play Console. Gemini may not pick up custom App Actions intents.
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

### In a separate open pull request

- **Failing Actions log context and voice close/reopen controls** are already being implemented in PR #116. They are
  intentionally not duplicated in this branch.
