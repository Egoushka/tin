# Spec: Home that fits, is yours, and hosts widgets

Date: 2026-11-03. Status: draft for Yehor's review; builds on `docs/specs/2026-11-02-home-launcher.md` (Home, slices 1–6).
Design source: `docs/design/night-glass/` (`cards/Gestures.md`, `cards/Tune.md`, `Gestures.png`).

## Goal

Yehor called Home "really not usable" as a daily launcher. Three things in the build cause it, all in the shape of the
screen, none in a card:

1. Home is a fixed column of six cards, a spacer, the dock and the search bar, with no scroll. When the cards are
   taller than the space (font scale 1.3, an agenda with five events, a long Needs you list) the dock and the search bar
   are pushed off the screen and Home cannot be used at all.
2. The cards are not his. Six are always there, in one order, and a card whose permission is missing takes a full card
   of space to ask for it.
3. There is no place for the apps he opens all day, and no way to host an Android widget.

This spec makes the screen work: the card area scrolls and the thumb zone stays put, the cards can be hidden and
reordered, apps can be pinned above the dock, and widgets live in the card stack.

Value hypothesis: with the dock and the search bar always in thumb reach and a stack that is his, Yehor stops reaching
for the stock launcher. Cheapest test: use this Home for 7 days at font scale 1.3 and note each time he leaves it.
**It has failed if the stock launcher comes back, or if a card he hid or a widget he added is lost after a reboot.**

## Facts this rests on

- Today `Home` in `ui/Home.kt` is `Column { Glance, six cards, Spacer(weight 1f), DockRow, SearchBar }`. Nothing
  scrolls; `Spacer(weight)` takes whatever is left, which is nothing when the cards are tall, and the dock and the
  search bar are measured last, so they are the part that gets cut.
- Gestures (`cards/Gestures.md`, `Gestures.png`): they start on empty Home space only; the 24 dp side edges belong to
  Android's back gesture; every gesture has a visible route; long-press on empty space is "Edit Home: widgets, dock,
  gestures"; on a widget a swipe scrolls or acts on that widget. Today a long-press opens Tune and a vertical swipe of
  one target (48 dp) opens the drawer (up) or the notification shade (down).
- Tune is a Preferences DataStore (`tin.tune`). The layout, the pins and the widget ids go in the same DataStore, under
  keys of their own, so they survive a restart with the knobs and "Reset" in Tune does not wipe them.
- Android hosts widgets with `AppWidgetHost` and `AppWidgetManager`: a host allocates an id, the id is bound to a
  provider (`bindAppWidgetIdIfAllowed`, or the `ACTION_APPWIDGET_BIND` permission dialog when that returns false), a
  provider with a configure activity is configured through `AppWidgetHost.startAppWidgetConfigureActivityForResult`,
  and the host deletes an id when the widget is removed. The system keeps every allocated id until the host deletes it,
  so an id that is not in Home's layout is a leak and an id in the layout that the host no longer knows is a ghost.
- Providers are subject to package visibility: Home must declare `android.appwidget.action.APPWIDGET_UPDATE` in
  `<queries>` to list them.
- The phone is a Pixel 8a, 1080 × 2400 px at 420 dpi = 411 × 914 dp, so 412 × 915 dp is the real screen.

## Scope

### 1. Never clip

- Home's frame is **top** (the glance), **stack** (`weight(1f)`, scrolls) and **bottom** (pins row, dock, search bar,
  pinned to the screen's bottom edge above the navigation bar). The stack is the only part that gives way.
- The stack scrolls vertically. The vertical swipes keep their meaning only at the scroll edges: a drag that **starts**
  with the stack already at its top and goes down opens the shade; one that starts with the stack at its bottom (or when
  everything fits) and goes up opens the drawer; a drag in the middle of the list only scrolls. Each needs one target
  (48 dp) of travel, as today, and a drag that starts in the 24 dp side edges starts nothing.
- The glance (clock, date) stays where it is, above the stack, so tin-weather's call site is unchanged.

### 2. Cards are yours

- Slots in the stack: the six cards (Needs you, Today, Glance, Music, Alarm, Homelab) and any number of widgets. The
  default order is today's order, all visible.
- **Edit Home** is a mode of Home, not a layer. A long-press on empty Home space enters it (it replaces the
  long-press-opens-Tune of slice 1; Tune is one button away in the edit bar, so nothing becomes unreachable). Its other
  route is a visible "Edit Home" button at the end of the Tune sheet.
- In edit mode each slot gets a header with its name and 48 dp controls: up, down, hide (cards) or remove (widgets);
  card taps are off so a card cannot fire while it is being arranged; widgets add smaller and taller. A "Hidden" tray
  under the stack lists the hidden cards, tapping one brings it back in its old place. The edit bar holds Add widget,
  Add app, Tune and Done. Reordering uses buttons, not dragging: a drag competes with the scroll and has no visible
  route.
- A card whose source lacks a permission shows one 48 dp **grant row** (icon, "Allow calendar access", a hide
  control) in place of the full card: Today without READ_CALENDAR, Glance without the notification-listener grant. The
  row is the same slot, so hiding it hides the slot, and the Hidden tray brings it back. `Cards.kt` is not touched; the
  decision is made in the layout code from the same signals the cards read.
- The layout is `order` (slot codes), `hidden` (slot codes) and `rows` (widget id → height in cells). Unknown codes
  are dropped on read; a card missing from `order` is appended, so a future release can add a card.

### 3. Pinned apps on Home

- A row of the user's pinned apps above the dock: 56 dp round glass tiles with the themed monochrome icon, like the
  drawer and the dock; at most 12 (starting value), one row, scrolls sideways past about five; absent when empty.
  Tap launches; **long-press unpins**, with haptic feedback. Apps that are no longer installed are skipped, not deleted
  from the store (a work profile can be locked for a while).
- Stored as app keys (`AppEntry.key`), in pin order.
- `pinToHome(context, app)` (in `layout/PinStore.kt`) is the call tin-apps-manage makes from its long-press menu; it
  needs no activity. `unpinFromHome` is its pair. Until that menu lands, edit mode's **Add app** (a plain A–Z list)
  is the way in.

### 4. Android widgets

- `AppWidgetHost` with a fixed host id, listening while Home is started. Edit mode's **Add widget** opens a list of the
  installed providers (label, app). Choosing one runs the id lifecycle below; the widget joins the end of the stack.
- A widget is a glass-framed cell, the card width wide, `rows` × 70 dp − 30 dp tall (the stock cell rule), with
  `updateAppWidgetSize` told its dp size; in edit mode smaller and taller change `rows` within the provider's resize
  limits.
- **Id lifecycle** (`WidgetLifecycle`, pure, with the Android calls behind `WidgetBackend`): allocate → bind (permission
  dialog if needed) → configure (if the provider has a configure activity) → add to the layout. A refused bind or a
  cancelled configure deletes the id. Removing a widget deletes its id and removes it from the layout. At every start,
  **sweep**: ids the host holds that the layout does not list (and that are not mid-flow) are deleted, and layout
  widgets the host no longer knows are dropped.
- A widget whose provider is gone (app uninstalled) shows one compact row "Widget unavailable" with a remove control;
  it is dropped by the sweep once the host forgets it.

### Out

Drag-to-reorder, widget previews in the picker, widgets in the dock, folders, per-widget gestures, a second Home page,
and the polish of card internals (`Cards.kt` is another session's).

## Coordination

- tin-apps-manage: calls `pinToHome(context, app)` from its menu; it edits `DockRow` and `Drawer.kt`, this change only
  wraps `DockRow` in the bottom block and touches neither.
- tin-weather: the glance call site stays above the stack.
- tin-search and tin-voice-engine: `SearchBar` keeps its signature and sits at the bottom of the bottom block.
- tin-motion: `Cards.kt` and `LayerCard.kt` are untouched.
- `HomeScreen` gains one parameter (`home`), `HomeActivity` one field; everything else lives in the new `layout/`
  package.

## Acceptance checks

Machine-run checks come first; the build and test commands run from `android/` with JDK 21
(`JAVA_HOME=…/jbr-21.0.8/Contents/Home`), outside any sandbox that cannot write `~/.gradle`.

| # | Check | Command / method | Pass |
|---|---|---|---|
| L1 | Layout model defaults | `HomeLayoutTest` | default order is the six cards in slice-1 order, nothing hidden |
| L2 | Reorder and hide | `HomeLayoutTest` | up/down swap with the nearest *visible* neighbour and clamp at the ends; hidden slots keep their position and come back there; a widget can be removed but not hidden |
| L3 | Layout encoding | `HomeLayoutTest` | encode → decode is the identity; unknown codes, duplicates and malformed rows are dropped; a missing card is appended; empty or garbage input gives the default |
| L4 | Layout survives a restart | `LayoutStoreTest`, a file-backed DataStore | a change made through one store instance is read by a new instance over the same file; concurrent updates do not lose a change |
| P1 | Pins store | `LayoutStoreTest` | pin appends, a repeat is ignored, unpin removes, the 12 cap holds, order is kept across a new instance over the same file |
| P2 | Pins resolve | `PinResolveTest` | keys resolve to installed apps in pin order; unknown keys are skipped and kept |
| W1 | Widget id lifecycle | `WidgetLifecycleTest`, a fake backend | bound without configure → added; bind refused → permission step → granted → added; permission denied → id deleted; configure needed → OK → added; configure cancelled → id deleted; allocation failure → no id left |
| W2 | Sweep | `WidgetLifecycleTest` | host ids not in the layout and not pending are deleted; layout widgets the host lost are dropped; the pending id survives a sweep |
| W3 | Widget removal | `WidgetLifecycleTest` | remove deletes the host id and the layout entry together, and is safe to repeat |
| C1 | Dock and search stay on screen | `HomeFrameTest` (Compose under Robolectric, 412 × 915 dp), at font scale 1.0 and 1.3 | with 0, 6 and 12 tall cards the dock row and the search bar are fully inside the window and in the same place; the stack is shorter than its content with 12 cards |
| C2 | Every card is reachable | `HomeFrameTest` | scrolling the stack to its end puts the last card fully inside the stack's bounds; the first card starts below the glance |
| C3 | Swipes only at the edges | `HomeFrameTest` | swipe up at the end of a long stack → drawer once; swipe down at its top → shade once; a swipe in the middle of the list only scrolls and opens nothing; with everything fitting both directions fire; a swipe that starts in the 24 dp edge fires nothing |
| C4 | Grant row is one row | `GrantRowTest` | the grant row is 48 dp tall and a card is not composed in its place |
| B1 | Build, unit tests, lint | `cd android && ./gradlew :home:testDebugUnitTest :home:assembleDebug :home:lintDebug` | passes; no new lint error; `MissingTranslation` is an error, so every new string has `values-uk` |
| B2 | CI set | `./gradlew :speech:testDebugUnitTest :home:testDebugUnitTest :home:assembleRelease --no-daemon` | passes |
| B3 | Tokens untouched | `TokensTest` | passes: no icon outside `lucide-icons.json` was added |
| D1 | No clipping on the phone | Pixel 8a, debug build, `adb shell settings put system font_scale 1.3`, `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME`, `adb shell uiautomator dump /sdcard/h.xml` | the bounds of the search bar and of the five dock tiles end above the navigation bar's top (2400 px minus the gesture-bar inset) at font scale 1.0 and 1.3 |
| D2 | Edit mode keeps the layout | long-press empty space (`adb shell input swipe 540 1500 540 1500 700`), hide Today, move Homelab up, Done, `adb shell am force-stop io.github.egoushka.tin.home`, Home | Today is gone and Homelab is in its new place after the restart; Hidden tray in edit mode lists Today; "show" restores it where it was |
| D3 | Pins | Add app → pick one, `am force-stop`, Home; long-press the pin | the pin is back after the restart, tap launches the app, long-press removes it |
| D4 | Clock widget | Add widget → Clock → allow the bind dialog; `adb shell dumpsys appwidget` before and after | the widget shows in the stack and `dumpsys appwidget` lists one id for `io.github.egoushka.tin.home`; after `am force-stop` and Home it is still there with the same id; Remove drops it and the id disappears from `dumpsys appwidget` |
| D5 | No leaked ids | add a widget, cancel its configure or deny the bind; `dumpsys appwidget` | no id remains for Home |
| D6 | Swipes on the device | with the stack scrolled to the bottom, `adb shell input swipe 540 1700 540 1400 150`; at the top, `… 1400 1700 150` | the first opens the drawer, the second the shade; the same swipe mid-list only scrolls |

## Open

- Grid width of the pins row (one scrolling row at 12 pins) is a starting value; revise after a week of use.
- Widget `rows` default comes from the provider's `minHeight`; widgets that ignore `updateAppWidgetSize` may draw their
  own size.
