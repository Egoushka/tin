# Tin Home: Living bento

2026-11-15 · spec, written before any code · inputs: the style board `docs/design/board-2026-11/` (Yehor picked **b5** in round 2),
`DESIGN.md` (tokens), `docs/specs/2026-11-03-home-layout.md` (the current card stack).

## Goal

The main page becomes a bento whose **tile sizes follow the moment**. The thing that needs Yehor now is the biggest tile. Everything
else shrinks but keeps its place in the order, so a thumb still finds it. The dock, search, pages, gestures, Tune, widgets and
voice keep working as they do today.

## What changes and what stays

- **Changes:** how the main page (page 0) draws its six cards (NeedsYou, Today, Glance, Music, Alarm, Homelab). The full-width
  stack becomes a 4-column grid with sizes taken from the rules below. The theme moves from Night glass to the Living bento tokens in
  `DESIGN.md`, light and dark from the system.
- **Stays:** `HomeLayout.order` and `hidden` (Yehor's order is still the base order, and edit mode still moves and hides),
  widgets (placed as 4×N tiles at their position in the order, where N is their rows), `PinsRow`, the dock, `SearchBar`, the pages
  to the right, gestures, `GrantRow` for missing permissions, and freshness (age on every tile, fading when stale).
- **Not in this spec:** a readiness tile (Home has no Oura source yet), real travel times (see "Leave now" below), and new pages.

## Sizing rules

A pure function `arrange(now, signals, layout, held): List<Placed>` lives in `layout/Bento.kt`. It has no Compose and no clock of
its own. A tile's size is `w×h` in grid units.

| Tile | Calm | Moment | Size in the moment |
|---|---|---|---|
| Needs you | 1×1, count only | `open > 0` | 2×2, the lead figure and up to 3 rows |
| Next event | 2×1, one line | starts in ≤ 30 min | 2×2, the minutes as the lead figure |
| Leave now | absent | an event with a location whose leave time (below) is within 45 min, until it starts | 4×2 at the top, the minutes until leave as the lead figure |
| Rest of today | 2×1 rows, up to 3 | at least one clash | the clash row shows the clash line |
| Homelab | 1×1, grey dot | `broken > 0` | 2×1, the box and the failed service |
| Alarm | 1×1 | — | — |
| Clock and date | 2×1, always first | — | — |
| Weather | 1×1 | — | — |
| Music | absent | playing or paused within 10 min | 4×1 |
| Notifications | 4×1 when `total > 0` | — | — |

- **One lead at a time.** When several tiles are in their moment, the order is Leave now > Needs you > Next event > Homelab.
  Only the first gets the lead treatment: its moment size, a saturated fill and the display figure. The others step down to 2×1 in their pastel, as Needs you does at 12:30 in b5.
- **Order.** Leave now goes first while it exists. Otherwise tiles follow `HomeLayout.order`, packed densely (first fit, row by row,
  no holes except at the end of the grid).
- **Stability.** A tile changes size at most once in 5 minutes. A new Needs-you item, a new fault or a Leave-now start bypasses
  this, because they are new information. `held` carries the last sizes and when they last changed.
- **Leave time.** v1 has no routing. Leave time is `begin − lead`, where `lead` is a Tune knob ("Travel lead", 15/30/45/60
  min, default 30). The tile says "Leave by 12:30" and names the event. It never claims a drive time it did not measure.
  Real travel times are open question 1.
- **Edit mode** draws every tile at its calm size with the existing move and hide controls, so the order is edited without the
  moment moving things.

## Look

Tokens, type, grid and contrast are in `DESIGN.md`. A size change animates once, with the emphasized easing over 400 ms; under
reduced motion it does not animate. TalkBack reads each tile as its label, value and state ("Needs you, 4, 2 PRs to merge…").
Ukrainian strings keep the layout at font scale 2.0 (the existing `HomeFrameTest` bar).

## Acceptance checks (machine-run)

| ID | Check | How |
|---|---|---|
| LB1 | At 10:12 with the board's fixture (4 open, standup 10:30, lunch 13:00 at Podil, a fault, music playing), `arrange` gives Needs you 2×2 as the lead, Next event 2×2 at standup, Homelab 2×1 and Music 4×1 | `BentoTest` (unit) |
| LB2 | At 12:05 with the same fixture and lead 30 min, Leave now is 4×2, first and the lead. Needs you steps down to 2×1. At 12:31 the Leave-now tile still shows "Leave by 12:30" until 13:00, then it is gone | `BentoTest` |
| LB3 | Packing never overlaps, never exceeds 4 columns, and leaves no hole before the last tile, for every subset of the 10 tiles (2^10 cases) | `BentoTest` (property) |
| LB4 | Stability: a signal that flips every minute changes a tile's size at most once in 5 min, and a new Needs-you item changes it at once | `BentoTest` |
| LB5 | The dock and search bar stay on screen and in place at font scale 1.0 and 2.0, in English and Ukrainian, at both fixture times | `HomeFrameTest` (Compose, updated) |
| LB6 | Every text and fill pair in `DESIGN.md` is at least 4.5:1, or 3:1 for the display figure, in light and dark | `ContrastTest` (updated to the new tokens) |
| LB7 | Edit mode shows calm sizes and the move and hide controls still reorder `HomeLayout` | `HomeControlsTest` (updated) |
| LB8 | CI's emulator job saves screenshots of Home at 10:12 and 12:05, light and dark, at 412×915 dp as an artifact. They are the gate's before and after | `connectedCheck` screenshot test |
| LB9 | `:home:testDebugUnitTest :home:lintDebug :home:assembleRelease` green; Night glass tests retired, not skipped | CI `android` |

## Slices

1. **S1 `arrange`:** `Bento.kt`, `BentoTest` (LB1–LB4) and the Travel lead knob. No UI change.
2. **S2 tokens and font:** the Living bento tokens next to Night glass's, Plus Jakarta Sans bundled (400/500/700/800), and the
   `ContrastTest` update (LB6).
3. **S3 the grid:** page 0 draws `arrange`'s output, with the tiles restyled, edit mode, and LB5, LB7 and LB8.
4. **S4 retire Night glass:** remove its tokens, `NightGlassCssTest` and the glass renderer from page 0 (LB9), and point
   `docs/design/night-glass/README.md` at this spec.

## Open questions for Yehor

1. **Travel time.** v1 uses a fixed lead (default 30 min). A real drive time needs a routing source: Google Maps' Routes API
   (paid, with a key on the phone), OSRM on the box (free and self-hosted, but no live traffic), or the time Maps shows when the
   event opens. If he says nothing, the fixed lead stays.
2. **Readiness.** The b5 tile shows Oura readiness, but Home has no Oura source. Adding one is its own spec, with an Oura token on
   the phone or a relay on the box. Without it, the tile is absent.
3. **Font size in the APK.** Plus Jakarta Sans with 4 weights (OFL) adds a few hundred KB, **UNKNOWN** until measured in S2.
