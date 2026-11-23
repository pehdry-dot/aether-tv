package com.aether.tv.unit

import com.aether.tv.core.model.TemperatureUnit
import com.aether.tv.data.weather.Conditions
import com.aether.tv.data.weather.Place
import com.aether.tv.data.weather.WeatherKind
import com.aether.tv.data.weather.WeatherRepository
import com.aether.tv.data.weather.WeatherRules
import com.aether.tv.data.weather.WeatherSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WeatherTest {
    @Test fun wmoCodes() {
        assertEquals(WeatherKind.Clear, WeatherRules.kind(0))
        assertEquals(WeatherKind.PartlyCloudy, WeatherRules.kind(2))
        assertEquals(WeatherKind.Cloudy, WeatherRules.kind(3))
        assertEquals(WeatherKind.Fog, WeatherRules.kind(45))
        assertEquals(WeatherKind.Drizzle, WeatherRules.kind(53))
        assertEquals(WeatherKind.Rain, WeatherRules.kind(63))
        assertEquals(WeatherKind.Rain, WeatherRules.kind(81))
        assertEquals(WeatherKind.Snow, WeatherRules.kind(75))
        assertEquals(WeatherKind.Storm, WeatherRules.kind(95))
    }

    @Test fun cityComesFromTheTypedNameOrTheTimeZone() {
        assertEquals("Lisbon", WeatherRules.cityFromTimeZone("Europe/Lisbon"))
        assertEquals("Buenos Aires", WeatherRules.cityFromTimeZone("America/Argentina/Buenos_Aires"))
        assertNull(WeatherRules.cityFromTimeZone("UTC"))
        assertNull(WeatherRules.cityFromTimeZone("Etc/GMT+3"))
        assertEquals("Porto", WeatherRules.query("  Porto ", "Europe/Lisbon"))
        assertEquals("Lisbon", WeatherRules.query("", "Europe/Lisbon"))
    }

    @Test fun unitFollowsTheCountryUnlessChosen() {
        assertTrue(WeatherRules.useFahrenheit(TemperatureUnit.Auto, Locale.US))
        assertFalse(WeatherRules.useFahrenheit(TemperatureUnit.Auto, Locale("pt", "PT")))
        assertTrue(WeatherRules.useFahrenheit(TemperatureUnit.Fahrenheit, Locale("pt", "PT")))
        assertFalse(WeatherRules.useFahrenheit(TemperatureUnit.Celsius, Locale.US))
    }

    private class FakeSource : WeatherSource {
        var geocodes = 0; var reads = 0; var failing = false
        override suspend fun geocode(city: String, countryHint: String?): Place? { geocodes++; return if (failing) null else Place(city, 1.0, 2.0) }
        override suspend fun current(place: Place, fahrenheit: Boolean): Conditions? {
            reads++; if (failing) throw java.io.IOException("offline")
            return Conditions(if (fahrenheit) 57.4 else 14.2, 61, isDay = false)
        }
    }

    @Test fun readsOncePerHalfHourAndKeepsTheLastReadingBriefly() = runBlocking {
        var t = 1_000_000L
        val src = FakeSource()
        val repo = WeatherRepository(src) { t }
        val pt = Locale("pt", "PT")
        repo.update("", TemperatureUnit.Auto, timeZoneId = "Europe/Lisbon", locale = pt)
        val w = repo.now.value!!
        assertEquals("Lisbon", w.place); assertEquals(14, w.temperature); assertEquals(WeatherKind.Rain, w.kind); assertFalse(w.isDay)
        t += 10 * 60_000
        repo.update("", TemperatureUnit.Auto, timeZoneId = "Europe/Lisbon", locale = pt)
        assertEquals(1, src.reads)                       // fresh: no request
        repo.update("", TemperatureUnit.Fahrenheit, timeZoneId = "Europe/Lisbon", locale = pt)
        assertEquals(57, repo.now.value!!.temperature)   // unit changed: read again, same place reused
        assertEquals(1, src.geocodes)
        t += 40 * 60_000; src.failing = true
        repo.update("", TemperatureUnit.Fahrenheit, timeZoneId = "Europe/Lisbon", locale = pt)
        assertEquals(57, repo.now.value!!.temperature)   // offline: last reading kept for a while
        t += 3 * 60 * 60_000
        repo.update("", TemperatureUnit.Fahrenheit, timeZoneId = "Europe/Lisbon", locale = pt)
        assertNull(repo.now.value)                       // too old to show
    }

    @Test fun sameNamedPlacesPreferWhatWasTypedThenTheTvsCountry() {
        val c = listOf(
            WeatherRules.Candidate("England", "United Kingdom", "GB"),
            WeatherRules.Candidate("Ontario", "Canada", "CA"),
            WeatherRules.Candidate("Massachusetts", "United States", "US"),
        )
        assertEquals(2, WeatherRules.pick(c, null, "US"))
        assertEquals(2, WeatherRules.pick(c, "MA", "GB"))
        assertEquals(2, WeatherRules.pick(c, "massachusetts", null))
        assertEquals(1, WeatherRules.pick(c, "Canada", "US"))
        assertEquals(0, WeatherRules.pick(c, null, "PT"))
        assertNull(WeatherRules.pick(emptyList(), null, "US"))
        assertEquals("East Cambridge" to "MA", WeatherRules.splitCity(" East Cambridge , MA "))
        assertEquals("East Cambridge" to null, WeatherRules.splitCity("East Cambridge"))
    }

    @Test fun noCityNoWeather() = runBlocking {
        val repo = WeatherRepository(FakeSource())
        repo.update("", TemperatureUnit.Auto, timeZoneId = "UTC")
        assertNull(repo.now.value)
    }
}
