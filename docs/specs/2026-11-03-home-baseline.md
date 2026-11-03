# Home baseline

Against main (`7f43974`, after #38). Scope: `android/home`. Source: `docs/results/home-baseline-feedback-2026-11-03.md`, an on-device walk-through of Home 0.1.34 on a Pixel 8a (findings F1 to F19). Yehor chose to start the baseline with the must-haves below.

This file was committed before any code. Every check is a test that fails on `7f43974` and passes on the branch; the Result column is filled in by later commits. Compose checks run under Robolectric on a 412×914 dp screen like `HomeFrameTest`.

Out of scope: a settings screen, backup and restore, folders, drag to rearrange, widget previews, wallpaper, motion polish (F12 to F19 except where a row below says otherwise).

## Layers

Each layer is one commit. Layers 4 to 9 get their rows here when they start, before their code.

| # | Layer | Findings |
|---|---|---|
| 1 | The long-press menu stops crashing | F1, F2 |
| 2 | A swipe up on a dock tile never opens All apps | F3 |
| 3 | Home hides the Needs-you card while Approvals refuses it access | F4 |
| 4 | Apps on the main page; cards with nothing to act on are hidden | F5, F6 |
| 5 | Sheets are opaque; Tune sits below the status bar | F7 |
| 6 | Icons: a full-colour fallback and Tin's own icons | F8 |
| 7 | The drawer gets an A to Z scroller; labels are not cut off | F9 |
| 8 | A covered layer leaves the screen-reader tree | F11 |
| 9 | A dock slot with no app is hidden or offers "Choose an app" | F13 |

## Layer 1: the menu

F2 (Android dropping Home as the preferred Home) follows from F1: the system clears the preference after repeated crashes of the HOME activity. Nothing in Home sets it, so the fix is that the menu cannot crash.

| ID | Claim | Test | Result |
|---|---|---|---|
| B1 | Every icon name written as a literal in `android/home/src/main` is a key of `Lucide.bodies` | `LucideTest` | pass |
| B2 | `Lucide.icon` with a name that has no body returns a glyph instead of throwing | `LucideTest` | pass |
| B3 | `AppSheet` composes in every branch (on the dock or not, pinned, unpinned, full pins, with and without page actions, choosing a slot) and shows its Info and Hide rows | `AppSheetTest` | pass |

## Layer 2: dock swipes

G10 and G11 of the gestures spec say a swipe that starts on a dock tile never opens the drawer. `DockSwipe.of` returned null for a docked app that lists no shortcuts, so the tile did not own the swipe and the frame opened All apps. `DockSwipeTest` composed a bare box and never the real tile.

| ID | Claim | Test | Result |
|---|---|---|---|
| B4 | On the real `DockTile` with a docked app that lists no shortcuts, a swipe up opens neither All apps nor the long-press menu and is not a tap; a tap still launches the app | `DockTileTest` | pass |
| B5 | On the real `DockTile` with a docked app that lists shortcuts, a swipe up starts the first one and nothing else | `DockTileTest` | pass |
| B6 | On the real `DockTile`, the empty Camera slot starts the video intent on a swipe up, and an empty slot without a swipe default (Maps) does nothing and does not open All apps | `DockTileTest` | pass |

## Layer 3: the Needs-you card

Approvals guards its provider with `signature|knownSigner`; Home's release certificate is not on Approvals' list, so the card said "isn't sharing yet" for ever. The list is in another repo and is changed there: add `E36AC5BFF41B458AB59DB502EADD0C72B17B650E8E52592CDAC91CF6893215B2` to `needs_you_known_certs` in `approvals/android/app/src/main/res/values/known_certs.xml`, then ship an Approvals build. Until then Home shows nothing instead of a card that cannot clear.

| ID | Claim | Test | Result |
|---|---|---|---|
| B7 | While reading the Needs-you provider is refused, Home shows no Needs-you card; once it is readable the card is back | `NeedsYouCardTest` | pass |

## Layer 4: apps on the main page, no dead cards

The pins row already sits above the dock, but nothing is pinned on a new install, so the band stays empty (F5). Until pins have been written once, Home pins the phone's default app for a few common jobs (settings, contacts, email, calendar, gallery, music, calculator, clock). Unpinning them all leaves the row empty for good. A card is hidden when it has nothing to show or to act on (F6): Homelab while unreachable, Today when nothing is left today (the Today sheet still says so), and the Alarm card now opens the alarm list on tap.

| ID | Claim | Test | Result |
|---|---|---|---|
| B8 | With no pins ever written, `PinStore.seed` stores the defaults (at most `PinStore.MAX`); once any pin list has been written, even an empty one, it stores nothing | `LayoutStoreTest` | pass |
| B9 | `DefaultPins.keys` returns the installed app that handles each job, skips a job nothing handles or only the system chooser handles, and lists an app once | `DefaultPinsTest` | pass |
| B10 | While the Homelab endpoint is unreachable, Home shows no Homelab card | `DeadCardsTest` | pass |
| B11 | With the calendar readable and no event left today, Home shows no Today card | `DeadCardsTest` | pass |
| B12 | A tap on the Alarm card starts the alarm list | `DeadCardsTest` | pass |

## Layer 5: opaque sheets

Tune, Hidden apps, Gestures and the pickers (Add widget, Choose an app) paint their panel with the frosted room at the tint knob's alpha, so the page behind shows through the text (F7). Their panel is now `Ng.surface` at full alpha, with the same edge. Tune is also pushed below the status bar, like the other sheets.

| ID | Claim | Test | Result |
|---|---|---|---|
| B13 | Over a white backdrop, the top padding of the Tune, Hidden apps, Gestures and picker panels is exactly `Ng.surface`: no backdrop colour reaches the panel | `SheetOpaqueTest` | pass |
| B14 | With a 48 dp status bar, the Tune panel starts at or below it | `SheetOpaqueTest` | pass |

## Layer 6: icons

An app whose adaptive icon has no monochrome layer showed a white blank on the glass tile (F8). The drawer now draws its full-colour icon, clipped to the disc. Tin's own apps declared no launcher icon, so they showed the stock Android robot; Tin Home, tin and Tin Fixture now ship an adaptive icon with a monochrome layer, so they are themed like any other app. Approvals lives in another repo: its icon is the follow-up named in the PR.

| ID | Claim | Test | Result |
|---|---|---|---|
| B15 | An adaptive icon with a monochrome layer is drawn in the tint, as before | `IconRenderTest` | pass |
| B16 | An adaptive icon with no monochrome layer is drawn in its own colours, inside the disc: the centre is its colour, the corner of the square is empty | `IconRenderTest` | pass |
| B17 | Tin Home's launcher icon is an adaptive icon with a monochrome layer | `IconRenderTest` | pass |

## Layer 7: the drawer's A to Z scroller

The drawer is a long list with no index, and two-word labels end in "..." (F9). A strip of letters now sits on the right edge: a tap or a drag along it scrolls the grid to the first app under that letter. Only letters that have an app are listed, in list order; digits and symbols share "#". A tile's label may take two lines before it is cut.

| ID | Claim | Test | Result |
|---|---|---|---|
| B18 | `AzIndex` lists each first letter once, in list order, with the position of its first app; a label that does not start with a letter files under "#"; a letter with an accent files under its base letter | `AzIndexTest` | pass |
| B19 | A tap on a letter of the strip reports that letter's position, and every letter has a "Jump to X" description | `AzScrollerTest` | pass |
| B20 | A dragged y position maps to the letter under the finger, clamped to the first and last letter | `AzIndexTest` | pass |
| B21 | A two-word label that needs two lines in a drawer tile is shown whole | `AzScrollerTest` | pass |

