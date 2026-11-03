# Home controls, music and spacing

Against main (`70ca30d`, after #32, #34 and #35). Scope: `android/home`. Three jobs, each with a check:

1. **Every control on Home makes sense.** Each tappable thing has one row below saying what it shows and what a tap, a long press, a double tap or a drag does. A control that does nothing useful, or surprises, is fixed.
2. **Music opens the app that is playing.** The music card shows title and artist, has a clear empty state, and a tap on it opens the app that owns the active media session.
3. **Spacing matches the design.** Paddings, gaps and card heights are measured on the phone and compared with `docs/design/night-glass`.

This file was committed before any code. The Result column started as `pending` or `code` and was filled in by later commits on this branch; the device pass has run once (see Device checks), and no row is `pending` now.

## How a claim is labelled

| Label | Meaning |
|---|---|
| **device** | Seen on the Pixel 8a (1080×2400 at 420 dpi, 1 dp = 2.625 px) over adb: `uiautomator dump` bounds, `input tap`, `dumpsys`. The dump is the unmerged tree: it shows no custom actions and no TalkBack merge. |
| **test** | A unit or Robolectric test, named. Robolectric Compose tests exist on this branch (`HomeFrameTest`, `GrantRowTest`); the a11y spec's "Why no Compose test" is out of date. |
| **code** | Read from the source, not run. |
| **pending** | Not checked yet. None is left after the device pass; a check that was not run says "not run". |

TalkBack is not run. What TalkBack reads is claimed only as **test** (the semantics tree of a Robolectric test) or **code**.

## What is audited

Everything a finger can do on the Home layer and the three layers it opens: the glance, the pinned apps, the dock, the search bar and the mic, every card kind and its grant row, widgets, the edit mode and stack controls from #29, the pickers, All apps, the search results and the Tune sheet. Home has no row of quick toggles; the flashlight is the only toggle on it, and it becomes a visible control here.

Not covered: Android widgets' own controls (they belong to their apps), the voice answer card, and the app-manage menu in #30 (still open).

## Controls

Expected result is what the control must do after this PR. **Changed** marks a row whose behaviour or look changes.

| ID | Control | Shows | Expected result | Check | Result |
|---|---|---|---|---|---|
| H1 | Glance: clock, date, weather line | Time at 76 sp, date, weather. Not tappable. | Tap: nothing. Drags: see H4 and H5. One TalkBack node with three actions: open all apps, open notifications, Tune. **Changed:** the torch action is gone (H2, H3). | code (`ui/Home.kt`, the glance's `customActions`); device: the dump shows no clickable glance | code; **device:** the glance node is not clickable (PR head) |
| H2 | Torch switch | **New.** A 36 dp disc in a 48 dp target, a sibling at the right edge of the glance row, with the lucide `torch` glyph. Off: glass disc, `ink` glyph. On: `accent` disc, `on-accent` glyph (state is fill, not hue). Hidden when the phone has no flash unit. | Tap: flashlight on, tap again: off. The state follows the system, so switching the torch from Quick Settings changes the disc. TalkBack: "Flashlight, switch, on" or "off". Not part of the glance node. | test: `HomeControlsTest` (48 dp target, role Switch, label, On/Off from the switch role, a tap asks for the opposite state, no flash unit draws nothing, `toggleTorch` and the system callback); device: tap, then `dumpsys media.camera` torch state and the flash itself | test; **device:** a 48×48 dp node, checkable and clickable, its child labelled "Flashlight". A tap lit the torch and the next tap put it out: `dumpsys media.camera` logged "turned on" then "turned off" for Home's own PID, and the node's `checked` followed (`true`, then `false`). The flash was not looked at, and TalkBack was not run |
| H3 | Double tap on empty Home space | Nothing visible. | **Changed:** does nothing. It lit the flashlight by accident and had no visible route. The gesture is removed from `HomeFrame`, `Home` and the test. | test: `HomeFrameTest` ("a double tap on empty space does nothing"); device: double tap on empty space | test; device not run |
| H4 | Swipe up on empty space (or on the stack when it is scrolled to its end) | Nothing visible. | Opens All apps. Visible route: the search bar (H9) opens the same panel with the keyboard. A swipe from the 24 dp side edges starts nothing. | test: `HomeFrameTest`; code | code (test exists, unchanged) |
| H5 | Swipe down | Nothing visible. | Opens the notification shade. Visible route: the notification card (H11), only while it is on screen. | test: `HomeFrameTest`; code | code (test exists, unchanged) |
| H6 | Long press on empty space or on the stack | Nothing visible. | Enters Edit Home (H22). Visible route: the Tune sheet's Edit Home chip (H29). The press is read before the cards, so a press on a card does not click it. | test: `HomeFrameTest`; code | code (test exists, unchanged) |
| H7 | Pinned apps row (#29) | Round 56 dp glass tiles, one per pinned app, scrolls sideways. Hidden with no pins. | Tap: launches the app. Long press: unpins it (TalkBack: long-click label "Unpin"). | code; device: the dump lists the tiles with their labels | code; device: no pinned row on the phone (no pins set), so not run |
| H8 | Dock: Phone, Messages, Camera, Browser, Maps | Five 56 dp glass circles, a 24 dp glyph. | Tap: opens the dialer, the SMS app, the camera, the default browser, the maps app (OpenStreetMap in the browser when none). When nothing handles the intent, the toast "Can't open …". Long press: nothing. TalkBack: the name. | code; device: tap Phone and Camera, `dumpsys activity activities` for the top activity | code; **device:** five 56×56 dp clickable tiles labelled Phone, Messages, Camera, Browser, Maps; taps not run |
| H9 | Search bar | A 56 dp glass row: search glyph, the hint or the voice state word, bars or dots while listening. | Tap: opens All apps with the keyboard (the search layer). Long press: starts listening (TalkBack long-click label = "Speak"). | code; device: tap, dump the opened panel | code; **device:** one clickable 56 dp row with a 48 dp "Speak" target inside; tap not run |
| H10 | Mic | 48 dp accent disc inside the bar. | Tap: starts listening; while listening, thinking or on error the label is the state word and a tap stops or retries. When the voice engine is missing the tap opens the search layer. | code (A5 in the a11y spec) | code |
| H11 | Notification card (glance card) | Unread counts per app and the latest message with its age. | Tap: opens the notification shade. Without the listener grant the card is the grant row (H12). | code | code |
| H12 | Grant rows: Notification access, Calendar access | A 48 dp glass row, a glyph and the title; in Edit Home an `x` hides the card. | Tap: opens the listener settings, or asks for the calendar permission. | test: `GrantRowTest` (exists); code | test + code; **device:** the "Allow notification access" row is a 48 dp clickable row with a 48 dp "Hide" button (seen while the listener was unbound); the tap was not run |
| H13 | Today card | Today's events, the current one marked, clashes marked, "+N more". | Tap: nothing. Read-only. Open question 3. | code | code |
| H14 | Needs you card | The count and the oldest items, or "nothing waiting". | Tap: opens the approvals app, or the toast "Can't open Approvals" when it is not installed. | code | code |
| H15 | Homelab card | Calm line or the failed services. | Tap: nothing. Read-only. Open question 3. | code | code |
| H16 | Alarm card | The next alarm and "in 18 min". Hidden with no alarm set. | Tap: nothing. Read-only. Open question 3. | code | code |
| H17 | Music transport: previous, play/pause, next | Three 48 dp glyph buttons on the music card. Play/pause shows the action it will take. | Tap: sends the command to the active session. TalkBack: "Previous track", "Pause" or "Play", "Next track". | code | code; **device:** three clickable 48×48 dp nodes labelled Previous track, Pause, Next track; not pressed, so the player was left alone |
| H18 | Music card body | **Changed.** Art (48 dp), title, artist, the earbuds' battery when known. | Tap: opens the app that owns the session (see "Music" below). TalkBack: title, artist, "Open Spotify" as the click label. With no way to open it, the body is not a button. | test: `MusicOpenTest` (the choice and the fall-through); code: the click label ("Open <app>") and the non-button case in `MusicCard`; device: needs something playing | test + code; **device:** with Spotify playing, the card shows title and artist and the body is a clickable 216×48 dp node (on main it is not clickable); one tap on it left `com.spotify.music/.MainActivity` on top. Whether the session's own screen or the launcher opened is not told apart; the click label and TalkBack were not read |
| H19 | Music empty state | **New.** The card stays as a plain 48 dp row, a play glyph and "Nothing playing"; no transport buttons; in Edit Home the `x` hides it. While the notification listener is not granted the slot is the grant row, unless the glance card is on screen: it asks for the same grant, so the music slot draws nothing then. | Tap: nothing. | test: `GrantRowTest` (a row with no grant is not a button, the `x` still hides); code: the slot's `when` in `HomeStack`; device: with nothing playing | test + code; **device:** with the listener bound and nothing playing, a 48 dp "Nothing playing" row with a 48 dp Hide button (`after-home`). With the listener unbound the glance showed the grant row and the music slot drew nothing, as written |
| H20 | Android widget | The widget, in a glass cell. | Its own taps. A long press on a widget is the widget's. In Edit Home a blocker covers it. | code; the widgets belong to their apps | code |
| H21 | "Widget unavailable" row | A grant row with a warning glyph, shown when a placed widget's app is gone. | **Changed:** it is a button that does nothing, so a tap does nothing and TalkBack says "button". It becomes plain text; the `x` in Edit Home still removes it. | test: `GrantRowTest` (a row without an action has no click action, the `x` still hides); code: `WidgetCell` passes no action | test + code |
| H22 | Edit Home: hint | One line: move or hide the cards. | No tap. Back leaves Edit Home. | code | code |
| H23 | Edit Home: slot header | Card or widget name, `↑` `↓`, `x`; widgets also `−` `+`. | `↑` `↓` move one slot (disabled at the ends); `−` `+` change a widget's height (disabled at the ends of its range); `x` hides a card or removes a widget. Each is 48 dp and named "Move … up" and so on. A tap on the card under the header does nothing. | test: `HomeLayoutTest`, `HomeFrameTest`; device: Edit Home through the Tune chip | code + test; device not run |
| H24 | Edit Home: hidden tray | The hidden cards, each with "+". | Tap: shows the card again at the end. | code | code |
| H25 | Edit Home: bottom bar | Four pills: Add widget, Pin app, Tune, Done (Done is the accent one). | Add widget, Pin app: open the pickers. Tune: opens Tune. Done: leaves Edit Home. | code | code |
| H26 | Pickers: widgets and apps | A sheet with rows and an `x`. | Tap a row: places the widget, or pins the app (up to the limit; the notice "Pins are full"). `x` or a tap outside: closes. Back closes. | code. The sheet's scrim has no label and the panel merges its rows; same defect as A7. Open question 5. | code |
| H27 | All apps: header, tiles, field | "ALL APPS", Tune and Close buttons, a grid of apps, the search field. | Tune: opens the sheet. Close or Back: Home. Tile tap: launches. Field: filters; the keyboard's Go opens the top hit. | code; a11y spec A6 | code |
| H28 | Search results | Grouped rows: apps, people, ask Tin, web. | Row tap: opens the hit. A person row has a call button. | code; a11y spec A6 | code |
| H29 | Tune sheet | Edit Home chip, presets, attention, three sliders, density and stroke chips, the Focus switch, Reset. | Each sets one value. Edit Home: closes the sheet and enters Edit Home. | a11y spec A7 | code |

## Torch

Today the flashlight sits behind a double tap on empty space. It lit the flashlight when it was not meant to, it has no visible route, and its state is private to `Actions`, so nothing on screen shows whether it is on. The design (Gestures card) lists the double tap as a default, so this is a change to the design: open question 1.

- The double tap is removed from `HomeFrame` (`onDoubleTap` parameter, its `rememberUpdatedState`, the `detectTapGestures` handler), from the `Home` call and from `HomeFrameTest`.
- `Actions` exposes the torch as a `StateFlow<Boolean>` fed by `CameraManager.TorchCallback`, so the switch shows the real state, including a change from Quick Settings.
- The switch is a `toggleable(role = Role.Switch)` with a label. TalkBack reads On or Off from the switch role; no extra `stateDescription` is set. State is fill and glyph colour, never hue alone (calm has no colour).
- It sits outside the glance's merged node, so it is its own TalkBack stop.
- The glance's `action_torch` custom action is removed (string and Ukrainian string too). The glance's actions exist for the gestures that have no other route (drags, long press). The torch now has a control that TalkBack focuses on its own, so a second route would be a duplicate with a label that cannot say on or off.

## Music

Today `MusicSource` keeps the `MediaController` private and `MusicState` carries only title, artist, art and the buds. The card is hidden when nothing plays and is not tappable.

`MusicState` gains `packageName`, `app` (the player's label, for "Open Spotify"), `sessionActivity` (the controller's `PendingIntent`) and `launchable` (the package has a launch intent). Two pure functions in `signals/MusicOpen.kt` choose and walk what a tap does:

- `openOrder(hasSession, hasLaunch)` returns the ways to try, in this order: the session's own `sessionActivity` (it opens the player on the right screen), then the launch intent of the session's package. With neither it returns nothing, and the card body is not a button.
- `openFirst(order, open)` tries them in order and stops at the first that opens. If sending the `sessionActivity` fails (`CanceledException`), the walk falls through to the launcher. When every way fails the tap shows the "Can't open <app>" toast.

The package must be visible to Home: the manifest already queries every launcher activity, which covers media apps. The sessions are read with the notification-listener grant; without it there is no music state (see H19).

`MusicOpenTest` (pure JVM) covers both present, only the session, only the package, neither, the first success ending the walk, a cancelled session, every way failing, and nothing to try.

Empty state: with the grant and no active session the slot shows "Nothing playing" (H19) instead of a gap.

## Spacing

Reference. `tokens.json` and the design README are the rule: gutter 16, gap between cards 10 (the density knob: 8, 10, 14), card padding 12 (it follows the gap), rows inside a card 28 − 12 = 16, hit target 48 with 8 between, search bar and app tile 56 with a 48 mic, glyphs 20 beside titles, 16 beside secondary text, 24 in tiles. `bundle.css` is the reference for the numbers the tokens do not give. The design folder holds no rendered page, so a card's height is compared with the height its CSS gives, not with a picture.

Rule for this PR: a difference is fixed when the design states the number on the 4 px grid and the device shows another. A difference where the design contradicts itself, or states a number off the grid, goes to the open questions and stays as it is.

Measured by `uiautomator dump` bounds ÷ 2.625 at font scale 1.0, English, the unchanged build (the "before", `origin/main` at `70ca30d`) and then the build with these fixes (the "after", versionCode 35). All Device cells are **device-verified on the unmerged tree** unless they say "inferred" (`uiautomator` pads any node under 48 dp to 48 dp, so a small node's bounds are not its drawn size; a drawn size is then read from the offsets of its neighbours). The phone's Tune gap is 8, not the default 10, so every size that follows the gap (card padding, glance to first card, dock to search bar) reads 2 dp under its default.

| ID | Where | Design | Code (read) | Device (before → after) | Decision |
|---|---|---|---|---|---|
| S1 | Screen gutter | 16 | `Dimen.gutter` 16 | 16.0 → 16.0 | none needed: matches |
| S2 | Gap between stack cards | 10 (tune default) | `gapDp` 10 | 8.0 → 8.0. His stored Tune gap is 8 (`tin.tune.preferences_pb`), so the default 10 was not measured | none: matches his setting; the default is code only |
| S3 | Card padding | 12 all round (tokens, README). `bundle.css` `.w` in Night glass is 12 vertical by 14 horizontal | `padDp` = gap + 2 = 12 at the default | 10.0 → 10.0 (gap 8 + 2) | tokens win: none. The CSS contradiction is open question 4 |
| S4 | Music art | 48 (`.art`) | `tile − space3` = 44 | 44 → 48, **inferred** from the title's left offset (82.3 → 86.1 dp, +3.8 for +4) | **Fixed** in `491c761`: `Dimen.target` (48) |
| S5 | Music card height | 12 + 48 + 12 = 72 | 12 + 48 (transport buttons) + 12 = 72 | 67.8 → 67.8 (10 + 48 + 10 at his padding; 72 at the default) | none: the 48 dp transport buttons set the height, not the art |
| S6 | Today time column | 52 (`.ag-row` grid) | `target + space2` = 56 | **not measured**: no events today, so the card was not drawn | **Fixed** in `491c761`: `Dimen.target + Dimen.space1` (52); code only |
| S7 | Today row | min 36, 8 sides | min 36, 8 sides | **not measured**: same | none expected; code only |
| S8 | Alarm card height | `.mini`: at least 52 | 12 + 20 + 12 = 44 | 40.0 → 40.0 (10 + 20 + 10 at his padding; 44 at the default) | not fixed here. `.mini` says padding 10 and min-height 52 without saying whether the 52 includes the padding; the card is read-only, so it is no hit-area question. Open question 8 |
| S9 | Notification card padding and rows | 10 / 14 / 12, row gap 6, apps gap 14 (`.nt`); overridden by `.w` | `padDp` 12, rows 8, apps gap 12 | padding 10.0 → 10.0 (gap 8 + 2). Row and apps gaps **not measured** (the card holds his notification text; one row was drawn) | rows 8 vs 6 and apps gap 12 vs 14 are off-grid in the design: open question 4 |
| S10 | Search bar | 56 high; padding 0 4 0 **18**; gap 10; mic 48 with a 22 glyph | 56; padding 16 / 4; gap 12; mic 48 with a 20 glyph | bar 56.0, mic 48.0, mic edge 4.2 from the bar's edge (both builds). Left padding, gap and glyph sizes not read from the dump | 18, 10 and 22 are off the 4 px grid, and 20 is the token for a glyph beside a title: none. Open question 4 |
| S11 | Dock | 56 tiles in 64 wide slots, inset 6, 4 above | 56 tiles spread edge to edge | 56.0 × 56.0 tiles, edge to edge (both builds) | the design's numbers are off-grid: open question 4 |
| S12 | Glance to first card | 10 if the glance is a child of the card column, 18 if not (the CSS does not say) | `gap + space2` = 18 | 16.0 → 16.0 (his gap 8 + 8; 18 at the default) | UNKNOWN reference: none |
| S13 | Bottom group (pins, dock, search) | dock 4 above the bar | `gap + space2` = 18 between the three | dock to search bar 16.0 → 16.0 (gap 8 + 8). The pins row was not on screen | not fixed: it goes with S11's off-grid dock layout (open question 4) |
| S14 | Glance top | `.gl` padding 6 / 4 / 4 | `space6` = 24 above the clock | clock box top at 70.1 dp from the screen's top edge (both builds). The status bar inset was not read, so the gap above the clock cannot be split into inset and 24 | not fixed: it cannot be judged without the status bar inset. Open question 9 |
| S15 | Torch switch (new) | 36 visible in a 48 target (Controls card: chips are 36 with a 48 hit area) | 48 target, 36 disc | target 48.0 × 48.0, at the glance row's right edge (x 347.4 to 395.4, y 92.2 to 140.2). The 36 dp disc is code only (the dump does not show it) | n/a |

Every cell without the word "device" or a number from the dump is **code**. Screenshots and the dumps behind these numbers are in the shared notes folder, not in git (see Device checks).

## Machine-run checks

`./gradlew :home:cleanTestDebugUnitTest :home:testDebugUnitTest :home:lintDebug` from `android/`.

- `StringsTest` loads every `strings*.xml` in both languages: the new strings live in `strings_controls.xml` and its Ukrainian pair, with key, placeholder and plural parity and no English words in Ukrainian.
- `ContrastTest` reads colours from `tokens.json`: the torch switch uses `accent`, `on-accent`, `ink` and the glass only.
- New: `MusicOpenTest` (pure JVM, 8 tests) and `HomeControlsTest` (Robolectric, 4 tests).
- Changed: `HomeFrameTest` (no `onDoubleTap`, 14 tests), `GrantRowTest` (a row without an action, 5 tests).

Result on this branch: 27 suites, 206 tests, 0 failures, 0 errors; lint reports no issues.

## Device checks

Pixel 8a, serial `45281JEKB07997`, behind a secure lock: it is not unlocked from here; a request goes to Yehor once. Tapping on it is limited to Home's own controls: the dock's Phone and Camera (they open an app and nothing else happens), the torch, the music card with something playing, the search bar, Edit Home and its controls. No tap on a notification, a call button or the Approvals card. Screenshots and dumps show his notification text and the repo is public, so they are kept out of git and out of the PR; the paths are named in the PR description. The font scale, the app locale and `stay_on_while_plugged_in` are restored after each window.

Results: **run once, with Yehor's go-ahead, about ten minutes.** Builds: `before` is `origin/main` (`70ca30d`), `after` is this branch's head (versionCode 35); both debug, installed with `adb install -r`. His own build 28 was put back at the end. All dumps are `uiautomator dump`, which shows the unmerged tree, so "device" in this spec means that tree, not what TalkBack reads.

- **Device-verified:** the torch switch (node size, `checked`, the camera service's "turned on" and "turned off" for Home's own PID; it ended **off**); the music card body (a clickable 216×48 dp node with Spotify playing, and a tap left `com.spotify.music/.MainActivity` on top); the "Nothing playing" row; the dock tiles (56 dp), the search bar (56 dp) and its mic (48 dp); S1 to S5, S8, S10 to S15 as the Spacing table says.
- **Not verified on the phone:** TalkBack (labels, the Switch state, the click label "Open Spotify", the glance's three actions); whether the music tap went through `sessionActivity` or the launch intent; the dock Phone and Camera taps and the search bar tap; Edit Home and every control in it (H22 to H26); the Today card (S6, S7, H13); the Homelab card; the notification card's row and app gaps (S9); the pinned row (no pins); the double tap doing nothing (H3, covered by `HomeFrameTest`); the status bar inset (S14). None of these were tapped, to keep his notifications, calls and Approvals out of it.
- **Quirk worth knowing:** after `adb install -r` the notification listener (`TinListener`) can stay "already bound" and never call `onListenerConnected`. Home then thinks it has no grant: the glance shows "Allow notification access" and the music slot draws nothing. `cmd notification disallow_listener` then `allow_listener` did **not** fix it; `am force-stop io.github.egoushka.tin.home` then `am start -n io.github.egoushka.tin.home/.HomeActivity` did. `TinListener.kt` and the manifest are unchanged in this PR, so this is the install path, not the change. I did toggle the listener grant for Tin's own component once with `disallow_listener` and `allow_listener`; the end state is granted, as before.
- **Phone restored:** build 28, listener bound, Home in front, font scale 1.0, no app locale, `stay_on_while_plugged_in` 0, torch off. His Tune values were read, not written.
- **Evidence, not in git** (notification text on screen): `/Users/yehorhrabovskyi/Library/Application Support/Claude/side-session-notes/local_44616042-e088-4485-b84a-2cf7c7a3b2b9/tin-controls-device/`. `before-home`, `before-music` (main), `after-home` (nothing playing), `after-torch-on`, `after-torch-off`, `after-music3` and `after-music4` (PR head, music card) each as `.xml` and `.png` where both exist.

## Open questions for Yehor

1. **The double-tap torch is gone**, and the design's Gestures card and the launcher spec (`2026-11-02-home-launcher.md`) still list it as a default. Keep it removed and update the two documents, or bring it back as an opt-in setting in Tune?
2. **Gestures in the design that do not exist:** two-finger swipes, pinch in, and swipe up on a dock icon. The Gestures card lists them as defaults; Home does not implement them. Drop them from the card, or build them?
3. **Today, Homelab and Alarm are read-only.** A tap does nothing. A stock launcher opens the Calendar on an event and the Clock on an alarm. Wanted?
4. **The design disagrees with itself on card padding and uses off-grid numbers.** `tokens.json` and the README say card padding is 12; `bundle.css` makes Night glass cards 12 by 14, the notification card 10 / 14 / 12 with a 6 gap, the search bar 18 on the left, the dock inset 6. This PR follows the tokens and leaves the CSS-only numbers (S3, S9 to S11). Which one is the truth?
5. **The picker sheets have the A7 defect:** the scrim is a clickable with no label and the panel merges its rows. Not in this PR's ask; fix in a separate PR?
6. **No "All apps" button.** Swipe up and the search bar are the only routes. A button would make the visible route obvious.
7. **The phone's state:** early in this work the font scale read 2.0 and the app locale `uk`, which I did not set; later reads show 1.0 and `en-US`, which I did not set either (another session restored them). A Digital clock widget from another session's testing was on Home. I changed none of it. His Tune gap is 8, not the default 10, so the spacing was measured at gap 8 (S2).
8. **Alarm card height (S8).** `.mini` says min-height 52 against 44 in the code (40 on the phone at gap 8). Is the 52 the whole card or the content inside the padding? If the whole card, add a 52 minimum.
9. **Glance top (S14).** The clock box starts 70.1 dp below the screen's top edge, with 24 dp of code padding in it. The design's `.gl` padding is 6 / 4 / 4. The status bar inset was not read, so I cannot say how much of the 70.1 is the system's. Measure the inset, then decide whether 24 stays.
