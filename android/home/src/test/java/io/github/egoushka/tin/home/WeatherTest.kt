package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.signals.Sky
import io.github.egoushka.tin.home.signals.Weather
import io.github.egoushka.tin.home.signals.WeatherSource
import io.github.egoushka.tin.home.signals.WeatherSource.State
import io.github.egoushka.tin.home.signals.WeatherSource.Status
import io.github.egoushka.tin.home.theme.Lucide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    // Fetched from api.open-meteo.com on 2026-11-02 for Kyiv.
    private val real = """{"latitude":50.423317,"longitude":30.491209,"utc_offset_seconds":0,""" +
        """"current_units":{"temperature_2m":"°C","weather_code":"wmo code","is_day":""},""" +
        """"current":{"time":1793655900,"interval":900,"temperature_2m":7.7,"weather_code":45,"is_day":0}}"""

    private fun body(temperature: String, code: Int = 3, day: Int = 1) =
        """{"current":{"time":1,"interval":900,"temperature_2m":$temperature,"weather_code":$code,"is_day":$day}}"""

    private val allCodes = listOf(
        0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 97, 99,
    )
    private val minute = 60_000L

    @Test fun parsesARealResponse() {
        assertEquals(Weather(8, Sky.Fog, day = false), Weather.parse(real))
    }

    @Test fun roundsToWholeDegrees() {
        val shown = listOf("7.7", "-0.4", "-3.2", "-3.5", "0.5").map { "${Weather.parse(body(it))!!.celsius}°" }
        assertEquals(listOf("8°", "0°", "-3°", "-3°", "1°"), shown) // never "-0°"
    }

    @Test fun badBodiesGiveNoWeather() {
        val bad = listOf(
            "", "{}", "<html>", """{"latitude":50.4}""", """{"current":5}""", """{"current":{}}""",
            """{"current":{"temperature_2m":null,"weather_code":3}}""",
            """{"current":{"temperature_2m":7.7,"weather_code":null}}""",
            """{"current":{"temperature_2m":7.7}}""",
            """{"current":{"temperature_2m":"warm","weather_code":3}}""",
        )
        bad.forEach { assertNull(it, Weather.parse(it)) }
    }

    @Test fun everyWmoCodeHasASky() {
        assertEquals(29, allCodes.size)
        allCodes.forEach { assertNotNull("code $it", Sky.of(it)) }
    }

    @Test fun unknownCodeHasNoSky() {
        listOf(4, 100, -1).forEach { assertNull("code $it", Sky.of(it)) }
        val unknown = Weather.parse(body("5.0", code = 4))!!
        assertEquals(5, unknown.celsius)
        assertNull(unknown.sky)
        assertNull(unknown.icon)
    }

    @Test fun iconFollowsSkyAndDaylight() {
        fun icon(sky: Sky, day: Boolean) = Weather(0, sky, day).icon
        assertEquals("sun", icon(Sky.Clear, day = true))
        assertEquals("moon", icon(Sky.Clear, day = false))
        assertEquals("cloud", icon(Sky.PartlyCloudy, day = true))
        assertEquals("drop", icon(Sky.Rain, day = false))
        assertEquals("zap", icon(Sky.Thunder, day = true))
    }

    @Test fun everyWeatherIconIsInLucide() {
        for (sky in Sky.entries) for (day in listOf(true, false)) {
            val icon = Weather(0, sky, day).icon
            assertTrue("$sky $day -> $icon", icon in Lucide.bodies.keys)
        }
    }

    @Test fun staleAfterThreeMissedRefreshes() {
        val state = State(Weather(8, Sky.Fog, false), Status.Ok, at = 1_000)
        val limit = 1_000 + 3 * WeatherSource.INTERVAL_MS
        assertFalse(state.isStale(limit))
        assertTrue(state.isStale(limit + 1))
        assertEquals(45 * minute, 3 * WeatherSource.INTERVAL_MS)
        assertFalse(State(null, Status.Unavailable).isStale(Long.MAX_VALUE)) // nothing to fade
    }

    @Test fun ageAppearsAfterOneMissedRefresh() {
        val state = State(Weather(8, Sky.Fog, false), Status.Ok, at = 0)
        assertFalse(state.showsAge(15 * minute - 1_000)) // 14 min 59 s
        assertTrue(state.showsAge(15 * minute))
        assertFalse(State(null, Status.Unavailable).showsAge(Long.MAX_VALUE))
    }
}
