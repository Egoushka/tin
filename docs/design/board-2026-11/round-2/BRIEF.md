# Round 2: between Bento and Day river

Yehor's verdict on round 1 (2026-11-15) was that he "liked 5 and 11, but they still was not quite what i want". 05 Bento widgets is a pastel grid on a soft gradient, with tiles of different sizes, Needs you as the largest, bold numerals and generous radii. 11 Day river lays the day out as one vertical time axis with a bright "now" line, and everything is pinned to the axis at its time.

This round tests **what is missing between them**. Each tile is one hypothesis and differs from the others on a clear axis, so his reaction tells us which axis matters. They are not reskins of each other.

Everything in `../BRIEF.md` still applies: frame, content, his past rejections, Lucide only, weights and chips, 412 × 915, and the box name **hedzer**. Look at `../shots/tile-05-bento.png` and `../shots/tile-11-river.png` first, and reuse their best parts.

| File | Hypothesis | What it must show |
|---|---|---|
| `tile-b1-bento-river.html` | **Bento laid out by time.** River's structure with Bento's skin. | A light pastel wallpaper. A slim time axis runs down the left. The bento tiles hang off it at their times, sized by importance. Needs you and the failed deploy are big tiles at "now". |
| `tile-b2-dark-river.html` | **River, but warmer and friendlier.** Bento's colour and roundness on River's dark axis. | A dark background. Each kind of item has its own soft pastel tint (agenda, needs you, health, homelab, music). Big radii, bold numerals, and a glowing now line. |
| `tile-b3-now-next-later.html` | **River compressed to three zones.** The time idea without a literal axis. | Three bands: **Now** (needs you, the failed deploy, music, and the standup in 18 min), **Next** (leave 12:36 for lunch, then the clash at 15:00) and **Later** (dentist, the alarm for tomorrow). Each band is a row of bento tiles, so the grid reads in time order. |
| `tile-b4-horizontal-day.html` | **A horizontal river on top, bento below.** | A wide strip under the clock shows the day from 07:00 to 23:00 as a horizontal track. A vertical now marker sits at 10:12. Events are blocks, with a leave marker and the clash hatched. Below it sits a compact pastel bento of needs you, readiness, homelab, alarm and music. |
| `tile-b5-living-bento.html` | **Bento whose sizes change with the moment.** Time drives size, not position. | Two phones side by side in one 824 × 915 file: **10:12** (needs you is big, lunch is small) and **12:30** (a huge "Leave now · 6 min" tile, needs you shrunk). Label each one. This tile tests whether "the layout adapts to the hour" is what he wants. |
| `tile-b6-glass-bento.html` | **Bento in a dark room.** The same grid as 05, but at night. | A deep dark gradient. Frosted, translucent tiles with pastel *accents* rather than pastel fills. A small "day ribbon" tile shows the next 6 hours as a mini timeline. This tests whether 05's light pastel was the problem. |

Add the usual HTML comment at the top: the hypothesis, fonts, palette, and its cost on a phone.
