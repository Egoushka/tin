# Harvest map: which launcher's code goes under Tin Home

2026-11-04 · proposal by Claude, re-aimed the same day after Yehor chose "take their cores and make our own implementation on top
of that", inside tin · spec: `docs/specs/2026-11-04-home-on-kvaesitso.md` (S0, M1–M8) · clones (read-only) in `~/Projects/oss/`:
`Kvaesitso`, `Olauncher`, `KISS`, `Launcher` (Fossify).

## Bottom line

**Kvaesitso v1.41.0 (`328073f38f8472118a377f69cce34a2c86372ab9`) supplies the engine; Tin Home keeps its own UI.** Of the four,
only Kvaesitso has a separable engine: apps, profiles, ranked search, icons, shortcuts, hidden items, backup and crash capture live
in `core`, `data` and `services` modules, and only `:app:app` depends on `:app:ui`. Those modules go into tin untouched (vendored
at `android/kvaesitso`, an included build), so upstream fixes keep arriving with `git subtree pull`. Nothing is cut: the earlier
"fork and delete four fifths" plan is void.

**The check that stops the pick:** slice 0 (spec S0) must build `:home` against `:data:applications` and `:services:icons` with no
edits to upstream's files beyond aligning AGP and Kotlin, and Tin Home's cold start may get at most 20 % slower (a starting value).
If either fails, bring the numbers to Yehor before slice 1.

## The four, measured

Lines are production Kotlin and Java: `find <module> \( -name '*.kt' -o -name '*.java' \)` without `build/`, `test/` or
`androidTest/`, piped to `cat | wc -l`. Module counts come from `grep -c '^include' settings.gradle.kts`. Everything else is from
the manifests and build files at the SHAs below.

| | Kvaesitso | Olauncher | KISS | Fossify Launcher |
|---|---|---|---|---|
| Tag, SHA | v1.41.0 `328073f` | v6.10.27 `cc6b728` | v3.26.0 `05d6cc9` | 1.10.0 `2191e64` |
| Licence | GPL-3.0 | GPL-3.0 | GPL-3.0 | GPL-3.0 |
| Code | 103.4k (102.2k Kotlin), 51 modules | 6.1k Kotlin, 1 module | 22.7k Java, 1 module | 6.8k Kotlin, 1 module, plus external `org.fossify:commons` |
| UI | Compose, Material 3 | Views | Views, ListView | Views |
| Storage | Room (schema v33), DataStore, Koin | SharedPreferences | SharedPreferences, SQLite | Room v5 |
| minSdk / target | 26 / 36 | 24 / 36 | 21 / 36 | 26 / 36 |
| R8 in release | yes, plus resource shrinking | yes | yes, plus resource shrinking | yes, plus resource shrinking |
| Baseline profile, macrobenchmark | none | none | none | none |
| Tests | 18 unit | 3 unit | 9 unit, 18 instrumented | 0 |
| Firebase, Play Services, analytics | none | none | none | none (a `gplay` flavour exists) |

Lawnchair: its `LICENSE.txt` is Apache-2.0 (the GitHub API says `NOASSERTION`). Per-file licences: UNKNOWN. It was not cloned.

## Per item: where each piece comes from

Action: **engine** = a Kvaesitso module `:home` depends on, used as is; **copy** = a few lines from `:app:ui` or another GPL
repo into `android/home`, keeping the licence header; **reference** = read it, don't copy it; **write** = nobody has it;
**tin** = Tin Home already has it.

| Item | Source | Paths (repo-relative) | Lines | Action |
|---|---|---|---|---|
| M1 crash capture | Kvaesitso | `core/crashreporter` (uncaught handler chains to the previous one; lists the last 7 days of reports) | 792 | engine; its viewer screen is in `:app:ui` settings, a reuse candidate |
| M1 exit-reason count (M1.7) | — | `ApplicationExitInfo` at start, `Activity.dump` | ~80 | write |
| M1 role request | Fossify | `extensions/Context.kt` 86 ff., `MainActivity.kt:932` (`RoleManager.createRequestRoleIntent(ROLE_HOME)`) | ~30 | copy: Kvaesitso has no `RoleManager` call |
| M1 render-everything test | KISS (pattern only) | `app/src/androidTest/.../MainActivityTest.java`, `AbstractMainActivityTest` | — | reference; write `RenderEverythingTest` in Compose |
| M2 clock and glance line | Kvaesitso | `app/ui/.../launcher/widgets/clock/parts/`: `PartProvider.kt` 14, `DatePartProvider` 139, `AlarmPartProvider` 132 | ~300 | reference: `:app:ui` is not used; write in `:home` |
| M2 glance: next event within 2 h | — | `CalendarContract.Instances` | ~100 | write (`:data:calendar` comes with the engine, slice 0 checks whether it fits) |
| M2 favourites | Kvaesitso | `services/favorites` 94; UI in `app/ui/.../search/favorites/SearchFavorites.kt` 81 | 94 | engine for the data, Tin Home's UI in the lower half (M2.3) |
| M2 search bar | Kvaesitso | `app/ui/.../launcher/searchbar/` 377 | — | tin (`search/`); Kvaesitso's as reference |
| M3 app list, packages, profiles | Kvaesitso | `data/applications` 874 (`AppRepository.kt` with `LauncherApps.Callback`), `core/profiles` 225 | 1,099 | engine (slice 0) |
| M3 ranking | Kvaesitso | `data/database/.../SearchableDao.kt:168` `touch` (launch weight: the launched app's rises, all others decay); `SearchVM.kt:410-436` `applyRanking` (`score*0.6 + weight*0.4`) is in `:app:ui` | ~30 to copy | engine for `touch`; copy `applyRanking` into `:home`. KISS's frecency (`db/DBHelper.java:90-165`, `searcher/QuerySearcher.java:49-62`) is the reference if M3.4 fails |
| M3 Enter opens top hit | Kvaesitso | `SearchVM.launchBestMatchOrAction` (line 143, `:app:ui`) | ~20 | copy |
| M3 A–Z scroller | tin | `android/home/.../apps/AzIndex.kt`; Fossify's `fragments/AllAppsFragment.kt` for behaviour | — | tin |
| M4 long-press, shortcuts | Kvaesitso | `data/appshortcuts` 895; UI in `app/ui/.../search/apps/AppItem.kt` 569 | 895 | engine for shortcuts; `AppItem.kt` as reference for the sheet |
| M4 hide | Kvaesitso | `data/searchable/.../VisibilityLevel.kt`, `SavableSearchableRepository.kt`; settings `settings/hiddenitems/` 418 | in engine modules | engine; the hidden-items screen is a reuse candidate |
| M5 icons | Kvaesitso | `services/icons` 2,566 (Coil-based, icon packs included; adaptive handling internals UNKNOWN) | 2,566 | engine (slice 0) |
| M5 never-blank fallback | Fossify | `extensions/Context.kt` `getDrawableForPackageName` (badged icon, then `getApplicationIcon`), `MainActivity.kt:1082-1130` | ~60 | check `services/icons`' own fallback first; add the letter fallback (M5.2) in `:home` if it can return a blank |
| M6 light, dark, colours | — | `dynamicLightColorScheme`, `dynamicDarkColorScheme` in Tin Home's `theme/` | ~40 | write; `:data:themes` is not used (`:libs:material-color-utilities` still comes in through `:core:base`) |
| M6 `GestureNavContract` | Kvaesitso (AOSP code, Apache-2.0 header) | `core/base/.../com/android/launcher3/GestureNavContract.kt` 79; the `onNewIntent` wiring and `launcher/transitions/` ~120 are in `:app:ui` | ~120 | engine for the class; write the wiring in `:home` with Kvaesitso's as reference. GrapheneOS: UNKNOWN |
| M7 export and restore | Kvaesitso | `services/backup` 227 (`BackupManager.kt`, `BackupMetadata.kt`), `core/base/.../backup/Backupable.kt`, `settings/backup/` ~180 | 227 | engine; Tin Home's stores implement `Backupable`; the backup screen is a reuse candidate |
| M8 R8 | tin | `android/home/build.gradle.kts` (`isMinifyEnabled` not set today); the engine modules' consumer rules: UNKNOWN | — | write |
| M8 baseline profile, macrobenchmark | — | new `:baselineprofile` module (AndroidX Baseline Profile Gradle plugin) | ~150 | write |
| M8 speed and jank scripts | tin | `scripts/home-baseline.sh`, `scripts/home-jank.sh` | — | tin, with the package, activity and drawer swipe as parameters |

## Kvaesitso: engine, reference, unused

All of `android/kvaesitso` stays byte-for-byte upstream (spec S0.1); "unused" means `:home` doesn't depend on it, not that it is
deleted. Per-module lines are measured as above; the closures come from the `project(...)` lines in each module's
`build.gradle.kts` at `328073f`.

- **Engine, depended on by `:home` (20 modules, ~17k):** `:core:base` 2,614, `:core:shared` 1,499, `:core:preferences` 2,514,
  `:core:ktx` 540, `:core:compat` 32, `:core:i18n`, `:core:profiles` 225, `:core:permissions` 319, `:core:crashreporter` 792,
  `:data:applications` 874, `:data:appshortcuts` 895, `:data:searchable` 634, `:data:database` 1,823, `:data:customattrs` 556,
  `:data:i18n` 148, `:services:icons` 2,566, `:services:search` 373, `:services:favorites` 94, `:services:backup` 227,
  `:services:tags` 84.
- **Pulled in with them (7 modules, ~13k):** `:libs:material-color-utilities` 8,692 (through `:core:base`), `:data:calendar` 1,185
  and `:data:wikipedia` 284 (through `:data:searchable`), `:data:search-actions` 1,668, `:data:unitconverter` 1,013,
  `:data:currencies` 269 and `:data:calculator` 171 (through `:services:search`). In total the APK carries 27 modules, ~30.3k lines.
  Slice 0's two modules alone pull 17 of them (~25.4k lines).
- **Reference and reuse (`:app:ui`, 58.6k):** the launcher activity, search VM, long-press sheet, transitions and clock parts are
  read, and small pieces copied (table above). Their settings (20.5k) are the biggest reuse candidate; reusing a screen means
  depending on `:app:ui` or copying it, and needs a spec row first.
- **Unused:** `:app:app`, `:data:themes`, the other search sources (files, contacts, locations, weather, notifications, widgets,
  music, badges, feed, accounts), plugins (`:plugins:sdk`, `:data:plugins`, `:services:plugins`), `:core:devicepose` and the libs
  nextcloud, owncloud, webdav and address-formatter.
- **Manifest:** upstream's app asks for 24 permissions, among them location, contacts and `MANAGE_EXTERNAL_STORAGE`, mostly from the
  unused modules. Which ones the engine modules' own manifests add is measured in slice 0, and the merged list is pinned in CI
  (spec M8.7).
- **Identity:** Tin Home keeps `io.github.egoushka.tin.home`, so upstream Kvaesitso installs beside it for M8.2 and M8.3. Upstream's
  copyright notices stay in `android/kvaesitso`, and tin's README says Tin Home is built on Kvaesitso by MM2-0.

## Why not the others

- **Olauncher (6.1k, Views):** the smallest start, with a clean app list (`helper/Utils.kt:67-145`, collator sort, private space).
  It has no icons, no ranking, no file backup, no contract, no crash handler, no tests, and its package callbacks are no-ops. There
  is no engine to depend on, only code to port.
- **KISS (22.7k Java):** the best ranking (`utils/fuzzy/`, frecency SQL), icon packs and the only instrumented tests. But it is one
  module with ListView UI, so its engine can't be taken without its UI.
- **Fossify (6.8k):** the A–Z scroller and a compact long-press menu. But it has no package listener, no work profile, no ranking
  and no tests.

## Risks

- **Two builds in one:** Kvaesitso is on AGP 9.3.1 and tin on 9.4.1; Gradle may refuse to load two AGP versions. Aligning them is
  the one patch S0.1 allows. If more than that is needed, slice 0's stop check fires.
- **Koin:** the engine wires itself with Koin modules; `:home` has no DI and has to start Koin with every module in the closure.
  A missing binding fails at run time, not at build time, which M1.1's render test is there to catch.
- **Start time:** Compose, Koin and a 33-version Room schema are heavy. S0.6 measures it against today's Tin Home, M8.2 against
  upstream Kvaesitso. If it is slow, look at Koin start-up and Room first.
- **Upstream drift:** with no local edits, `git subtree pull` brings each release; a release that changes an engine API breaks
  `:home`'s build, not upstream's files.
- **UNKNOWN:** the internals of `services/icons`, the engine modules' consumer ProGuard rules and manifest permissions, whether
  `GestureNavContract` works on GrapheneOS with Android 17, and Kvaesitso's real start time on the Pixel.

## What we learned

- **Kvaesitso's engine is separable from its UI:** only `:app:app` depends on `:app:ui`. Source: `grep` over the modules'
  `build.gradle.kts` at `328073f`.
- **The engine is not leaf-clean:** `:services:icons` reaches `:data:calendar` and `:data:wikipedia` through `:data:searchable`,
  and `:services:search` reaches four more data modules. Source: the closures above.
- **None of the four launchers ships a baseline profile or macrobenchmark**, so M8 has to be written. Source: `find` and `grep`
  over all four clones.
- **Kvaesitso is the only one of the four with `GestureNavContract`.** It is AOSP code under Apache-2.0
  (`core/base/.../GestureNavContract.kt`).
- **Kvaesitso ranks by an exponential launch weight, not by time decay.** Source: `SearchableDao.touch` at `328073f`.
- **Lawnchair's top-level licence is Apache-2.0.** Source: its `LICENSE.txt`.
