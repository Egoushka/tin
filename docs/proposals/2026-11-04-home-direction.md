# Home direction: frankenstein, fork, or stop building a launcher

2026-11-04 · proposal by Claude, not a decision · inputs: `docs/results/home-baseline-feedback-2026-11-03.md` (F1–F19),
`docs/results/2026-11-03-home-baseline.md`, the Night glass mock (`docs/design/night-glass/cards/HomeNightGlass.png`),
the phone-shell dataset (`personal/research`, `phone-shell/facts.jsonl`, ids `FA-…`), and a web teardown of 19
launchers done today (sources linked inline).

## Outcome (2026-11-04, Yehor)

None of the options below as written. Yehor chose to keep Tin Home and put Kvaesitso's engine under it: "take their cores and make
our own implementation on top of that", "Yes, inside tin". The plan is in `docs/specs/2026-11-04-home-on-kvaesitso.md` and the
harvest map. The rest of this page is the proposal as it stood before that answer.

## Bottom line

Don't build the frankenstein as a new launcher. You're right that it would never end, and Home is already a
frankenstein, which is part of why it feels bad. My pick: **spend a few days living on the best existing launchers, then
ship tin's own layer (Needs you, today, homelab, voice) as widgets and tiles that work on any launcher.** Only if no
launcher fits, reshape Tin Home around one core idea and cut most of the rest. Don't start from scratch either way.

## Why Home feels bad (from evidence, not taste)

You couldn't name it, so here are the causes I can show, biggest first.

1. **The mock lied.** The Night glass mock was drawn on a full day: four events, "Needs you 4", a readiness ring, a
   failed deploy, music. On Tuesday 3 Nov the phone showed five cards, and four of them were empty or broken:
   "Approvals isn't sharing yet", "Nothing else today", "Gate paused: no answer", "Homelab not reachable". A third of
   the screen below them was blank (F5, F6). A dashboard Home is only as lively as its data, and on most days there is
   little data. That is the normal day, so the normal Home looks dead.
2. **No app is one move away.** The main page holds no apps. Anything outside the five dock tiles takes a swipe up and
   then a scroll or typing (F5). Muscle memory, not looks, makes a launcher feel fast: the thumb goes to the same spot
   and the app opens.
3. **Every icon looks the same.** Monochrome glyphs on identical dark discs strip out colour and shape, which is how
   your eye finds an app before reading its label. Some icons are blank (Privat24) and some show the stock robot
   (Approvals, tin) (F8). Four big columns fit 28 apps per screen (F9).
4. **It broke trust once, and badly.** A long press crashed Home three times, and Android dropped it as the default Home
   (F1, F2). One crash that costs you your Home outweighs every bit of polish.
5. **The build you use is unmeasured and untuned.** The debug build measured a 1,398.5 ms median start and 15.25% janky
   drawer frames (`2026-11-03-home-baseline.md`). The release build you run (0.1.34) has no numbers. It is also built
   without R8 (`isMinifyEnabled` is not set in `android/home/build.gradle.kts`) and without a baseline profile, so it
   ships with less tuning than any of the launchers below. The blur isn't the suspect: Frost renders once per knob
   change into a shared bitmap (`theme/Glass.kt:80-96`).
6. **It fights the system.** Home is always dark while the phone is light (F18). It shows no wallpaper (F14), its
   sheets are see-through (F7), and swipe-home uses the stock fallback animation (F19). Home doesn't implement
   `GestureNavContract` (below), the standard way a third-party launcher gets a proper swipe-home landing.
7. **It already is a frankenstein.** In two days (22 commits on 2–3 Nov) Home gained cards, a dock, a drawer, side
   pages, widgets, pins, seven reassignable gestures and about ten Tune knobs (frost, tint, room light, density, and
   more). No single idea leads, so there is nothing simple to learn.

## The frankenstein idea, critiqued

- **Cores don't add up.** Each launcher's core works because everything else in it serves that core. Niagara's list,
  AIO's feed and Kvaesitso's search each want the whole home screen. Glue three cores together and you get three half
  cores fighting for the thumb. Point 7 above is that result.
- **The core isn't what takes forever; everything around it is.** F8–F17 are the long tail: icon fallbacks, an A–Z
  index, folders, widget previews, backup, wallpaper, work profile, drag and drop. Lawnchair has 280 contributors
  (FA-BIZ-007), and users still say "it likes to forget things on upgrades"
  ([Lemmy](https://lemmy.nekusoul.de/post/1539736)). Nova shrank to one developer after the 2024 layoffs. Instabridge
  bought it in January 2026, and the next release brought crashes and ad trackers
  ([Android Authority](https://www.androidauthority.com/nova-launcher-status-after-layoffs-3470026/),
  [CC Startup](https://ccstartup.com/blog/2026/01/27/nova-launcher-users-are-seeing-bugs-as-new-owners-take-over)).
- **Reading can't settle "I can't say why".** Research can tell you what each launcher's core is. Only your thumb can
  tell you which one feels right.
- **A different stack doesn't help.** The problems are design and tuning. Kotlin and Compose are the right tools here;
  every launcher below is Kotlin or Java.

## What each launcher's core is

| Launcher | Core idea, concretely | Status (2026) | Can we use it |
|---|---|---|---|
| Niagara | One vertical list of chosen apps under the thumb; the rest sit behind a right-edge A–Z scrubber you slide along ([Xataka](https://www.xatakamovil.com/sistemas-operativos/he-vuelto-a-instalar-este-launcher-minimalista-mi-android-mejor-decision-que-he-tomado-para-desengancharme-movil/amp)) | Maintained; closed source; Pro costs 179.99 UAH a year [marketing] | Install only |
| Kvaesitso | Search first: an endless home scroll of widgets, and typing finds apps, contacts, calendar and files ([GitHub](https://github.com/MM2-0/Kvaesitso)) | v1.41.0, 2026-09-15; GPL-3.0, Kotlin and Compose | Install; plugin SDK (Apache-2.0) for weather, file, contact, places and calendar ([guide](https://kvaesitso.mm20.de/docs/developer-guide/)); fork has to stay GPL |
| KISS | Type two letters; the results you open most rank first ([GitHub](https://github.com/Neamar/KISS)) | v3.24.2, 2026-03-11; GPL-3.0 | Steal the ranking idea |
| AIO | Home is a scroll of one-line text widgets that expand in place when tapped; apps are sorted by launch count ([review](https://www.androidauthority.com/aio-launcher-3607681/)) | Active; Lua script extensions | Closest to Tin Home; praised for in-place expansion, criticised as "rather overwhelming" |
| Olauncher | A few text-only apps; long-press the clock or date to choose what it opens ([GitHub](https://github.com/tanujnotes/Olauncher)) | v6.9.17, 2026-09-17; GPL-3.0 | Steal the friction-free text list |
| Lawnchair | Pixel Launcher behaviour on Launcher3 ([docs](https://docs.lawnchair.app/developers/architecture/project-overview)) | Pre-releases only; nightly 2026-11-02 | Install; shows how to use `GestureNavContract` |
| Smart Launcher 6 | Apps auto-sorted into category pages | Release date UNKNOWN | Skip |
| Ratio | Collapsible drawers that show time per app; a card feed opened by typing | Closed, subscription, trust problems | Skip |
| Nova | Grid with endless settings | Sold; buggy | Skip; a warning about where deep settings lead |
| Pixel, One UI, Nothing, iOS | Vendor homes; iOS 18 lets apps and widgets sit in any open space | Closed | Reference only |
| AI-first launchers | None with real traction found; the biggest has about 17k installs (Play, via a search summary) | — | The AI layer is not what people pick a launcher for |

Swipe-home without root: a third-party launcher can't own Recents. On Android 11 and later it can still get the
landing animation through `GestureNavContract`: on swipe-home, Home receives `onNewIntent` with the extra
`gesture_nav_contract_v1` and reports where the closing app should land
([AOSP commit](https://android.googlesource.com/platform/packages/apps/Launcher3/+/30ac97d9386736c3322a3277f74523497627b9f5),
[Lawnchair docs](https://docs.lawnchair.app/troubleshooting/gesture-navigation-issues)). Whether this works on
GrapheneOS with Android 17 is UNKNOWN.

## Options, ranked

### 1. Use a polished launcher, plug tin's layer into it (my pick)

- **Value hypothesis:** the feel you want already exists in a launcher that someone else polishes full time. Tin's own
  value is the layer (what needs you, what's next, whether the box is up, voice), not the app grid.
- **How:** first a taste test. Spend one day each on Niagara (Play), Kvaesitso (GitHub, Obtainium) and Lawnchair. Each
  evening, write one line: the best moment and the worst moment. Then use the winner for 7 days. Meanwhile the layer
  becomes plug-ins:
  - **Needs you as an Approvals home widget.** It ships from Approvals, the app that owns the data, so F4's signing
    problem goes away.
  - **A Smartspacer target** for the glance line. Smartspacer needs Shizuku (FA-SHL-043), and tin already runs it.
  - **The existing `PushToTalkTile`** for voice.
- **Cost:** no code for the taste test. The widget is one slice in Approvals. Home's 8,171 lines are parked, not
  deleted.
- **Check and stop:** at day 7, did you go back to Tin Home, or do you miss one specific thing from it? If yes, that
  thing is Tin Home's real core, and option 2 is built around it.
- **Open questions:** whether Kvaesitso hosts third-party app widgets (I didn't check). Niagara is closed and comes from
  Play, which runs sandboxed on GrapheneOS. One Kvaesitso user reported 27% battery in a day and another under 3%
  ([Lemmy](https://lemmy.nekusoul.de/post/1539736)).

### 2. Reshape Tin Home around one core (the strongest version of your frankenstein)

- **The screen:**
  - **Top:** a small clock, then one glance line showing the single most urgent thing: an event in the next 2 hours, a
    Needs you count, or a broken box. On a quiet day the line is empty.
  - **Middle:** a Niagara-style list of 6–8 apps in their own colours, on the right-thumb side, with an A–Z scrubber on
    the edge.
  - **Bottom:** a search bar that ranks results by what you open (KISS) and also finds approvals, contacts and
    calendar items (Kvaesitso).
  - **Cards** move to a pinch-in page, are one line each, and expand in place (AIO).
- **Cut:** most Tune knobs (keep two), the gestures from seven down to three, glass for flat, side pages and room
  light. **Keep:** the app list, search, the notification listener, the widget host and voice.
- **Add:** `GestureNavContract`, R8 and a baseline profile.
- **Check and stop:** measure the release build with `scripts/home-jank.sh` and the start time against Kvaesitso on
  the same phone. If its jank stays above Kvaesitso's, or after 7 days you would rather use Kvaesitso, stop and go to
  option 1.

### 3. Fork Kvaesitso

- **What you get:** the long tail for free, plus full control. Kvaesitso is maintained and uses the same stack.
- **What it costs:** the fork has to stay GPL and live in its own repo (tin stays MIT), and you merge upstream for as
  long as you use it. A smaller eternity, but still one.
- **When:** only if option 1 shows Kvaesitso is 90% right and the layer can't plug in from outside.

### 4. No Home of yours at all (the unconventional one)

Keep the stock launcher and put the layer where you already look. One ongoing "Needs you N" notification, the
Smartspacer glance on the lock screen and Home, and Quick Settings tiles. This is option 1 without the launcher hunt.
It also survives the next phone or ROM. The cheapest test is one ongoing notification for a week.

## If Home stays your default for even one more day

These fix real harm, whichever option wins:

- **F1:** the missing `home` glyph, plus a fallback in `Lucide.icon`. Without it the crash keeps costing you the default
  Home.
- **F5:** put 6–8 apps on the main page.

Both are outside this proposal's ask.

## What we learned

- **A dashboard mock has to be drawn on a quiet day too.** The Night glass mock showed only a full one. This lesson
  belongs in tin's AGENTS.md as a design gotcha.
- **`GestureNavContract` (Android 11+) gives a third-party launcher a swipe-home landing without root.** Source: the
  AOSP commit above.
- **Kvaesitso's plugin SDK is Apache-2.0, while its app is GPL-3.0.** Source: its developer guide.
- **Nova was sold to Instabridge in January 2026, and the next release was buggy.** Source: CC Startup.
- **No AI-first launcher had real traction as of 2026-11-04.** Source: the teardown; this is a negative result, so it
  is weaker evidence.
- **Home's release build has no R8 and no baseline profile.** Source: `android/home/build.gradle.kts`.
