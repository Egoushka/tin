# Home accessibility and locale audit

Against main. It audits the Night glass Home as it was on main after #27 (polish), #26 (weather) and #28 (search results); #25 (voice engine) and #29 (the HomeFrame restructure) landed while the audit ran, and this branch merges them (see "Merge with #29"). Scope: `android/home`. Four audits: TalkBack, font scale, contrast, Ukrainian. Each gap has an ID, a check, and a status.

## What is audited

The glance and its weather line, and the music, today, needs-you, homelab and alarm cards (`ui/Cards.kt`, `layer/LayerCard.kt`), the dock, the search bar and the mic (`ui/Home.kt`), the search results and the drawer rows (`ui/SearchPanel.kt`, `ui/Drawer.kt`), the Tune sheet (`ui/TuneSheet.kt`), the press modifiers (`theme/Press.kt`), and every string. The search results and the drawer were audited by reading their semantics and strings, not on the device; the Tune sheet by a device dump and by reading.

Not audited. The scrolling card stack, edit mode, pinned apps and widgets (#29) and the voice-engine UI (#25) are on main now. Their semantics were not read; their strings and colours are covered, because `StringsTest` and `ContrastTest` run over the merged tree. Pass 2 of the device checks dumped the stack (F4) and edit mode at Ukrainian 2.0, for layout only. Apps manage (#30, `home-apps-manage-7c11e5`) and the dock menu are still in open PRs and get this audit when they land.

## Findings

| ID | Gap | Check | Fix | Status |
|---|---|---|---|---|
| A1 | A card's label, its age and its rows are separate focus stops on cards that are not clickable (Homelab, Alarm, Music, Today when granted); the age is not spoken with the label | the semantics code (merged card, one `text` on the header); the spoken strings by `StringsTest`; not listened to | the card is one merged node; its header reads "Needs you, 1 minute ago" | fixed |
| A2 | The age is spoken as its visible abbreviation ("1 d" may be read "1 D"); it has no plural forms | `StringsTest` (spoken-age plurals exist, every CLDR form) | spoken age plurals | fixed |
| A3 | The search bar has a click label but no long-press label: TalkBack does not say that a long press speaks | the semantics code (`onLongClickLabel`); not listened to | `onLongClickLabel` = the mic's label | fixed |
| A4 | Vertical drags (apps, notifications), double-tap torch and long-press Tune have no accessibility action | the semantics code (`customActions`); not listened to | four custom actions on the glance, the first stop | fixed |
| A5 | The mic's label is already the state word; keep it | reading `SearchBar` in `Home.kt`: the mic's `contentDescription` is the state word (`mic`, `voice_listening`, `voice_thinking`, `voice_error`) and the hint is a polite live region | none | pinned |
| A6 | A search result row (`HitRow`) and an app tile in the drawer (`AppTile`) are clickable with no role, so TalkBack does not say "button" | reading the semantics; the clickables on Home and in the drawer and search already set a role (`plainClickable`, `pressGlass` and `IconButton` default to button), A7 aside | `role = Role.Button` | fixed in code; not dumped on the device |
| A7 | The Tune sheet, from a device dump: the scrim is a clickable with no label and no role; the panel is a `clickable {}` that merges its descendants; the three sliders have no name (label and value are sibling texts); the Focus switch is not tied to its label; Reset is exposed as a radio button; a selected chip is not marked selected | device dump of the sheet (unmerged tree); the merge and the announcements are by the semantics code, not listened to | scrim is a sibling of the panel with the Close label and button role; the panel swallows taps with `pointerInput`; each slider carries its name as `contentDescription` and its value as `stateDescription`, and the sighted label row is hidden from semantics; the Focus row is `toggleable(Role.Switch)` over a `Switch(onCheckedChange = null)`; chips that choose are `selectable`, Reset is a button | fixed; the new sheet was dumped on the device (pass 2): scrim, sliders, chips, Reset and Focus read as intended in the unmerged tree; the announcements were not listened to |
| F1 | Large text: the clock, the alarm label and the fixed rows may clip, wrap or overlap at scale 1.3 and 2.0 | device check at 412×915 dp, scale 1.3 and 2.0, English and Ukrainian: no node leaves the screen, none overlaps, every clickable is at least 48 dp | see F2 to F4 | see below |
| F2 | English, 2.0: the alarm label broke mid-word ("Alar/m") because it was weighted and got what the time and "in 18 min" left | device dump and screenshot | label measured first, a spacer takes the slack | fixed; the label no longer breaks (pass 2), but see G1 |
| F3 | Ukrainian, 2.0: the clock wrapped ("01:3/0") because the wider date squeezed it | device dump and screenshot | clock measured first, the date column takes the rest | fixed; on the device (pass 2) the clock stays on one line and the date column wraps to three |
| F4 | Ukrainian, 2.0: the card stack is taller than the screen, so the dock and the search bar with its mic are pushed off | device dump | #29 makes the stack scroll between a pinned top and bottom | fixed by #29; on the device (pass 2) the dock and the search bar with its mic are on screen and the stack scrolls between them |
| G1 | English and Ukrainian, 2.0, after F2: the alarm label and the time touch (English: the label ends at x=334 and the time starts at x=334; Ukrainian 498 and 498). The weighted spacer between them gets 0 px when the row is full | pass 2 dump | a fixed `Spacer(Modifier.width(Dimen.space2))` before the weighted one (`AlarmCard`, `ui/Cards.kt`) | fixed; on the device the gap is 21 px (8 dp) in both languages (pass 3) |
| C1 | `ink` and `ink-2` text on the glass for tint 35–100% and for the three skins | `ContrastTest` over `tokens.json` | see below | fixed or documented |
| C2 | The clash icon, the only mark of a clash, is the plain attention hue: worst case over tint, room and row, Violet 2.16:1, Blue 2.94:1, Rose 3.22:1, Amber 4.60:1 (a UI part needs 3:1) | `ContrastTest` | the hue mixed 35% toward `ink`: 3.34:1 or more for every hue | fixed |
| L1 | `today_more` ("ще %d") and `needs_more` ("+%d ще") say the same thing two ways; English says "+%d more" for both | `StringsTest` (key and placeholder parity) and reading | "+%d ще" for both | fixed |
| L2 | `age_day` ("%d д") is not the abbreviation of "день" | reading | "%d дн" | fixed |
| L3 | `voice_error` ("Не розчув") is masculine; the app speaks for no one | reading | "Не вдалося розчути", gender-neutral | fixed |
| L4 | Untranslated or mismatched keys, placeholders or plural forms | `StringsTest` | none found beyond L1 to L3 | pinned |

## Machine-run checks

`cd android && ./gradlew :home:testDebugUnitTest :home:lintDebug`.

- **`StringsTest`** parses `values/strings.xml`, `values-uk/strings.xml` and the `strings_a11y.xml` pair. Same `string` and `plurals` names in both languages. Same format placeholders (`%d`, `%1$s`) per name. Every `plurals` has the CLDR quantities of its language (English: one, other; Ukrainian: one, few, many, other). No empty value. No Ukrainian value left identical to the English one, except the ICU date skeleton, which is a pattern and not text. The spoken-age plurals exist.
- **`ContrastTest`** reads the colours from `tokens.json` (never from Kotlin). Model, from `bundle.css`: the glass is `surface` at the tint alpha over the backdrop; the backdrop is the clearest spot of the room (`bg`, `glow-3` at 85% and `glow-2` at 22% of room light, room 0, 1 and 1.4), then `saturate(160%)`. Spots: calm glass, the now row (`signal` 10%), a broken card (`bad` 22%), and a lit card for each of the four attention hues at a flat 30%. The app paints the lit light as a radial gradient from the card's corner, so the flat 30% is an upper bound. Thresholds: text 4.5:1, large text and UI parts 3:1. Tint is swept 35% to 100% in 1% steps; the Tune knob itself stops at 85% (`Tune.glassTint`), the sweep above is the margin the task asked for.
- Font scale and TalkBack are device checks (below). The unit tests cannot cover them.

### C1 results (worst case over every tint 35–100% and room)

| Text | Surface | Worst ratio | Needs | Result |
|---|---|---|---|---|
| ink | every spot, lit included | 6.50 (lit Amber, room 1.4, glow-2, tint 35%) | 4.5 | pass |
| ink-2 | calm glass | 6.18 | 4.5, README says 5.0 at tint 35% | pass |
| ink-2 | the now row, tint 35% | 4.80 | 4.5 | pass; below the README's 5.0 |
| ink-2 | a lit card, Violet | 4.79 | 4.5 | pass; below the README's 5.0 |
| ink-2 | a lit card, Rose | 4.53 | 4.5 | pass; below the README's 5.0 |
| ink-2 | a lit card, Blue | about 4.12 | 4.5 | **limit**, pinned at 4.0 |
| ink-2 | a lit card, Amber | about 3.71 | 4.5 | **limit**, pinned at 3.5 |
| bad figure | a broken card | 3.47 | 3.0 (large text) | pass |
| mic: on-accent on accent | | 8.42 | 4.5 | pass |
| clash icon | glass and now row | 3.0 or more, all hues | 3.0 (UI part) | pass after C2 |
| ink, ink-2 | the Glass and Night skins | 4.5 or more | 4.5 | pass |

The limit is the design's, not the code's: the README says `ink-2` is at least 5.0:1 under a lit card and 4.5:1 for all four hues. At the extremes of the Tune knobs (room light 1.4, tint 35%) Blue and Amber do not reach it. Changing `ink-2` to fix them would change every calm card, so this PR documents the limit and pins today's floor; the design needs a decision, which goes to Yehor (see the PR).

C2 is a deviation from the design: the clash icon is `lerp(attention, ink, 35%)` instead of the plain attention hue (`CLASH_ICON_INK_MIX` in `ui/Cards.kt`). Violet needs about 22% toward ink to reach 3:1 (20% gives 2.89, 30% gives 3.34); 35% leaves margin and the icon still reads as the hue. Figures from the same model as the test (`ContrastTest` pins the 3:1).

## Device checks

Pixel 8a, 1080×2400 at 420 dpi, which is 412×915 dp. `uiautomator dump` plus screenshots, in English and Ukrainian (`cmd locale set-app-locales`), at font scale 1.0, 1.3 and 2.0. A small script read each dump for bounds outside the screen, overlapping siblings and clickable nodes under 48 dp (126 px). The screenshots show notification text and are not published. The font scale and the app locale were restored afterwards.

Three passes. Pass 1 ran on the APK built before the F2 and F3 fixes. Pass 2 ran after #32 merged, on a build with the same content (squash `269b8e8`), with the phone unlocked again. Pass 3 is the G1 fix, English and Ukrainian at 2.0.

### Pass 1, before the F2 and F3 fixes

| Locale, scale | Result |
|---|---|
| English 1.0 | nothing off screen, no overlap, every clickable at least 48 dp |
| English 1.3 | same |
| English 2.0 | same, except F2 (the alarm label broke mid-word); one-line previews (the Approvals row, the track title) end in an ellipsis, as designed |
| Ukrainian 2.0 | F3 (clock wraps) and F4 (the stack is taller than the screen: the dock is pushed down, the search bar is clipped and the mic is off screen; the dump shows the dock's clickable nodes cut at the search bar's top edge) |
| Ukrainian 1.0 and 1.3 | not run (done in pass 2) |

Strings read in Ukrainian on the device were correct.

### Pass 2, after the fixes

Home in English and Ukrainian at 1.0, 1.3 and 2.0, six combinations: nothing off screen, no overlap, every clickable at least 48 dp (the dock buttons 56 dp), 84 nodes in each dump.

| Item | Result |
|---|---|
| F2 | "Alarm" no longer breaks mid-word at 2.0. New finding G1: the label and the time touch. English 2.0 also wraps "in 5 h 58 min" to two lines; the row still fits |
| F3 | Ukrainian 2.0: the clock stays on one line and the date column takes the slack, wrapping to three lines |
| F4 | Ukrainian 2.0: the stack scrolls between the pinned glance and the dock; the dock and the search bar with its mic are on screen |
| Edit mode (#29) | Ukrainian 2.0: every button at least 48 dp and on screen; the move and hide buttons have spoken names |
| A7 | The Tune sheet, in the unmerged tree. The scrim is a `Button` "Close" and a sibling of the panel, which is a `ScrollView` that is not clickable. The three sliders are `SeekBar` nodes named "Room light", "Frost", "Glass tint"; the sighted label rows are not in the tree. The preset, attention, density and weight chips are radio buttons with a checked state; "Edit Home" and "Reset" are buttons. The Focus row is one clickable, checkable row. Ukrainian 2.0: the sheet is taller than the screen and scrolls, and Focus and Reset are 48 dp after a scroll |
| A6 | not dumped (search results and the drawer were not opened) |

The sliders are 44 dp tall (116 px). They are not flagged clickable in the dump, and their height is unchanged by this audit; see the open questions.

### Pass 3, the G1 fix

After the fix, at 2.0 in English the label ends at x=334 and the time starts at x=355; in Ukrainian 498 and 519. That is 21 px, 8 dp (`Dimen.space2`), the same gap the time keeps from "in 5 h 49 min". The dump is clean (nothing off screen, no overlap, no small clickable). The font scale and the app locale were restored afterwards.

### Not done

TalkBack was not run, and A6 was not checked on the device. Both stay open.

TalkBack itself was not run: `uiautomator` dumps the unmerged semantics tree (the existing dock buttons look the same), so it cannot show that TalkBack reads a card as one unit. It does show every leaf's text and description, bounds, and clickable and long-clickable flags. The one-unit reading, the custom actions and the long-click label are verified by the semantics code and the string checks, not by listening. A TalkBack pass on the phone is the check this audit still does not have.

## Why no Compose test for font scale or semantics

`android/home` has no Compose UI test or Robolectric dependency (`build.gradle.kts` lists JUnit and kotlinx-serialization only). A JVM Compose test needs Robolectric plus a Robolectric jar for compileSdk 37, the same cost #27 declined for goldens. The device checks above replace it.

## Merge with #29

#29 moved the Home layout into `HomeFrame`. `origin/main` (`e9d6b0b`) is merged into this branch with no rebase. The one conflict was in `ui/Home.kt`: the branch kept `HomeFrame`'s side and re-added `actions, onLayer` to the `Glance(...)` call inside `HomeFrame(top = { ... })`. The clock and date weights (F3) are on the merged `Glance`; `Glance` keeps the four `customActions` (A4) and the search bar keeps its long-click label (A3).

The other edits are one-liners in other files: `ui/SearchPanel.kt` (`HitRow`) and `ui/Drawer.kt` (`AppTile`) get `role = Role.Button` (A6), `ui/Cards.kt` (`AlarmCard`) stops weighting the label (F2) and moves the clash icon colour (C2), and `ui/TuneSheet.kt` carries A7. The rest is `layer/LayerCard.kt`, `theme/Press.kt` (`onLongClickLabel`), `theme/Tokens.kt` (a comment) and the new `strings_a11y.xml` pair.

## Open questions for Yehor

- `age_day` is "дн" and `alarm_in_hm` ("за 1 год 5 хв") keeps its abbreviations for speech; only the card ages have spoken forms.
- The Tune tint knob stops at 85%; the sweep to 100% passes ink and ink-2 off the lit card, so the knob has margin.
- `voice_error` changed wording (L3).
- Ukrainian `date_skeleton` is the full month ("3 листопада"); English is the short month ("3 Nov"). It is what makes the glance wide at 2.0 (F3); whether to shorten it is the design's call.
- The design README's `ink-2` claims for a lit card need a correction, or Blue and Amber need a different lit-card fill.
- The three Tune sliders are 44 dp tall, under the 48 dp target. Whether to grow them is a design call; nothing here changes it.
