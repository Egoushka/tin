# Home style board, November 2026: brief for every tile

Yehor asked for a **new direction** for Home, his phone launcher, which runs on a Pixel 8a with GrapheneOS and is used with one hand. The current design is "Night glass" (`../night-glass/`). This board shows about 12 strongly contrasting directions. Every tile has **the same content**, so he can choose by recognising the look he wants. Use real content only, never lorem.

## What he has said before (follow it)

- v1, OLED black with stock filled rounded buttons: rejected as "looks like a uni project".
- v2, three directions that were variations inside one box: "i don't like each". **Tiles must differ in layout concept and type, not only in colour.**
- An earlier board of 12 style tiles: rejected because it had "no quality of life things… it needs to be functional like any other phone and bring my own customization on top". **Every tile must show the full working Home**, not a mood swatch.
- Hand-drawn icons were rejected. Use **Lucide** icons only, as inline SVG with one stroke width per tile (copy the path data from `../night-glass/lucide-icons.json`), or no icons at all if the direction is type-only.
- He likes weight for emphasis: figures and names that matter at 600 or heavier, body at 400. Chips are at least 24 px tall with vertical padding. He dislikes coloured rails on list items, and heavy saturated buttons on secondary actions.
- Night glass principle he approved: colour only means attention (one meaning per colour). A tile may break this, but only if it says so in its caption.

## Frame

- One self-contained HTML file per tile, `tile-NN-slug.html`. No build step. Fonts may come from Google Fonts `<link>`.
- The phone screen is **412 × 915 CSS px** (the 8a in dp). Set the body to exactly that: `margin:0; width:412px; height:915px; overflow:hidden`. Do not draw a phone bezel. Draw a status bar at the top (time 10:12, wifi, battery 82%) and the gesture pill at the bottom.
- Keep 24 px clear at the left and right edges for touch (the back gesture), and keep 48 px hit targets. Put the search bar in the lower third, under the thumb.
- Add an HTML comment at the top: direction name, a one-sentence idea, fonts, palette as named hex values, and what it costs or risks on a real phone.

## Content: identical on every tile

Stock job:
- Clock **10:12**, date **Sat, 14 Nov**, weather **7° Cloudy**.
- Alarm: **07:30 · Tomorrow · Sun**.
- Music: **Music for Airports — Brian Eno**, prev / pause / next, earbuds **L 80 · R 76%**.
- Notifications: **Signal 3 · Gmail 5 · Slack 2**; latest **Mom: "Call me when you're free" · 4 min**.
- Dock with 5 apps: Phone, Messages, Camera, Browser, Maps.
- Search bar **"Search or ask"** with a mic. The mic is the voice assistant, so do not add a separate assistant button.

His layer:
- **Today**, 4 events: 10:30 **Standup** (now-ish: "in 18 min"); 13:00 **Lunch, Podil**, "Leave 12:36 · 24 min drive"; 15:00 **Sprint review**, "Overlaps Dentist by 30 min" (a clash, so warn); 15:30 **Dentist**, 45 min.
- **Needs you**: **4**, made of 2 PRs to merge, 1 approval and 1 reply owed. Age: "1 min". This is the attention item.
- **Readiness** (Oura): **62**, sleep **6 h 12 m**, as of **07:40**; nudge (written by a model, so italic): *"Low recovery. Keep today light."*
- **Homelab**, box **hedzer**: broken: **"1 deploy failed · nytka"**. This is the error item.

The hierarchy has to read in a glance: what needs me, then when to leave, then the rest.
