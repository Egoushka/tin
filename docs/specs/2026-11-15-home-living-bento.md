# Tin Home: Living bento

2026-11-15 · spec, written before any code · inputs: the style board `docs/design/board-2026-11/` (Yehor picked **b5** in round 2),
`DESIGN.md` (tokens), `docs/specs/2026-11-03-home-layout.md` (the current card stack). Revised the same day after a design review
(blockers: lead contradictions, fit, packing holes, the tile mapping, and Leave-now edge cases).

## Goal

The main page becomes a bento whose **tile sizes follow the moment**. The one thing that needs Yehor now is the biggest tile. Every
other tile keeps its place in his order, so a thumb still finds it. The dock, search, pages, gestures, widgets and voice keep working
as they do today.

## Tiles and how today's cards map onto them

| Tile | From today's code | Orderable | Notes |
|---|---|---|---|
| Glance row | `top()`: clock, date, weather, torch | no, always row 0 | Clock 2×1, weather 1×1, alarm 1×1. Fixed sizes. The torch becomes a 48 dp key in the weather tile. The clock's tap routes are unchanged. |
| Leave now | new, from the agenda | no, first below row 0 while it exists | See "Leave now" |
| Needs you | `CardId.NeedsYou` | yes | |
| Next event | `CardId.Today`, its first upcoming item | yes, moves with Today | |
| Rest of today | `CardId.Today`, the other items | yes, directly after Next event | Up to 3 rows, then "+N more" |
| Homelab | `CardId.Homelab` | yes | |
| Music | `CardId.Music` | yes | |
| Notifications | `CardId.Glance` (the notification glance) | yes | |
| Widget | `Slot.Widget` | yes | 4 wide; grid rows = ceil((70·rows − 30 + 12) / 94), capped at 4 |

`CardId.Alarm` moves into the glance row. A saved `home.order` decodes as before, with `Alarm` dropped and `Today` expanded into
Next event plus Rest of today, so no saved layout breaks (LB10). The hidden flag keeps working per tile, and a hidden Alarm hides it
from the glance row.

## Sizing rules

`arrange(now, signals, order, rows, held): Arrangement` is a pure function in `layout/Bento.kt`, with no Compose and no clock of its
own. `rows` is the number of grid rows that fit on page 0 above the pins, the dock and search. `held` is the last arrangement and
when it was made.

**One lead at most.** The lead is the only tile with a saturated fill and the display figure. The first case that holds wins:

1. **Leave now**, while its window is open (below): 4×2.
2. **Next event** starting in 15 min or less: 2×2, with the minutes as the figure.
3. **Homelab** with a fault that began within the last 30 min: 2×2, naming the box and the service.
4. **Needs you** with a held call, or an item newer than 30 min: 2×2, with the count and up to 3 rows.
5. Otherwise there is no lead. Everything is calm.

Needs you does not lead just because `open > 0`. For a developer that is nearly always true, so the page would never change.

**Calm and stepped-down sizes.** Every tile that isn't the lead takes one of these sizes:

| Tile | Size | Content |
|---|---|---|
| Needs you | 2×1 when `open > 0`, 1×1 at 0 | count, plus the first row in 2×1 |
| Next event | 2×1 | title and "in 18 min"; "now · ends 11:00" while it is in progress |
| Rest of today | 2×1 per row, up to 3 | "+N more" in the last row; absent when nothing is left today |
| Homelab | 2×1 when broken, 1×1 when healthy | the box and service, or a grey "all up" |
| Music | 4×1 while playing or paused within 10 min, otherwise absent | |
| Notifications | 4×1 when `total > 0`, otherwise absent | |

**Packing.** Tiles go strictly in order, row by row. A tile that doesn't fit the rest of a row starts the next row, and the last tile
in the unfinished row stretches to fill it. Nothing is ever pulled forward out of order, so positions only move when sizes change.

**Fit.** If the tiles need more than `rows`, calm tiles collapse in this order until they fit:

1. Rest of today down to 1 row.
2. Notifications into a 28 dp chip line under the glance row.
3. Music to 2×1.
4. Healthy Homelab and zero Needs you vanish (their state stays in edit mode).

If it still doesn't fit, page 0 scrolls, which is today's behaviour. On the Pixel 8a the space is about 6 rows with pins and 7 without
them, a starting value from the review that LB11 measures.

**Stability.** The whole arrangement (sizes and positions) is held for 5 minutes. These bypass the hold, because they are new
information or a predictable boundary: a new Needs-you item or held call, a new fault, Leave now opening or closing, Next event
crossing 15 min, an event starting or ending, and music starting (it answers his own tap).

**Leave now.**

- An event qualifies when its location is a place. Locations with a URL, or naming Meet, Zoom, Teams, Webex, "online" or
  "call", don't qualify.
- The leave time is `begin − lead`, where `lead` is a Tune knob ("Travel lead": 15, 30, 45 or 60 min, default 30).
- The window opens 45 min before the leave time and closes when the event begins. While open, the tile shows "Leave by 12:30 ·
  Lunch, Podil" with the minutes until then as the figure.
- After the leave time the tile reads "Late · starts 13:00", and the figure becomes the minutes until the start.
- It never shows a drive time it did not measure.
- Next event skips the event that Leave now is showing.
- All-day events are already filtered out (`CalendarSource.query` skips `ALL_DAY` rows).

**Edit mode** stays as the current full-width list (`EditedSlot` with its move, hide and resize controls). The bento resumes when
edit mode ends.

## Look

- **Tokens, type, grid and contrast:** see `DESIGN.md`, which holds the light and dark sets as real keys.
- **Wallpaper:** Home paints its own wallpaper gradient. Showing the system wallpaper is a Tune choice later.
- **Edges:** every tile has a 1 dp edge (ink at 8%), so the pastel tiles stay visible against the pastel wallpaper in sunlight.
- **Stale and focus:** a stale tile fades its fill to 55% and tints its age line. The text stays at full contrast. Focus mode dims
  calm fills the same way.
- **Motion:** a size change animates once, with the emphasized easing over 400 ms, and does not animate under reduced motion.
- **TalkBack** reads each tile as its label, value and state ("Needs you, 4, 2 PRs to merge…").

## Acceptance checks (machine-run)

| ID | Check | How |
|---|---|---|
| LB1 | At 10:12 with the board fixture (4 open, the newest 1 min old; standup 10:30; lunch 13:00 at Podil; a fault from 09:00; music playing), Needs you is the 2×2 lead. Next event is 2×1 "in 18 min". Homelab is 2×1 and not the lead, because its fault is older than 30 min. Music is 4×1 | `BentoTest` |
| LB2 | At 12:05, lead 30: Leave now is the 4×2 lead, first below the glance row, with the figure 25. Needs you is 2×1. Next event shows Sprint review, not Lunch. At 12:31 it reads "Late · starts 13:00", and at 13:00 it is gone | `BentoTest` |
| LB3 | Across every subset of tiles and sizes, nothing overlaps, nothing is wider than 4, the order equals the input order, and the only gaps are filled by a stretched last-in-row tile | `BentoTest`, property-based |
| LB4 | A signal flipping every minute changes the arrangement at most once in 5 min. Each bypass event in "Stability" changes it within the same minute | `BentoTest` |
| LB5 | Dock and search stay on screen and in place, and no tile text is clipped, at font scale 1.0 and 2.0, in English and Ukrainian, at 10:12 and 12:05 | `HomeFrameTest`, updated |
| LB6 | Every text and fill pair in `DESIGN.md` (both sets) is at least 4.5:1. Display figures are at least 3:1 | `ContrastTest`, reading the new tokens |
| LB7 | Edit mode shows the full-width list, and move and hide still change `HomeLayout` | `HomeControlsTest` |
| LB8 | Robolectric and Roborazzi screenshots of page 0 at 10:12 and 12:05, light and dark, at w412dp-h914dp-420dpi, with a fixed clock and fake signals, compared against committed goldens | the `android` job, a new `HomeShotsTest` |
| LB9 | `:home:testDebugUnitTest :home:lintDebug :home:assembleRelease` is green. Any retired test is deleted with its feature, not skipped | the CI `android` job |
| LB10 | Every old `home.order` value, including ones with `Alarm` and widgets, decodes to the new tiles without losing a widget or a hidden flag | `LayoutStoreTest`, `HomeLayoutTest` |
| LB11 | `rows` is measured from the real frame at 412×915 with and without pins. Both fixtures fit without scrolling when pins are off | `HomeFrameTest` |
| LB12 | A missing calendar or notification permission shows a 4×1 `GrantRow` tile in that tile's place | `GrantRowTest` |

Tests to update alongside: `HomeLayoutTest`, `LayoutStoreTest`, `TokensTest`, `TuneTest`, `MotionTest`, `GrantRowTest`,
`WidgetLifecycleTest`, `HomeFramePagesTest`, `StringsTest`, `DeadCardsTest`, `NeedsYouCardTest`, `TodaySheetTest`.

## Slices (one PR each)

1. **S1 `arrange`:** `Bento.kt` and `BentoTest` (LB1–LB4). Pure Kotlin, no UI change.
2. **S2 screenshots:** add Roborazzi with the fixed clock and fake signals, and record goldens of **today's** Home (the "before").
   Re-render the b5 mockup with the `DESIGN.md` tokens for Yehor to see before S4.
3. **S3 tokens and font:** add the Living bento tokens next to `Ng`, bundle Plus Jakarta Sans (400/500/700/800) and measure the
   APK delta, and update `ContrastTest` (LB6). No visual change yet.
4. **S4 the grid:** page 0 draws `arrange` with the existing card contents in the new tile shells. This covers the glance row, Leave
   now, widgets, grants, the tile mapping and migration (LB5, LB7, LB10–LB12). The goldens are updated: this is the "after".
5. **S5 tile restyle:** each tile's content in the final type and colour, plus the Travel lead knob in Tune.
6. **S6 Tune cleanup:** gap is fixed at 12 dp on page 0. The attention colour, frost, glass tint, room light and presets keep
   working for the drawer, sheets and pages, which still use `Ng`. Restyling those surfaces is a separate spec, so nothing outside
   page 0 changes here.

## Open questions for Yehor (each has a default)

1. **Travel time.** v1 uses a fixed lead. A real drive time needs a routing source: Google's Routes API (paid, a key on the phone),
   OSRM on nickel (free, no live traffic), or nothing. Default: the fixed lead.
2. **Readiness.** Home has no Oura source, so there is no tile until one exists. Default: absent.
3. **Font.** S3 measures the APK size cost of the bundled font. Default: bundle it.
4. **Mockup changes.** b5 used all-caps labels and white on tangerine. The build uses sentence-case labels and ink on tangerine,
   because white was 2.6:1. S2's re-render shows this before any tile is restyled.
