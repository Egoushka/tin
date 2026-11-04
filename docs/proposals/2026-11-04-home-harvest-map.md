# Harvest map: which launcher's code builds Daily Home

2026-11-04 · proposal by Claude; the base pick is the agent's, made under Yehor's Q2 "fork and cut down" · spec:
`docs/specs/2026-11-04-daily-home.md` (M1–M8) · clones (read-only) in `~/Projects/oss/`: `Kvaesitso`, `Olauncher`, `KISS`,
`Launcher` (Fossify).

## Bottom line

**Fork Kvaesitso v1.41.0 (`328073f38f8472118a377f69cce34a2c86372ab9`) and delete about four fifths of it.** It is the only one
of the four that already does most of M1–M8. It has a crash reporter, a clock with glance parts, favourites, ranked search with
Enter-to-best-match, shortcuts, `GestureNavContract`, file backup and solid work-profile handling. It is also Compose, which the
spec's tests and our sessions already use. Day 1 it is a working launcher; every slice after that is deletion plus a little writing.
Olauncher is the fallback. It starts at 6k lines, but it would need roughly 5–6k lines ported in from three other codebases in
three styles (Java, Views, Compose), and that porting is the "never finish" path Yehor ruled out.

**The check that stops the pick:** slice 1 (fork and cut) must build, keep upstream's remaining unit tests green, and bring
`:app:ui` under 12k lines without rewriting a screen. If it can't, switch to Olauncher before slice 2.

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

Action: **keep** = it is in the base already; **port** = copy from another repo, keeping its licence header; **write** = nobody
has it; **reference** = read it, don't copy it.

| Item | Source | Paths (repo-relative) | Lines | Action |
|---|---|---|---|---|
| M1 crash capture | Kvaesitso | `core/crashreporter` (uncaught handler chains to the previous one; lists the last 7 days of reports) | 792 | keep: it shows the stack trace on the phone |
| M1 exit-reason count (M1.7) | — | `ApplicationExitInfo` at start, `Activity.dump` | ~80 | write |
| M1 role request | Fossify | `extensions/Context.kt` 86 ff., `MainActivity.kt:932` (`RoleManager.createRequestRoleIntent(ROLE_HOME)`) | ~30 | port: Kvaesitso has no `RoleManager` call |
| M1 render-everything test | KISS (pattern only) | `app/src/androidTest/.../MainActivityTest.java`, `AbstractMainActivityTest` | — | reference; write `RenderEverythingTest` in Compose |
| M2 clock and glance line | Kvaesitso | `app/ui/.../launcher/widgets/clock/parts/`: `PartProvider.kt` 14, `DatePartProvider` 139, `AlarmPartProvider` 132 | ~300 of 3,611 | keep these; delete the other clock faces and parts |
| M2 glance: next event within 2 h | — | a `PartProvider` on `CalendarContract.Instances` | ~100 | write (Kvaesitso's calendar module goes, it is a search source) |
| M2 favourites | Kvaesitso | `app/ui/.../search/favorites/SearchFavorites.kt` 81, `SearchFavoritesVM.kt` 23, `services/favorites` 94 | ~200 | keep; move to the lower half of the screen (M2.3) |
| M2 search bar | Kvaesitso | `app/ui/.../launcher/searchbar/` 377 | 377 | keep |
| M3 app list, packages, profiles | Kvaesitso | `data/applications` 874 (`AppRepository.kt` with `LauncherApps.Callback`), `core/profiles` 225 | 1,099 | keep |
| M3 ranking | Kvaesitso | `data/database/.../SearchableDao.kt:168` `touch` (launch weight: the launched app's rises, all others decay), `SearchVM.kt:410-436` `applyRanking` (`score*0.6 + weight*0.4`) | in kept modules | keep; KISS's frecency (`db/DBHelper.java:90-165`, `searcher/QuerySearcher.java:49-62`) is the reference if M3.4 fails |
| M3 Enter opens top hit | Kvaesitso | `SearchVM.launchBestMatchOrAction` (line 143) | in kept modules | keep |
| M3 A–Z scroller | Fossify (behaviour) | `fragments/AllAppsFragment.kt`, `adapters/LaunchersAdapter.kt` (Views, via `RecyclerViewFastScroller`) | ~150 in Compose | write; Fossify's is Views and won't port |
| M4 long-press, shortcuts | Kvaesitso | `app/ui/.../search/apps/AppItem.kt` 569 (cut share and notifications), `data/appshortcuts` 895 | ~1.2k | keep |
| M4 hide | Kvaesitso | `data/searchable/.../VisibilityLevel.kt`, `SavableSearchableRepository.kt`; settings `settings/hiddenitems/` 418 | in kept modules | keep; the long-press entry for Hide is UNKNOWN, add it if missing |
| M5 icons | Kvaesitso | `services/icons` 2,566 (Coil-based; adaptive handling internals UNKNOWN) | 2,566 | keep; cut icon packs if they are separable (out of scope) |
| M5 never-blank fallback | Fossify | `extensions/Context.kt` `getDrawableForPackageName` (badged icon, then `getApplicationIcon`), `MainActivity.kt:1082-1130` | ~60 | port the chain, add the letter fallback (M5.2) |
| M6 light, dark, colours | Kvaesitso | `ui/theme/LauncherTheme.kt` (System pref uses `isSystemInDarkTheme()`), `data/themes` 2,094, `libs/material-color-utilities` 8,692 | 10.8k | replace with `dynamicLight/DarkColorScheme` (~40 lines) and delete both modules, if M6.1 passes |
| M6 `GestureNavContract` | Kvaesitso (AOSP code, Apache-2.0 header) | `core/base/.../com/android/launcher3/GestureNavContract.kt` 79, `LauncherActivity.kt` `onNewIntent`, `launcher/transitions/` ~120 | ~200 | keep; check on GrapheneOS (UNKNOWN) |
| M7 export and restore | Kvaesitso | `services/backup` 227 (`BackupManager.kt`, `BackupMetadata.kt`), `core/base/.../backup/Backupable.kt`, `settings/backup/` ~180 | ~450 | keep; only the kept repositories implement `Backupable` |
| M8 R8 | Kvaesitso | `app/app/build.gradle.kts` (minify and resource shrinking already on) | — | keep |
| M8 baseline profile, macrobenchmark | — | new `:baselineprofile` module (AndroidX Baseline Profile Gradle plugin) | ~150 | write |
| M8 speed and jank scripts | tin | `scripts/home-baseline.sh`, `scripts/home-jank.sh` | — | port, with the package, activity and drawer swipe as parameters |

## Kvaesitso: keep, cut, replace

Per-module lines measured as above. The `:app:ui` split is an estimate from per-package sizes (`settings/` 20.5k,
`launcher/search/` 9.0k, `launcher/widgets/` 7.6k, `component/` 8.1k).

- **Keep (~17k):** `:app:app` 165, `:core:base` 2,614, `:core:shared` 1,499, `:core:preferences` 2,514 (to be trimmed),
  `:core:ktx` 540, `:core:compat` 32, `:core:i18n`, `:core:profiles` 225, `:core:permissions` 319, `:core:crashreporter` 792,
  `:data:applications` 874, `:data:appshortcuts` 895, `:data:searchable` 634, `:data:database` 1,823, `:data:customattrs` 556,
  `:data:i18n` 148, `:services:icons` 2,566, `:services:search` 373, `:services:favorites` 94, `:services:backup` 227,
  `:services:tags` 84 (if `customattrs` needs it).
- **`:app:ui` (58.6k now), target under 12k:** keep the launcher activity, scaffold, search bar, app results, favourites, the clock
  with its date and alarm parts, the long-press sheet, transitions, theme, and from settings only hidden items and backup. Delete the
  other settings screens (about 19k), the other widgets and clock faces, and the search UI for every non-app result.
- **Replace (10.8k):** `:data:themes` and `:libs:material-color-utilities` with system dynamic colour (M6.1).
- **Delete whole (~23k):** weather, locations, files, calendar (as a search source), unit converter, currencies, calculator,
  contacts, wikipedia, websites, search actions, notifications, widgets, plugins (`:plugins:sdk`, `:data:plugins`,
  `:services:plugins`), music, badges, feed, accounts, global actions, `:services:widgets`, `:core:devicepose`, and the libs
  nextcloud, owncloud, webdav and address-formatter.
- **Manifest:** upstream asks for 24 permissions, among them `INTERNET`, location, contacts and `MANAGE_EXTERNAL_STORAGE`. Daily
  Home keeps an allowlist checked in CI (spec M8.7): `QUERY_ALL_PACKAGES`, `ACCESS_HIDDEN_PROFILES`, `REQUEST_DELETE_PACKAGES`,
  `EXPAND_STATUS_BAR`, `READ_CALENDAR`, plus whatever AndroidX adds. It has no network permission at all.
- **Identity:** a new `applicationId` (not `de.mm20.launcher2*`), so upstream Kvaesitso installs beside it for M8.2 and M8.3. Keep
  upstream's copyright notices and say "forked from Kvaesitso by MM2-0" in the README. Keep an `upstream` remote; merging upstream is
  optional and done by hand.

Result: about 29k lines with `:app:ui` at 12k, down from 103k. That is an estimate until slice 1 measures it, and M8.6's scope
budget is set from that measurement.

## Why not the others

- **Olauncher (6.1k, Views):** the smallest start, with a clean app list (`helper/Utils.kt:67-145`, collator sort, private space).
  It has no icons, no ranking, no file backup, no contract, no crash handler, no tests, and its package callbacks are no-ops. Adding
  all that is about 5–6k lines of porting.
- **KISS (22.7k Java):** the best ranking (`utils/fuzzy/`, frecency SQL), icon packs and the only instrumented tests. But it has no
  clock, no dynamic colour, no contract, exports to the clipboard only, and uses ListView UI.
- **Fossify (6.8k):** the A–Z scroller and a compact long-press menu. But it has no package listener, no work profile, no ranking
  and no tests.

## Risks

- **Framework weight:** Compose, Koin and a 33-version Room schema are heavy for a minimal launcher. Start time is unmeasured until
  M8.2. If the cut build starts slower than upstream Kvaesitso, look at Koin start-up and Room migrations first.
- **Cutting a 51-module graph:** a module that looks separable may be wired in through Koin. Delete one module per commit, building
  after each.
- **Upstream drift:** after the cut, upstream merges are partial at best. Plan on taking fixes by cherry-pick only.
- **UNKNOWN:** the internals of `services/icons`, the long-press Hide entry, whether `GestureNavContract` works on GrapheneOS with
  Android 17, and Kvaesitso's real start time on the Pixel.

## What we learned

- **None of the four launchers ships a baseline profile or macrobenchmark**, so M8 has to be written. Source: `find` and `grep`
  over all four clones.
- **Kvaesitso is the only one of the four with `GestureNavContract`.** It is AOSP code under Apache-2.0
  (`core/base/.../GestureNavContract.kt`).
- **Kvaesitso ranks by an exponential launch weight, not by time decay.** Source: `SearchableDao.touch` at `328073f`.
- **Lawnchair's top-level licence is Apache-2.0.** Source: its `LICENSE.txt`.
