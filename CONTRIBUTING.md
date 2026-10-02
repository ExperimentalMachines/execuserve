# Contributing to ExecuServe

Issues and pull requests are welcome. This page is how to set up, what the checks are, and
what a change needs before it is reviewed.

## Setting up

You need JDK 21, the Android SDK (platform 37, NDK 29), and for the browser checks Node.js 22
or newer. A phone is optional for most changes.

```sh
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
./gradlew verify
```

## The checks

| Command | What it covers |
|---|---|
| `./gradlew verify` | ktlint, detekt, Android lint, every JVM and Android host test, the debug build, and the iOS compilation of the shared modules. Every Kotlin warning fails the build. CI runs exactly this |
| `./gradlew ktlintFormat` | Fixes most style findings in place (`.editorconfig` holds the rules) |
| `tools/web` | The browser chat's checks: start `:jvm:devserver` on port 8082 with key `sk-dev`, then `npm ci && npx playwright install chromium && node browser-check.mjs` |
| `tools/compat/` | The OpenAI SDK, Anthropic SDK and edge-case suites, against the dev server or a phone |
| `tools/design/contrast.py` | WCAG and APCA for every colour pair, after any palette change |
| `tools/design/mark.py` | Regenerates every copy of the mark; never edit its outputs by hand |
| [docs/TESTING-ON-A-PHONE.md](docs/TESTING-ON-A-PHONE.md) | The on-device steps, from install to a screen-off soak |

detekt's per-module `detekt-baseline.xml` files record complexity findings that predate the
gate. New code must pass without adding to them; a change that simplifies a baselined method
should regenerate its baseline (`./gradlew detektBaseline`) so the entry goes away.

## What a change needs

- **Shared code stays shared.** Anything that is not a platform fact belongs in `:shared:*`
  `commonMain` and must compile for iOS; `verify` proves it on every run.
- **A test for the behaviour.** Engine and server changes are tested on the JVM against the
  scripted runtime in `:jvm:testing`; browser chat changes in `tools/web/browser-check.mjs`.
- **Numbers from a real phone** for any performance claim, before and after, with the device
  and the model file named.
- **No new hard-coded values.** Limits and choices live once (`HostSettings`, `Choices`,
  `Units`, `HttpStatus`); user-visible text lives in string resources.
- **Security posture holds.** Every route needs a key unless it is the health check or the
  static chat shell; the browser chat keeps its Content-Security-Policy with no inline script
  or style; nothing new is written where backups or other apps could read it.

## Reporting

Bugs: open an issue with the phone, Android version, model file and the run from the
Activity tab. Security issues: see [SECURITY.md](SECURITY.md), not a public issue.
