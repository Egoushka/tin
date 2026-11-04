# Daily Home: a minimal launcher forked from an open-source one

2026-11-04 · spec, written before any code · working name "Daily Home" (the repo name is Yehor's call) · inputs:
`docs/proposals/2026-11-04-home-direction.md`, `docs/proposals/2026-11-04-home-harvest-map.md`,
`docs/results/home-baseline-feedback-2026-11-03.md` (F1–F19).

## Decisions this spec stands on (Yehor, 2026-11-04)

- **Licence:** the launcher lives in its own public GPL-3.0 repo. tin stays MIT. This spec moves into that repo as its first commit;
  creating the repo waits for Yehor's go.
- **Base:** fork the best-fitting open-source launcher and delete what is outside M1–M8. The harvest map picks Kvaesitso v1.41.0
  (agent's pick, with a stop check in slice 1) and lists what comes from Olauncher, KISS and Fossify. `android/home` (Tin Home) is
  parked, not deleted.
- **Updates:** the phone gets a release only from a `v*` tag whose checks passed. A push to `main` publishes nothing.
- **Look:** follow the system: light or dark from the system, Material You colours, the wallpaper visible. Night glass is parked.

## Goal

A Home Yehor uses every day without fear: it never crashes, never loses its Home role or his layout, every app is one tap or two
letters away, it starts and scrolls at least as fast as Kvaesitso, and it looks like the rest of the phone. Nothing beyond M1–M8.

## Out of scope (v1)

Cards dashboard, side pages, widgets, Tune knobs, gestures beyond swipe up and swipe down, folders, icon packs, a feed, web or
contact or file search, tin's layer (Needs you, homelab, voice). The layer stays in tin and reaches any launcher as widgets and tiles
(direction proposal, option 1). Anything the base has that is out of scope gets deleted at the fork, not hidden behind a setting.

## The screen

- **Main page, top to bottom:** clock and date; one glance line; empty space with the wallpaper; 6–8 favourite apps (icon in colour
  plus label) in the lower half, under the thumb; a search bar at the bottom.
- **Glance line:** the next calendar event starting within 2 hours, else the next alarm within 12 hours, else nothing. With nothing,
  the line takes no space. Calendar permission denied counts as nothing.
- **Swipe up or a tap on the search bar:** the drawer opens with the keyboard up. **Swipe down:** notifications.
- **Drawer:** every launchable app once, A–Z by the system's collator (Ukrainian and English mixed correctly), an A–Z scroller on the
  right edge. Typing filters and ranks; Enter opens the top hit.
- **Long press on any app:** App info, Uninstall (not for system apps), Hide, Add to or remove from favourites, then the app's
  shortcuts.
- **Settings (one screen):** hidden apps (unhide), export layout, restore layout. Nothing else.

## How the checks run

| Kind | Where | Command |
|---|---|---|
| JVM | CI and the Mac: pure Kotlin and Robolectric Compose tests on a 412×914 dp screen | `./gradlew testReleaseUnitTest` |
| EMU | CI: instrumented tests on an emulator (API 35, `reactivecircus/android-emulator-runner`) | `./gradlew connectedCheck` |
| BUILD | CI and the Mac, on the release APK | `scripts/check-release.sh` |
| DEV | The Pixel 8a on USB, unlocked, Daily Home the default Home | `scripts/dd-*.sh`, each exits 0 on pass, 1 on fail, 2 with "no device" |

Every row below is a test or script that fails on the fork commit before its slice and passes after. JVM, EMU and BUILD gate every PR
and every tag. DEV checks gate a tag: `scripts/dd-all.sh` runs them in order and prints one PASS/FAIL line per ID. The Result column
is filled in by later commits.

## Acceptance checks

### M1 Never crashes, never loses the Home role or the layout

| ID | Claim | Kind | Test or command | Pass | Result |
|---|---|---|---|---|---|
| M1.1 | Every screen, sheet and branch composes without throwing for every fixture app: no icon, a broken icon, a 200-character label, a Ukrainian label, a work-profile app, a disabled app, a system app, and lists of 0, 1 and 500 apps. This is the test that would have caught F1. | JVM | `RenderEverythingTest` | 0 failures; the test lists every route and sheet by name and fails when a new one is not in the list | |
| M1.2 | No icon or string lookup can throw: every lookup by name has a fallback, and a test feeds it every name used in the source plus one that does not exist | JVM | `LookupFallbackTest` | 0 exceptions | |
| M1.3 | Process death keeps the layout: favourites, hidden apps and settings written, the process killed, the same values read back | EMU | `ProcessDeathTest` | equal | |
| M1.4 | A corrupt layout store does not crash Home: the store is overwritten with garbage, Home starts and restores the last good snapshot of the 3 it keeps | EMU | `CorruptStoreTest` | starts; layout equals the last snapshot | |
| M1.5 | Killing Home 5 times in a row does not lose the role: `am force-stop`, then `KEYCODE_HOME`, five times | DEV | `scripts/dd-role.sh` | each time the resumed activity is ours within 2 s, and `cmd role get-role-holders android.app.role.HOME` prints our package | |
| M1.6 | An upgrade keeps the layout: install the previous tag's APK, set a known layout, `adb install -r` the new APK | DEV | `scripts/dd-upgrade.sh` | layout read back over `dumpsys` equals the one set | |
| M1.7 | 7 days without a crash or ANR. At each start Home reads `getHistoricalProcessExitReasons` and appends every crash, native crash and ANR it has not seen yet to its store; `Activity.dump` prints the counts, so `dumpsys activity <component>` reads them from a release build | DEV | `scripts/dd-health.sh --days 7` | `crashes=0 anrs=0` for the 7 days before the run | |

### M2 Main page

| ID | Claim | Kind | Test or command | Pass | Result |
|---|---|---|---|---|---|
| M2.1 | Clock and date show the current time in the system's 12/24-hour setting and locale | JVM | `ClockTest` | text matches `DateFormat` for en and uk, 12 h and 24 h | |
| M2.2 | Glance line: an event in 90 minutes shows it; one in 3 hours with an alarm in 8 hours shows the alarm; nothing, or calendar permission denied, leaves no node and no space | JVM | `GlanceTest` | as stated | |
| M2.3 | 6 to 8 favourites, each with its colour icon and its label, all inside the lower half of the screen, so the thumb reaches them | JVM | `FavouritesLayoutTest` | every favourite's bounds are below y = 457 dp of 914 | |
| M2.4 | One tap on a favourite starts that app through `LauncherApps.startMainActivity` with its user handle | JVM | `FavouritesLayoutTest` | the fake records one start with the right component and user | |
| M2.5 | No favourites yet: the main page shows "Long-press an app in the drawer to add it here", not a blank | JVM | `FavouritesLayoutTest` | the hint node exists | |
| M2.6 | A tap on the search bar and a swipe up both open the drawer with the search field focused and the keyboard requested | JVM | `HomeFrameTest` | focused, IME requested | |

### M3 Drawer and search

| ID | Claim | Kind | Test or command | Pass | Result |
|---|---|---|---|---|---|
| M3.1 | Every launchable activity of every profile appears once; hidden apps do not | JVM | `AppListTest` | counts equal the fake `LauncherApps` minus hidden | |
| M3.2 | Order follows the system collator: Ukrainian and English labels mixed, case-insensitive | JVM | `AppListTest` | equals `Collator.getInstance(locale)` order for en and uk | |
| M3.3 | The A–Z scroller shows a letter only when some app starts with it (Cyrillic included); dragging to a letter puts its first app at the top | JVM | `FastScrollerTest` | as stated | |
| M3.4 | Ranking: a better text match ranks first (start of a word beats inside a word); among equal matches, the app with the higher launch weight ranks first. Each launch raises the launched app's weight and lowers every other app's (Kvaesitso's `SearchableDao.touch`). Ties go A–Z | JVM | `RankingTest` | a fixed fixture of launches gives a fixed order; an app launched 5 times last week ranks above one launched 5 times then left alone for 20 launches of other apps | |
| M3.5 | Enter in the search field opens the top hit and records one launch | JVM | `SearchTest` | one start, count +1 | |
| M3.6 | An app installed or removed appears or disappears without reopening Home (`LauncherApps.Callback`) | JVM, DEV | `AppListTest`; `scripts/dd-install.sh` installs and removes a test APK | JVM: list updated on the callback; DEV: in the drawer's dump within 2 s, gone within 2 s | |

### M4 Long-press sheet

| ID | Claim | Kind | Test or command | Pass | Result |
|---|---|---|---|---|---|
| M4.1 | The sheet opens for every fixture app (M1.1's list) and shows App info, Hide, Add to or Remove from favourites, plus Uninstall only for apps that are not system apps | JVM | `AppSheetTest` | as stated, 0 exceptions | |
| M4.2 | Each action fires its exact intent or store change: App info `ACTION_APPLICATION_DETAILS_SETTINGS`, Uninstall `ACTION_DELETE` with `package:` URI, Hide adds the component and user to the hidden set | JVM | `AppSheetTest` | as stated | |
| M4.3 | The app's shortcuts (`LauncherApps.getShortcuts`, dynamic and manifest, at most 4) are listed and each starts through `startShortcut`; no shortcut permission (Home not default) lists none and does not throw | JVM | `AppSheetTest` | as stated | |
| M4.4 | Hidden apps are listed in Settings, and unhiding one returns it to the drawer and search | JVM | `HiddenAppsTest` | as stated | |

### M5 Icons

| ID | Claim | Kind | Test or command | Pass | Result |
|---|---|---|---|---|---|
| M5.1 | Adaptive icons are drawn whole in the system's shape; legacy icons are scaled into it, not cropped | JVM | `IconLoaderTest` | rendered bitmap has non-transparent pixels in the shape's centre and none outside it | |
| M5.2 | A null, failing or fully transparent icon falls back to a coloured circle with the label's first letter | JVM | `IconLoaderTest` | fallback used in each case, never an empty bitmap | |
| M5.3 | On a real device every launchable app's icon has visible pixels (F8: Privat24 was blank) | EMU, DEV | `IconsNeverBlankTest` (`connectedCheck`, also run on the Pixel) | 0 apps whose icon has fewer than 5 % non-transparent pixels | |
| M5.4 | A second drawer open loads no icon from `PackageManager`: all come from the cache | JVM | `IconCacheTest` | 0 loads on the second open | |

### M6 Follows the system

| ID | Claim | Kind | Test or command | Pass | Result |
|---|---|---|---|---|---|
| M6.1 | Dark and light follow the system setting; on API 31+ the colours come from `dynamicDarkColorScheme` and `dynamicLightColorScheme` | JVM | `ThemeTest` | under `uiMode` night yes and no, the scheme matches the dynamic one | |
| M6.2 | The wallpaper shows behind Home: the activity theme sets `windowShowWallpaper` and the root has no opaque background | EMU | `WallpaperTest` | attribute true; root background alpha 0 | |
| M6.3 | Text on the wallpaper stays readable: the scrim plus text colour give a contrast ratio of 4.5 or more over a white and a black wallpaper | JVM | `ContrastTest` | ≥ 4.5 both | |
| M6.4 | Sheets are opaque surfaces (F7: they were see-through) | JVM | `AppSheetTest` | sheet background alpha 1 | |
| M6.5 | `GestureNavContract`: an `onNewIntent` with the extra `gesture_nav_contract_v1` gets a reply with the icon's on-screen rect when the closing app is a visible favourite, and no rect otherwise | JVM | `GestureNavContractTest` | as stated | |
| M6.6 | Content stays clear of the status and navigation bars | JVM | `InsetsTest` | no node's bounds overlap the bars' insets | |

### M7 Layout export and restore

| ID | Claim | Kind | Test or command | Pass | Result |
|---|---|---|---|---|---|
| M7.1 | Export writes one file through the system file picker (Kvaesitso's backup format: a zip of JSON with a metadata entry and a format version) holding favourites (component and user), hidden apps, launch weights and settings; restore of that file gives an equal layout | JVM | `LayoutFileTest` | round trip equal | |
| M7.2 | Restore skips apps that are not installed and says how many it skipped | JVM | `LayoutFileTest` | skipped count in the message; the rest restored | |
| M7.3 | A malformed file, or one with a newer schema version, changes nothing and shows an error | JVM | `LayoutFileTest` | store unchanged; error shown | |

### M8 Release, speed and scope

| ID | Claim | Kind | Test or command | Pass | Result |
|---|---|---|---|---|---|
| M8.1 | The release build runs R8 and carries a baseline profile | BUILD | `scripts/check-release.sh`: `mapping.txt` exists and the APK holds a non-empty `assets/dexopt/baseline.prof` | both | |
| M8.2 | Cold start is no slower than upstream Kvaesitso v1.41.0 (`de.mm20.launcher2.release`, installed beside ours) on the same Pixel: 20 starts each with `am start -S -W` while Settings is on top (`scripts/home-baseline.sh` with the package and activity as parameters). Each run makes the measured launcher the default Home first | DEV | `scripts/dd-speed.sh` | our p95 `TotalTime` ≤ Kvaesitso's p95 | |
| M8.3 | Drawer jank is no worse than Kvaesitso's: 10 opens each, `dumpsys gfxinfo` (`scripts/home-jank.sh` with the package and the opening swipe as parameters; Kvaesitso's swipe is checked on the phone first) | DEV | `scripts/dd-speed.sh` | our janky-frame % ≤ Kvaesitso's | |
| M8.4 | Only a `v*` tag publishes: the release workflow triggers on `push: tags: ['v*']` only and its publish job `needs` the JVM, EMU and BUILD jobs | BUILD | `scripts/check-release.sh` reads the workflow with `yq` | as stated | |
| M8.5 | Each tag's `versionCode` is higher than the last tag's, so Obtainium and Android accept the upgrade | BUILD | `scripts/check-release.sh` | strictly higher | |
| M8.6 | Scope guard: production Kotlin stays under a budget. Slice 1 sets the budget to the measured size after the cut plus 20 % (estimate: about 29k after the cut, so about 35k), and `:app:ui` stays under 12k. Raising either needs a change to this spec | BUILD | `scripts/check-release.sh` counts lines the way the harvest map does | both under budget | |
| M8.7 | The merged release manifest asks for exactly the allowed permissions: `QUERY_ALL_PACKAGES`, `ACCESS_HIDDEN_PROFILES`, `REQUEST_DELETE_PACKAGES`, `EXPAND_STATUS_BAR`, `READ_CALENDAR`, plus those AndroidX adds, listed in `scripts/permissions.txt`. No `INTERNET` | BUILD | `scripts/check-release.sh` diffs `aapt2 dump permissions` against the list | no diff | |

## Slices, each usable on the phone

1. **Fork and cut:** fork Kvaesitso v1.41.0 (`328073f`) with its history and upstream's copyright notices. Delete the harvest
   map's list one module per commit, building after each. Give it a new `applicationId`, add the baseline profile module, and set up CI
   with JVM, EMU and BUILD. Checks M1.1, M1.2, M8.1, M8.4 to M8.7. Yehor can install it from the first tag. **Stop check for the
   base:** if `:app:ui` can't get under 12k lines without rewriting a screen, or the cut won't build, switch to Olauncher (harvest
   map, "Why not the others") before slice 2.
2. **Main page:** M2, M6.
3. **Drawer, search and long press:** M3, M4, M5.
4. **Trust:** M1.3 to M1.7, M7, M8.2, M8.3. Then 7 days on the phone; `dd-health.sh --days 7` closes v1.

## Open questions for Yehor

1. **Repo name.** Creating a public repo is his go. Working name "Daily Home"; any name works.
2. **Glance line sources.** Next event within 2 hours, else next alarm within 12 hours. tin's Needs you count could join later
   through a broadcast from Approvals, outside v1. Keep it to these two?
3. **The phone today.** Moving to Daily Home means one `adb uninstall` of Tin Home or leaving it installed but not default. Tin Home's
   layout is not migrated (its favourites are 5 dock tiles).
