# Spec: Home weather in the glance

Date: 2026-11-03 (the wave plan's date; written 2026-11-02). Status: proposal. Builds on the launcher spec
(`2026-11-02-home-launcher.md`), which left weather as "UNKNOWN; no source in the map". This spec resolves it.

## Goal

Home's glance shows the weather next to the clock and date, the one stock-launcher job Home still lacks
(`docs/design/night-glass/cards/HomeNightGlass.md`: "clock, date, weather"). One line under the date: an icon, whole
degrees and one word, as in the card ("☁ 7° Cloudy" / "☁ 7° Хмарно").

Value hypothesis: with the temperature on the first screen, Yehor stops opening a weather app on waking, and a wrong or
old number never reads as current because it says its age. Cheapest test: the 7-day Home trial. **It has failed if
Yehor still opens a weather app in those 7 days, or the line shows an old value without its age.**

## Facts this rests on

- **A real response, fetched 2026-11-02** (`https://api.open-meteo.com/v1/forecast?latitude=50.45&longitude=30.52&current=temperature_2m,weather_code,is_day`):
  `{"latitude":50.423317,"longitude":30.491209,"utc_offset_seconds":0,"current_units":{"temperature_2m":"°C","weather_code":"wmo code","is_day":""},"current":{"time":1793655900,"interval":900,"temperature_2m":7.7,"weather_code":45,"is_day":0}}`.
  `interval: 900` is the data step: polling faster than 15 minutes returns the same number.
- **Terms** (`open-meteo.com/en/terms`, read 2026-11-02): the free API needs no key; under 10,000 calls a day, 5,000 an
  hour, 600 a minute; non-commercial use only, and "private … apps that do not have subscriptions or advertising" and
  "personal home automation" are named as non-commercial; data is CC BY 4.0, so it needs attribution; they may block
  misuse. A 15-minute poll is 96 calls a day, about 1% of the cap.
- **Weather codes** (`open-meteo.com/en/docs`, "WMO Weather interpretation codes"): 0, 1, 2, 3, 45, 48, 51, 53, 55, 56,
  57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 97, 99.
- **No existing source.** `~/Projects/context/map.md`, the homelab gateway tools and the personal repos have no
  queryable weather. The homelab Glance dashboard (`personal/homelab`, `stacks/glance/config/pages/you.yml`) is a
  `weather` widget for Kyiv that calls Open-Meteo itself and exposes no API, so there is nothing to reuse. A gateway
  endpoint in front of Open-Meteo would add a server hop for no gain, and the phone is not on the tailnet yet (launcher
  spec, slice 5).
- **GrapheneOS location.** Network location is an opt-in toggle (Settings > Location > Location services), Wi-Fi based
  at the time of the 2025 release notes and forum posts I found, with cell towers announced as future work. Whether it
  is on, on this phone, is **UNKNOWN**.
- **Icons.** `lucide-icons.json` has `sun`, `moon`, `cloud`, `drop` and `zap`. It has no snowflake and no fog glyph.
- **Staleness is defined in `layer/Freshness.kt`**: stale after 3 missed refreshes (`MISSED_TO_STALE`), shown at 55%
  opacity (`Motion.STALE_ALPHA`) with its age.

## Decision: source and location

| Question | Pick | Why |
|---|---|---|
| Source | **Open-Meteo, straight from the phone, no key** | It is the only candidate: no source exists in the map, Yehor's own Glance dashboard already trusts it for Kyiv, and the terms cover a private app. No paid service, no account, no server of ours in the path. |
| Location | **A fixed Kyiv (50.45, 30.52) in code**, no location permission | See the ranking below. |

Location options, ranked:

1. **Fixed Kyiv constant (picked).** No new permission, so Home asks only for what it shows. It works the first second
   after install and cannot silently depend on a toggle. Yehor lives and works in Kyiv. The request carries the
   coordinates of a city, never the phone's position.
2. **A city row in Tune, with Open-Meteo's geocoding.** Strongest version: type "Lviv" once, Home remembers it. It is a
   second endpoint, a text field and a DataStore key; worth it only if the trial shows Yehor travelling. Later slice.
3. **Coarse location (`ACCESS_COARSE_LOCATION`) with Kyiv as the fallback.** Follows the phone, but it adds a runtime
   permission and a dependency on GrapheneOS network location being on (UNKNOWN), and it would still need option 1 as the
   fallback. Not picked.

The known cost of option 1: away from Kyiv the line is wrong and does not say so. The stop condition: if Yehor is outside
Kyiv during the 7-day trial and the line misleads, build option 2 before anything else.

## Design

**Signal source** `signals/WeatherSource.kt`, shaped like `HomelabSource`:
- `State(weather, status, at)` with `Status { Loading, Unavailable, Ok }`. `at` is the time of the last good fetch.
- A failed fetch keeps the last good value and does **not** move `at`, so its age keeps growing and the line fades. With
  no good value yet, a failed fetch gives `Unavailable` and never a number. Any exception (offline, DNS, timeout,
  `SecurityException`, bad body) is a failed fetch; nothing reaches the UI thread.
- Poll every **15 minutes** (`INTERVAL_MS`, the data's own step). After a failure retry in **2 minutes** (`RETRY_MS`,
  starting value, not calibrated): Home starts at boot before Wi-Fi is up, and waiting 15 minutes with an empty line
  would be wrong.
- `refreshIfDue()` is called from the glance's existing minute tick. Kotlin's `delay` does not count time the CPU sleeps,
  so after a night in a pocket the loop could lag; the tick brings the first glance of the morning up to date within a
  minute of the screen coming on.
- The source is created in `Signals` (one per process) like the others.

**The line** `ui/WeatherLine.kt`, one call site in `Glance` (`ui/Home.kt`):
- Right-aligned under the date: Lucide icon 16 dp (`Dimen.glyphSmall`, the stroke from Tune), then `7° Cloudy` in
  `NgType.body` (13/16, tabular figures, `ink-2`). Calm has no colour. No new text size.
- Age on the surface: nothing extra while the value is within one refresh (as in the card). Once one refresh is missed
  the age is appended in the same style ("7° Cloudy · 22 min"), because that is the moment the age is information; a
  permanent "· 4 min" under the date would teach the eye to skip ages. After 3 missed refreshes (45 min) the line fades
  to 55% over 1000 ms and keeps its age; fresh data brings it back in 100 ms; reduced motion is instant
  (`animatorScale()`).
- `Loading`: nothing is drawn (no flash). `Unavailable` with no value: "Weather unavailable" at 55%, no icon, no number.
- A weather code outside the table shows the temperature alone, with no icon and no word.
- Icon by code: 0 and 1 `sun` (`moon` when `is_day` is 0); 2, 3, 45, 48, 71–77, 85, 86 `cloud`; 51–57, 61–67, 80–82
  `drop`; 95–99 `zap`. Words: Clear, Partly cloudy, Cloudy, Fog, Drizzle, Rain, Snow, Thunderstorm, in English and
  Ukrainian. Snow and fog share the cloud glyph until the Lucide subset has its own; the word carries the meaning.
- Temperature: rounded to whole degrees, ties toward positive infinity (`roundToInt`), printed as an integer so a
  small negative never shows "-0°".

**Not done, on purpose:** a forecast, highs and lows, "rain in an hour", a warn light for storms (the ladder is
colourless at rest), a persisted last value across process death (offline right after a restart reads "Weather
unavailable" rather than a remembered number; how often Home is killed is UNKNOWN and is watched in the trial).

**Permissions and privacy.** Only `INTERNET`, already declared. No runtime permission exists to revoke; the real
"permission revoked" case on GrapheneOS is the per-app Network toggle, which makes the socket fail and is handled as a
failed fetch. Open-Meteo sees the phone's IP address like any website.

**Attribution.** Data is CC BY 4.0. `assets/licenses/open-meteo.txt` carries the notice and link, as
`inter-OFL.txt` does for Inter. Home is not distributed; a public release needs the notice shown in the app.

## Scope of this build PR

1. `signals/Weather.kt`: the model, `parse`, the WMO mapping, the request URL. `signals/WeatherSource.kt`.
2. `Lucide.bodies` gains `sun`, `moon`, `cloud`, `drop`, `zap`, copied from `lucide-icons.json`.
3. `ui/WeatherLine.kt`; one call and a `Column` in `Glance`; one line in the glance's minute tick; `weather` in `Signals`.
4. Strings in `values/` and `values-uk/`; the attribution file.
5. Tests below. `testImplementation("org.json:json")` so `Weather.parse` runs on real JSON in unit tests (the Android
   stub of `org.json` returns defaults under `isReturnDefaultValues`).

## Acceptance checks

Run from `android/`. `W` = machine-run, `D` = on the Pixel 8a.

| # | Check | Command / method | Pass |
|---|---|---|---|
| W1 | Parses a real response | `./gradlew :home:testDebugUnitTest --tests '*WeatherTest'`: `parsesARealResponse` feeds the 2026-11-02 body above | 7.7 becomes 8, code 45, night |
| W2 | Rounding and sign | `roundsToWholeDegrees`: 7.7, -0.4, -3.2, -3.5, 0.5 | 8, 0, -3, -3, 1; text never "-0°" |
| W3 | Bad bodies are no weather, never 0° | `badBodiesGiveNoWeather`: empty, `{}`, `<html>`, no `current`, null temperature, null code | `parse` returns null each time |
| W4 | Code table | `everyWmoCodeHasASky`, `unknownCodeHasNoSky`, `iconFollowsSkyAndDaylight` | all 29 codes map; 4, 100, -1 map to nothing; sun by day, moon by night |
| W5 | Every icon exists | `everyWeatherIconIsInLucide`; `TokensTest.lucideBodiesAreTheCommittedOnes` | passes; copy equals `lucide-icons.json` |
| W6 | Stale rule | `staleAfterThreeMissedRefreshes`: 45 min fresh, 45 min + 1 ms stale | boundary exact |
| W7 | Age shows after one missed refresh | `ageAppearsAfterOneMissedRefresh` | none at 14 min 59 s, "15 min" at 15 min |
| W8 | Offline keeps the value and its time | `failedFetchKeepsTheLastValueAndItsTime` | value and `at` unchanged after a failure |
| W9 | No value, no number | `firstFailureIsUnavailableNotZero`, `recoveryReplacesTheValue` | `Unavailable`, `weather == null`; then `Ok` |
| W10 | Revoked network or any exception does not escape | `anyExceptionIsAFailedFetch`: reader throws `SecurityException`, `IOException`, `IllegalStateException` | `load` returns normally, state `Unavailable` |
| W11 | Real HTTP path | `readsOverHttp`, `httpErrorIsAFailedFetch`, `refusedConnectionIsAFailedFetch` against a local JDK `HttpServer` | 200 gives `Ok`; 500 and a closed port give `Unavailable` |
| W12 | Schedule | `retriesSoonerAfterAFailure`, `refreshIfDueWaitsForTheInterval` with a fake clock | no read before 2 min after a failure, none before 15 min after a success; one read at each boundary |
| W13 | No new text size | `! grep -nE 'fontSize|[0-9]\.sp\b' home/src/main/java/io/github/egoushka/tin/home/ui/WeatherLine.kt`; `TokensTest` | no match; passes |
| W14 | Builds, lints, both languages | `./gradlew :home:testDebugUnitTest :home:assembleDebug :home:lintDebug` | all succeed; `MissingTranslation` is an error and stays clean |
| D1 | Live value | install, set Home as default, open it online | within 5 s a line under the date shows an icon, a whole-degree value and a word; it matches `curl 'https://api.open-meteo.com/v1/forecast?latitude=50.45&longitude=30.52&current=temperature_2m,weather_code,is_day'` to within rounding |
| D2 | Ukrainian and big text | `adb shell cmd locale set-app-locales io.github.egoushka.tin.home --locales uk`; then `adb shell settings put system font_scale 1.3` | Ukrainian words, the clock is not squeezed or clipped, the longest word ("Мінлива хмарність") ellipsizes rather than wraps; reset `font_scale` to 1.0 after |
| D3 | Network toggle off | GrapheneOS: Settings > Apps > Tin Home > Network off, force-stop, press Home | Home opens normally; the line reads "Weather unavailable" at 55%; turn it on and the value appears within about 2 minutes |
| D4 | Offline keeps the value | with a value showing, airplane mode on | the number stays; at 15 minutes it gains "· 15 min"; at 46 minutes it fades to 55% and says its age |
| D5 | Waking late | leave the screen off for an hour, then wake it | the line is fresh within about a minute, not stale for 15 |
| D6 | Reduced motion | `adb shell settings put global animator_duration_scale 0` | the fade is instant; set it back to 1 |

## Later slices

- A city row in Tune with geocoding (option 2 above), when the trial shows travel.
- A persisted last value, if Home is found to restart often (D3 shows the cost).
- Snowflake and fog glyphs in `lucide-icons.json`.
- Rain in the next hour feeding "time to leave" on the Today card.
