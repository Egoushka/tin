package io.github.egoushka.tin.home.signals

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

/** What the sky is doing, in the eight words Home has for it. */
enum class Sky { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Thunder;

    companion object {
        /** Open-Meteo's WMO codes (open-meteo.com/en/docs); null for a code outside the table. */
        fun of(code: Int): Sky? = when (code) {
            0, 1 -> Clear
            2 -> PartlyCloudy
            3 -> Cloudy
            45, 48 -> Fog
            51, 53, 55, 56, 57 -> Drizzle
            61, 63, 65, 66, 67, 80, 81, 82 -> Rain
            71, 73, 75, 77, 85, 86 -> Snow
            95, 96, 97, 99 -> Thunder
            else -> null
        }
    }
}

/** The current temperature in whole degrees Celsius and the sky; [sky] is null when the code is not in the table. */
data class Weather(val celsius: Int, val sky: Sky?, val day: Boolean) {
    /** A Lucide name; snow and fog share the cloud until the subset has its own glyphs. */
    val icon: String? get() = when (sky) {
        null -> null
        Sky.Clear -> if (day) "sun" else "moon"
        Sky.Drizzle, Sky.Rain -> "drop"
        Sky.Thunder -> "zap"
        else -> "cloud"
    }

    companion object {
        // Kyiv. A city, not the phone's position: no location permission (docs/specs/2026-11-03-home-weather.md).
        const val LATITUDE = 50.45
        const val LONGITUDE = 30.52
        const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"

        fun url(base: String = ENDPOINT) =
            "$base?latitude=$LATITUDE&longitude=$LONGITUDE&current=temperature_2m,weather_code,is_day"

        /** The body of `GET /v1/forecast`; null when it holds no temperature or no weather code, never a made-up 0. */
        fun parse(json: String): Weather? = try {
            val current = JSONObject(json).getJSONObject("current")
            if (current.isNull("temperature_2m") || current.isNull("weather_code")) null
            else Weather(
                current.getDouble("temperature_2m").roundToInt(),
                Sky.of(current.getInt("weather_code")),
                current.optInt("is_day", 1) == 1,
            )
        } catch (_: JSONException) {
            null
        }
    }
}
