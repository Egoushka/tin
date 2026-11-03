# Tin Home on the phone: baseline feedback, 2026-11-03

Build `0.1.34 · 7f43974` (Tune shows it; versionCode 34, installed by Obtainium), the default Home on a Pixel 8a
(GrapheneOS, Android 17 `CP3A.261005.005`, 1080x2400 at 420 dpi, gesture navigation, system light mode, font scale 1.0).
Walked through by an agent over adb, about 15:50 to 16:20. Nothing in the repo was changed; no setting on the phone was changed on purpose.

## Bottom line

Home is not ready for daily use, for two reasons and one shape problem:

1. **Long-pressing any app crashes Home** (3 crashes logged, the same exception each time). Android then drops Tin Home as the default
   Home and shows "Select a Home app". The phone is in that state now (see "State left on the phone").
2. **Two of the five Home cards are dead on this phone**: "Approvals isn't sharing yet" is a signing mismatch, not a missing feature;
   "Homelab not reachable" has no cause shown.
3. **Shape:** the main page has no apps on it. Apart from the five dock tiles, opening any app takes swipe up, then scroll or type.
   Every mainstream launcher puts your apps one tap away; Home puts status cards there and leaves a third of the screen empty.

Most gestures work exactly as the gestures spec says. Drawer, search, Tune, Edit Home and the widget and pin pickers open and close as they should.

Screenshots and UI dumps are in `/var/folders/8s/4byqbs494knb713q1c4sv70m0000gn/T/tinfb/` on the Mac (written as `tinfb/NN-name.png` below).
They show notification text; keep them out of git, because the repo is public.

## State left on the phone

- **Tin Home is no longer the preferred Home.** The role holder is still `io.github.egoushka.tin.home` (`cmd role get-role-holders
  android.app.role.HOME`), but `resolve-activity` for `HOME` now returns the chooser (`isDefault=false`). Before the crashes it returned
  Tin Home with `isDefault=true`. I picked "Tin Home, Just once" so Home is in front now, torch off. **Fix it yourself:** press Home, then pick
  Tin Home and "Always". I did not, because the brief rules out setting changes.
- Temp files I made on the phone (`/data/local/tmp/tinfb-touch.sh`, `/sdcard/tinfb.xml`) are removed.

## Findings

Type: **BUG** means the spec says X and the device does Y. **GAP** means a mainstream feature is missing. **DESIGN** means it works but looks or reads badly.
Severity: **blocks**, **annoying** or **polish**. "Spec" says whether a spec covers the item now.

| # | Type | Severity | What I saw | Evidence | Spec |
|---|---|---|---|---|---|
| F1 | BUG | blocks | Long-pressing an app in the drawer (Settings) crashes Home: `java.util.NoSuchElementException: Key home is missing in the map.` at `Lucide.icon(Lucide.kt:69)` ← `SheetRow(Sheet.kt:80)` ← `AppSheet(AppSheet.kt:113)`. `AppSheet.kt:112-113` (added in #37) ask for icon `"home"`; the `Lucide` map has no `"home"` key. It is the only missing key: I compared every literal icon name in `android/home` with the map. The crash happens whenever fewer than 12 apps are pinned or the app is already pinned, so in practice it happens every time. | `am_crash` at 16:02:18, 16:15:32 and 16:17:08 (PIDs 16750, 16869, 21138); `logcat -b crash`; `tinfb/27-longpress-app.png` (black frame) | Apps spec: the long-press sheet with Info, Pin, Hide, Uninstall. Pages spec: Pin to Home. Unit tests did not compose the sheet with a real icon lookup. |
| F2 | BUG | blocks | After repeated crashes, Android dropped Tin Home as the preferred Home: "Select a Home app" (Launcher or Tin Home) appeared and focus went to `com.android.launcher3`. This follows from F1, but it means one bad long-press costs the user their Home. | `tinfb/34-after-crash.png`; `resolve-activity` output above | Launcher spec H1 (answers HOME, survives process death). Nothing covers crash recovery. |
| F3 | BUG | blocks | A swipe up on the dock Vanadium tile ended in the same crash: the stack shows `AppSheet` being composed from `Home.kt:287`, so the swipe opened the long-press sheet or was read as a long press (which one is UNKNOWN). A swipe up on the Camera tile opened All apps and the keyboard, not video. | `am_crash` 16:17:08; `tinfb/33-vanadium-swipe.png` ("Tin Home keeps stopping"); `tinfb/29-dockswipe.png`, `tinfb/30-dockswipe2.png` | Gestures spec #9, G10/G11 ("a swipe up that starts on a dock tile runs the tile's swipe and not the frame's swipe"; Camera slot opens video). G10/G11 are marked pass in `DockSwipeTest`, but the device does something else. |
| F4 | BUG | blocks | "Needs you: Approvals isn't sharing yet" never clears. System log: `Permission Denial: opening provider com.yehor.approvals.needsyou.NeedsYouProvider ... requires com.yehor.approvals.permission.READ_NEEDS_YOU`. The permission is `signature\|knownSigner`; Home is now signed with the Obtainium release key, and Approvals does not list that digest. | logcat 16:15:33 (`ContentProviderHelper`); `dumpsys package com.yehor.approvals` (`prot=signature\|knownSigner`) | Launcher spec, Needs you row: "`signature\|knownSigner` on Tin's signing digest". The release key from #34 is not in Approvals' list. |
| F5 | GAP | blocks | The main page has no app icons, only the clock row, five cards and the dock. About y 1350 to 1950 px is empty. Any app outside the 5 dock tiles takes at least two actions. Pinned apps exist, but only in Edit Home, and none are set. | `tinfb/02-home.png`, `tinfb/28.png` | Layout spec (pins, at most 12) and pages spec (pages beside the main one) cover it, but nothing is placed by default. |
| F6 | DESIGN | annoying | Two of the five cards are permanent dead states: Needs you (F4) and "Homelab not reachable" (no reason, no action, and a tap does nothing). Today ("Nothing else today") and Alarm are read-only. Most of the dashboard shows nothing to act on. | `tinfb/02-home.png` | Controls spec, open questions: Today, Homelab and Alarm are read-only. No rule hides a card that has nothing to show. |
| F7 | DESIGN | annoying | Sheets are see-through: under Tune, Hidden apps, Gestures and Add widget, the card text behind stays readable and collides with the sheet's own text. For example, "Hidden apps" sits over "Add widget" and "Nothing is hidden" over "Tune". The Tune sheet runs under the status bar. The Today sheet (pinch) covers the dock without dimming much. | `tinfb/15-tune.png`, `tinfb/16-addwidget.png` (Hidden apps sheet), `tinfb/14-gestures-sheet.png`, `tinfb/11-pinch.png`, `tinfb/19-addwidget.png` | Polish spec G1 to G13 (glass fidelity) covers blur and tint, but no check says a sheet must hide what is behind it. |
| F8 | DESIGN | annoying | Icons are mixed: most are monochrome glyphs on glass discs. Approvals, tin and Tin Fixture show the stock Android robot (as does Tin Home itself in the system chooser). Okta Verify, Rozetka and Власний Рахунок are full colour. Privat24 is a plain white disc with nothing on it. Bolt Food and mono are wordmarks. | `tinfb/25-drawer-nokb.png`, `tinfb/26-drawer-scrolled.png`, `tinfb/34-after-crash.png` | Launcher spec: themed monochrome icons. Icon packs are out of scope. There is no fallback for apps without a monochrome layer, and no "original icons" option. |
| F9 | GAP | annoying | The drawer is one 4-column alphabetical grid with no A to Z index or fast scroller. 28 apps fit without the keyboard, about 16 with it. Labels are cut ("Privat24 bu...", "Власний Pa..."). The count is right: 39 tiles = 39 launcher activities. | `tinfb/25-drawer-nokb.png`, `tinfb/26-drawer-scrolled.png`; `cmd package query-activities ... LAUNCHER` = 39 | Launcher spec H5 (count) passes. No spec covers an index or fast scroll. |
| F10 | DESIGN | annoying | Search results for "se": the shortcut rows "Search", "Search" and "supabase" use one generic glyph and do not name their app, so the two "Search" rows cannot be told apart. "Search the web" sits below the keyboard. | `tinfb/05-search-se.png`, `tinfb/06-back1.png` | Search spec (grouped results). Apps spec puts shortcut icons out of scope (one Lucide glyph). The missing owning-app name is not covered. |
| F11 | BUG (a11y) | annoying | When the drawer is open, the UI tree still contains the Home layer under it: dock tiles, the search bar, Flashlight. TalkBack can probably reach controls the user cannot see (not tried with TalkBack on). | `tinfb/05-search.xml`, `tinfb/23-1.xml` | A11y spec (#32) covers labels and Ukrainian. Hiding a covered layer is not covered. |
| F12 | DESIGN | annoying | The Add widget picker is a plain text list (widget name and app) with no preview, no size and no grouping. The Pin app picker is a plain text list with no icons and no search. | `tinfb/19-addwidget.png`, `tinfb/21.xml` | Layout spec: widget previews are out of scope. |
| F13 | BUG | annoying | The Maps dock tile does nothing on tap: no Maps app is installed (it is missing from the 39 launcher activities), and there is no feedback or chooser. | `tinfb/36-maps-tap.png`, focus unchanged | Gestures G11 mentions empty slots. A slot whose default handler is missing is not covered. |
| F14 | GAP | annoying | Home paints its own gradient and never shows the system wallpaper (an `ImageWallpaper` is set, 4800x2400). There is no wallpaper option in Tune. | `dumpsys wallpaper`; every Home screenshot | Not covered. |
| F15 | GAP | annoying | There is no settings screen beyond Tune. Tune holds only looks: preset, accent, room light, frost, tint, density, icon weight, focus, hidden apps and reset. It has no grid or columns, label toggle, icon style, backup or restore of the layout, "set as default Home" entry, or about/feedback. | `tinfb/15-tune.png` | Not covered (no backup in any spec). |
| F16 | GAP | polish | No folders or categories. | none on the device; every Home spec puts folders out of scope | Out of scope by spec |
| F17 | DESIGN | polish | In Edit Home, each card gets a row of ↑ ↓ × buttons and there is no drag on the main page. The "+" for a new page has a TalkBack label ("Add page") but no visible text. Arrow rows show clipped behind the glance row when a sheet is open. | `tinfb/12-edit.png`, `tinfb/13-edit-scrolled.png`, `tinfb/19-addwidget.png` (top) | Layout spec: no drag on the main page. Pages spec: drag on side pages. |
| F18 | DESIGN | polish | Home is always dark, while the system and keyboard are light: the light keyboard under the dark drawer is a hard seam. | `tinfb/04-drawer-settled.png`, `tinfb/24-drawer-end.png`; `cmd uimode night` = no | Night glass is dark by design; light mode is not specced. |
| F19 | DESIGN | polish | Swiping up shows Home through the drawer for a frame, and recents shows a short double-layer animation. Recents itself works through the stock Quickstep fallback. | `tinfb/03-swipeup.png`, `tinfb/35-recents.png` | Polish spec (motion) |

## Works as specified (seen on the device)

- **Swipe up** opens All apps with the keyboard up (`mInputShown=true`). The first Back hides the keyboard and keeps the query; the second returns Home (`tinfb/03`, `06`, `07`).
- **Swipe down** opens the notification shade (`tinfb/08`).
- **Two fingers down** opens Quick Settings (`tinfb/10`). **Two fingers up** opens the camera (`app.grapheneos.camera`).
- **Double tap** on empty space turns the torch on from Home's process (`dumpsys media.camera`: "Torch for camera id 0 turned on for client PID 16869"). The torch switch shows it and turns it off. A single tap does nothing.
- **Pinch in** opens Today in full (`tinfb/11`). **Long press** on empty space opens Edit Home (`tinfb/12`). The Gestures sheet lists all 7 reassignable gestures with the spec's defaults (`tinfb/14`).
- **Back** closes Tune, Hidden apps, Add widget and Pin app one at a time.
- **Notification dots** show on the Vanadium and Approvals tiles (content-desc "Vanadium, has notifications"). The Notifications card counts per app.
- **The drawer** lists all 39 launchable apps (H5). Latin names sort first, then Cyrillic.
- **The weather** line shows ("8° Cloudy"). The alarm card shows the next alarm and a countdown.
- **Recents** (`KEYCODE_APP_SWITCH`) from Home works, and Back returns.
- **Start time:** `Displayed io.github.egoushka.tin.home/.HomeActivity: +219ms` after the crash restart (16:17:53), and `wm_activity_launch_time` 175 ms at 15:52. The process was already running for the listener service in both cases, so neither is a true cold start. The baseline in `2026-11-03-home-baseline.md` (debug 0.1.0) measured WARM median 1398.5 ms.
- **No ANRs** for the package (`am_anr` none, dropbox none). The errors in logcat are all the F1 crash (`AndroidRuntime`, `ComposeInternal`).

## UNKNOWN (not checked, and why)

- **Cold start after force-stop:** not run. With Tin Home no longer the preferred Home (F2), a force-stop would bring up the system chooser again.
- **Rotation:** auto-rotate is on (`accelerometer_rotation` 1) and HomeActivity asks for no orientation (`SCREEN_ORIENTATION_UNSPECIFIED`), so Home should rotate. Forcing a rotation needs a settings change, so the landscape layout was not seen.
- **Long press on a dock tile:** it uses the same `AppSheet`, so the same crash is expected. I did not trigger it again.
- **App sheet contents** (shortcuts, Info, Pin, Hide, Uninstall): never rendered because of F1.
- **Work profile and private space:** the phone has only user 0 (`pm list users`), so there was nothing to test. The apps spec handles profile changes; there is no badge (out of scope).
- **TalkBack in use and font scale 1.3 or more:** not tried, because both need a settings change. Dock tiles are 147 px, which is 56 dp, above 48 dp.
- **Why Homelab is unreachable** (network, Tailscale or endpoint): not looked into.
- **Drawer jank on this build:** not re-measured. The baseline measured 15.25% janky frames on debug 0.1.0.

## Against mainstream launchers

Compared with Pixel Launcher, Lawnchair, Niagara and Olauncher. "Has" means seen on the device.

| Feature | Tin Home 0.1.34 |
|---|---|
| Drawer with search | Has (grouped, Enter opens the top hit) |
| A to Z index or fast scroll | Missing (F9) |
| Dock and favourites | Dock has 5 slots. Pins exist but are hidden in Edit Home; none on the main page (F5) |
| Icon packs, themed icons, original icons | Themed only, and inconsistent (F8) |
| Long-press: shortcuts, info, uninstall, hide | Crashes (F1) |
| Widgets | Picker works (text-only list); placing one not tried |
| Folders or categories | Missing; out of scope (F16) |
| Notification dots | Has |
| Recents and gesture navigation | Works through the stock fallback; swipe-home animation is the stock one |
| Set-default flow | No in-app entry. Repeated crashes lose the default (F2) |
| Work profile and private space | UNKNOWN (no second profile on the phone) |
| Accessibility | Labels present; covered layer leaks into the tree (F11) |
| Settings screen | Tune only, looks only (F15) |
| Backup and restore of the layout | Missing (F15) |
| Wallpaper | Not shown (F14) |

## Proposed baseline

### Must have for v0.2: daily use without surprises

1. **Fix F1.** Add the `home` glyph. Make `Lucide.icon` fall back to a default glyph instead of `getValue` throwing. Add a test that composes `AppSheet` for every branch.
2. **Make dock swipes match G10/G11 on the device (F3).** Add an instrumented check: a swipe up from the dock tile does not open the drawer.
3. **Make Needs you work with the release key (F4).** Add the release digest to Approvals' `knownSigner`, or hide the card while access is missing.
4. **Put apps on the main page (F5).** Show a default row or grid of pinned apps (for example the most used, or set in onboarding), using the empty band.
5. **Hide cards that have nothing to act on** (F6), or collapse them into one line.
6. **Make sheets opaque or properly scrimmed (F7).** Give Tune a status-bar inset.
7. **Fix the icon fallback (F8).** If an app has no monochrome layer, show its full-colour icon, clipped to the disc, instead of a white blank or a stock robot. Give Tin's own apps real icons.
8. **Add an A to Z fast scroller to the drawer (F9).**
9. **Hide covered layers from accessibility** while a sheet or the drawer is open (F11).
10. **Mark a dock slot with no handler (F13).** Hide it or show "Choose an app".

### Later

- Shortcut rows show their app's icon and name (F10). Widget previews and a pin picker with icons and search (F12).
- Show the system wallpaper, or offer it as a Tune option (F14). A light variant or a keyboard-matched drawer (F18).
- A settings screen with grid, labels, icon style, backup and restore of the layout (export to a file), and a "Set as default Home" entry (F15).
- Folders or categories (F16). Drag on the main page (F17). Motion polish (F19).
- Instrumented checks on the device, not only JVM tests, for each gesture row. G10/G11 passed in tests and failed on the phone.
