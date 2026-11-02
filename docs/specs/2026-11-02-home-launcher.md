# Spec: Tin Home, a launcher in the Night glass skin

Date: 2026-11-02. Status: design accepted (Night glass v4.1, Yehor 2026-11-02, "Lands, build it"); this spec is the
build plan. Design source, committed so the build does not depend on the sandbox: `docs/design/night-glass/`
(`README.md`, `tokens.json`, `bundle.css`, `lucide-icons.json`, one note per card, four rendered cards).

## Goal

Tin Home replaces the stock launcher on Yehor's Pixel 8a (GrapheneOS, Android 16, 412 × 915 dp, one hand). It does
the stock launcher's job first, then shows Yehor's layer on top: the next event, what needs him, readiness and the
homelab, with voice inside the one search bar.

Value hypothesis: one glance at Home tells Yehor what needs him and when to leave, faster than the stock launcher plus
four apps. Cheapest test, from the design README: use Night glass as the default Home for 7 days. **It has failed if
the stock launcher comes back, or a meeting or an approval is missed because Home hid it.**

## Facts this rests on

- Design constants (`docs/design/night-glass/README.md`): grid 4 dp, target 48 dp, curve 28 dp, stroke 1.6 dp; type
  Inter in 5 sizes only (76, 44, 15/20, 13/16, 11/16), tabular figures; light ladder calm (no colour), now
  (`signal`), needs you (`glow-1`), broken (`bad`, after 2 failed checks), stale (55% opacity after 3 missed refreshes).
- Colours: the `nightglass` column of `tokens.json`. Frost is `blur(k-frost) saturate(160%)` over the room light;
  glass is `surface` at the tint knob's alpha (55% default, 35% floor) with a 1 dp `edge` and a lit top edge `edge-hi`
  (`bundle.css`, Night glass block).
- Android: `RenderEffect` blur exists from API 31; themed monochrome icons (`AdaptiveIconDrawable.getMonochrome`) and
  per-app locales from API 33. The phone runs API 36, so `minSdk` 33 costs nothing and removes two fallbacks.
- The existing `:app` module is the Shizuku host and MCP bridge (`io.github.egoushka.tin`). Home is a separate
  application, `:home` (`io.github.egoushka.tin.home`), so a launcher crash never takes the bridge down and the bridge
  never needs launcher permissions.

## Data sources (checked against `~/Projects/context/map.md` and the existing code)

| Widget | Source | Status |
|---|---|---|
| Apps, drawer, dock | `LauncherApps` (profiles included); dock = default handlers for dial, messaging, still camera, browser, maps | slice 1 |
| Clock, date | system clock, `TIME_TICK` | slice 1 |
| Notification glance, music, earbuds battery | `NotificationListenerService` in `:home` (also unlocks `MediaSessionManager.getActiveSessions`); battery from the media route / `BluetoothDevice` battery level | slice 2 |
| Today, travel time | `CalendarContract` (`READ_CALENDAR`), on the phone; travel time UNKNOWN (no source in the map) and left out until one exists | slice 3 |
| Alarm | `AlarmManager.getNextAlarmClock()` | slice 3 |
| Needs you | approvals server `GET /api/items?view=open` → `counts` (`personal/approvals/server/README.md`), with a device token Home registers like the approvals client does. Not a copy of the approvals app: Home shows the count and opens the approvals app. | slice 4 |
| Homelab | Gatus endpoint statuses on the tailnet (the homelab gateway already reads them); broken after 2 failed polls | slice 5 |
| Readiness | oura-platform warehouse (`personal/oura-platform`); its API has only OAuth routes today (`OuraPlatform.Api/Program.cs`), so the read endpoint is UNKNOWN and is specified in that repo first | slice 5 |
| Weather | UNKNOWN; no source in the map. Left out rather than invented. | later |
| Voice | the speech work on `feat/speech` (another session). Until it lands the mic starts the system voice assistant. Voice never approves or merges; it opens the item. | slice 6 |

Every widget shows its source age ("Needs you · 1 min"), fades to 55% after 3 missed refreshes, and has an empty state
(`docs/design/night-glass/cards/LayerStates.md`).

## Scope of slice 1 (this build PR): the stock job in Night glass

1. **Launcher.** `HomeActivity` answers `MAIN` + `HOME` + `DEFAULT`, `singleTask`, survives rotation and process death,
   pressing Home on Home closes the drawer.
2. **Room and glass.** Edge-to-edge; Home paints its own dark room (`bg` plus two room lights, `glow-3` top left and
   `glow-2` bottom right, scaled by the room-light knob). Each card, the dock tiles and the search bar draw the room
   behind them through a `RenderEffect` blur of `frostDp`, then `surface` at `glassTint`. `frostDp = 0` skips the
   `RenderEffect` entirely (the Battery preset).
3. **Glance.** Clock (76, weight 200, tabular) and date, localised.
4. **Dock and search bar.** Five 56 dp round glass tiles with Lucide icons in `ink`; the 56 dp search bar at the bottom
   with a 48 dp white mic.
5. **All apps and search.** Swipe up, or tap the bar: all launchable apps A–Z as themed monochrome icons on glass
   tiles, the keyboard up, instant prefix and substring match on label, Enter opens the top result, a "Search the web"
   row when nothing matches.
6. **Gestures** (empty Home space only; nothing starts in the 24 dp side edges): swipe up = all apps; swipe down =
   notifications; double tap = torch; long press = Tune. Each has a visible route too (bar, Tune button in the drawer).
7. **Tune.** Knobs attention, roomLight, frostDp, glassTint, gapDp, strokeDp, focus in Preferences DataStore
   `tin.tune`; presets Default, Bedside (roomLight .4, tint .7), Battery (frost 0, roomLight .6), Deep work (focus,
   gap 8). A change by hand clears the preset; Default resets.
8. **Theme.** Kotlin constants map `tokens.json` one to one; the type scale has exactly the five sizes; motion
   durations come from Material 3 tokens (medium2 300, long2 500, short4 200, extraLong4 1000, short2 100) and are
   instant when the system animator scale is 0. Home never vibrates on a light change.
9. **Strings** in English and Ukrainian; layouts are checked with Ukrainian.
10. **Font.** Roboto (system) until Yehor decides on Inter; the APK cost is measured in this slice (H10).

Out of slice 1: every widget of Yehor's layer (slices 2–6), two-finger gestures, pinch, dock swipe shortcuts, Edit
Home, widgets hosting, icon packs.

## Acceptance checks

Run from `android/` with the phone on USB. `H` = Home.

| # | Check | Command / method | Pass |
|---|---|---|---|
| H1 | Builds and installs | `./gradlew :home:assembleDebug :home:testDebugUnitTest :home:lintDebug` then `adb install -r home/build/outputs/apk/debug/home-debug.apk` | all succeed |
| H2 | Is a launcher | `adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME` | lists `io.github.egoushka.tin.home/.HomeActivity` |
| H3 | Is the Home | `adb shell cmd package set-home-activity io.github.egoushka.tin.home/.HomeActivity`, `adb shell input keyevent KEYCODE_HOME`, `adb shell dumpsys activity activities \| grep -m1 ResumedActivity` | shows `.HomeActivity` |
| H4 | Tokens match the design | unit test `TokensTest` reads `docs/design/night-glass/tokens.json` and compares every `nightglass` colour, radius, size and the five type sizes with the Kotlin theme | passes; any drift fails |
| H5 | Every app is in the drawer | unit test of the drawer model plus a device count: drawer count == `adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \| grep -c /` minus Home itself | equal |
| H6 | Search | unit test `AppSearchTest`: prefix beats substring, case and diacritics ignored, Ukrainian labels match, empty result offers the web | passes |
| H7 | Tune persists | set Battery, `adb shell am force-stop io.github.egoushka.tin.home`, press Home | Battery still selected; unit test `TuneTest` covers presets and "a hand change clears the preset" |
| H8 | Frost 0 is free | unit test: `frostDp = 0` yields no `RenderEffect`; on the phone Battery preset shows flat glass | passes |
| H9 | Ukrainian | `adb shell cmd locale set-app-locales io.github.egoushka.tin.home --locales uk`, open Home and the drawer; lint `MissingTranslation` is an error | every string Ukrainian, no clipped text |
| H10 | APK cost of Inter | `ls -l` of the debug APK with and without Inter (weights used) | both numbers reported before Yehor decides |
| H11 | Back edge is Android's | swipe up starting 10 dp from the left edge: `adb shell input swipe 26 1800 26 600` (≈10 dp at 2.625 px/dp) | drawer does not open |
| H12 | Start time baseline | `adb shell am start -W -n io.github.egoushka.tin.home/.HomeActivity` after force-stop | `TotalTime` recorded; no threshold yet, it is the baseline for slices 2–6 |

The 7-day stop condition above is the check of the whole project, not of a slice.

## Later slices and their checks (written in full in each slice's PR)

- Slice 2, glance and music: a posted test notification (`adb shell cmd notification post`) appears in the glance
  within one listener callback; play/pause works on the active session.
- Slice 3, Today and alarm: an event inserted with `adb shell content insert --uri content://com.android.calendar/events`
  shows; the current event is teal; a clash marks its icon with `glow-1`.
- Slice 4, Needs you: an item `PUT` to the approvals server lights the card within one poll; deciding it on the laptop
  clears it (500 ms out).
- Slice 5, homelab and readiness: one failed Gatus poll keeps calm, two make it red; three missed refreshes fade it.
- Slice 6, voice: states from `cards/VoiceStates.md`; "approve" by voice opens the item and changes nothing.
