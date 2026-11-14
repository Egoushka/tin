# Home updates: the phone follows `main`

Every commit on `main` where every CI job passes (Rust tests and clippy, the Android build, the emulator tests)
becomes a GitHub release (`v0.1.<commit count>`, two APKs, both signed with the Tin release key): Home as `home-<version>.apk` and the tin host app as `tin-<version>.apk`. Obtainium on the Pixel polls the releases and updates both in place. No cable, no `adb install`.

```
merge to main -> CI core, android and emulator jobs green -> signed home + tin APKs -> release job -> GitHub Release v0.1.N -> Obtainium -> phone
```

Why this route and not an updater inside Home or an adb push from a Mac/box: it needs no app code (Home would
otherwise hold the install-packages permission and its own update UI), no machine that must be awake and in reach of
the phone, and the signing check is Android's own. The comparison is in the PR that added this file.

## One-time setup (Yehor)

### 1. Create the release key and store it as repo secrets

The key is created here on the Mac, pushed straight into GitHub Actions secrets, and also appended to
`~/.gradle/gradle.properties` so a Mac build (`tin.signing.*`, see `android/build.gradle.kts`) is signed with the same
key and installs over a CI build. Nothing is printed. `keytool` comes from Homebrew's `openjdk` (`/usr/bin/keytool` is a stub).

```bash
bash -c 'set -euo pipefail; umask 077; export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"; d="$HOME/.config/tin"; mkdir -p -m 700 "$d"; test ! -e "$d/release.jks"; pw=$(openssl rand -base64 33 | tr -dc A-Za-z0-9); keytool -genkeypair -keystore "$d/release.jks" -storetype PKCS12 -alias tin -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Tin" -storepass "$pw" -keypass "$pw" 2>/dev/null; base64 < "$d/release.jks" | tr -d "\n" | gh secret set TIN_SIGNING_KEYSTORE_B64 -R Egoushka/tin; printf %s "$pw" | gh secret set TIN_SIGNING_PASSWORD -R Egoushka/tin; printf "tin.signing.storeFile=%s\ntin.signing.storePassword=%s\ntin.signing.keyAlias=tin\ntin.signing.keyPassword=%s\n" "$d/release.jks" "$pw" "$pw" >> "$HOME/.gradle/gradle.properties"'
```

Back up `~/.config/tin/release.jks` and the `tin.signing.storePassword` line of `~/.gradle/gradle.properties` in
your password manager. GitHub never shows a secret again; if the key is lost, the phone needs one `adb uninstall`
(which clears Home's data and the Home role) before a new key can install.

Then re-run CI on `main` (push any commit, or "Re-run all jobs" on the last `ci` run): the `release` job publishes
`v0.1.N`. Until the secrets exist, CI stays green, builds a debug-signed APK and publishes nothing.

### 2. Install Obtainium on the Pixel

1. Get the APK from `https://github.com/ImranR98/Obtainium/releases` (or F-Droid / IzzyOnDroid) and install it.
   GrapheneOS asks once to allow Obtainium to install unknown apps (Settings > Apps > Obtainium).
2. Remove the Home that is on the phone now; it is signed by another key and cannot be updated in place. Do this once,
   with Obtainium already installed, so the phone is never without a launcher for long:
   ```bash
   adb uninstall io.github.egoushka.tin.home
   ```
3. In Obtainium: Add App > `https://github.com/Egoushka/tin` (the repo is public, no token). In the app's options keep
   "Include prereleases" off and set the APK filter to `home-.*\.apk`. The release has two APKs, so the filter is required.
4. Install the Home it offers, then set it as the default home app again (Settings > Apps > Default apps > Home app).
5. Add the tin app as a second entry on the same repo: Add App > `https://github.com/Egoushka/tin` again, APK filter
   `tin-.*\.apk`. First remove the tin that is on the phone (see "Moving tin to the release build" below), then install the
   one Obtainium offers.
6. In Obtainium's settings set the background update interval (for example 1 hour) and allow it to install updates
   without asking. Android only skips the confirmation for an update when Obtainium is the installer that installed
   the app, which is why step 2 matters; if a confirmation still shows, accept it once and check this setting.

### Moving tin to the release build (once)

The tin app on the phone now is a laptop debug build, so it has another signer than Home, and Android never grants
Home the signature-level `VOICE` permission (Home's mic falls back to the assistant). Remove it once; the release build
is signed with the Tin key:

```bash
adb uninstall io.github.egoushka.tin
```

What this loses, and what does not:

- Kept: everything in `/data/local/tmp/tin/` (the tin binary, `policy.toml`, `tin.log`). It belongs to the shell user,
  not the app, so the policy stays. The app copies the binary again on its next start.
- Lost: the app's own folder. That is the speech model (`files/models/`, about 650 MB) and the speech bench output. Push the
  model again after installing the release build:
  ```bash
  scripts/speech-push-model.sh
  ```
  It needs the bench cache (`bench/run-all.sh` fills it) or a models folder as its argument. The release build is
  debuggable on purpose so `run-as` in that script works.
- Lost: Shizuku's permission for the app and the microphone and notification grants. Open tin once and tap "Allow" in
  Shizuku's dialog, then start Shizuku's service as in the README Quick start (`libshizuku.so`) if it is not running.
- Not touched: Home and its data. Re-open Home after tin is installed so it binds `VoiceService` again.

Obtainium compares the release tag (`v0.1.N`) with Home's `versionName` (`0.1.N`), so both sides stay in the same format.

## Am I on the latest?

- Home shows `Build 0.1.N · <short sha>` through `BuildStamp()` (`ui/BuildStamp.kt`), the last line of the Tune sheet.
- From the Mac: the phone's version against the newest release.
  ```bash
  adb shell dumpsys package io.github.egoushka.tin.home | grep versionName
  adb shell dumpsys package io.github.egoushka.tin | grep versionName
  ```
  ```bash
  gh release list -R Egoushka/tin --limit 1
  ```
- `versionCode` is the commit count of `main` at build time, so Android treats every later commit as an update.
  A build from a laptop checkout reports its own count and short SHA, with `-dirty` when tracked files are modified.

## Failure modes

| Symptom | Cause | Fix |
|---|---|---|
| CI warns "TIN_SIGNING_KEYSTORE_B64 ... not set" | secrets missing | step 1 |
| CI `Check Home's signer` fails with `CN=Android Debug` | key secret present but Gradle did not pick it up | check the `Release signing key` step ran (main pushes only) |
| Obtainium or `adb install` says update incompatible | installed Home has another signer (hand-installed debug or an old CI build) | `adb uninstall io.github.egoushka.tin.home` once |
| Home's mic falls back to the assistant | tin and Home have different signers (the `VOICE` permission is signature-level) | build tin with the same key: `tin.signing.*` in `~/.gradle/gradle.properties` is already set by step 1 |
| CI `Check the APK signers` fails with "different signers" | tin and Home were built with different keys | check `tin.signing.*` reaches both modules (`android/build.gradle.kts`) |
| Obtainium offers `home-*.apk` for the tin entry, or the reverse | APK filter missing or wrong | set `home-.*\.apk` on the Home entry and `tin-.*\.apk` on the tin entry |
| tin: `adb install` or Obtainium says update incompatible | installed tin is a laptop debug build | `adb uninstall io.github.egoushka.tin` once (loses the speech model, see above) |
| tin says "model missing" after the move | the model lived in the old app's folder | `scripts/speech-push-model.sh` |
| No new release after a merge | a newer `main` commit exists (its run publishes), or the run was red | open the `ci` run |
