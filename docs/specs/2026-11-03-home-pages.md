# Spec: Tin Home, pages beside the main one

Date: 2026-11-03. Status: build plan. Yehor uses Home on the Pixel 8a and asked for "spaces beside the main one" and a
way to place icons himself, "or another management model if there is a better one". #29 gave Home one page (glance, card
stack, widgets) and #30 gave every app a menu. This spec adds the pages and the way things get onto them. Stacked on #30
(`home-apps-manage-7c11e5`), because the app menu is where "Add to page" lives.

## Goal

Swipe sideways from the main page to more pages. A page holds the apps, shortcuts and widgets he puts there, in the order
he puts them. He places and moves them in edit mode with long-press and drag, or with a menu that does the same; both
survive a restart. Nothing changes for him until he adds a page.

Value hypothesis: the apps he opens weekly, not daily, sit one swipe from the main page instead of in the drawer, and the
main page stays as calm as it is. **It has failed if, after 7 days, an app that sits on a page is still opened from the
drawer, or he removes the pages again.**

Bold bet: widgets on pages do not make paging janky. One check (D7), one result that stops it: more than 15% janky frames
(the drawer's baseline in `scripts/home-baseline.sh`, #31) when paging with two widgets on page 2 means widgets leave the
pages; pages stay apps and shortcuts.

## Models considered (pick first)

| # | Model | What he does | For | Against |
|---|---|---|---|---|
| 1 | **Flowing pages (pick)** | Swipe to a page; things sit in the order he gave, four tiles to a row, a widget takes its own row. Drag to reorder or move to another page. | No empty cells, no overlap, no resize logic, so it holds at font scale 2.0 and on other screens; the drop target is plain geometry, so unit tests cover it without a phone; the same menu works for TalkBack | Not exact placement; a tile can't be left floating with a hole beside it |
| 2 | Free grid (stock Android) | Drop on any of 4×6 cells | What he knows from other launchers | Collisions, widget spans, what happens to a cell when the font or the screen changes; most code, least testable off a device |
| 3 | Folders on the main page | Tap a folder tile, a sheet opens | No swiping | Not a space beside the main page; a folder is one more thing to name and open |
| 4 | A page that fills itself (unconventional) | Swipe to "Lately": the apps launched most or last through Home | Nothing to place or maintain | Not "place icons himself"; cold at first. Fits the same pager as a second page kind, so a follow-up, not instead of 1 |

Why 1: it is the cheapest model that gives both asks (spaces, placing) and the only one whose placement logic is fully
checked by machine. If the 7-day trial says the order model is too loose, a free grid can replace the page body without
touching the pager, the dots, the storage of page count or the menu.

## Facts this rests on (read at `280374e`: #30 merged with main at `269b8e8`)

- `HomeFrame` is a pinned top and bottom with a scrolling stack between. A vertical swipe counts only on the stack at the
  end of its scroll (or anywhere else), never from the 24 dp side edges (Android's back gesture). It has no pager and no
  rule that a swipe must be more vertical than sideways.
- The pinned apps and the dock sit in the bottom block, so they show on every layer. "An app on the main page" is a pin;
  this spec leaves pins as they are.
- `StackWidgetView` hands only vertical drags to the stack; a widget view keeps a sideways drag for itself.
- `tuneStore` is one DataStore per process and one `edit {}` is atomic over all its keys, so one write can move a widget
  from `home.order` to a page.
- `LauncherApps.getShortcuts` answers only while Home is the default home app, and an app can drop a dynamic shortcut.
  `pinShortcuts` keeps what the launcher pinned.
- `StringsTest` (#32) needs every string in English and Ukrainian, same placeholders, all plural forms, no English words
  in Ukrainian. Tap targets are at least 48 dp and every clickable has a role.

## Scope

1. **Pager and dots.** Page 0 is today's main page, untouched. Pages 1 to 5 are extra; at most 6 in all. With no extra page
   there is no pager and no dots, the frame is as it is on main. With pages, a row of dots sits between the content and
   the pinned apps (current dot full ink, others dimmed); it is one accessibility node, "Page 2 of 3", with
   next/previous page actions. A swipe counts as a page swipe when it is more sideways than vertical; an up or down
   swipe is judged against the page on screen, with that page's own scroll.
2. **Pages model and storage.** `Pages`: an ordered list of extra pages, each an ordered list of items (`App(key)`,
   `Shortcut(pkg, id, user, label)`, `Widget(id, rows)`). Pure Kotlin: add and remove a page, add, move (within a page
   and across), remove an item, widgets in use, drop widgets the host no longer has. Stored under one new key
   `home.pages` in `tuneStore`; no key, or a corrupt part, reads as no extra pages. A `Board` update changes `home.pages`
   and the main layout in one `edit {}`.
3. **A page.** Apps and shortcuts as the drawer's tile (56 dp glass circle, themed icon, label), four to a row, flowing in
   order; a widget a full-width row of its rows (1 to 8, the main stack's height rule); a widget starts a new row. A
   shortcut tile carries a small arrow badge and reads "label, shortcut of app". A page taller than the screen scrolls.
   An empty page shows one calm line. Items that no longer exist (uninstalled app) are skipped on screen and kept in
   storage, as pins are.
4. **Placing.** Edit mode (long-press empty space, as today) on any page. On an extra page the edit bar's Add app and Add
   widget add to that page (on the main page they do what they did). The dots row gets a plus: Add page, which appends
   one and shows it. An extra page's header reads "Page 2 of 3" with Remove page (asks first when the page holds
   anything: its apps and shortcuts only leave the page, the drawer still has them; its widgets are deleted). The app
   sheet from #30 gets Add to page (choose a page or New page) and, on a page tile, Move to page and Remove from page;
   its shortcut rows get a pin button, "Add <label> to a page". A shortcut is also pinned to Home with
   `LauncherApps.pinShortcuts` so it outlives the app's dynamic set; removing it from every page unpins it.
5. **Drag.** In edit mode a long-press on a tile or a widget lifts it and it follows the finger above the pages. The
   drop goes before or after the item under the finger (left half before, right half after), at the end of the page if
   the finger is on no item. Held at the left or right edge for 0.6 s the page flips and the drag goes on; the drop on
   another page moves it there. The same moves exist without dragging: Move earlier, Move later, Move to page N,
   Remove, as accessibility actions and in the sheet. A widget on the main page gets Move to page in its edit controls;
   a widget moved to a page keeps its id and its size.
6. English and Ukrainian strings for every new string (`strings_pages.xml` in both).

Out: page names, free placement, folders, an auto-filling page, moving an app between a pin and a page, drag from the
drawer onto a page (the sheet's Add to page is that path), dragging a widget's edge to resize (the row steps stay), the
same app twice on one page, a dock per page, page backgrounds, icon packs.

## Acceptance checks

`P` = unit test, `R` = Robolectric Compose test, both run from `android/` with `./gradlew :home:testDebugUnitTest`. `D` =
device, Pixel 8a on USB, Home as the home app, run from `android/`; `F` = the throwaway fixture app (`:fixture`) so no check
touches something Yehor uses.

| # | Check | Command / method | Pass |
|---|---|---|---|
| P1 | Pages model | `PagesTest`: add up to 5 extra pages, a sixth changes nothing; remove a page; add appends; the same app twice on a page is ignored; move within a page and across pages, with the index clamped; a bad page or index changes nothing; remove an item; widgets in use; drop dead widgets; shortcuts per app; flow rows (tiles four to a row, a widget alone) | passes |
| P2 | Codec | `PagesCodecTest`: round trip of every item kind including ids with `,;|` and non-ASCII; empty string and a missing key are no extra pages; a corrupt page or item is skipped and the rest kept; a hostile 1 MB string decodes without throwing | passes |
| P3 | Drop geometry | `PageDropTest`: before or after by half; between rows; below everything is the end; an empty page is 0; the dragged item is skipped; the edge zone is 48 dp and flips only after 0.6 s | passes |
| P4 | Store | `PageStoreTest` on a file-backed DataStore as `LayoutStoreTest`: empty file reads empty; a change is read by a new store; 20 concurrent updates keep every change; pins, layout and pages share the file without touching each other; `Board.moveWidgetToPage` leaves the id in exactly one of layout and pages, and the reverse | passes |
| P5 | Widgets kept alive | `WidgetsInUseTest`: the host's sweep keeps the ids of both the layout and the pages | passes |
| R1 | Frame without pages is today's | the existing `HomeFrameTest` runs unchanged | passes |
| R2 | Frame with pages | `HomeFramePagesTest` (412×914 dp): a sideways swipe on the stack moves one page and fires no up or down; a swipe from the 24 dp edge does neither; the dots read "Page 2 of 3"; on page 2 a swipe up at the end of its scroll fires the drawer once, in the middle of it only scrolls; the dock and the search bar stay on screen at font scale 1.0 and 1.3 | passes |
| R3 | Placing | `PagesUiTest`: long-press tile 0 and drag past tile 3 moves it to the right index; drag to the right edge and hold flips the page and the drop lands there; a tap launches, a long-press (not edit mode) opens the sheet and not edit mode; the action Move later gives the same order as the drag | passes |
| B1 | Builds, tests, lints | `./gradlew :speech:testDebugUnitTest :home:testDebugUnitTest :home:assembleDebug :home:assembleRelease :home:lintDebug :fixture:assembleDebug` | all succeed; `StringsTest` and `MissingTranslation` hold |
| D1 | Swipe and dots | `adb shell input swipe 800 1300 200 1300`, then back; `adb shell uiautomator dump` | page 2 shows, the dots node reads "Page 2 of 2", the main page is back after the reverse swipe; no drawer opens |
| D2 | Add a page and an app | long-press empty space (`input swipe X Y X Y 700`), tap Add page, Add app, pick "Tin Fixture" | its tile (at least 48 dp) is on page 2 in the dump |
| D3 | Drag across pages | a second page with the fixture and one more app; `adb shell input draganddrop X1 Y1 X2 Y2 1500` from a tile to the right edge of a third page | the dump shows the tile on the page it was dropped on, in the order dropped |
| D4 | Restart | `adb shell am force-stop io.github.egoushka.tin.home`, Home, swipe | pages, items and order are as left |
| D5 | Shortcut | long-press an app with shortcuts (`dumpsys shortcut`), pin one to a page, tap it | it starts (`dumpsys activity activities \| grep -m1 ResumedActivity`) and the tile still starts it after the app is force-stopped |
| D6 | Widget on a page | Add widget on page 2 (Digital clock), start a swipe on the widget | the widget draws; the sideways swipe starting on it pages; removing it deletes it (gone from the page and from `dumpsys appwidget`); a main-page widget moved to page 2 keeps its size |
| D7 | Bold bet | `adb shell dumpsys gfxinfo io.github.egoushka.tin.home reset`; 20 sideways swipes with two widgets on page 2; `dumpsys gfxinfo ... \| grep -A1 'Janky frames'` | at most 15% janky; more stops widgets on pages |
| D8 | Ukrainian, large text | `cmd locale set-app-locales io.github.egoushka.tin.home --locales uk`, font scale 1.3 and 2.0 | every new string Ukrainian, nothing clipped, every clickable at least 48 dp |

Device checks need `adb` outside the sandbox. If no device is attached the PR lists them for Yehor instead of claiming
them. The accessibility actions are read from the semantics code, not listened to with TalkBack, as in #32.

## Open

- Page names. Numbers in v1; a name is one more string to edit and translate.
- The cap of 6. Dots and the add/remove flow work to any number; 6 is a starting value, not measured.
- Apps on the main page itself (a grid under the stack) would replace pins; not asked for.
- A shortcut tile needs Home as the default home app to resolve; without it the tile opens the app, and that fallback is
  not checked on a device.
- Model 4 (the self-filling page) is the next cheapest thing to try beside this one.
