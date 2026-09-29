package com.aura.companion.ai

import com.aura.companion.BuildConfig
import com.aura.companion.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

// Represents a log entry for transparency dashboard
data class NetworkCallLog(
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,       // "WEATHER", "NEWS", "SEARCH"
    val query: String,
    val success: Boolean,
    val dataReturned: String
)

/**
 * The only code that talks to the network: real-time factual lookups (weather, news).
 * Every call is logged for the Settings screen; conversations and reasoning never go online.
 */
class OnlineLookupManager {

    private val _callLogs = MutableStateFlow<List<NetworkCallLog>>(emptyList())
    val callLogs: StateFlow<List<NetworkCallLog>> = _callLogs

    // True while a request is in flight, for the "online" indicator
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private fun log(entry: NetworkCallLog) {
        _callLogs.value = _callLogs.value + entry
    }

    // Detects if user query needs real-time data
    fun needsOnlineData(query: String): OnlineQueryType {
        val lower = query.lowercase()
        return when {
            lower.contains("weather") || lower.contains("temperature") ||
            lower.contains("rain") || lower.contains("hot") || lower.contains("cold") ||
            lower.contains("forecast") -> OnlineQueryType.WEATHER

            lower.contains("news") || lower.contains("headlines") ||
            lower.contains("latest") || lower.contains("today's news") -> OnlineQueryType.NEWS

            else -> OnlineQueryType.NONE
        }
    }

    /**
     * Extracts target city from user query like:
     * - "What is the weather in Delhi?" -> "Delhi"
     * - "How's the weather of London?" -> "London"
     * - "Forecast for Paris today" -> "Paris"
     * - "Tokyo weather" -> "Tokyo"
     * - "What is the weather?" -> fallback to BuildConfig.DEFAULT_CITY ("Mumbai")
     */
    fun extractCity(query: String): String {
        val clean = query.trim().trimEnd('?', '.', '!', ',', ';')

        // 1. Match prepositions: "in <city>", "for <city>", "of <city>", "at <city>"
        val prepRegex = Regex("""(?i)\b(?:in|for|of|at)\s+([A-Za-z\s]+)""")
        val match = prepRegex.findAll(clean).lastOrNull()
        if (match != null) {
            val rawCity = match.groupValues[1].trim()
            val candidate = rawCity
                .replace(Regex("""(?i)\b(today|tomorrow|tonight|now|right now|currently|this week|please|tell me|thanks)\b.*"""), "")
                .trim()
            if (candidate.isNotBlank() && candidate.length in 2..40) {
                return candidate
            }
        }

        // 2. Match pattern "<city> weather" or "<city> temperature"
        val prefixRegex = Regex("""(?i)\b([A-Za-z\s]+?)\s+(?:weather|forecast|temperature)\b""")
        val prefixMatch = prefixRegex.find(clean)
        if (prefixMatch != null) {
            val candidate = prefixMatch.groupValues[1].trim()
                .replace(Regex("""(?i)\b(what is|what's|how is|how's|check|tell me|show me|get)\b"""), "")
                .trim()
            if (candidate.isNotBlank() && candidate.length in 2..40) {
                return candidate
            }
        }

        return BuildConfig.DEFAULT_CITY
    }

    suspend fun fetchWeather(city: String = BuildConfig.DEFAULT_CITY): String =
        withContext(Dispatchers.IO) {
            val targetCity = if (city.isBlank()) BuildConfig.DEFAULT_CITY else city.trim()
            _active.value = true
            try {
                val response = ApiClient.weatherApi.getCurrentWeather(
                    city = targetCity,
                    apiKey = BuildConfig.WEATHER_API_KEY
                )
                val desc = response.weather.firstOrNull()?.description ?: "clear"
                val result = "Weather in ${response.name}: ${response.main.temp}°C, " +
                    "feels like ${response.main.feels_like}°C, $desc. " +
                    "Humidity: ${response.main.humidity}%, Wind: ${response.wind.speed} m/s."

                log(NetworkCallLog(type = "WEATHER", query = targetCity, success = true, dataReturned = result))
                result
            } catch (e: Exception) {
                val error = "Weather data unavailable for '$targetCity'."
                log(NetworkCallLog(type = "WEATHER", query = targetCity, success = false, dataReturned = error))
                error
            } finally {
                _active.value = false
            }
        }

    suspend fun fetchNews(country: String = "in"): String =
        withContext(Dispatchers.IO) {
            try {
                // Note: NewsAPI requires a key. Using a free alternative approach.
                // For hackathon, we use a placeholder or free tier
                val headlines = "Top news: Unable to fetch without a NewsAPI key. " +
                    "Please add NEWS_API_KEY in BuildConfig for live headlines."
                log(NetworkCallLog(type = "NEWS", query = country, success = true, dataReturned = headlines))
                headlines
            } catch (e: Exception) {
                val error = "News unavailable right now."
                log(NetworkCallLog(type = "NEWS", query = country, success = false, dataReturned = error))
                error
            }
        }

    fun clearLogs() {
        _callLogs.value = emptyList()
    }
}

enum class OnlineQueryType {
    WEATHER, NEWS, SEARCH, NONE
}
