# Releasing to Google Play

What the build already does for a Play release, how a release goes out from GitHub Actions,
and what is left to do by hand. The technical requirements were checked on the release build
on 2 October 2026.

## What the build does

| Requirement | State | How it is checked |
|---|---|---|
| Android App Bundle | `./gradlew :android:app:bundleRelease` | Refuses to run without the upload key, so a debug-signed bundle never reaches Play |
| Upload signing | From `keystore.properties` or the environment, never the repository | `keytool -printcert -jarfile app-release.aab` names the upload key |
| Version code | The commit count on `main`, ratcheted by `versionCodeFloor` | `aapt2 dump badging` on the APK |
| `targetSdk` 36 | Done | `aapt2 dump badging` |
| 16 KB page alignment | All native libraries, ExecuTorch included, have 16 KB `LOAD` alignment, and the APK passes `zipalign -c -P 16` | Checked on the release APK |
| Minified release runs | R8 with resource shrinking; the release build is what serves on the test phone | On device |
| Generative-AI reporting | **Report this reply** on every reply in the console's Chat | `ReportDialogTest`, `ContentReportTest` |
| Uncensored models | The catalog leaves out Heretic and other abliterated derivatives unless built with `-PcatalogUncensored=true`; the Play bundle never is | `CatalogTest.uncensoredDerivativesAreKnownByName` |
| Privacy policy | [privacy-policy.md](privacy-policy.md), live at [experimentalmachines.org/execuserve/privacy](https://experimentalmachines.org/execuserve/privacy/) | Published with the organisation's site |

## The upload key, once

ExecuServe uses Experimental Machines' upload key, the one OpenWeights already uploads
with: Play App Signing gives each app its own signing key, and one upload key may serve
several apps. Point `storeFile` in `keystore.properties` at that keystore. A new key, if one
is ever wanted, is made like this:

```sh
keytool -genkeypair -keystore ~/keys/execuserve-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and its passwords outside the repository and backed up; losing the
upload key means asking Google to reset it. Then either create `keystore.properties` in the
repository root (it is git-ignored):

```properties
storeFile=/Users/you/keys/execuserve-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

or set `EXECUSERVE_KEYSTORE`, `EXECUSERVE_KEYSTORE_PASSWORD`, `EXECUSERVE_KEY_ALIAS` and
`EXECUSERVE_KEY_PASSWORD`. Enrol the app in **Play App Signing** when creating it: Google
holds the key users verify, and this one only uploads.

Without an upload key, `assembleRelease` still signs with the debug key. That is deliberate:
it lets a release build install over the one already on a test phone, since a different key
would force an uninstall and lose its models. Note that once the upload key is configured,
`assembleRelease` signs with it too, and installing that over a debug-signed build needs an
uninstall first.

## Version codes

`versionCode` is the number of commits on the branch (`git rev-list --count HEAD`), so it
rises with every commit and is the same locally and in CI. Cut releases from `main`. A
shallow clone stops the build rather than counting wrong; CI fetches the full history. If a
squash or rebase ever takes the count below a code already uploaded, the build stops and
asks for `versionCodeFloor` in `android/app/build.gradle.kts` to be raised. `versionName` is
typed by hand.

## Releasing from GitHub Actions

`.github/workflows/release.yml`, started by hand: Actions, **Release**, **Run workflow**. It is
OpenWeights' release workflow and its `tools/release/play.py`, ported: what differs is the
package (`org.experimentalmachines.execuserve`), where `versionName` lives
(`android/app/build.gradle.kts`), the secrets' names (`EXECUSERVE_*`), and which paths count as
shipping in the bundle. It builds main as it is at that moment and takes five inputs: the
track (internal, closed testing `alpha`, open testing `beta`, or production), the rollout
percentage (below 100 is a staged rollout), the What's new text (optional), a `version_code`
to promote instead of building, and a dry run switch.

It runs in two jobs.

1. **prepare** runs the release tool's tests, checks the secrets, asks Play which version
   code each track is serving, and stops within seconds if main's code is not higher than
   every code Play has, or if nothing has changed. It then writes the release notes (below),
   builds `:android:app:bundleRelease` with the upload key (the store build: without
   `-PcatalogUncensored`), checks the bundle is signed, and keeps the bundle, `mapping.txt`
   and the notes as the run's artifact. The run's summary shows the notes, the commits they
   came from, and the SHA-256 of the signing certificate, which must match the upload
   certificate in the Console.
2. **publish** uploads the bundle and the R8 mapping file, puts the release on the track with
   the notes, and commits the edit. For production it waits first: the job runs in the
   `play-production` environment, which needs an approval from @alpharomercoma, so nothing
   reaches users until somebody has read the summary and pressed Approve. The testing tracks
   go straight through (`play-testing`). Both environments deploy from main only. A dry run
   stops before this job and uploads nothing.

A track with no release yet, production before the first one there, is measured from the
newest version any testing track has, below the one being released. With no release on any
track at all, the run needs the text in whats_new.

### Testing first, then promoting the same bundle

Release to internal testing, look at it on a phone, then promote that bundle: given a
`version_code` Play already has, the workflow builds nothing and releases that bundle on the
chosen track, with notes from the commits since the track's version up to the one that bundle
was built from. It refuses a code Play does not have, and a code no commit on main has the
count of.

```
Run workflow: track internal                       builds main as 31, releases it to testers
Run workflow: track production, version_code 31    promotes that bundle, after the approval
```

### Where "what changed" comes from

The version code is the commit count on main, so the code a track is serving names the commit
it was built from, and the release is every commit after it. `tools/release/play.py notes`
reads the code from Play, finds that commit on main, and lists everything since. For a
testing track it measures from the higher of that track's code and production's, because
testers are production users too and Play gives them whichever is newer.

Typed into the whats_new box, the text is used as it is (`\n` between lines). Left empty,
Gemini drafts it on the API's free tier, in at most 450 characters, from the commits that
changed what ships in the Android bundle: `android/*`, `shared/*` and the build
configuration (`gradle/`, `gradle.properties`, `build-logic/`, the root build and settings
scripts), with test, debug and iOS
source sets aside. Commits that touched only docs,
results, tests, `jvm/` or tooling are listed on the summary but never shown to the model.
Each draft tries `gemini-3.8-flash`, then `gemini-3.5-flash`, then `gemini-2.5-flash-lite`,
three times each with backoff, and the summary names the one that wrote it. That needs a
`GEMINI_API_KEY` secret; without one an empty box stops the run with a message. What is sent
is commit messages that are already public in this repository, and nothing else. Either way
the text is held to Play's 500-character limit and the house style (no em or en dashes), and a
production release waits for the approval, so a bad draft is rejected there and the run started
again with the text typed in.

### Choices made on purpose

- **Main only.** A branch builds a lower commit count, and Play refuses a code that goes down.
- **Nothing already in review is cancelled.** The edit is committed with `ERROR_IF_IN_REVIEW`,
  so a listing change waiting in review stops the run instead of being resubmitted with it.
- **No third-party action sees the Play key.** The upload is Google's own Python client,
  pinned in `tools/release/requirements.txt`.
- **A dry run uploads nothing.** It checks the secrets, Play access, the version code, the
  notes, the build and the signature.
- **One release at a time**, and a running one is never cancelled by the next.

If a run fails after the upload with "version code has already been used", push any commit to
main and run it again; the new count is a new code.

### Setting it up, once

1. **A service account with access to ExecuServe.** OpenWeights already releases with one. In
   the Play Console, *Users and permissions*, open that service account (or invite a new one)
   and under *App permissions* add ExecuServe, with *View app information (read-only)*,
   *Release apps to testing tracks* and *Release to production, exclude devices, and use Play
   App Signing*. Nothing account-wide. Its JSON key is not kept anywhere once it is a secret,
   so create a new key for it in the Google Cloud Console (*IAM*, *Service accounts*, *Keys*).
2. **The secrets.** On the machine that signs releases, with `keystore.properties` in place:

   ```sh
   GEMINI_API_KEY=... tools/release/set_play_secrets.sh ~/Downloads/<the key>.json
   ```

   It sets `EXECUSERVE_KEYSTORE_BASE64`, `EXECUSERVE_KEYSTORE_PASSWORD`,
   `EXECUSERVE_KEY_ALIAS` and `EXECUSERVE_KEY_PASSWORD` from `keystore.properties` and the
   keystore it names, `PLAY_SERVICE_ACCOUNT_JSON` from the key, and `GEMINI_API_KEY` if one is
   exported. Every value goes from its file into `gh secret set` on stdin and is never
   printed. Delete the downloaded key after.
3. **A dry run** on the internal track, then a real one there, before the first production
   release from the workflow.

The same script runs from a laptop, with the key's JSON in `PLAY_SERVICE_ACCOUNT_JSON`:
`python3 tools/release/play.py notes --track production` prints what the next release would
say, and `--base-code <code>` measures from a code of your choosing without asking Play.

## In the Play Console, by hand

1. **Privacy policy URL.** `https://experimentalmachines.org/execuserve/privacy/`, published
   from the organisation's site (`app/execuserve/privacy/page.tsx` in
   [experimentalmachines.org](https://github.com/ExperimentalMachines/experimentalmachines.org)).
   Paste it into App content → Privacy policy. It mirrors [privacy-policy.md](privacy-policy.md):
   change both together. The listing's website is `https://experimentalmachines.org/execuserve/`.
2. **Data safety.** No data collected or shared by the developer: there is no server, no
   analytics and no account. Data processed only on the device is not "collected" under
   Play's definition, and what the user sends through the share sheet is their action. Model
   downloads go to Hugging Face; say so in the description rather than as collection.
3. **Foreground service declaration.** `specialUse`, subtype as in the manifest: "Local AI
   inference server". Play asks for a short video: start the server, background the app,
   send a request from another app or the computer, show it answered. The captioned video is
   [play/execuserve-foreground-service.mp4](play/execuserve-foreground-service.mp4); upload
   it to YouTube as unlisted and paste the link.
4. **Battery optimisation.** Nothing to declare: the app does not hold
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and Settings opens the system's battery-optimisation
   list for anyone who wants to exempt it.
5. **Content rating, target audience** (not children), category (Tools), contact details.
6. **Listing.** The text, eight phone screenshots and how to remake them are in
   [play/listing.md](play/listing.md); the 512 px icon and 1024 × 500 feature graphic are in
   [brand/](brand/).
7. **Testing.** Upload to internal testing first and read the pre-launch report. A personal
   developer account must also run a closed test with at least 12 testers for 14 days
   before production.
