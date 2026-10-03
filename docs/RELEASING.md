# Releasing to Google Play

What the build already does for a Play release, and what is left to do by hand. The
technical requirements were checked on the release build on 2 October 2026.

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
