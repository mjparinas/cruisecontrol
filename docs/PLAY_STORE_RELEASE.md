# Releasing on Google Play

A plan for getting Cruise Control onto Google Play, and what it takes to pass review. Nothing here is built yet; each
step under "Changes to the app" is meant to be its own small pull request. Policies were checked against Google's
pages in November 2026 (links at the end). Re-check them before each step, because the car rules change often.

## The short answer

Calling Cruise Control a messaging app won't get it through production review, and trying is risky:

- The car app quality guidelines say a messaging app "must implement a peer-to-peer messaging service" (`MF-5`).
  Every conversation in Cruise Control is between the driver and Claude, so a reviewer will see it isn't one.
- `PC-1` rejects car apps with features outside the supported categories (media, messaging, calling, navigation,
  points of interest, IoT, weather, and parked video, games and browsers). A coding agent isn't any of them.
- The IoT category, which the manifest also declares, only allows checking device state and one-touch on and off
  while driving. Starting tasks and merging pull requests is the "creation or modification" it forbids (`IT-1`).
- Templated messaging (the app's own car screen) is in beta and can only be published to internal and closed
  testing. Google rejects open testing and production submissions that include it.
- Describing the app as something it isn't breaks Play's misrepresentation rules. Repeated rejections or a
  suspension land on the developer account, and from 2027 that same verified identity is what lets anyone sideload
  the app on certified Android devices. Losing it would cost the GitHub releases too.

What does work: Play's own rules let a car app that doesn't fit the categories ship on the testing tracks. Car review
is skipped on internal testing and is non-blocking on closed testing, where a non-compliant build gets a warning
but is still approved. Open testing and production are blocking. So the plan is two Play builds and an honest
listing:

1. **Full app, with the car screen, on internal and closed testing.** The owner and invited testers install it from
   Play, so Android Auto shows it without the Unknown sources developer setting. This is the build that matters for
   the drive.
2. **Phone-only app in production.** The same app without Android Auto support, listed honestly as a voice-first
   Claude coding agent for GitHub. With no car artifact there is no car review, only the normal phone policies,
   which the app can meet with the changes below.
3. **GitHub releases stay** for anyone who wants the APK directly, as today.

## Getting more out of Play without misrepresenting the app

These stretch what Play allows without telling a reviewer anything untrue:

- **Grow the closed track.** Closed testing isn't only for the 12 testers. Anyone can join through a Google Group or
  an opt-in link, so the car build can reach every real user that way. It won't show up in Play search, and the
  listing should say the car screen is a test feature.
- **Hands-free without Android Auto.** The production build can still read updates aloud and take spoken replies over
  the car's Bluetooth, like any phone app, as long as nothing asks the driver to look at the phone. It just doesn't
  appear on the car screen.
- **Make it genuinely peer-to-peer.** If people working on the same repo could message each other in the app, with
  Claude taking part in their conversations, it would be a real messaging app and could ship messaging
  notifications to production (templated screens are still testing-only). That's a product change, not a relabel,
  and reviewers judge the app's main purpose, so it only works if talking to people is the main thing it does.
- **Wait for the categories to change.** Google adds car categories every few months (games and weather were added
  recently). If templated messaging opens to production or an assistant category appears, revisit this plan.

## Builds

Add a `store` product flavour dimension in `app/build.gradle.kts`, so one codebase gives three outputs:

| Flavour | Where it goes | Car screen and messaging notifications in Android Auto | Self-update from GitHub |
| --- | --- | --- | --- |
| `github` | GitHub releases (today's APK) | Yes | Yes |
| `play` | Play internal and closed testing | Yes | No |
| `playPhone` | Play production (and open testing, if used) | No | No |

- `playPhone` drops the `CarAppService`, the `com.google.android.gms.car.application` meta-data and the
  car-only `<queries>` from the merged manifest (a flavour manifest with `tools:node="remove"`). Without that
  meta-data Android Auto doesn't treat the app as a car app, so there's nothing to review for cars.
- Both Play flavours drop `REQUEST_INSTALL_PACKAGES`, the `FileProvider` for updates and the update check in
  Settings. Play forbids self-updates through that permission, and Play delivers updates itself.
- Version codes keep coming from the Release workflow's run number, so they always rise across all three flavours.
  Give `play` builds a higher code than the `playPhone` build of the same release, so testers keep the car build.
- Upload Android App Bundles (`bundlePlayRelease`, `bundlePlayPhoneRelease`), not APKs. Play requires bundles for
  new apps.

### Signing

Turn on Play App Signing and choose to upload the existing release key (with Google's PEPK tool) instead of letting
Google generate one. Then Play builds and GitHub builds are signed with the same key, `dev.aiauto` stays the same
app everywhere, and someone can move from a GitHub install to Play (or back) without uninstalling. Make a separate
upload key for CI. If Google generates the key instead, the two channels can never install over each other, and
switching means uninstalling first, which loses the saved key, token and tasks.

Check that `dev.aiauto` is free on Play before anything else. AGENTS.md says the package never changes, so if it's
taken, that decision has to be revisited first.

## Changes to the app

Each item is a small, separate pull request with tests, in roughly this order.

1. **Flavours.** Add `github`, `play` and `playPhone` as above. Move the updater behind a flag the flavour sets,
   and test that the Play flavours have no update entry point. Keep `github` as the default so CI and the README
   instructions don't change.
2. **Target API 36.** Done: since August 31, 2026, new apps and updates must target Android 16 (API 36), even on
   the testing tracks. The app now targets 36, and Android 16's behaviour changes (predictive back, orientation
   and resizability on large screens, job quotas) were checked against it; AGENTS.md says what was looked at.
3. **Reporting AI answers.** Play's AI-Generated Content policy requires a way to report or flag offensive output
   without leaving the app. Add a Report action on Claude's messages in the task screen and the chat, which records
   the message and opens a prefilled email or GitHub issue to the developer. Say in Settings that answers come from
   Claude and may be wrong.
4. **Battery optimization.** Play only allows asking for a direct exemption when the core function breaks without
   it, and the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt draws extra scrutiny. In the Play flavours, replace
   the prompt in `MainActivity.askToRunInBackground` with an explanation and a button to the battery settings list
   (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), which needs no permission. Keep today's prompt in `github`.
5. **Foreground service declaration.** The task service uses the `dataSync` type to keep the session's event
   stream open. Play Console asks which types are used and why, with a short video. Record a video of starting a
   task, locking the phone and the update arriving. Fit it to `dataSync` honestly (it transfers task data between
   the device and the cloud, and the user starts it); only fall back to `specialUse` if Play pushes back.
6. **Microphone disclosure.** The app asks for the microphone at first launch. Ask in context instead (the first time
   someone taps a mic button), with one sentence saying speech goes to the phone's speech recognizer and the text to
   Claude. Play's prominent disclosure rule expects that.
7. **Release workflow.** Build the bundles alongside the APK and upload them to Play with the Play Developer API
   (a service account key in repo secrets), sending `play` to internal testing. Promotion to closed testing and
   production stays a manual step in Play Console.

## Play Console setup

- **Developer account.** A personal account made after November 13, 2023 must run a closed test with at least 12
  testers, opted in for 14 days in a row, before it can apply for production. An organization account (it needs a
  D-U-N-S number) skips that. The account's identity verification also covers developer verification for sideloaded
  apps, so register the GitHub release signing key there too.
- **Privacy policy.** Required, because the app handles a microphone, an API key, a GitHub token and code. Host it
  on GitHub Pages and say plainly: there is no Cruise Control server; prompts, spoken text and repo contents go to
  Anthropic; the GitHub token is sent to Anthropic for each session so the sandbox can push; sync writes to a
  private repo on the user's own GitHub account; keys stay encrypted on the device.
- **Data safety form.** Declare messages and voice-derived text, and app activity, as shared with a third party
  (Anthropic) for app functionality; data encrypted in transit; nothing sold; no accounts made by the app, so the
  account deletion requirement doesn't apply. Clearing the app's data deletes everything it keeps.
- **App access.** Reviewers can't use the app without a Claude API key and a GitHub token. Give them a low-limit API
  key and a fine-grained token for a throwaway public repo, with steps. A missing login is a common rejection.
- **Content rating and audience.** Fill in the questionnaire honestly (user-generated and AI-generated text) and set
  the target audience to 18 and over, which keeps it out of the Families program.
- **Store listing.** Category Productivity or Tools. Call it what it is: a voice-first app for running Claude coding
  tasks on your GitHub repos, needing your own Claude API key and GitHub token, with costs billed to your Anthropic
  account. Keep "Claude" and Anthropic's logo out of the app name and icon (say "works with Claude" in the text) so
  it doesn't read as an official Anthropic app. The production listing mustn't show or promote using the phone
  while driving, since that build has no car screen.

## Rollout

1. **Prepare.** Make the developer account, check the package name, set up Play App Signing with the existing key,
   write and host the privacy policy, and land the app changes above.
2. **Internal testing.** Upload the `play` bundle. Up to 100 testers, no car review. Install it from Play on the
   owner's phone and confirm Android Auto shows the app with Unknown sources turned off.
3. **Closed testing.** Promote the same build to a closed track with at least 12 testers (a Google Group is
   easiest), kept opted in and actually using the app for 14 days. Expect a car review notice that the build doesn't
   meet the guidelines; on this track it doesn't block approval. Fix anything phone-side it raises.
4. **Production access.** Apply from the Play Console dashboard, describing the closed test and what changed because
   of it.
5. **Production.** Release the `playPhone` bundle with a staged rollout, starting small. Keep testers on the
   closed track so they keep the car build.
6. **After launch.** Answer reviews, watch Android vitals for crashes and ANRs, and keep `targetSdk` current each
   August. Re-read the car app categories when Google updates them: if templated messaging opens to production or a
   category for assistants appears, a car build in production can be reconsidered then, honestly described.

## If a review fails

- **Car quality notice on closed testing:** expected and non-blocking. Don't put the `play` build on open testing or
  production to "fix" it.
- **Permissions or foreground service:** make sure the Play bundle really lacks `REQUEST_INSTALL_PACKAGES`, answer
  the declaration with the video, and reply through the Play Console appeal form with the reason.
- **AI content or misrepresentation:** check the Report action is easy to find and the listing matches what the app
  does.
- **Can't log in:** refresh the reviewer API key and token in App access; keys with spent budgets fail quietly.

## Sources

- Car app quality guidelines, including `PC-1`, `MF-5` and `IT-1`:
  https://developer.android.com/docs/quality-guidelines/car-app-quality
- Supported car app categories: https://developer.android.com/training/cars#supported-app-categories
- Templated messaging is limited to internal and closed testing:
  https://developer.android.com/training/cars/communication/templated-messaging
- Car review by release track: https://developer.android.com/training/cars/distribute
- Target API level requirements: https://support.google.com/googleplay/android-developer/answer/11926878
- Testing requirements for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- REQUEST_INSTALL_PACKAGES policy: https://support.google.com/googleplay/android-developer/answer/12085295
- AI-generated content policy: https://support.google.com/googleplay/android-developer/answer/14094294
- Foreground service declarations: https://support.google.com/googleplay/android-developer/answer/13392821
- Doze exemptions: https://developer.android.com/training/monitoring-device-state/doze-standby
- Play App Signing: https://support.google.com/googleplay/android-developer/answer/9842756
- Developer verification: https://developer.android.com/developer-verification
