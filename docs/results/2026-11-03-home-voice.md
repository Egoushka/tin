# Home voice: device run, 2026-11-03

Phone: Pixel 8a (`akita`), stock Android 17 (build `CP3A.261005.005`), over USB. tin and Home debug builds from
[PR #25](https://github.com/Egoushka/tin/pull/25), Home built with `-Ptin.voice=real`, both signed by the debug key
(signer `6b1f0dd7`). Checks from [the spec](../specs/2026-11-02-home-voice.md).

| # | Result | Evidence |
|---|---|---|
| V1 | passed | `ListenerTest` (2 new tests) in #21, CI green |
| V2 | passed | `TinVoiceEngineTest`: 15 of 15 |
| V3 | passed | the V3 build and lint list is green locally; CI `android` and `core` green on #25 |
| V4 | passed | `permission.VOICE` is `prot=signature`; Home shows `granted=true`; one signer |
| V5 | passed, with a note | tin force-stopped, then tin disabled: the tap falls back, Home keeps its pid, `adb logcat -b crash -d` empty. This phone has no `VOICE_COMMAND` handler, so the fallback is Home's Search layer, not an assistant |
| bind | passed | `dumpsys activity services io.github.egoushka.tin` lists `VoiceService` with Home as the client; gone with Settings in front, back when Home returns |
| V6 | needs a voice | script line 2 and 3 |
| V7 | needs a voice and the tile | script line 4 |
| V8 | needs a voice | script line 5 |
| V9 | not run | the phone was in use by another session's Home check (last touch 37 s before I looked), and installing my build would have replaced its build; nobody has to speak, steps in "V9" below |
| S7 | blocked | Shizuku is not installed on the phone now (the 2026-11-01 run had it), so nothing serves `@tin`; see "S7" below |

## What is on the phone

- Model: `files/models/` in tin holds Parakeet-tdt-0.6b-v3 int8 (encoder 652 184 281 B, decoder 11 845 275 B, joiner
  6 355 277 B, tokens 93 939 B) and Silero VAD (643 854 B), pushed by `./scripts/speech-push-model.sh` from files whose
  SHA-256 match the pins in `ModelStore.kt`.
- Policy: `/data/local/tmp/tin/policy.toml` is only `[speech]` / `enabled = true` (mode 600, folder 700). It did not
  exist; it holds no AppFunction rules, so merge it with `examples/demo-policy.toml` before the A and D checks.
- Permissions: `RECORD_AUDIO` and `POST_NOTIFICATIONS` granted to tin with `adb shell pm grant`; tin's start code
  (`MainActivity.startSpeech`) does not start `SpeechService` until both are held.

## Your script (V6 to V8)

Setup, once (installs Home with the real engine, opens tin so `SpeechService` loads the model):

```bash
cd android && ./gradlew :home:installDebug -Ptin.voice=real && adb shell am start -n io.github.egoushka.tin/.MainActivity
```

1. tin shows "Shizuku is not running" on its own screen; ignore it, Home's mic does not use Shizuku. Wait until tin's
   notification reads `ready (model loaded in … ms)`, press Home, then run lines 2 to 5 with Home in front.
2. V6 English: tap the mic and say "open calendar", ten times. Each time the bar reads "Listening…", then "Thinking…",
   then Home goes back to "Search or ask" and Vanadium opens a search for your words (Home hands them to
   `ACTION_WEB_SEARCH`; the bar itself never shows the text). Pass: 10 of 10 in that order with the words as you said them.
3. V6 Ukrainian: the same ten times with "відкрий календар" ("Слухаю…", "Думаю…"). Time each try from your last word
   to Vanadium opening; the 20 tries stop the plan if p95 is above 1.5 s. Write p50, p95 and the misheard words
   (WER) into this file.
4. V7: add the "Talk to tin" tile to Quick Settings, tap it and keep talking, close the shade and tap Home's mic. Pass:
   the bar shows "Didn't catch that" ("Не розчув") for 3 s, and after a second tap on the tile tin's notification reads
   `heard #N: ` followed by what you said.
5. V8: run `adb shell content insert --uri content://io.github.egoushka.tin.home.needsyou.fake/items --bind id:s:a1
   --bind title:s:Deploy --bind source:s:pr`, tap the mic and say "approve the deploy" (or "підтверди деплой"). Pass:
   `adb shell dumpsys activity activities | grep -m1 ResumedActivity` names `com.yehor.approvals/.ui.ItemLinkActivity`,
   and `adb shell content query --uri content://io.github.egoushka.tin.home.needsyou.fake/items` still lists `a1`.
   Clean up with `adb shell content delete --uri content://io.github.egoushka.tin.home.needsyou.fake/items`.

## V9 (nobody speaks, before line 2)

Read Home's memory, tap its mic once and say nothing, wait until the bar is back to "Search or ask" (a second tap on the
mic cancels a listen that runs on), read it again:

```bash
adb shell dumpsys meminfo io.github.egoushka.tin.home | grep 'TOTAL PSS'
```

Pass: the second `TOTAL PSS` (KB) is at most 5 120 KB above the first.

## Counter

Afterwards the counter should show `uses` and nothing that was said:

```bash
adb exec-out run-as io.github.egoushka.tin.home cat files/datastore/tin.voice.preferences_pb | strings
```

## S7

`uv run scripts/speech-check.py` needs tin serving on `@tin`, and `@tin-speech` is served by the Shizuku user service.
Shizuku is not installed, so S7 did not run and the phone has no speed number yet. To run it: install Shizuku v13.6.0 from
its releases page, start it with the `libshizuku.so` starter over adb, push `examples/demo-policy.toml` merged with
`[speech]` to `/data/local/tmp/tin/policy.toml`, open tin and tap "Allow" in Shizuku's dialog (README, "Quick
start"), then `adb forward tcp:7381 localabstract:tin` and `uv run scripts/speech-check.py`. The 20 clips S7 uses
are already cached in `bench/.cache/data/` on the Mac. Pass is RTF p95 below 0.5; above 1.0 stops the plan.
