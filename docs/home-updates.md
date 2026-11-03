# Home updates: the phone follows `main`

Every commit on `main` that passes CI becomes a GitHub release of Home (`v0.1.<commit count>`, one APK, signed with
the Tin release key). Obtainium on the Pixel polls the releases and updates Home in place. No cable, no `adb install`.

```
merge to main -> CI android job -> signed home-release.apk -> release job -> GitHub Release v0.1.N -> Obtainium -> phone
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
   "Include prereleases" off; if the repo later ships more APKs, set the APK filter to `home-.*\.apk`.
4. Install the Home it offers, then set it as the default home app again (Settings > Apps > Default apps > Home app).
5. In Obtainium's settings set the background update interval (for example 1 hour) and allow it to install updates
   without asking. Android only skips the confirmation for an update when Obtainium is the installer that installed
   the app, which is why step 2 matters; if a confirmation still shows, accept it once and check this setting.

Obtainium compares the release tag (`v0.1.N`) with Home's `versionName` (`0.1.N`), so both sides stay in the same format.

## Am I on the latest?

- Home shows `Build 0.1.N · <short sha>` through `BuildStamp()` (`ui/BuildStamp.kt`), the last line of the Tune sheet.
- From the Mac: the phone's version against the newest release.
  ```bash
  adb shell dumpsys package io.github.egoushka.tin.home | grep versionName
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
| No new release after a merge | a newer `main` commit exists (its run publishes), or the run was red | open the `ci` run |
