# Spec: Home voice on tin's on-device speech (Home slice 7)

Date: 2026-11-02. Status: proposal. Builds on Home slice 6 (#19, voice states with a scripted debug engine) and the
speech module (#6, Parakeet v3 int8 through sherpa-onnx in tin's `SpeechService`).

## Goal

The mic in Home's search bar recognises Ukrainian and English on the phone, offline, with no Google services. Today
the release build has no engine (`home/src/release/.../voice/Engines.kt` returns null), so the mic hands over to the
system assistant, which on GrapheneOS is nothing useful.

Value hypothesis: one tap from Home to "open the calendar" or "відкрий апрув" beats unlocking, finding an app and
typing, and no other GrapheneOS launcher does it offline in Ukrainian. Cheapest test: V6 below (latency on the phone),
then the 7-day Home trial with a voice-use counter (count only, never the text).

## Facts this rests on

- `Voice` (`home/src/main/.../voice/Voice.kt`) already runs one listen at a time, draws Listening, Thinking and Error,
  cancels on a second tap and maps deciding words to "open the oldest item" (`VoiceIntent.of`). It needs only a
  `VoiceEngine`.
- The model lives in tin (`io.github.egoushka.tin`), not in Home: `SpeechService` loads Parakeet (650 MB encoder) and
  Silero VAD from `files/models/` (`ModelStore`), owns `AudioRecord` as a `microphone` foreground service and guards
  the mic with `SpeechServer.micBusy` against push-to-talk.
- `ISpeech.handle(line)` is the shell-uid path (`@tin-speech`); `SpeechService` is `exported="false"`.
- `Listener.command` returns only when the first speech segment ends; it has no hook for "speech ended, decoding now"
  and no way to stop early.
- Device speed is UNKNOWN: the speech spec's S7 (RTF) and S8 (mic latency) were never run on the phone
  (`docs/results/` has no speech numbers).

## Design

**One model, one mic owner: tin.** Home does not link `:speech`. Loading the model in the launcher would hold a second
~650 MB copy on disk and its working set in the process that must never be slow or killed, and two `AudioRecord`
owners would fight push-to-talk. Home holds a Binder proxy and nothing else.

- **`:voice-ipc`, a new library module** with only AIDL, so both apps compile against the same descriptors and another
  app can take it:
  ```aidl
  interface IVoice {
      // Records until the first speech segment ends or maxSeconds pass. Answers on cb; returns at once.
      void listen(double maxSeconds, IVoiceCallback cb) = 1;
      void cancel() = 2;
      // False while the model is missing or loading, or SpeechService is not running.
      boolean ready() = 3;
  }
  oneway interface IVoiceCallback {
      void onState(int state) = 1;      // 1 listening, 2 thinking
      void onHeard(String text) = 2;    // empty: nothing understood
      void onError(String reason) = 3;  // "mic busy", "speech off", ...
  }
  ```
- **tin (`:app`)**: a new `VoiceService`, exported, guarded by a permission `io.github.egoushka.tin.permission.VOICE`
  with `protectionLevel="signature"`. Both APKs must share one key: debug builds already do (the machine's
  `~/.android/debug.keystore`); no module has a release `signingConfig` yet, so the release key is a prerequisite
  this slice adds as one shared config read from `gradle.properties`, never committed. It delegates to the running
  `SpeechService.instance`; when that is null it answers `ready() = false` and never starts the mic service itself,
  because Android does not let a microphone foreground service start from the background. `listen` takes `micBusy`
  like `SpeechServer.transcribe`; a busy mic is `onError("mic busy")` and push-to-talk keeps recording.
- **`:speech`**: `Listener.command` gains two parameters with defaults, so the shell path is unchanged:
  `onSegment: () -> Unit = {}`, called once when the VAD closes the segment and before decoding (drives Thinking), and
  `cancelled: () -> Boolean = { false }`, checked per 512-sample chunk (32 ms), which ends the command with empty text.
- **Home (`:home`)**: `TinVoiceEngine` binds `VoiceService` when Home starts and unbinds when it stops. `listen` is a
  `suspendCancellableCoroutine`; coroutine cancellation (the second tap) calls `cancel()`. `VoiceEngine` gains
  `fun ready(): Boolean = true`; `Voice.start` returns false when the engine is not ready, so the existing fallback to
  the system assistant covers "tin not installed", "model missing" and "speech service not running".
  - Release: `Engines.default()` returns `TinVoiceEngine`.
  - Debug: the scripted engine stays the default so slice 6's checks stay deterministic; `-Ptin.voice=real` switches
    debug to `TinVoiceEngine` for the device checks below.
- **Privacy.** Audio never leaves tin. Home receives text only, keeps it in memory for the search bar and never logs
  it. The voice-use counter in DataStore (`tin.voice.uses`) is a number.

## Out of scope

Wake word, streaming partial text, the answer sheet (needs an engine that answers, not only transcribes), starting
`SpeechService` from Home, and Home loading a model itself.

## Acceptance checks

Run from `android/`. `V` = voice. Device checks need the phone on USB with tin opened once (model loaded).

| # | Check | Command / method | Pass |
|---|---|---|---|
| V1 | Listener hooks | `./gradlew :speech:testDebugUnitTest` | `ListenerTest`: `onSegment` runs once, before `decode`; `cancelled` ends a command within one chunk with empty text and no decode; existing tests unchanged |
| V2 | Home engine | `./gradlew :home:testDebugUnitTest` | `TinVoiceEngineTest` with a fake `IVoice`: states 1, 2 then text gives Listening → Thinking → the utterance; `onHeard("")` gives Error; `ready() = false` makes `Voice.start` return false; cancelling the coroutine calls `cancel()` |
| V3 | Builds | `./gradlew :voice-ipc:assembleDebug :app:assembleDebug :home:assembleDebug :home:assembleRelease :app:lintDebug :home:lintDebug` | all succeed; CI green on the PR |
| V4 | Only tin's signer may bind | `adb shell dumpsys package io.github.egoushka.tin \| grep -A2 permission.VOICE` and `adb shell dumpsys package io.github.egoushka.tin.home \| grep 'permission.VOICE: granted'` | protection level `signature`; Home `granted=true` |
| V5 | Fallback | `adb shell am force-stop io.github.egoushka.tin`, tap the mic | the system assistant opens as before slice 7; Home does not crash (`adb logcat -b crash` empty) |
| V6 | Real voice and its speed | debug with `-Ptin.voice=real`; 10 tries each of "open calendar" and "відкрий календар" | each shows Listening, then Thinking, then the text in the search bar; end of speech → text p50/p95 written to `docs/results/2026-11-xx-home-voice.md` with WER of the 20 tries |
| V7 | Mic conflict | start push-to-talk from the tile, then tap Home's mic | Home shows Error "mic busy"; push-to-talk's transcript is intact |
| V8 | Voice still never decides | with the debug fake Needs-you provider, say "approve the deploy" | opens the oldest item's `deep_link`; the item is still waiting |
| V9 | Home stays light | `adb shell dumpsys meminfo io.github.egoushka.tin.home \| grep 'TOTAL PSS'` before slice 7 and after one listen | grows by ≤ 5 MB (starting value: Home holds only a Binder proxy, so anything near the model's size means it leaked in) |

**Stop condition.** If V6's p95 is above 1.5 s (starting value, to calibrate: no device baseline exists because S8 never
ran), release keeps the system-assistant fallback and the slow part is measured first (VAD tail vs decode). If the
7-day trial counts fewer than one voice use a day, voice in Home is dropped and the mic goes back to the assistant.
