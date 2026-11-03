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
| B7 | While reading the Needs-you provider is refused, the Home stack has no Needs-you card; once it is readable the card is back | `HomeStackTest` | pending |
