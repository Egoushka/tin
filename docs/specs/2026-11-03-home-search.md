# Spec: Home search, the full AppSearch card

Date: 2026-11-03. Status: proposal, built in the same PR as its checks. Design source:
`docs/design/night-glass/cards/AppSearch.md` and `AppSearch.png` (the middle phone, "Typing"). Builds on the drawer
of `2026-11-02-home-launcher.md` (scope item 5, check H6) and the drawer PR `perf/home-drawer-jank`.

## Goal

Typing in Home's search bar answers with everything Yehor could mean, in one panel, and Enter does the likeliest thing:
apps, people, "Ask Tin", "Search the web". Today the bar searches app labels only (`apps/Apps.kt`, `ui/Drawer.kt`).

Value hypothesis: one query reaches an app, a person or an answer without choosing the tool first, so the bar replaces
the stock launcher's search plus the contacts app. Cheapest test, from the launcher spec: 7 days as the default Home.
**It has failed if Yehor opens the Contacts app to find a person, or the keyboard lags behind his typing.**

## Facts this rests on

- The card: "Typing: results grouped as apps, people, ask Tin, search the web. Enter opens the top result."
  The picture shows one glass panel with section labels (APPS, PEOPLE, ASK OR SEARCH), 48 dp rows, the top row lit
  with "Open ↵", a phone icon at the end of a person.
- Night glass constants: rows radius 16 (`Dimen.radiusRow`), 48 dp targets, five type sizes, Lucide icons, calm has no
  colour (light means attention; a search result is calm).
- Matching today (`AppSearch`): prefix beats word start beats substring, case and diacritics ignored. Each keystroke
  compiles a `Regex` per label and re-normalises every label. That is the cost to remove before adding 1000 contacts.
- Home is the default launcher, so `LauncherApps.hasShortcutHostPermission()` is true and `getShortcuts` works. When
  it is not (a debug install that is not the Home), the shortcut source returns nothing.
- `VoiceIntent.of` already decides what a spoken line does: a deciding word ("approve", "підтверди") opens the oldest
  waiting item, anything else is a web search. Voice opens things and never decides them.
- `Actions.webSearch` sends `ACTION_WEB_SEARCH`; the manifest already declares it under `<queries>`.

## Data sources

| Group | Source | Permission | Status |
|---|---|---|---|
| Apps | `LauncherApps.getActivityList` (existing `AppRepository`) | none | exists |
| App shortcuts | `LauncherApps.getShortcuts` (dynamic, manifest, pinned), `startShortcut`; refreshed on `onShortcutsChanged` | default launcher | this PR |
| People | `ContactsContract.Contacts` (all contacts: id, lookup key, display name, has-phone; no visible-group filter, because messenger-synced contacts are never in one); the number is read only when the phone icon is tapped; refreshed by a `ContentObserver` | `READ_CONTACTS`, runtime | this PR |
| Ask Tin | `voice/Ask.kt` over `VoiceIntent.of`; the engine that answers belongs to the voice session | none | hand-off only |
| Web | `Actions.webSearch` (`ACTION_WEB_SEARCH`, the default browser's own search) | none | exists |

## Decisions (proposals; Yehor can overturn any in the PR)

1. **Ranking across groups.** Each group's best match has a tier: 0 prefix, 1 word start, 2 anywhere. Apps and people
   are ordered by their best tier; a tie goes to apps. Ask Tin and the web row are always last. Within a group:
   tier, then A–Z on the folded label. Why: an exact app name should beat a stranger's name, and a name that only
   matches by a prefix should beat an app that matches in the middle.
2. **Enter opens the top result**: the first real row. Nothing matches → the Ask Tin row, which for a line without a
   deciding word is the same web search Enter did before.
3. **Contacts are asked by a tap, never by typing.** With the permission missing, the people group shows one row
   "Allow access to contacts" (the calendar and notification rows work the same way). A tap shows the system dialog.
   If the dialog does not appear or is refused, the row becomes "Contacts are off" and opens the app's settings.
   That row is never the top result. Why: a dialog over the keyboard on the first letter breaks the one thing the bar
   is for.
4. **A person's row opens the contact; calling is the phone icon.** Enter never dials. Why: Enter is the least
   deliberate tap and a call is hard to take back.
5. **Shortcuts live in the apps group**, match on their own label only (not the app's), and come after an app of the
   same tier. At most 6 rows for apps and shortcuts together and 4 people (starting values; the panel scrolls).
6. **Folding.** Lower case, accents removed (so "й" matches "и", "ї" matches "і"), apostrophes removed (`'`, `’`, `ʼ`
   are the same letter in "Мар'яна"). No transliteration between Latin and Cyrillic: "irina" does not find "Ірина".
   Later, if Yehor wants it, as its own change.

## Scope

1. `search/` package: folding and the precomputed index (`Match.kt`), the contact and shortcut sources, `Results`
   (grouping, order, top result).
2. `voice/Ask.kt`: one function that runs a line the way voice does; Home's `listen` uses it too, so typed and spoken
   lines cannot drift apart.
3. `ui/Drawer.kt` and `ui/SearchPanel.kt`: while the query is not blank the panel replaces the grid; Enter runs the
   top result; the bar and the header stay as they are.
4. Strings in English and Ukrainian (the "↵" in "Open ↵" is text, as on the card), the Lucide bodies `ask` and `user`
   from `lucide-icons.json`, `READ_CONTACTS` in the manifest.
5. Out of scope: suggested apps, the voice answer card (third phone), Latin/Cyrillic transliteration, contact photos,
   learning from usage.

## Acceptance checks

Unit checks run from `android/` with no phone (`./gradlew :home:testDebugUnitTest`). `HS` = Home search.

| # | Check | Command / method | Pass |
|---|---|---|---|
| HS1 | Matcher keeps H6 | `AppSearchTest`: tiers, case, diacritics, empty query A–Z, no match (assertions unchanged after the matcher moved to `search/`) | passes |
| HS2 | Ukrainian and diacritics | `SearchMatchTest`: `ірина` and `ІРИНА` find `Ірина`; `йосип` finds `Йосип`; `марян` finds `Мар'яна`, `Мар’яна` and `Мар'яна`; `jose` finds `José`; `zoe` finds `Zoë`; `irina` does not find `Ірина` | passes |
| HS3 | Ranking across groups | `ResultsTest`: tie goes to apps; a person at a lower tier comes before an app; Ask and web last; a shortcut follows an app of the same tier; blank query gives no results; row caps | passes |
| HS4 | Enter opens the top result | `ResultsTest`: `top` is an app, a person or a shortcut when one matches, else the Ask row; the permission row is never `top` | passes |
| HS5 | People and `READ_CONTACTS` | `ResultsTest`: access "ask" gives one allow row and no people, "denied" gives one off row, "granted" gives people and no row when nothing matches. Device: D1, D2 | passes |
| HS6 | Shortcuts | `ResultsTest` for ranking; device D4 for the real `getShortcuts` | passes |
| HS7 | Ask Tin hands off | `AskTest`: "approve the deploy" with a waiting item opens that item's deep link and calls nothing else; the same in Ukrainian ("підтверди деплой"); no waiting item opens the queue; any other line is a web search of the trimmed line | passes |
| HS8 | Web row | `ResultsTest`: the web row carries the trimmed query. Device D6 and `adb shell cmd package resolve-activity --brief -a android.intent.action.WEB_SEARCH` | prints an activity |
| HS9 | Search is instant | `SearchSpeedTest`: 300 apps and 1000 contacts (Latin, Cyrillic, accents), 8 queries; median of the whole lookup (both indexes plus grouping) over 200 runs after 50 warm-ups | under 8 ms, one frame at 120 Hz (starting value); the measured median goes in the PR body |
| HS10 | Night glass | `TokensTest` (the new icon bodies equal `lucide-icons.json`) and `grep -rn "fontSize\|\.sp\b" android/home/src/main/java/io/github/egoushka/tin/home/search android/home/src/main/java/io/github/egoushka/tin/home/ui/SearchPanel.kt` | tests pass, grep prints nothing |
| HS11 | Ukrainian | lint `MissingTranslation` is an error; device D5 | no error, no clipped text |
| HS12 | Builds | `./gradlew :home:testDebugUnitTest :home:assembleDebug :home:lintDebug` | all succeed |

Device checks (phone on USB, Home installed with `adb install -r home/build/outputs/apk/debug/home-debug.apk`):

| # | Check | Method | Pass |
|---|---|---|---|
| D1 | Home starts without the permission | `adb shell pm revoke io.github.egoushka.tin.home android.permission.READ_CONTACTS`, `adb shell am force-stop io.github.egoushka.tin.home`, `adb shell am start -W -n io.github.egoushka.tin.home/.HomeActivity` | `Status: ok` |
| D2 | No dialog until the row is tapped | tap the bar, type `si`; `adb shell uiautomator dump /sdcard/w.xml; adb shell grep -c "Allow access to contacts" /sdcard/w.xml` and the same with `permissioncontroller` | first is 1 or more, second is 0; tapping the row shows the dialog; refusing it shows "Contacts are off" |
| D3 | People | `adb shell pm grant io.github.egoushka.tin.home android.permission.READ_CONTACTS`; type the start of a saved name | the person is listed; Enter opens the contact; the phone icon opens the dialer with the number |
| D4 | Shortcuts | with Home as the default launcher, type a label from an app's shortcuts (for example Vanadium's "New incognito tab") | the row is listed under apps and opens that shortcut |
| D5 | Ukrainian | `adb shell cmd locale set-app-locales io.github.egoushka.tin.home --locales uk`, open the drawer, type | every string Ukrainian, nothing clipped |
| D6 | Ask and web | type a word nothing matches, press Enter | Ask Tin is lit and Enter opens the browser's search; with a waiting item in the fake provider, `approve deploy` opens that item and decides nothing |
