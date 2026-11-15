---
name: Tin Home — Living bento
description: Yehor's one-handed phone Home. A pastel bento whose tile sizes follow the moment; the dock and search never move.
colors:
  # Light set (system light theme). Contrast is WCAG 2 against the surface named, checked by ContrastTest.
  wall-1: "#FBE4D6"        # wallpaper gradient, top
  wall-2: "#E6DCF7"        # middle
  wall-3: "#CFE6F5"        # bottom
  ink: "#1E1A2E"           # 11.8:1 on lilac, 6.5:1 on tangerine
  muted: "#5E5873"         # ≥4.7:1 on every tint below
  needs: "#FF7A3D"         # Needs-you fill; text on it is ink (white would be 2.6:1)
  leave: "#0B7A6A"         # Leave-now fill; white text 5.2:1
  next: "#4B3FD8"          # next-event figure; 6.8:1 on cream
  clash: "#A64B00"         # clash text; 5.6:1 on cream
  fault: "#A81E17"         # broken text and dot; 5.4:1 on rose
  tint-time: "#DCD3F8"     # clock (lilac)
  tint-sky: "#CBE7F7"      # weather
  tint-alarm: "#FBEBB0"    # alarm (butter)
  tint-event: "#FFFBF4"    # agenda (cream)
  tint-health: "#CDEFD9"   # readiness (mint)
  tint-lab: "#FFD3D1"      # homelab (rose)
  tint-music: "#F8D3E3"    # music
  tint-notes: "#D6E6FA"    # notifications
  glass: "#FFFFFFB8"       # dock keys and the search bar
  # Dark set (system dark theme): the same names. Tints are 14% of the light tint over bg #16131F.
  # ink #F3F0FA (≥10.9:1), muted #ABA5BF (≥5.2:1), needs #FF8A52 with ink-dark #1E1A2E (7.3:1),
  # leave #3DD6BE with ink-dark (9.3:1), fault #FF8A80 (5.7:1 on dark rose); light-set tints become the accents (≥9.2:1).
typography:
  # One family: Plus Jakarta Sans, bundled (400, 500, 700, 800 only). Tabular figures everywhere.
  display: { fontSize: "56sp", fontWeight: 800, lineHeight: "56sp" }  # the lead figure: "4", "6 min", "18 min"
  clock:   { fontSize: "44sp", fontWeight: 700, lineHeight: "48sp" }
  title:   { fontSize: "17sp", fontWeight: 700, lineHeight: "22sp" }  # names: event, track, box
  body:    { fontSize: "14sp", fontWeight: 400, lineHeight: "20sp" }
  label:   { fontSize: "13sp", fontWeight: 500, lineHeight: "16sp" }  # tile labels, sentence case, muted
rounded: { tile: "24dp", key: "20dp", pill: "999dp" }
spacing: { 1: "4dp", 2: "8dp", 3: "12dp", 4: "16dp", 6: "24dp" }
grid: { columns: 4, gap: "12dp", gutter: "24dp", unit: "82dp" }  # (412 − 2·24 − 3·12) / 4 = 82 on the Pixel 8a
components:
  tile: { rounded: "{rounded.tile}", padding: "16dp" }
  chip: { height: "28dp", padding: "6dp 10dp", rounded: "{rounded.pill}", typography: "{typography.label}" }
  key: { size: "56dp", rounded: "{rounded.key}", fill: "{colors.glass}" }
---

## Overview

Home is a bento of tiles on a soft pastel wallpaper. The **moment decides each tile's size**: the thing that matters now is the big tile, and everything else shrinks but stays where your thumb expects it. At 10:12 Needs you is 2×2. At 12:30, before a drive, "Leave now · 6 min" spans the full width and Needs you drops to 2×1. The dock and the search bar never move. This is the round 2 pick (b5) from `docs/design/board-2026-11/`.

## Colors

Each kind of tile keeps its own pastel tint (lilac for time, butter for the alarm, mint for health, rose for homelab and so on), so a tile is recognised by colour before it is read. **Attention is saturation plus size**, not colour alone. Only three fills are saturated: tangerine for Needs you, teal for Leave now and red for a fault. Everything else stays pastel. System light gives the light set and system dark gives the dark set (the 2026-11-04 decision: follow the system).

## Typography

There is one family, Plus Jakarta Sans, with four weights that each have a job: 400 for body text, 500 for labels, 700 for names and the clock, and 800 for the one lead figure in a big tile. Labels are sentence case and muted. They are never all-caps tracked eyebrows.

## Layout

```
┌──────────────────────────────┐  status bar
│ ┌──────────┐ ┌──────┐┌─────┐ │
│ │ Needs you│ │10:12 ││ 7°  │ │  row 1–2: the lead tile (2×2) + clock (2×1)
│ │    4     │ ├──────┤├─────┤ │           + weather/alarm (1×1 each)
│ │ 2 PRs…   │ │ ⏰7:30││  …  │ │
│ └──────────┘ └──────┘└─────┘ │
│ ┌──────────┐ ┌─────────────┐ │
│ │ Standup  │ │ 13:00 Lunch │ │  next event big (2×2) when < 30 min away;
│ │  18 min  │ │ 15:00 Sprint│ │  the rest of the day as 2×1 rows
│ └──────────┘ └─────────────┘ │
│ ┌──────────────────────────┐ │
│ │ ♪ Music for Airports ⏮⏸⏭│ │  4×1 while playing
│ └──────────────────────────┘ │
│ ┌──────────────────────────┐ │
│ │ Signal 3 · Gmail 5 …     │ │  4×1
│ └──────────────────────────┘ │
│  ( ☎ )( ✉ )( ◉ )( ◍ )( ⌖ )   │  dock: fixed
│ [ 🔍 Search or ask      🎙 ] │  search: fixed
└──────────────────────────────┘
```

The grid has 4 columns with an 82 dp unit, a 12 dp gap and 24 dp gutters (the side edges belong to the back gesture). Tiles pack densely in a stable order. The sizing rules are in `docs/specs/2026-11-15-home-living-bento.md`.

## Elevation & Depth

The tiles are flat pastel with no shadow. The dock keys and the search bar are white glass (72% white) with a soft shadow, because they float over the moving grid.

## Shapes

Tiles have a 24 dp radius, keys 20 dp and chips and pills are fully round. A tile that grows keeps its radius; it never morphs its shape.

## Components

- **Lead tile:** a saturated fill (needs or leave), the display figure, a muted label and up to three rows.
- **Tile:** a pastel fill, a label, a title and a body line.
- **Chip:** 28 dp tall, used for notification counts.
- **Key:** a 56 dp dock key.
- **Transport:** the music controls are 48 dp, with the play state as an ink-filled circle.

## Do's and Don'ts

- Do keep the dock and search fixed. Do animate a size change once, using the emphasized easing (400 ms), with no animation under reduced motion.
- Don't use all-caps eyebrows, " · " joins on every line, or a big number on a tile that isn't leading.
- Don't change a tile's size more than once in 5 minutes unless something new arrives.

## Kept on purpose

- 2026-11-15: Living bento (b5) chosen over the other round 2 tiles and over Night glass. Pastel tiles, sizes follow the moment.
- 2026-11-02, from Night glass: Lucide icons at one stroke; function first (the stock job is done in full before the personal layer); every number shows its age.
