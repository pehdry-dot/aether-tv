package com.aether.tv.data.weather

import com.aether.tv.core.model.TemperatureUnit
import com.aether.tv.data.media.JsonHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class WeatherKind(val label: String) {
    Clear("Clear"), PartlyCloudy("Partly cloudy"), Cloudy("Cloudy"), Fog("Fog"),
    Drizzle("Drizzle"), Rain("Rain"), Snow("Snow"), Storm("Thunderstorm"),
}

data class Place(val name: String, val latitude: Double, val longitude: Double, val region: String? = null) {
    /** "East Cambridge, Massachusetts": enough to tell same-named places apart in Settings. */
    val fullName: String get() = listOfNotNull(name, region?.takeIf { it.isNotBlank() && it != name }).joinToString(", ")
}

data class Conditions(val temperature: Double, val code: Int, val isDay: Boolean)

/** What the top bar shows. Only ever built from a real reading; there is no placeholder weather. */
data class WeatherNow(
    val place: String,
    val temperature: Int,
    val fahrenheit: Boolean,
    val kind: WeatherKind,
    val isDay: Boolean,
    val fetchedAt: Long,
    /** The city that was looked up (typed, or derived from the time zone), to know when settings changed. */
    val query: String,
    /** Place with its region, for Settings. */
    val fullPlace: String = place,
) {
    val text: String get() = "$temperature°"
    val description: String get() = "${kind.label}, $temperature°${if (fahrenheit) "F" else "C"} in $place"
}

object WeatherRules {
    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    fun kind(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        in 51..57 -> WeatherKind.Drizzle
        in 61..67, in 80..82 -> WeatherKind.Rain
        in 71..77, 85, 86 -> WeatherKind.Snow
        in 95..99 -> WeatherKind.Storm
        else -> WeatherKind.Cloudy
    }

    /** "Europe/Lisbon" -> "Lisbon", "America/Argentina/Buenos_Aires" -> "Buenos Aires". Null for "UTC", "GMT" etc. */
    fun cityFromTimeZone(id: String): String? {
        if ('/' !in id || id.startsWith("Etc/")) return null
        return id.substringAfterLast('/').replace('_', ' ').takeIf { it.isNotBlank() }
    }

    /** Countries that use Fahrenheit day to day. */
    private val fahrenheitCountries = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    fun useFahrenheit(unit: TemperatureUnit, locale: Locale): Boolean = when (unit) {
        TemperatureUnit.Celsius -> false
        TemperatureUnit.Fahrenheit -> true
        TemperatureUnit.Auto -> locale.country.uppercase() in fahrenheitCountries
    }

    /** The city to look up: the one typed in Settings, else the time zone's city. */
    fun query(typed: String, timeZoneId: String): String? = typed.trim().ifBlank { null } ?: cityFromTimeZone(timeZoneId)

    /** "East Cambridge, MA" -> ("East Cambridge", "MA"): the lookup searches the name, the rest picks among matches. */
    fun splitCity(typed: String): Pair<String, String?> {
        val parts = typed.split(',', limit = 2).map { it.trim() }
        return parts[0] to parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    data class Candidate(val region: String?, val country: String?, val countryCode: String?)

    /**
     * Index of the best match among same-named places (results come most populous first): one matching what was typed
     * after the comma (state, country or code, abbreviations by first letters), else one in the TV's country, else the first.
     */
    fun pick(c: List<Candidate>, qualifier: String?, countryHint: String?): Int? {
        if (c.isEmpty()) return null
        if (qualifier != null) {
            val q = qualifier.lowercase()
            fun fits(s: String?) = s != null && (s.lowercase() == q || s.lowercase().startsWith(q) || initials(s) == q || usStates[q] == s)
            c.indexOfFirst { fits(it.region) || fits(it.country) || fits(it.countryCode) }.takeIf { it >= 0 }?.let { return it }
        }
        if (countryHint != null) c.indexOfFirst { it.countryCode.equals(countryHint, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
        return 0
    }

    private fun initials(s: String) = s.split(' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.lowercase()

    /** Postal abbreviations, so "Cambridge, MA" works. */
    private val usStates = mapOf(
        "al" to "Alabama", "ak" to "Alaska", "az" to "Arizona", "ar" to "Arkansas", "ca" to "California", "co" to "Colorado",
        "ct" to "Connecticut", "de" to "Delaware", "fl" to "Florida", "ga" to "Georgia", "hi" to "Hawaii", "id" to "Idaho",
        "il" to "Illinois", "in" to "Indiana", "ia" to "Iowa", "ks" to "Kansas", "ky" to "Kentucky", "la" to "Louisiana",
        "me" to "Maine", "md" to "Maryland", "ma" to "Massachusetts", "mi" to "Michigan", "mn" to "Minnesota",
        "ms" to "Mississippi", "mo" to "Missouri", "mt" to "Montana", "ne" to "Nebraska", "nv" to "Nevada",
        "nh" to "New Hampshire", "nj" to "New Jersey", "nm" to "New Mexico", "ny" to "New York", "nc" to "North Carolina",
        "nd" to "North Dakota", "oh" to "Ohio", "ok" to "Oklahoma", "or" to "Oregon", "pa" to "Pennsylvania",
        "ri" to "Rhode Island", "sc" to "South Carolina", "sd" to "South Dakota", "tn" to "Tennessee", "tx" to "Texas",
        "ut" to "Utah", "vt" to "Vermont", "va" to "Virginia", "wa" to "Washington", "wv" to "West Virginia",
        "wi" to "Wisconsin", "wy" to "Wyoming", "dc" to "District of Columbia",
    )

    const val STALE_MS = 30 * 60_000L
    /** A failed refresh keeps showing the last reading for this long, then hides it rather than show old weather. */
    const val MAX_AGE_MS = 3 * 60 * 60_000L
}

interface WeatherSource {
    /** [countryHint]: ISO country of the TV (its locale), preferred when several places share the name. */
    suspend fun geocode(city: String, countryHint: String?): Place?
    suspend fun current(place: Place, fahrenheit: Boolean): Conditions?
}

/**
 * Open-Meteo (open-meteo.com): free, no account or key. Requests carry only the city name (to find its coordinates)
 * and the coordinates; nothing about the TV or the person.
 */
class OpenMeteo(private val http: JsonHttp = JsonHttp()) : WeatherSource {
    override suspend fun geocode(city: String, countryHint: String?): Place? {
        val (name, qualifier) = WeatherRules.splitCity(city)
        val q = URLEncoder.encode(name, "UTF-8")
        val json = http.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&format=json").jsonObject
        val results = json["results"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val candidates = results.map { r -> WeatherRules.Candidate(r.str("admin1"), r.str("country"), r.str("country_code")) to r }
        val r = WeatherRules.pick(candidates.map { it.first }, qualifier, countryHint)?.let { candidates[it].second } ?: return null
        return Place(
            name = r.str("name") ?: name,
            latitude = r["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null,
            longitude = r["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null,
            region = r.str("admin1"),
        )
    }

    override suspend fun current(place: Place, fahrenheit: Boolean): Conditions? {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit&timezone=auto"
        val c = http.get(url).jsonObject["current"]?.jsonObject ?: return null
        return Conditions(
            temperature = c["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: return null,
            code = c["weather_code"]?.jsonPrimitive?.intOrNull ?: return null,
            isDay = c["is_day"]?.jsonPrimitive?.intOrNull != 0,
        )
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
}

/**
 * Current weather for the top bar. [update] is called when Aether comes to the front, every 30 minutes while it stays
 * there, and when the weather settings change; it only goes to the network when the reading is stale or the city or
 * unit changed.
 */
@Singleton
class WeatherRepository(private val source: WeatherSource, private val clock: () -> Long = System::currentTimeMillis) {
    @Inject constructor() : this(OpenMeteo())

    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now.asStateFlow()
    private val lock = Mutex()
    private var place: Place? = null
    private var placeQuery: String? = null

    suspend fun update(
        typedCity: String,
        unit: TemperatureUnit,
        force: Boolean = false,
        timeZoneId: String = TimeZone.getDefault().id,
        locale: Locale = Locale.getDefault(),
    ) = lock.withLock {
        val query = WeatherRules.query(typedCity, timeZoneId) ?: run { _now.value = null; return@withLock }
        val fahrenheit = WeatherRules.useFahrenheit(unit, locale)
        val t = clock()
        val cur = _now.value
        if (!force && cur != null && cur.query == query && cur.fahrenheit == fahrenheit && t - cur.fetchedAt < WeatherRules.STALE_MS) return@withLock
        val reading = runCatching {
            withTimeoutOrNull(15_000) {
                val p = place?.takeIf { placeQuery == query } ?: source.geocode(query, locale.country.ifBlank { null })?.also { place = it; placeQuery = query }
                p?.let { pl -> source.current(pl, fahrenheit)?.let { pl to it } }
            }
        }.getOrNull()
        _now.value = if (reading != null) {
            val (p, c) = reading
            WeatherNow(p.name, c.temperature.roundToInt(), fahrenheit, WeatherRules.kind(c.code), c.isDay, t, query, p.fullName)
        } else {
            // Network or service trouble: keep a recent reading for the same place and unit, otherwise show nothing.
            cur?.takeIf { it.query == query && it.fahrenheit == fahrenheit && t - it.fetchedAt < WeatherRules.MAX_AGE_MS }
        }
    }

    fun clear() { _now.value = null }
}
