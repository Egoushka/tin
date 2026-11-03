# Home gestures

Against main (`c0483d9`, after #30, #36 and #37). Scope: `android/home`. Source: `docs/design/night-glass/cards/Gestures.md` and `Gestures.png`.

The card lists nine gestures, each reassignable in Tin's settings. Home has three of them today (swipe up, swipe down, long press on empty space; the long press on the search bar is the mic). #36 removed the double-tap torch because it fired by accident and had no visible route (`2026-11-03-home-controls.md`, H3 and its open questions 1 and 2). Yehor asked for the card to be built, so this spec brings the torch back **with** what was missing then: a visible route (the torch switch from #36 stays) and a setting that turns the gesture off.

This file was committed before any code. Every check below is a test that fails on `c0483d9` and passes on the branch; the Result column is filled in by later commits.

## Gestures and their routes

Gestures start on empty Home space only: never in the 24 dp side edges (`Dimen.backEdge`), never on a widget (`noHomeGestures`), never in edit mode or under another layer (`gestures = layer == Home && !editing`). Every gesture has a route that needs no gesture, so TalkBack and switch users lose nothing.

| # | Gesture | Default action | Route without the gesture (TalkBack) |
|---|---|---|---|
| 1 | Swipe up | All apps and search, keyboard ready (`Layer.Search`) | Search bar tap; glance action "All apps" (both exist) |
| 2 | Swipe down | Notifications | Glance action "Notifications" (exists) |
| 3 | Two-finger swipe down | Quick settings | **New** glance action "Quick settings" |
| 4 | Two-finger swipe up | Camera | Dock Camera tile (exists) |
| 5 | Double tap on empty space | Torch on or off | Torch switch beside the clock (exists, #36) |
| 6 | Pinch in | Today in full: the day's agenda and the approvals queue | **New** tap on the Today card, and glance action "Today in full" |
| 7 | Long press on empty space | Edit Home | Tune sheet "Edit Home" (exists) |
| 8 | Long press on the search bar | Start listening | The mic button (exists) |
| 9 | Swipe up on a dock icon | That app's first shortcut; the default Camera slot opens video | Long-press sheet lists the shortcuts (exists) |

Reassignable: 1 to 7. Each takes any of: All apps and search, Notifications, Quick settings, Camera, Torch, Today in full, Edit Home, Nothing. Gestures 8 and 9 sit on a control, not on empty space; they stay fixed and the sheet says so. The assignment lives in the Tune DataStore under the key `home.gestures`; an unknown or missing entry reads as the default.

Where it is set: a "Gestures" button in the Edit Home bar opens a sheet listing 1 to 7, each row showing its current action; a tap on the row picks the next action in a list. `TuneSheet.kt` is not touched (another worker owns it).

## Acceptance checks

Pure checks run on the JVM; compose checks run under Robolectric on a 412×914 dp screen like `HomeFrameTest`.

| ID | Claim | Test | Result |
|---|---|---|---|
| G1 | The defaults are the card's table, one per gesture 1 to 7 | `GestureMapTest` | pass |
| G2 | A map survives encode and decode; an unknown action or gesture name reads as the default; reassigning one gesture changes no other | `GestureMapTest` | pass |
| G3 | Two pointers moving the same way by one target's travel or more are a two-finger swipe, up or down; moving apart or a diagonal drift is not | `MultiTouchTest` | pass |
| G4 | Two pointers closing to 70 % of their span or less, their middle moving less than half a target, are a pinch in; spreading is not | `MultiTouchTest` | pass |
| G5 | Two-finger swipe down and up fire their own callbacks once and no one-finger swipe | `HomeFrameTest` | pass |
| G6 | A pinch in fires its callback once; it opens no layer | `HomeFrameTest` | pass |
| G7 | A double tap on empty space fires once; a single tap and a long press do not | `HomeFrameTest` | pass |
| G8 | None of 5 to 7 start in the 24 dp edges or on a widget, and none while gestures are off | `HomeFrameTest` | pass |
| G9 | A one-finger swipe still opens the layers where it did (the existing `HomeFrameTest` cases stay green) | `HomeFrameTest` | pass |
| G10 | A swipe up that starts on a dock tile runs the tile's swipe and not the frame's swipe, and it is not also a tap; a tap still clicks | `DockSwipeTest` | pass |
| G11 | The swipe-up shortcut of a dock tile is the app's first listed shortcut; the empty Camera slot has a video intent and the other empty slots have none | `DockSwipeTest` | pass |
| G12 | Every action maps to one step Home can take; "Nothing" maps to none | `GestureMapTest` | pass |
| G13 | The gestures sheet lists gestures 1 to 7 with their current action as text, a row picks the next action, and showing or closing the sheet picks nothing | `GestureSheetTest` | pass |
| G14 | The Today sheet lists every event still to come, not the card's three, then the queue | `TodaySheetTest` | pass |
| G15 | The glance carries "Quick settings" and "Today in full" actions that run their routes; the Today card opens the sheet on a tap (code only, see below) | `GestureRoutesTest` | pass |
| G16 | The Ukrainian strings cover the new English ones (`StringsTest`, existing) | `StringsTest` | pass |

## TalkBack

TalkBack is not run here; claims about it are semantics-tree tests (G13, G15) or code. TalkBack replaces one-finger swipes and double tap with its own gestures, so none of 1 to 7 is reachable by it as a gesture, which is why every row of the table names a route. The torch switch announces its state (#36). The Today card gains a button role and the label "Today"; the gestures sheet rows are buttons whose text is "Swipe up, All apps and search".

## Not in this PR

- Gestures 8 and 9 are not reassignable.
- The Today card's tap has no test: `CalendarSource` needs a real Context, so G15 covers the glance action and the sheet, and the card's `onOpen` is checked by reading the code.
- The queue on the Today sheet is the card's open items (the provider returns its two oldest) and a button that opens the approvals app on its full list.
- No device pass: the phone is locked and another worker uses it. What needs a finger (two-finger travel on glass, the torch, quick settings, the camera) is listed under Device checks once CI is green.

## Open questions for Yehor

1. **The torch is back on double tap.** #36 removed it as an accident risk. It is a setting now ("Nothing" turns it off). Keep it on by default, as the card says, or ship it off?
   **Decided by Yehor: keep it on.**
2. **Swipe up now opens search with the keyboard up**, as the card says ("keyboard ready"); today it opens the drawer without. Wanted?
