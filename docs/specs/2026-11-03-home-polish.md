# Spec: Tin Home, polish pass (fidelity and motion)

Date: 2026-11-02, filed as 2026-11-03 for the wave plan. Status: audit done, fixes not started. Slice: the visual layer of
`ui/Cards.kt`, `layer/` and `theme/`. Design source: `docs/design/night-glass/` (`bundle.css` Night glass block,
`cards/Light.md`, `cards/System.md`, `cards/Controls.md`, `HomeNightGlass.png`).

## Goal

Yehor says Home looks "raw". The design is accepted, so the fix is fidelity: make the built Home match
`HomeNightGlass.png`, with every number derived from the six constants (grid 4, target 48, curve 28, stroke 1.6, Inter in
5 sizes, 4 light levels) and from the CSS, not by eye. Then make light move the way `cards/Light.md` says.

Value hypothesis: a Home that looks and moves like the design is one Yehor keeps using for the 7-day trial. Cheapest
test: Yehor puts the build beside `HomeNightGlass.png`. **Stop condition:** if the halo, the lit edge and the press
feedback are in and Home still reads as "raw", the cause is layout (duo rows, card order, Readiness), which is deferred
below, and the next slice starts there.

## Facts this rests on

- Method: read-through of `theme/Glass.kt`, `theme/Tokens.kt`, `layer/LayerCard.kt`, `ui/Cards.kt`, `ui/Home.kt` against
  the CSS and the PNG. No device run yet; the Pixel 8a is not reachable from this sandbox.
- `--spring` in `bundle.css` is `cubic-bezier(.2, 0, 0, 1)`, which is the Material 3 standard curve. Every press and
  fade in the CSS uses it.
- Already right, no gap: icon stroke (`Lucide.icon` rescales the viewport stroke so 20, 16 and 24 dp icons all draw at
  `strokeDp`, which is the CSS `non-scaling-stroke`); the five type sizes and weights; `tnum` on every style; radii
  28 and 16; the frost blur radius (CSS px equal dp on the 412 dp phone).

## Light over time (`cards/Light.md`), the table to implement

| Transition | Duration (M3 token) | Curve |
|---|---|---|
| Calm → needs you | 300 ms (`medium2`) | emphasized decelerate `(.05, .7, .1, 1)` |
| Needs you → calm | 500 ms (`long2`) | emphasized accelerate `(.3, 0, .8, .15)` |
| Calm → now | 300 ms (`medium2`) | standard `(.2, 0, 0, 1)` |
| Any → broken | 200 ms (`short4`) | standard |
| Any → stale | 1000 ms (`extraLong4`) | standard |
| Stale → fresh | 100 ms (`short2`) | standard |

Reduced motion (`ANIMATOR_DURATION_SCALE` = 0, which the "Remove animations" accessibility toggle sets) makes every
transition instant. Home never vibrates for a light change.

**Assumption for Yehor to confirm:** the table has no row for now → calm, broken → calm, or one lit kind replacing
another. Rule used: a light *arriving* takes the arrival row for its kind (needs you 300 decelerate, now 300 standard,
broken 200 standard); a light *leaving* takes the needs-you → calm row (500 ms accelerate), because Light.md says
"light comes faster than it goes".

## Audit: every gap

Status is "to fix" until the PR says otherwise. "Check" names the machine-run check from the last section.

| ID | Design says | Built today | Fix | Check | Status |
|---|---|---|---|---|---|
| G1 | Every card drops a soft shadow, `0 16px 32px -16px black 60%`; a needs-you card adds a halo `0 0 36px -12px glow-1`; a broken card has a red edge (the bad dot glows `0 0 10px 1px bad`). The halo is the most visible thing in `HomeNightGlass.png`. | `glass()` draws no shadow and no halo at all, so cards sit flat on the room. | Soft shadow and halo drawn behind the card, sizes from the CSS numbers. | C1, S1 | to fix |
| G2 | Needs-you light is `radial-gradient(120% 90% at 0 0, glow-1 30%, transparent 70%)`; broken is `110% 100% at 0% 50%`, `bad` 22%. | A circle (radius 1.2 × width) at 30% for every kind, so the gradient is round and the same shape for broken. | Elliptical gradients per kind with the CSS radii and alphas. | C1, S1 | to fix |
| G3 | Lit edge is a 1 dp line: `inset 0 1px 0` of `edge-hi`, mixed 60% with the light when lit; the border is `edge` mixed 55% (needs you) or 45% (broken) with the light. | The edge is a vertical gradient that fades over the top 10% of the card height, so a tall card gets a smeared 15 dp band instead of a line. | A 1 dp crescent along the top, following the 28 dp curve; border mixes per kind. | C1, S1 | to fix |
| G4 | Card padding is 12 (`--k-pad`, README: "card padding 12"); a mini card is 12 wide with a 48 dp minimum height. | `LayerCard` pads `padDp + space1` = 16 on all sides, so content sits too far in and the glass looks empty. `bundle.css` also has 14 horizontally (`pad + 2`), which is off the 4 dp grid and is not copied. | 12 on all sides from `Tune.padDp`; the alarm and homelab rows keep a 48 dp minimum. | C2 | to fix |
| G5 | Today does not light the card. Only the current row is lit: `signal` 10% fill, 1 dp inner ring `signal` 22%, radius 16 (`.ag-row.now`). | `TodayCard` passes `signal` as the card's light, so the whole card glows teal while the current time is also teal. | The card stays calm; the current row gets the fill and ring with radius `radiusRow`. | C1, S1 | to fix |
| G6 | Meta text (ages, `ag-r`) is `500 11/16 tnum` (`--t-meta`); the label turns `ink` on a lit card (`.w.hot .lab`); clock and times use `ss01`. | The age beside a label reuses `NgType.label` with its tracking removed, so it is weight 600, not 500; the label stays `ink-2` on a lit card. `ss01` is not set. | Add `NgType.meta` (outside `NgType.all`, same 11 size); lit label colour `ink`; `ss01` deferred (see below). | C3 | to fix |
| G7 | Press feedback: dock tile → `surface-2` fill, `edge-hi` border, scale .92, 160 ms standard; transport and mic → scale .92; the search bar and cards have no overlay. | Every control uses `clickable` with the default Material indication: a grey square ripple over round glass. This is the likeliest source of "raw". | One `Modifier.pressable(...)` in `theme/` with no indication; dock, transport and mic use it. | C4, S2 | to fix |
| G8 | Light moves per the table above. | One `animateFloatAsState` for all kinds with the default tween (a different curve from every M3 one), colour snaps when the kind changes, and the leave time is 500 ms for broken and now as well. | Per-kind alpha with the table; curves and durations in `theme/Motion.kt`. | M1, M2, M3 | to fix |
| G9 | Reduced motion is the system setting, live. | `animatorScale()` reads the setting once per composition and never again, so toggling "Remove animations" has no effect until the process restarts. | Observe `Settings.Global` with a `ContentObserver`. | M4 | to fix |
| G10 | Focus mode dims calm cards to 40% with a 220 ms fade; **Today is never dimmed** (`:not(.ag)`). | The dim snaps, and Today is dimmed like any calm card. | Animate the dim at 220 ms standard; `LayerCard(keepBright = true)` for Today. | C5, M5 | to fix |
| G11 | A clash in Today marks its second line with a `glow-1` icon and `ink` text. | `Ng.warn` (amber) icon. | `glow-1` icon, `ink` text. | C1 | to fix |
| G12 | The homelab dot is `ink-2` when calm and `bad` with a glow when broken; the bad card's gradient is leftward (see G2). | The card is a big red figure plus names, with no dot. | Deferred with layout (below). | none | deferred |

## Deferred, with the reason

- **Duo rows, card order, mini cards in a row:** need `ui/Home.kt`, which three other sessions edit this wave. Proposed
  as its own slice after those PRs merge.
- **Readiness card** (score ring, sleep): the data source is UNKNOWN (`2026-11-02-home-launcher.md`).
- **Needs-you grouped by kind and the weather line:** the weather session owns the glance; the grouping needs the
  approvals provider's kinds.
- **`ss01`, `cv11` font features:** `TokensTest` asserts `fontFeatureSettings == "tnum"` on every style, and the tokens
  file lists only `tnum`. Changing it is a design-system change, not a polish fix. G6 stops at `tnum`.
- **Clash overlap partner and minutes** (`Agenda` holds only a boolean): the calendar slice owns it.

## Checks (machine-run)

All run with `cd android && ./gradlew :home:testDebugUnitTest :home:assembleDebug :home:lintDebug`. `TokensTest` must stay
green.

| ID | Check |
|---|---|
| M1 | `MotionTest`: each row of the table above maps to its duration in ms and its curve control points (token values: `short2` 100, `short4` 200, `medium2` 300, `long2` 500, `extraLong4` 1000). |
| M2 | `MotionTest`: the unlisted exits (now → calm, broken → calm) and lit-to-lit swaps follow the assumption rule above. |
| M3 | `MotionTest`: every transition lasts exactly 0 ms when the animator scale is 0, and never uses haptics (no `Vibrator` or `HapticFeedback` import in `layer/` or `theme/`; asserted by a source grep in the test). |
| M4 | `MotionTest`: the scale reader returns the new value after the setting changes (a fake `ContentResolver` value, no device). |
| M5 | `MotionTest`: the focus dim lasts 220 ms with the standard curve. |
| C1 | `NightGlassCssTest`: the constants in `GlassPaint` (shadow offset, blur and spread, halo size, gradient radii and alphas, border mixes, the now-row fill and ring) equal the numbers parsed from the Night glass block of `bundle.css`. |
| C2 | `NightGlassCssTest`: card padding is `--k-pad` (12) from `Tune`, and is a multiple of the grid (4). |
| C3 | `NightGlassCssTest`: `NgType.meta` equals `--t-meta` (500, 11/16, `tnum`) and `NgType.all` still has the five sizes `[76, 44, 15, 13, 11]`. |
| C4 | `NightGlassCssTest`: the press scale (.92), duration (160 ms) and curve equal the CSS `.ic:active` rule and `--spring`. |
| C5 | `NightGlassCssTest`: the focus dim is `.ph.focus` opacity .4 and the Today card is exempt. |
| S1, S2 | Screenshots, below. |

## Evidence

If a JVM screenshot path fits in one small dependency, goldens of Home in calm, needs-you, broken and stale go in the PR
body (before from `main`, after from this branch). Otherwise the PR says why not and lists the device screenshots for
Yehor to take: the same four states, plus the dock tile and the mic pressed.

## Open questions for Yehor

1. The unlisted light exits (assumption above): 500 ms accelerate for all, or something faster for broken → calm?
2. Card padding 12 on all sides follows the README. The CSS has 12 vertical and 14 horizontal; say if the 14 is wanted.
