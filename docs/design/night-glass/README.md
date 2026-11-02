# Tin

Tin is the design system of Yehor's phone Home: a full launcher on a Pixel 8a (GrapheneOS, 412 × 915 dp, used with one hand), with Yehor's own layer on top and voice inside the search bar. It is its own system, not a branch of Approvals.

**Status: v4.1, Night glass, refined.** Glass's frosted cards and Inter, set in Night glow's dark room, where light means something needs attention. It is built from six constants and can be tuned live. Glass and Night glow stay as its two parents for comparison; Tin metal is gone.

## What Tin stands for

These principles are my reading of what Yehor values, taken from what Yehor asked for and rejected. Yehor corrects them; the cards follow.

1. **Signal over noise.** Nothing is coloured at rest. Light appears only when something needs Yehor, and each light has one meaning.
2. **Evidence on the surface.** Every number says where it came from and how old it is ("Needs you · 1 min", "Readiness · 07:40"). Stale data fades and says its age instead of pretending to be live.
3. **Yours to tune.** The system is meant to be adjusted, so the adjustments are part of it: one knob changes one variable, and the whole Home reads it.
4. **Function first.** The stock job (clock, apps, search, music, alarm, notifications, gestures) is done fully before the personal layer is added.
5. **One system from a few constants.** Parts agree because they derive from the same numbers, not because they were matched by eye.

## Scope: the stock job first, then the layer

Home does everything a stock launcher does: clock, date and weather; a 5-app dock; all apps on swipe up, with instant search; music controls with the earbuds' battery; an alarm; a notification glance; gestures.

Yehor's layer sits on top of that:

- **Today:** the agenda, with travel time and the time to leave, and clashes marked.
- **Needs you:** approvals, PRs to merge and replies owed.
- **Readiness:** the Oura score and sleep, with a nudge when recovery is low.
- **Homelab:** the health of the box `hedzer` and any failed deploy.

The voice assistant is the mic inside the one search bar, which searches apps, people and the web and takes questions. There is no separate hold-to-talk button.

## How we got here

| Version | Date | What it was | Why it moved on |
|---|---|---|---|
| v1 | 2026-11-01 | OLED black with stock filled buttons | "ordinary buttons… default design" |
| v2 | 2026-11-02 | Three researched directions: Instrument, Page and Reach | "i don't like each" |
| style board | 2026-11-02 | 12 style tiles | "no quality of life things… it needs to be functional like any other phone and bring my own customization on top" |
| v3 | 2026-11-02 | One layout in three skins: Glass, Night glow, Tin metal | Glass liked first, Night glow "also solid". But "icons are bad", and the parts were "kinda in sync and at the same time… completely not"; the system should be adjustable and represent Yehor's values. |
| v4 | 2026-11-02 | Night glass: Glass fused with Night glow, six constants, live Tune | "Lands, refine it" |

v4 answers each point of the v3 feedback:

- **Icons** were hand-drawn and uneven. They are now Lucide, one open set with one stroke (Icons card).
- **"In sync and not"**: sizes, radii and strokes were chosen per part. They now derive from six constants (System card).
- **Adjustable**: the Tune card changes the live Home.
- **Values**: the principles above, and light that carries meaning instead of decoration.

v4.1 refines v4 without changing its idea:

- **Calm has no colour.** Good states (readiness, all clear, healthy hosts) were green or teal; they are now white or grey, so any colour on Home means attention.
- **Five type sizes, enforced.** Every text role in the CSS maps to one of five sizes; stray sizes are gone.
- **Night glass first.** Every screen card leads with Night glass; the parents appear only for comparison.
- **Light over time.** A new card says how light arrives, holds and leaves, with timings from Material 3 motion tokens.
- **Tune presets and export.** Four presets and the JSON the phone would store.
- **Smaller fixes.** Gestures uses the theme tokens; "Нагадування створено" replaces a wrong Ukrainian string.

## The system: six constants

| Constant | Value | What derives from it |
|---|---|---|
| Grid | 4 px | Every space: 4, 8, 12, 16, 24. Gutter 16, card gap 10, card padding 12. |
| Target | 48 px | Search bar 56, app tile 56, icon buttons 48, chips 36 with a 48 hit area. |
| Curve | 28 px | Half of 56, so bar, tiles and cards share one curve. Rows inside a card: 28 − 12 = 16. |
| Stroke | 1.6 px | Every icon at every size; it matches the stem of 15 px Inter at 550. |
| Type | Inter, 5 sizes | 76 clock · 44 figures · 15/20 titles · 13/16 lines · 11/16 labels and meta. Every text role maps to one of these; nothing else is allowed. |
| Light | 4 levels | Calm (none) · now (teal) · needs you (`glow-1`, violet by default) · broken (red). Stale is no light at 55%. |

## Skins are themes

`tokens.json` has one set of colour names with three values each: `nightglass`, `glass` and `night`. Every phone and stage carries `data-theme`. `components/bundle.css` adds paint, fonts, radii and press behaviour, and for Night glass the `--k-*` knob variables.

| | Night glass (v4) | Glass (parent) | Night glow (parent) |
|---|---|---|---|
| Idea | Frosted glass in a dark room; light means attention | Tinted glass over a bright colour field | A black room with two lights |
| Widgets | Glass at 55% tint, blur 20, lit top edge, radius 28 | Dark glass, blur 18, radius 26 | Near-black, hairline edge, radius 22 |
| Primary | White | White | Violet with a glow |
| Type | Inter | Inter | Geist and Geist Mono |
| Cost | Blur needs `RenderEffect` (Android 12+); frost 0 is the power-saving fallback. | Same blur cost, and the colour field is always loud. | Least colour, so state must be read from shape. |

## Cards

| Card | What it shows |
|---|---|
| **System** | The six constants and what derives from them: one curve, one light ladder, one stroke. |
| **Icons** | The Lucide set used by Tin, sizes, stroke weights and themed third-party icons. |
| **Light** | Light over time: one morning on the Needs-you card, a live loop, and every transition with its trigger, Material 3 duration and reduced-motion rule. |
| **Tune** | Live knobs beside a live Home, four presets (Default, Bedside, Battery, Deep work) and the DataStore entry the phone would save. |
| **Home** (one per skin) | The full working Home, in English and Ukrainian. |
| **All apps and search** | The drawer, a typed query with grouped results, and a voice answer. |
| **Voice in the search bar** | The five voice states in Night glass, English beside Ukrainian, with motion, reduced-motion behaviour and haptics. |
| **Controls** | Every Night glass control at rest, pressed and disabled; the press and release of each skin in a table. |
| **Your layer: states** | Empty, good, unreachable, leave-now and stale states of the four widgets. |
| **Gestures** | The gesture map and the defaults table. |

## Rules for every skin

- **Size and position.** Hit targets are 48 dp with 8 dp between them. The 24 dp side edges belong to Android's back gesture. The search bar sits at the bottom, under the thumb.
- **Contrast.** 4.5:1 for text and 3:1 for UI parts, checked by script on every theme. For Night glass, `ink-2` stays at or above 5.0:1 on the clearest glass (tint 35%) under the room light and under a lit card. The attention colour is limited to violet, blue, rose and amber, the hues that keep 4.5:1 on a lit card; green fails (4.48:1) and would read as "all up".
- **Icons.** One open set (Lucide, ISC licence), one stroke, sized to the text line beside it: 20 px by titles, 16 px by secondary lines, 24 px in tiles. No hand-drawn glyphs, no sparkle for the assistant.
- **Language and motion.** Every string exists in Ukrainian and English; layouts are checked with the longer Ukrainian text. Numbers use tabular figures. Text a model wrote is italic. Every animation has a still state for reduced motion, and TalkBack reads the state word.
- **Voice limits.** Voice can set reminders and open things. It never approves or merges: those open the item and wait for a tap.

## How to check it, and when to stop

**Check.** On the Tune card, set the knobs to taste and note the values. On the Home card, Night glass should tell you what needs you and when to leave faster than either parent.

**Stop condition.** Build Night glass in Compose with the tuned values and use it for 7 days. It has failed if the stock launcher comes back, or if a meeting or an approval is missed because Home hid it. Then switch to the parent that came closer rather than tuning the loser.

## Open decision: fonts

Night glass needs only Inter (variable, weights 200–600). The previews load it from Google Fonts; on the phone it would be bundled in the APK. **Yehor decides** whether to bundle it.

- **Cost:** APK size, **UNKNOWN** until measured with only the weights used. An installed app has no flash of font.
- **Without it:** Night glass falls back to system Roboto and keeps every other rule.
