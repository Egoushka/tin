# Home accessibility and locale audit

Stacked on PR #27 (Home polish), so it audits the card, glass and press code that PR introduces. Scope: `android/home`, the Night glass Home on main plus #27. Four audits: TalkBack, font scale, contrast, Ukrainian. Each gap has an ID, a check, and a status that the PR updates.

## What is audited

On the base of this PR: the glance, music, today, needs-you, homelab and alarm cards (`ui/Cards.kt`, `layer/LayerCard.kt`), the dock, the search bar and the mic (`ui/Home.kt`), the drawer rows (`ui/Drawer.kt`), the Tune sheet (`ui/TuneSheet.kt`), the press modifiers (`theme/Press.kt`), and every string.

Deferred, because the surface is not in this base (it is on main after #27's branch point, or in an open PR): the search-results list (#28), the new layout and duo rows (#29), apps manage, voice engine UI, weather line, edit mode, dock menu. Each gets this audit when its PR lands; the checks below (`StringsTest`, `ContrastTest`) already cover their strings and colours once merged.

## Findings to confirm

| ID | Gap | Check | Fix |
|---|---|---|---|
| A1 | A card's label, its age and its rows are separate focus stops on cards that are not clickable (Homelab, Alarm, Music, Today when granted); the age is not spoken with the label | device `uiautomator dump`, one node per card | merge the card's semantics into one node |
| A2 | The age is spoken as its visible abbreviation ("1 d" is read "1 D"); it has no plural forms | `StringsTest` (spoken-age plurals exist, every CLDR form) | spoken age plurals, read as "Needs you, 1 minute ago" |
| A3 | The search bar has a click label but no long-press label: TalkBack does not say that a long press speaks | device dump | `onLongClickLabel` |
| A4 | Vertical drags (apps, notifications), double-tap torch and long-press Tune have no accessibility action | device dump of the root | custom actions on the root |
| A5 | The mic's label is already the state word; keep it | device dump of the mic in each state | none, pinned in the spec |
| F1 | Large text: the clock (76 sp, `NgType.clock`) and fixed 56 dp rows may clip or wrap at scale 1.3 and 2.0 | device check at 412×915 dp, scale 1.3 and 2.0: no node leaves the screen, every clickable is at least 48 dp | cap or wrap what fails |
| C1 | `ink` and `ink-2` text on the glass for tint 35–100% and for the three skins | `ContrastTest` over `tokens.json` | fix a token or document |
| L1 | `today_more` ("ще %d") and `needs_more` ("+%d ще") say the same thing two ways; English says "+%d more" for both | `StringsTest` (key and placeholder parity) and reading | one form |
| L2 | `age_day` ("%d д") is not the abbreviation of "день" | reading | "%d дн" |
| L3 | Untranslated or mismatched keys, placeholders or plural forms | `StringsTest` | add or fix |

## Machine-run checks

`cd android && ./gradlew :home:testDebugUnitTest`.

- **`StringsTest`** parses `values/strings.xml` and `values-uk/strings.xml`. Same `string` and `plurals` names in both. Same format placeholders (`%d`, `%1$s`) per name. Every `plurals` has the CLDR quantities of its language (English: one, other; Ukrainian: one, few, many, other). No empty value. The spoken-age plurals exist.
- **`ContrastTest`** reads the colours from `tokens.json` (never from Kotlin). Model, from `bundle.css`: the glass is `surface` at the tint alpha over the backdrop; the backdrop is the room (`bg`, plus `glow-3` at 85% and `glow-2` at 22% of room light, the clearest spot of each), then `saturate(160%)`. Lit card: `glow-1` 30% over the glass for each of the four attention hues; broken: `bad` 22%. Thresholds from the design README: text 4.5:1 (large text and UI parts 3:1), and `ink-2` at least 5.0:1 on the clearest glass and on a lit card. Tint is swept 35% to 100% in 1% steps; the Tune knob itself stops at 85% (`Tune.glassTint`), the sweep above is the margin the task asked for. Glass and Night glow are checked with their own paint and filter.
- Font scale and TalkBack are device checks (below). The unit tests cannot cover them.

## Why no Compose test for font scale or semantics

`android/home` has no Compose UI test or Robolectric dependency (`build.gradle.kts` lists JUnit and kotlinx-serialization only). A JVM Compose test needs Robolectric plus a Robolectric jar for compileSdk 37, the same cost #27 declined for goldens. The Pixel 8a is on the bench, so the font-scale and accessibility checks run there as `uiautomator dump` plus screenshots (not published: they show notification text), and the device settings are restored afterwards.

## Out of scope

Colours or layout changes beyond what a failing check forces; the deferred surfaces; the Tune sheet's own layout.
