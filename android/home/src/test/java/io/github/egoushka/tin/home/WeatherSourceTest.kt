package io.github.egoushka.tin.home

import com.sun.net.httpserver.HttpServer
import io.github.egoushka.tin.home.signals.Sky
import io.github.egoushka.tin.home.signals.Weather
import io.github.egoushka.tin.home.signals.WeatherSource
import io.github.egoushka.tin.home.signals.WeatherSource.Status
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherSourceTest {
    private val fog = """{"current":{"time":1,"interval":900,"temperature_2m":7.7,"weather_code":45,"is_day":0}}"""
    private val sun = """{"current":{"time":1,"interval":900,"temperature_2m":21.2,"weather_code":0,"is_day":1}}"""

    private var now = 0L
    private var reads = 0
    private var next: () -> String = { fog }

    private fun source() = WeatherSource("test://weather", { reads++; next() }, { now })

    private fun WeatherSource.refresh(force: Boolean = true) = runBlocking { load(force) }

    @Test fun failedFetchKeepsTheLastValueAndItsTime() {
        val source = source()
        now = 1_000
        source.refresh()
        val good = source.state.value
        assertEquals(Weather(8, Sky.Fog, day = false), good.weather)
        assertEquals(1_000L, good.at)

        now = 2_000
        next = { throw IOException("offline") }
        source.refresh()
        assertEquals(good, source.state.value)
    }

    @Test fun firstFailureIsUnavailableNotZero() {
        val source = source()
        assertEquals(Status.Loading, source.state.value.status)
        next = { throw IOException("offline") }
        source.refresh()
        assertEquals(Status.Unavailable, source.state.value.status)
        assertNull(source.state.value.weather)
    }

    @Test fun recoveryReplacesTheValue() {
        val source = source()
        next = { throw IOException("offline") }
        source.refresh()
        now = 5_000
        next = { sun }
        source.refresh()
        val state = source.state.value
        assertEquals(Status.Ok, state.status)
        assertEquals(Weather(21, Sky.Clear, day = true), state.weather)
        assertEquals(5_000L, state.at)
    }

    @Test fun anyExceptionIsAFailedFetch() {
        listOf(SecurityException("network permission revoked"), IOException("dns"), IllegalStateException("boom")).forEach { e ->
            val source = source()
            next = { throw e }
            source.refresh() // returns normally
            assertEquals(e.javaClass.name, Status.Unavailable, source.state.value.status)
        }
        val source = source()
        next = { "<html>captive portal</html>" }
        source.refresh()
        assertEquals(Status.Unavailable, source.state.value.status)
    }

    @Test fun retriesSoonerAfterAFailure() {
        val source = source()
        next = { throw IOException("offline") }
        source.refresh()
        assertEquals(1, reads)
        now = WeatherSource.RETRY_MS - 1
        source.refresh(force = false)
        assertEquals(1, reads)
        now = WeatherSource.RETRY_MS
        source.refresh(force = false)
        assertEquals(2, reads)
    }

    @Test fun refreshIfDueWaitsForTheInterval() {
        val source = source()
        source.refresh()
        assertEquals(1, reads)
        now = WeatherSource.INTERVAL_MS - 1
        source.refresh(force = false)
        assertEquals(1, reads)
        now = WeatherSource.INTERVAL_MS
        source.refresh(force = false)
        assertEquals(2, reads)
        now = WeatherSource.INTERVAL_MS + WeatherSource.RETRY_MS // a minute tick right after: still inside the interval
        source.refresh(force = false)
        assertEquals(2, reads)
    }

    // The real HttpURLConnection path, against a local server.
    private fun serve(status: Int, body: String, seen: MutableList<String> = mutableListOf(), test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/forecast") {
            seen += it.requestURI.toString()
            val bytes = body.toByteArray()
            if (status == 200) {
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.use { out -> out.write(bytes) }
            } else {
                it.sendResponseHeaders(status, -1)
            }
            it.close()
        }
        server.start()
        try {
            test(Weather.url("http://127.0.0.1:${server.address.port}/v1/forecast"))
        } finally {
            server.stop(0)
        }
    }

    @Test fun readsOverHttp() {
        val seen = mutableListOf<String>()
        serve(200, fog, seen) { url ->
            val source = WeatherSource(url)
            source.refresh()
            assertEquals(Status.Ok, source.state.value.status)
            assertEquals(Weather(8, Sky.Fog, day = false), source.state.value.weather)
        }
        assertEquals(1, seen.size)
        assertNotNull(seen[0].takeIf { "latitude=50.45" in it && "longitude=30.52" in it && "current=temperature_2m,weather_code,is_day" in it })
    }

    @Test fun httpErrorIsAFailedFetch() {
        serve(500, "") { url ->
            val source = WeatherSource(url)
            source.refresh()
            assertEquals(Status.Unavailable, source.state.value.status)
        }
    }

    @Test fun refusedConnectionIsAFailedFetch() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort } // closed again: nothing listens
        val source = WeatherSource(Weather.url("http://127.0.0.1:$port/v1/forecast"))
        source.refresh()
        assertEquals(Status.Unavailable, source.state.value.status)
    }
}
